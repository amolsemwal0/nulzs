package org.zeronura.engine.debug;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;

import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;

/**
 * Started before the game ({@code -javaagent}) in debug mode: wraps every OpenGL call the game's own classes make in
 * {@link GlCalls}, as each class loads, so the Debug panel can list them all — which function, called from where,
 * how many times a frame and how long each took to return.
 *
 * <p>Only the game's classes and the engine's are rewritten, not the JDK's or the libraries': a call into LWJGL is
 * counted where the game makes it, not again inside LWJGL. A class that cannot be rewritten (its type hierarchy
 * cannot be read) loads as it is and its calls go uncounted; the report says how many.
 *
 * <p>The editor makes the agent's jar — a manifest naming this class, nothing else — and the class itself loads from
 * the game's class path. Desktop only: Android has no agents.
 */
public final class GlCallAgent {

    private static final ClassDesc GL_CALLS = ClassDesc.of(GlCalls.class.getName());
    private static final MethodTypeDesc ENTER = MethodTypeDesc.of(CD_void, CD_int);
    private static final MethodTypeDesc EXIT = MethodTypeDesc.of(CD_void);
    private static final byte[] GL_OWNER = "org/lwjgl/opengl/".getBytes(StandardCharsets.UTF_8);

    private GlCallAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        GlCalls.startTracing();
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined, ProtectionDomain domain,
                                    byte[] bytes) {
                if (className == null || !traces(className) || !mentions(bytes, GL_OWNER)) {
                    return null;
                }
                try {
                    byte[] out = rewrite(loader, className, bytes);
                    GlCalls.rewrittenClasses.incrementAndGet();
                    return out;
                } catch (Throwable e) {
                    GlCalls.skippedClasses.incrementAndGet();
                    System.err.println("[Debug] Not tracing the OpenGL calls in " + className.replace('/', '.') + ": " + e);
                    return null;
                }
            }
        });
    }

    /** Everything but the JDK, the libraries and the debug mode itself. */
    static boolean traces(String className) {
        return !(className.startsWith("java/") || className.startsWith("javax/") || className.startsWith("jdk/")
                || className.startsWith("sun/") || className.startsWith("com/sun/") || className.startsWith("org/lwjgl/")
                || className.startsWith("org/joml/") || className.startsWith("org/junit/")
                || className.startsWith("org/opentest4j/") || className.startsWith("de/articdive/")
                || className.startsWith("org/zeronura/engine/debug/"));
    }

    /** Whether the class's bytes name {@code what} anywhere: a cheap test before parsing it. */
    static boolean mentions(byte[] bytes, byte[] what) {
        outer:
        for (int i = 0, end = bytes.length - what.length; i <= end; i++) {
            for (int j = 0; j < what.length; j++) {
                if (bytes[i + j] != what[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** The class with each {@code invokestatic org/lwjgl/opengl/...} between {@link GlCalls#enter} and {@link GlCalls#exit}. */
    static byte[] rewrite(ClassLoader loader, String className, byte[] bytes) {
        ClassLoader from = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ClassFile files = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(from).orElse(ClassHierarchyResolver.defaultResolver()).cached()));
        ClassModel model = files.parse(bytes);
        String owner = DebugMode.shortName(className.replace('/', '.'));
        return files.transformClass(model, (builder, element) -> {
            if (element instanceof MethodModel method && method.code().isPresent() && callsGl(method)) {
                String caller = owner + "." + method.methodName().stringValue();
                builder.transformMethod(method, MethodTransform.transformingCode((code, part) -> {
                    if (isGlCall(part)) {
                        InvokeInstruction call = (InvokeInstruction) part;
                        code.loadConstant(GlCalls.register(call.name().stringValue(), caller));
                        code.invokestatic(GL_CALLS, "enter", ENTER);
                        code.with(part);
                        code.invokestatic(GL_CALLS, "exit", EXIT);
                    } else {
                        code.with(part);
                    }
                }));
            } else {
                builder.with(element);
            }
        });
    }

    private static boolean callsGl(MethodModel method) {
        for (CodeElement part : method.code().orElseThrow()) {
            if (isGlCall(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGlCall(CodeElement part) {
        return part instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKESTATIC
                && call.owner().asInternalName().startsWith("org/lwjgl/opengl/");
    }
}

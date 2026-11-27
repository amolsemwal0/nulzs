package org.zeronura.nulzs.debug;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent wraps each OpenGL call, and only those, and what it writes is a class the JVM accepts. */
class GlCallAgentTest {

    private static final String FIXTURE = GlCallFixture.class.getName();

    private static byte[] fixtureBytes() throws IOException {
        try (InputStream in = GlCallFixture.class.getResourceAsStream("GlCallFixture.class")) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static byte[] rewritten() throws IOException {
        return GlCallAgent.rewrite(GlCallFixture.class.getClassLoader(), FIXTURE.replace('.', '/'), fixtureBytes());
    }

    @Test
    void everyOpenGlCallIsBetweenEnterAndExit() throws IOException {
        ClassModel model = ClassFile.of().parse(rewritten());
        int calls = 0;
        for (MethodModel method : model.methods()) {
            List<Instruction> code = new ArrayList<>();
            for (CodeElement element : method.code().map(c -> c.elementList()).orElse(List.of())) {
                if (element instanceof Instruction instruction) {
                    code.add(instruction);
                }
            }
            for (int i = 0; i < code.size(); i++) {
                if (code.get(i) instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKESTATIC
                        && call.owner().asInternalName().startsWith("org/lwjgl/opengl/")) {
                    calls++;
                    assertTrue(isGlCalls(code.get(i - 1), "enter"), "enter before " + call.name());
                    assertTrue(isGlCalls(code.get(i + 1), "exit"), "exit after " + call.name());
                }
            }
            if (method.methodName().equalsString("name")) {
                assertFalse(code.stream().anyMatch(c -> isGlCalls(c, "enter")), "a method without OpenGL is left alone");
            }
        }
        assertEquals(3, calls);
    }

    private static boolean isGlCalls(Instruction instruction, String name) {
        return instruction instanceof InvokeInstruction call && call.owner().asInternalName().equals("org/zeronura/nulzs/debug/GlCalls")
                && call.name().equalsString(name);
    }

    @Test
    void theRewrittenClassPassesVerification() throws Exception {
        byte[] bytes = rewritten();
        ClassLoader loader = new ClassLoader(GlCallFixture.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(FIXTURE)) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> made = findLoadedClass(name);
                        return made != null ? made : defineClass(name, bytes, 0, bytes.length);
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        Class<?> type = Class.forName(FIXTURE, true, loader);
        assertEquals(loader, type.getClassLoader());
        assertEquals("fixture", type.getMethod("name").invoke(type.getConstructor().newInstance()));
    }

    @Test
    void callSitesAreNamedByFunctionAndMethod() throws IOException {
        rewritten();
        int found = 0;
        for (int i = 0; i < GlCalls.sites(); i++) {
            if ("nulzs.debug.GlCallFixture.draw".equals(GlCalls.caller(i))) {
                found++;
                assertTrue(GlCalls.function(i).startsWith("gl"));
            }
        }
        assertEquals(3, found);
        assertEquals(GlCalls.register("glDrawArrays", "nulzs.X.y"), GlCalls.register("glDrawArrays", "nulzs.X.y"));
    }

    @Test
    void onlyTheGamesClassesAreTraced() {
        assertTrue(GlCallAgent.traces("org/zeronura/games/elementals/render/MeadowGrass"));
        assertTrue(GlCallAgent.traces("com/example/mygame/Player"));
        assertFalse(GlCallAgent.traces("java/lang/String"));
        assertFalse(GlCallAgent.traces("org/lwjgl/opengl/GL33"));
        assertFalse(GlCallAgent.traces("org/zeronura/nulzs/debug/GpuTimer"));
        assertTrue(GlCallAgent.mentions("abc org/lwjgl/opengl/GL11 x".getBytes(StandardCharsets.UTF_8),
                "org/lwjgl/opengl/".getBytes(StandardCharsets.UTF_8)));
        assertFalse(GlCallAgent.mentions("org/lwjgl/glfw/GLFW".getBytes(StandardCharsets.UTF_8),
                "org/lwjgl/opengl/".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void drawsAreCountedAsDraws() {
        assertTrue(GlCalls.isDraw("glDrawElementsInstanced"));
        assertTrue(GlCalls.isDraw("glMultiDrawArrays"));
        assertFalse(GlCalls.isDraw("glDrawBuffers"));
        assertFalse(GlCalls.isDraw("glBindVertexArray"));
    }
}

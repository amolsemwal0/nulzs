package org.zeronura.nulzs.render;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL33.*;

public class Shader {

    private final int programId;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    public Shader(String vertexResourcePath, String fragmentResourcePath) {
        this(readResource(vertexResourcePath), readResource(fragmentResourcePath),
                vertexResourcePath + ", " + fragmentResourcePath);
    }

    /**
     * A shader from source text rather than resources: for shaders generated at run time, such as materials
     * compiled from Blender shader nodes. {@code name} is only for error messages.
     */
    public static Shader fromSource(String vertexSource, String fragmentSource, String name) {
        return new Shader(vertexSource, fragmentSource, name);
    }

    private Shader(String vertexSource, String fragmentSource, String name) {
        int vertexShader = compile(GL_VERTEX_SHADER, vertexSource, name);
        int fragmentShader = compile(GL_FRAGMENT_SHADER, fragmentSource, name);

        programId = glCreateProgram();
        glAttachShader(programId, vertexShader);
        glAttachShader(programId, fragmentShader);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader link failed (" + name + "): " + glGetProgramInfoLog(programId));
        }

        glDetachShader(programId, vertexShader);
        glDetachShader(programId, fragmentShader);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
    }

    private static int compile(int type, String source, String name) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);

        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new RuntimeException("Shader compile failed (" + name + "): " + glGetShaderInfoLog(shader));
        }

        return shader;
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = Shader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader resource: " + resourcePath, e);
        }
    }

    public void use() {
        glUseProgram(programId);
    }

    public void bindUniformBlock(String blockName, int bindingPoint) {
        int blockIndex = glGetUniformBlockIndex(programId, blockName);
        if (blockIndex == GL_INVALID_INDEX) {
            throw new IllegalArgumentException("Uniform block not found: " + blockName);
        }
        glUniformBlockBinding(programId, blockIndex, bindingPoint);
    }

    /**
     * The cached location of a uniform.
     *
     * <p>Written as get-then-put rather than {@code computeIfAbsent} because the lambda that form needs
     * captures {@code this} (for {@code programId}), so it is allocated afresh on <b>every</b> call — and
     * this is called several times per draw, at roughly a thousand draws a frame. A miss is a one-off per
     * uniform name per program; the hit path now allocates nothing.
     *
     * <p>{@code -1} (an unknown or optimised-out uniform) is cached like any other value, which is what
     * keeps setting a uniform a shader does not declare a cheap no-op rather than a repeated GL query —
     * the outline and depth passes rely on exactly that.
     */
    private int location(String name) {
        Integer cached = uniformLocations.get(name);
        if (cached != null) {
            return cached;
        }
        int resolved = glGetUniformLocation(programId, name);
        uniformLocations.put(name, resolved);
        return resolved;
    }

    public void setUniform(String name, int value) {
        glUniform1i(location(name), value);
    }

    public void setUniform(String name, float value) {
        glUniform1f(location(name), value);
    }

    public void setUniform(String name, Vector2f value) {
        glUniform2f(location(name), value.x, value.y);
    }

    public void setUniform(String name, Vector3f value) {
        glUniform3f(location(name), value.x, value.y, value.z);
    }

    public void setUniform(String name, Vector4f value) {
        glUniform4f(location(name), value.x, value.y, value.z, value.w);
    }

    public void setUniform(String name, Matrix3f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix3fv(location(name), false, value.get(stack.mallocFloat(9)));
        }
    }

    public void setUniform(String name, Matrix4f value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            glUniformMatrix4fv(location(name), false, value.get(stack.mallocFloat(16)));
        }
    }

    public void delete() {
        glDeleteProgram(programId);
    }
}

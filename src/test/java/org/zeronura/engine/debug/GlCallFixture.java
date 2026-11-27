package org.zeronura.engine.debug;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL33;

/** A class that calls OpenGL as the game's do, for GlCallAgentTest to rewrite. Never run: there is no context. */
public class GlCallFixture {

    public int draw(int[] programs, boolean wide) {
        int errors = 0;
        for (int program : programs) {
            if (wide) {
                GL33.glUniform1f(program, 2f);
            } else {
                errors += GL11.glGetError() == 0 ? 0 : 1;
            }
        }
        long stamp = wide ? GL33.glGetInteger64(GL33.GL_TIMESTAMP) : 0L;
        return errors + (int) stamp;
    }

    public String name() {
        return "fixture";
    }
}

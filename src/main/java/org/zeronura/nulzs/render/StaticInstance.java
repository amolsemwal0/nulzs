package org.zeronura.nulzs.render;

import org.joml.Matrix4f;

public record StaticInstance(StaticMesh mesh, Matrix4f modelMatrix) {
}

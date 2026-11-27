package org.zeronura.engine.asset;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class SkeletonNode {

    public final String name;
    public final Matrix4f localBindTransform;
    public final SkeletonNode parent;
    public final List<SkeletonNode> children = new ArrayList<>();

    /** Index into Model's boneOffsetMatrices/final bone-matrix array, or -1 if this node is not a bone. */
    public int boneIndex = -1;

    public SkeletonNode(String name, Matrix4f localBindTransform, SkeletonNode parent) {
        this.name = name;
        this.localBindTransform = localBindTransform;
        this.parent = parent;
    }
}

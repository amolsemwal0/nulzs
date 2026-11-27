package org.zeronura.nulzs.asset;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

public class NodeAnimChannel {

    public record PositionKey(double timeTicks, Vector3f value) {}
    public record RotationKey(double timeTicks, Quaternionf value) {}
    public record ScaleKey(double timeTicks, Vector3f value) {}

    public final String targetNodeName;
    public final List<PositionKey> positionKeys;
    public final List<RotationKey> rotationKeys;
    public final List<ScaleKey> scaleKeys;

    public NodeAnimChannel(String targetNodeName, List<PositionKey> positionKeys,
                            List<RotationKey> rotationKeys, List<ScaleKey> scaleKeys) {
        this.targetNodeName = targetNodeName;
        this.positionKeys = positionKeys;
        this.rotationKeys = rotationKeys;
        this.scaleKeys = scaleKeys;
    }
}

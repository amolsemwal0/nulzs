package org.zeronura.engine.nodes.geometry;

import org.zeronura.engine.nodes.Socket;

/**
 * The fields an unconnected input means when it shows no value of its own: a texture's Vector is the position,
 * Random Value's ID is the ID, Set Position's Position is the current position, and so on.
 */
final class ImplicitInputs {

    private ImplicitInputs() {
    }

    static Field forSocket(Socket input) {
        String type = input.node.type;
        String id = input.id;
        if (type.startsWith("ShaderNodeTex") && id.equals("Vector")) {
            return Inputs.POSITION;
        }
        return switch (type + "/" + id) {
            case "FunctionNodeRandomValue/ID" -> Inputs.ID_OR_INDEX;
            case "GeometryNodeSetPosition/Position",
                 "GeometryNodeSampleNearest/Sample Position",
                 "GeometryNodeSampleNearestSurface/Sample Position",
                 "GeometryNodeProximity/Source Position",
                 "GeometryNodeRaycast/Source Position",
                 "GeometryNodeSetCurveHandlePositions/Position",
                 "GeometryNodeIndexOfNearest/Position",
                 "GeometryNodeMeshToPoints/Position",
                 "GeometryNodeInstancesToPoints/Position" -> Inputs.POSITION;
            case "GeometryNodeSampleIndex/Index", "GeometryNodeFieldAtIndex/Index" -> Inputs.INDEX;
            case "GeometryNodeExtrudeMesh/Offset" -> input.isLinked() ? null : Inputs.NORMAL;
            case "GeometryNodeMeshToPoints/Radius" -> null;
            case "GeometryNodeImageTexture/Vector" -> Inputs.POSITION;
            default -> null;
        };
    }

    /** A group interface input's "Default Input" setting, for an unconnected group socket. */
    static Field forInterface(String defaultInput) {
        if (defaultInput == null) {
            return null;
        }
        return switch (defaultInput) {
            case "POSITION" -> Inputs.POSITION;
            case "NORMAL" -> Inputs.NORMAL;
            case "INDEX" -> Inputs.INDEX;
            case "ID_OR_INDEX" -> Inputs.ID_OR_INDEX;
            case "INSTANCE_TRANSFORM" -> InstanceNodes.INSTANCE_TRANSFORM;
            default -> null;
        };
    }
}

package org.zeronura.nulzs.nodes.geometry;

import org.zeronura.nulzs.nodes.ColorRamp;
import org.zeronura.nulzs.nodes.CurveMapping;
import org.zeronura.nulzs.nodes.NodeValues;
import org.zeronura.nulzs.nodes.Socket;
import org.zeronura.nulzs.nodes.SocketType;
import org.zeronura.nulzs.nodes.tex.ColorMath;
import org.zeronura.nulzs.nodes.tex.NodeMath;
import org.zeronura.nulzs.nodes.tex.RandomValue;

import java.util.List;
import java.util.Map;

/** Nodes that compute values from values: maths, mixing, comparison, rotations, matrices, switches. */
final class FunctionNodes {

    private FunctionNodes() {
    }

    static void register(Map<String, NodeImpl> map) {
        map.put("ShaderNodeMath", FunctionNodes::math);
        map.put("ShaderNodeVectorMath", FunctionNodes::vectorMath);
        map.put("ShaderNodeClamp", FunctionNodes::clamp);
        map.put("ShaderNodeMapRange", FunctionNodes::mapRange);
        map.put("ShaderNodeMix", FunctionNodes::mix);
        map.put("ShaderNodeMixRGB", FunctionNodes::mixRgb);
        map.put("ShaderNodeCombineXYZ", call -> call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out.setVector(k, in[0].getFloat(k), in[1].getFloat(k), in[2].getFloat(k));
            }
        }, call.field("X", DataType.FLOAT), call.field("Y", DataType.FLOAT), call.field("Z", DataType.FLOAT))));
        map.put("ShaderNodeSeparateXYZ", FunctionNodes::separateXyz);
        map.put("FunctionNodeCombineColor", FunctionNodes::combineColor);
        map.put("ShaderNodeCombineColor", FunctionNodes::combineColor);
        map.put("FunctionNodeSeparateColor", FunctionNodes::separateColor);
        map.put("ShaderNodeSeparateColor", FunctionNodes::separateColor);
        map.put("FunctionNodeCompare", FunctionNodes::compare);
        map.put("FunctionNodeBooleanMath", FunctionNodes::booleanMath);
        map.put("FunctionNodeIntegerMath", FunctionNodes::integerMath);
        map.put("FunctionNodeBitMath", FunctionNodes::bitMath);
        map.put("FunctionNodeFloatToInt", FunctionNodes::floatToInt);
        map.put("FunctionNodeRandomValue", FunctionNodes::randomValue);
        map.put("FunctionNodeHashValue", FunctionNodes::hashValue);
        map.put("ShaderNodeValToRGB", FunctionNodes::colorRamp);
        map.put("ShaderNodeFloatCurve", FunctionNodes::floatCurve);
        map.put("ShaderNodeRGBCurve", FunctionNodes::rgbCurve);
        map.put("ShaderNodeVectorCurve", FunctionNodes::vectorCurve);
        map.put("ShaderNodeVectorRotate", FunctionNodes::vectorRotate);
        map.put("ShaderNodeMapping", FunctionNodes::mapping);
        map.put("ShaderNodeInvert", FunctionNodes::invert);
        map.put("ShaderNodeGamma", FunctionNodes::gamma);
        map.put("ShaderNodeBrightContrast", FunctionNodes::brightContrast);
        map.put("ShaderNodeHueSaturation", FunctionNodes::hueSaturation);
        map.put("ShaderNodeRGBToBW", call -> call.out("Val", call.field("Color", DataType.FLOAT)));

        map.put("ShaderNodeValue", call -> call.out("Value",
                Field.constant(DataType.FLOAT, call.node.outputs().get(0).value)));
        map.put("FunctionNodeInputBool", call -> call.out("Boolean", Field.constant(DataType.BOOL, call.propBool("boolean", false))));
        map.put("FunctionNodeInputInt", call -> call.out("Integer", Field.constant(DataType.INT, call.propInt("integer", 0))));
        map.put("FunctionNodeInputVector", call -> call.out("Vector", Field.constant(DataType.VECTOR, call.node.props.get("vector"))));
        map.put("FunctionNodeInputColor", call -> call.out("Color", Field.constant(DataType.COLOR, call.node.props.get("value"))));
        map.put("ShaderNodeRGB", call -> call.out("Color", Field.constant(DataType.COLOR, call.node.outputs().get(0).value)));
        map.put("FunctionNodeInputRotation", call -> call.out("Rotation",
                Field.constant(DataType.ROTATION, NodeValues.toVector(call.node.props.get("rotation_euler"), 3))));
        map.put("FunctionNodeInputString", call -> call.out("String", call.prop("string", "")));
        map.put("FunctionNodeInputMenu", call -> call.out("Menu", call.prop("value", "")));
        map.put("FunctionNodeInputSpecialCharacters", call -> {
            call.out("Line Break", "\n");
            call.out("Tab", "\t");
        });
        map.put("GeometryNodeStringJoin", call -> {
            StringBuilder out = new StringBuilder();
            Socket strings = call.inputSocket("Strings");
            List<Object> values = strings == null ? List.of() : call.run.inputValues(strings, call.scope);
            String delimiter = call.string("Delimiter");
            for (int k = 0; k < values.size(); k++) {
                out.append(k > 0 ? delimiter : "").append(NodeValues.toText(values.get(k), ""));
            }
            call.out("String", out.toString());
        });
        map.put("FunctionNodeStringLength", call -> call.out("Length", Field.constant(DataType.INT, call.string("String").length())));

        map.put("FunctionNodeEulerToRotation", call -> call.out("Rotation", call.field("Euler", DataType.VECTOR).as(DataType.ROTATION)));
        map.put("FunctionNodeRotationToEuler", call -> call.out("Euler", call.field("Rotation", DataType.ROTATION).as(DataType.VECTOR)));
        map.put("FunctionNodeAxisAngleToRotation", call -> call.out("Rotation", Field.of(DataType.ROTATION, (in, out, n) -> {
            float[] a = new float[3];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, a);
                Transforms.axisAngleToQuat(a[0], a[1], a[2], in[1].getFloat(k), out.f, 4 * k);
            }
        }, call.field("Axis", DataType.VECTOR), call.field("Angle", DataType.FLOAT))));
        map.put("FunctionNodeRotationToAxisAngle", FunctionNodes::rotationToAxisAngle);
        map.put("FunctionNodeQuaternionToRotation", call -> call.out("Rotation", Field.of(DataType.ROTATION, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                for (int c = 0; c < 4; c++) {
                    out.f[4 * k + c] = in[c].getFloat(k);
                }
                Transforms.quatNormalize(out.f, 4 * k);
            }
        }, call.field("W", DataType.FLOAT), call.field("X", DataType.FLOAT), call.field("Y", DataType.FLOAT),
                call.field("Z", DataType.FLOAT))));
        map.put("FunctionNodeRotationToQuaternion", call -> {
            Field[] parts = Field.multi(new DataType[]{DataType.FLOAT, DataType.FLOAT, DataType.FLOAT, DataType.FLOAT},
                    (in, out, n) -> {
                        for (int k = 0; k < n; k++) {
                            for (int c = 0; c < 4; c++) {
                                out[c].f[k] = in[0].f[4 * k + c];
                            }
                        }
                    }, call.field("Rotation", DataType.ROTATION));
            call.out("W", parts[0]);
            call.out("X", parts[1]);
            call.out("Y", parts[2]);
            call.out("Z", parts[3]);
        });
        map.put("FunctionNodeInvertRotation", call -> call.out("Rotation", Field.of(DataType.ROTATION, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                Transforms.quatInvert(in[0].f, 4 * k, out.f, 4 * k);
            }
        }, call.field("Rotation", DataType.ROTATION))));
        map.put("FunctionNodeRotateRotation", call -> {
            boolean local = "LOCAL".equals(call.prop("rotation_space", "GLOBAL"));
            call.out("Rotation", Field.of(DataType.ROTATION, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    if (local) {
                        Transforms.quatMul(in[0].f, 4 * k, in[1].f, 4 * k, out.f, 4 * k);
                    } else {
                        Transforms.quatMul(in[1].f, 4 * k, in[0].f, 4 * k, out.f, 4 * k);
                    }
                }
            }, call.field("Rotation", DataType.ROTATION), call.field("Rotate By", DataType.ROTATION)));
        });
        map.put("FunctionNodeRotateVector", call -> call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                Transforms.rotate(in[1].f, 4 * k, in[0].f, 3 * k, out.f, 3 * k);
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Rotation", DataType.ROTATION))));
        map.put("FunctionNodeRotateEuler", FunctionNodes::rotateEuler);
        map.put("FunctionNodeAlignRotationToVector", call -> alignToVector(call, false));
        map.put("FunctionNodeAlignEulerToVector", call -> alignToVector(call, true));
        map.put("FunctionNodeAxesToRotation", FunctionNodes::axesToRotation);

        map.put("FunctionNodeCombineTransform", call -> call.out("Transform", Field.of(DataType.MATRIX, (in, out, n) -> {
            float[] t = new float[3], s = new float[3];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, t);
                in[2].getVector(k, s);
                Transforms.compose(t[0], t[1], t[2], in[1].f, 4 * k, s[0], s[1], s[2], out.f, 16 * k);
            }
        }, call.field("Translation", DataType.VECTOR), call.field("Rotation", DataType.ROTATION),
                call.field("Scale", DataType.VECTOR))));
        map.put("FunctionNodeSeparateTransform", call -> {
            Field[] parts = Field.multi(new DataType[]{DataType.VECTOR, DataType.ROTATION, DataType.VECTOR},
                    (in, out, n) -> {
                        float[] d = new float[10];
                        for (int k = 0; k < n; k++) {
                            Transforms.decompose(in[0].f, 16 * k, d);
                            System.arraycopy(d, 0, out[0].f, 3 * k, 3);
                            System.arraycopy(d, 3, out[1].f, 4 * k, 4);
                            System.arraycopy(d, 7, out[2].f, 3 * k, 3);
                        }
                    }, call.field("Transform", DataType.MATRIX));
            call.out("Translation", parts[0]);
            call.out("Rotation", parts[1]);
            call.out("Scale", parts[2]);
        });
        map.put("FunctionNodeTransformPoint", call -> call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                Transforms.transformPoint(in[1].f, 16 * k, in[0].f, 3 * k, out.f, 3 * k);
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Transform", DataType.MATRIX))));
        map.put("FunctionNodeTransformDirection", call -> call.out("Direction", Field.of(DataType.VECTOR, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                Transforms.transformDirection(in[1].f, 16 * k, in[0].f, 3 * k, out.f, 3 * k);
            }
        }, call.field("Direction", DataType.VECTOR), call.field("Transform", DataType.MATRIX))));
        map.put("FunctionNodeProjectPoint", call -> call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float[] m = in[1].f;
                int o = 16 * k;
                float x = in[0].f[3 * k], y = in[0].f[3 * k + 1], z = in[0].f[3 * k + 2];
                float w = m[o + 3] * x + m[o + 7] * y + m[o + 11] * z + m[o + 15];
                Transforms.transformPoint(m, o, in[0].f, 3 * k, out.f, 3 * k);
                if (w != 0f) {
                    out.f[3 * k] /= w;
                    out.f[3 * k + 1] /= w;
                    out.f[3 * k + 2] /= w;
                }
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Transform", DataType.MATRIX))));
        map.put("FunctionNodeInvertMatrix", call -> {
            Field[] parts = Field.multi(new DataType[]{DataType.MATRIX, DataType.BOOL}, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out[1].i[k] = Transforms.invert(in[0].f, 16 * k, out[0].f, 16 * k) ? 1 : 0;
                }
            }, call.field("Matrix", DataType.MATRIX));
            call.out("Matrix", parts[0]);
            call.out("Invertible", parts[1]);
        });
        map.put("FunctionNodeMatrixMultiply", call -> call.out("Matrix", Field.of(DataType.MATRIX, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                Transforms.mul(in[0].f, 16 * k, in[1].f, 16 * k, out.f, 16 * k);
            }
        }, call.field("Matrix", DataType.MATRIX), call.field("Matrix_001", DataType.MATRIX))));
        map.put("FunctionNodeTransposeMatrix", call -> call.out("Matrix", Field.of(DataType.MATRIX, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                for (int c = 0; c < 4; c++) {
                    for (int r = 0; r < 4; r++) {
                        out.f[16 * k + c * 4 + r] = in[0].f[16 * k + r * 4 + c];
                    }
                }
            }
        }, call.field("Matrix", DataType.MATRIX))));
        map.put("FunctionNodeMatrixDeterminant", call -> call.out("Determinant", Field.of(DataType.FLOAT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out.f[k] = new org.joml.Matrix4f().set(in[0].f, 16 * k).determinant();
            }
        }, call.field("Matrix", DataType.MATRIX))));
        map.put("FunctionNodeCombineMatrix", FunctionNodes::combineMatrix);
        map.put("FunctionNodeSeparateMatrix", FunctionNodes::separateMatrix);

        map.put("GeometryNodeSwitch", FunctionNodes::switchNode);
        map.put("GeometryNodeIndexSwitch", FunctionNodes::indexSwitch);
        map.put("GeometryNodeMenuSwitch", FunctionNodes::menuSwitch);
    }

    // --- Maths -----------------------------------------------------------------------------------------------

    private static void math(NodeCall call) {
        String op = call.prop("operation", "ADD");
        boolean clamp = call.propBool("use_clamp", false);
        call.out("Value", Field.of(DataType.FLOAT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float v = NodeMath.math(op, in[0].getFloat(k), in[1].getFloat(k), in[2].getFloat(k));
                out.f[k] = clamp ? Math.clamp(v, 0f, 1f) : v;
            }
        }, call.field("Value", DataType.FLOAT), call.field("Value_001", DataType.FLOAT),
                call.field("Value_002", DataType.FLOAT)));
    }

    private static void vectorMath(NodeCall call) {
        String op = call.prop("operation", "ADD");
        Field[] parts = Field.multi(new DataType[]{DataType.VECTOR, DataType.FLOAT}, (in, out, n) -> {
            float[] a = new float[3], b = new float[3], c = new float[3], r = new float[4];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, a);
                in[1].getVector(k, b);
                in[2].getVector(k, c);
                NodeMath.vectorMath(op, a, b, c, in[3].getFloat(k), r);
                out[0].setVector(k, r[0], r[1], r[2]);
                out[1].f[k] = r[3];
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Vector_001", DataType.VECTOR),
                call.field("Vector_002", DataType.VECTOR), call.field("Scale", DataType.FLOAT));
        call.out("Vector", parts[0]);
        call.out("Value", parts[1]);
    }

    private static void clamp(NodeCall call) {
        String type = call.prop("clamp_type", "MINMAX");
        call.out("Result", Field.of(DataType.FLOAT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out.f[k] = NodeMath.clamp(type, in[0].getFloat(k), in[1].getFloat(k), in[2].getFloat(k));
            }
        }, call.field("Value", DataType.FLOAT), call.field("Min", DataType.FLOAT), call.field("Max", DataType.FLOAT)));
    }

    private static void mapRange(NodeCall call) {
        String interpolation = call.prop("interpolation_type", "LINEAR");
        boolean clamp = call.propBool("clamp", true);
        if ("FLOAT_VECTOR".equals(call.prop("data_type", "FLOAT"))) {
            call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
                float[][] v = new float[6][3];
                for (int k = 0; k < n; k++) {
                    for (int s = 0; s < 6; s++) {
                        in[s].getVector(k, v[s]);
                    }
                    for (int c = 0; c < 3; c++) {
                        out.f[3 * k + c] = NodeMath.mapRange(interpolation, v[0][c], v[1][c], v[2][c], v[3][c], v[4][c],
                                v[5][c], clamp);
                    }
                }
            }, call.field("Vector", DataType.VECTOR), call.field("From_Min_FLOAT3", DataType.VECTOR),
                    call.field("From_Max_FLOAT3", DataType.VECTOR), call.field("To_Min_FLOAT3", DataType.VECTOR),
                    call.field("To_Max_FLOAT3", DataType.VECTOR), call.field("Steps_FLOAT3", DataType.VECTOR)));
            return;
        }
        call.out("Result", Field.of(DataType.FLOAT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out.f[k] = NodeMath.mapRange(interpolation, in[0].getFloat(k), in[1].getFloat(k), in[2].getFloat(k),
                        in[3].getFloat(k), in[4].getFloat(k), in[5].getFloat(k), clamp);
            }
        }, call.field("Value", DataType.FLOAT), call.field("From Min", DataType.FLOAT), call.field("From Max", DataType.FLOAT),
                call.field("To Min", DataType.FLOAT), call.field("To Max", DataType.FLOAT), call.field("Steps", DataType.FLOAT)));
    }

    private static void mix(NodeCall call) {
        String dataType = call.prop("data_type", "FLOAT");
        boolean clampFactor = call.propBool("clamp_factor", true);
        switch (dataType) {
            case "VECTOR" -> {
                boolean nonUniform = "NON_UNIFORM".equals(call.prop("factor_mode", "UNIFORM"));
                Field factor = nonUniform ? call.field("Factor_Vector", DataType.VECTOR) : call.field("Factor_Float", DataType.FLOAT);
                call.out("Result_Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
                    float[] f = new float[3], a = new float[3], b = new float[3];
                    for (int k = 0; k < n; k++) {
                        if (nonUniform) {
                            in[0].getVector(k, f);
                        } else {
                            f[0] = f[1] = f[2] = in[0].getFloat(k);
                        }
                        in[1].getVector(k, a);
                        in[2].getVector(k, b);
                        for (int c = 0; c < 3; c++) {
                            float t = clampFactor ? Math.clamp(f[c], 0f, 1f) : f[c];
                            out.f[3 * k + c] = a[c] + (b[c] - a[c]) * t;
                        }
                    }
                }, factor, call.field("A_Vector", DataType.VECTOR), call.field("B_Vector", DataType.VECTOR)));
            }
            case "RGBA" -> {
                String blend = call.prop("blend_type", "MIX");
                boolean clampResult = call.propBool("clamp_result", false);
                call.out("Result_Color", Field.of(DataType.COLOR, (in, out, n) -> {
                    float[] a = new float[4], b = new float[4], r = new float[4];
                    for (int k = 0; k < n; k++) {
                        float t = in[0].getFloat(k);
                        if (clampFactor) {
                            t = Math.clamp(t, 0f, 1f);
                        }
                        in[1].getVector(k, a);
                        in[2].getVector(k, b);
                        ColorMath.blend(blend, t, a, b, r);
                        if ("MIX".equals(blend)) {
                            r[3] = a[3] + (b[3] - a[3]) * t;
                        }
                        for (int c = 0; c < 4; c++) {
                            out.f[4 * k + c] = clampResult && c < 3 ? Math.clamp(r[c], 0f, 1f) : r[c];
                        }
                    }
                }, call.field("Factor_Float", DataType.FLOAT), call.field("A_Color", DataType.COLOR),
                        call.field("B_Color", DataType.COLOR)));
            }
            case "ROTATION" -> call.out("Result_Rotation", Field.of(DataType.ROTATION, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    float t = in[0].getFloat(k);
                    if (clampFactor) {
                        t = Math.clamp(t, 0f, 1f);
                    }
                    slerp(in[1].f, 4 * k, in[2].f, 4 * k, t, out.f, 4 * k);
                }
            }, call.field("Factor_Float", DataType.FLOAT), call.field("A_Rotation", DataType.ROTATION),
                    call.field("B_Rotation", DataType.ROTATION)));
            default -> call.out("Result_Float", Field.of(DataType.FLOAT, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    float t = in[0].getFloat(k);
                    if (clampFactor) {
                        t = Math.clamp(t, 0f, 1f);
                    }
                    float a = in[1].getFloat(k);
                    out.f[k] = a + (in[2].getFloat(k) - a) * t;
                }
            }, call.field("Factor_Float", DataType.FLOAT), call.field("A_Float", DataType.FLOAT),
                    call.field("B_Float", DataType.FLOAT)));
        }
    }

    static void slerp(float[] a, int ao, float[] b, int bo, float t, float[] out, int oo) {
        double dot = 0;
        for (int c = 0; c < 4; c++) {
            dot += a[ao + c] * b[bo + c];
        }
        float sign = dot < 0 ? -1f : 1f;
        dot = Math.abs(dot);
        double w0, w1;
        if (dot > 0.9995) {
            w0 = 1 - t;
            w1 = t;
        } else {
            double theta = Math.acos(dot);
            double s = Math.sin(theta);
            w0 = Math.sin((1 - t) * theta) / s;
            w1 = Math.sin(t * theta) / s;
        }
        for (int c = 0; c < 4; c++) {
            out[oo + c] = (float) (w0 * a[ao + c] + w1 * sign * b[bo + c]);
        }
        Transforms.quatNormalize(out, oo);
    }

    private static void mixRgb(NodeCall call) {
        String blend = call.prop("blend_type", "MIX");
        boolean useAlpha = call.propBool("use_alpha", false);
        boolean clamp = call.propBool("use_clamp", false);
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] a = new float[4], b = new float[4], r = new float[4];
            for (int k = 0; k < n; k++) {
                float t = Math.clamp(in[0].getFloat(k), 0f, 1f);
                in[1].getVector(k, a);
                in[2].getVector(k, b);
                if (useAlpha) {
                    t *= b[3];
                }
                ColorMath.blend(blend, t, a, b, r);
                for (int c = 0; c < 4; c++) {
                    out.f[4 * k + c] = clamp && c < 3 ? Math.clamp(r[c], 0f, 1f) : r[c];
                }
            }
        }, call.field("Fac", DataType.FLOAT), call.field("Color1", DataType.COLOR), call.field("Color2", DataType.COLOR)));
    }

    private static void separateXyz(NodeCall call) {
        Field[] parts = Field.multi(new DataType[]{DataType.FLOAT, DataType.FLOAT, DataType.FLOAT}, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                out[0].f[k] = in[0].f[3 * k];
                out[1].f[k] = in[0].f[3 * k + 1];
                out[2].f[k] = in[0].f[3 * k + 2];
            }
        }, call.field("Vector", DataType.VECTOR));
        call.out("X", parts[0]);
        call.out("Y", parts[1]);
        call.out("Z", parts[2]);
    }

    private static void combineColor(NodeCall call) {
        String mode = call.prop("mode", "RGB");
        boolean hasAlpha = call.has("Alpha");
        Field alpha = hasAlpha ? call.field("Alpha", DataType.FLOAT) : Field.constant(DataType.FLOAT, 1f);
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] c = new float[3], rgb = new float[3];
            for (int k = 0; k < n; k++) {
                c[0] = in[0].getFloat(k);
                c[1] = in[1].getFloat(k);
                c[2] = in[2].getFloat(k);
                switch (mode) {
                    case "HSV" -> ColorMath.hsvToRgb(c, rgb);
                    case "HSL" -> ColorMath.hslToRgb(c, rgb);
                    default -> System.arraycopy(c, 0, rgb, 0, 3);
                }
                System.arraycopy(rgb, 0, out.f, 4 * k, 3);
                out.f[4 * k + 3] = in[3].getFloat(k);
            }
        }, call.field("Red", DataType.FLOAT), call.field("Green", DataType.FLOAT), call.field("Blue", DataType.FLOAT), alpha));
    }

    private static void separateColor(NodeCall call) {
        String mode = call.prop("mode", "RGB");
        Field[] parts = Field.multi(new DataType[]{DataType.FLOAT, DataType.FLOAT, DataType.FLOAT, DataType.FLOAT},
                (in, out, n) -> {
                    float[] c = new float[4], r = new float[3];
                    for (int k = 0; k < n; k++) {
                        in[0].getVector(k, c);
                        switch (mode) {
                            case "HSV" -> ColorMath.rgbToHsv(c, r);
                            case "HSL" -> ColorMath.rgbToHsl(c, r);
                            default -> System.arraycopy(c, 0, r, 0, 3);
                        }
                        out[0].f[k] = r[0];
                        out[1].f[k] = r[1];
                        out[2].f[k] = r[2];
                        out[3].f[k] = c[3];
                    }
                }, call.field("Color", DataType.COLOR));
        call.out("Red", parts[0]);
        call.out("Green", parts[1]);
        call.out("Blue", parts[2]);
        call.out("Alpha", parts[3]);
    }

    // --- Logic ---------------------------------------------------------------------------------------------

    private static void compare(NodeCall call) {
        String op = call.prop("operation", "GREATER_THAN");
        String type = call.prop("data_type", "FLOAT");
        String mode = call.prop("mode", "ELEMENT");
        Field epsilon = call.has("Epsilon") ? call.field("Epsilon", DataType.FLOAT) : Field.constant(DataType.FLOAT, 0.001f);
        switch (type) {
            case "INT" -> call.out("Result", Field.of(DataType.BOOL, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    int a = in[0].getInt(k), b = in[1].getInt(k);
                    out.i[k] = compareInt(op, a, b) ? 1 : 0;
                }
            }, call.field("A", DataType.INT), call.field("B", DataType.INT)));
            case "VECTOR" -> {
                Field c = call.has("C") ? call.field("C", DataType.FLOAT) : Field.constant(DataType.FLOAT, 0f);
                Field angle = call.has("Angle") ? call.field("Angle", DataType.FLOAT) : Field.constant(DataType.FLOAT, 0f);
                call.out("Result", Field.of(DataType.BOOL, (in, out, n) -> {
                    float[] a = new float[3], b = new float[3];
                    for (int k = 0; k < n; k++) {
                        in[0].getVector(k, a);
                        in[1].getVector(k, b);
                        out.i[k] = compareVector(op, mode, a, b, in[2].getFloat(k), in[3].getFloat(k), in[4].getFloat(k)) ? 1 : 0;
                    }
                }, call.field("A", DataType.VECTOR), call.field("B", DataType.VECTOR), c, angle, epsilon));
            }
            case "RGBA" -> call.out("Result", Field.of(DataType.BOOL, (in, out, n) -> {
                float[] a = new float[4], b = new float[4];
                for (int k = 0; k < n; k++) {
                    in[0].getVector(k, a);
                    in[1].getVector(k, b);
                    float eps = in[2].getFloat(k);
                    boolean r = switch (op) {
                        case "EQUAL" -> Math.abs(a[0] - b[0]) <= eps && Math.abs(a[1] - b[1]) <= eps && Math.abs(a[2] - b[2]) <= eps;
                        case "NOT_EQUAL" -> Math.abs(a[0] - b[0]) > eps || Math.abs(a[1] - b[1]) > eps || Math.abs(a[2] - b[2]) > eps;
                        case "BRIGHTER" -> ColorMath.luminance(a[0], a[1], a[2]) > ColorMath.luminance(b[0], b[1], b[2]) + eps;
                        case "DARKER" -> ColorMath.luminance(a[0], a[1], a[2]) < ColorMath.luminance(b[0], b[1], b[2]) - eps;
                        default -> false;
                    };
                    out.i[k] = r ? 1 : 0;
                }
            }, call.field("A", DataType.COLOR), call.field("B", DataType.COLOR), epsilon));
            case "STRING" -> {
                boolean equal = call.string("A").equals(call.string("B"));
                call.out("Result", Field.constant(DataType.BOOL, "NOT_EQUAL".equals(op) != equal));
            }
            default -> call.out("Result", Field.of(DataType.BOOL, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.i[k] = compareFloat(op, in[0].getFloat(k), in[1].getFloat(k), in[2].getFloat(k)) ? 1 : 0;
                }
            }, call.field("A", DataType.FLOAT), call.field("B", DataType.FLOAT), epsilon));
        }
    }

    static boolean compareFloat(String op, float a, float b, float epsilon) {
        return switch (op) {
            case "LESS_THAN" -> a < b;
            case "LESS_EQUAL" -> a <= b;
            case "GREATER_THAN" -> a > b;
            case "GREATER_EQUAL" -> a >= b;
            case "EQUAL" -> Math.abs(a - b) <= epsilon;
            case "NOT_EQUAL" -> Math.abs(a - b) > epsilon;
            default -> false;
        };
    }

    private static boolean compareInt(String op, int a, int b) {
        return switch (op) {
            case "LESS_THAN" -> a < b;
            case "LESS_EQUAL" -> a <= b;
            case "GREATER_THAN" -> a > b;
            case "GREATER_EQUAL" -> a >= b;
            case "EQUAL" -> a == b;
            case "NOT_EQUAL" -> a != b;
            default -> false;
        };
    }

    private static boolean compareVector(String op, String mode, float[] a, float[] b, float c, float angle, float eps) {
        switch (mode) {
            case "LENGTH" -> {
                return compareFloat(op, (float) Math.sqrt(NodeMath.dot(a, a)), (float) Math.sqrt(NodeMath.dot(b, b)), eps);
            }
            case "AVERAGE" -> {
                return compareFloat(op, (a[0] + a[1] + a[2]) / 3f, (b[0] + b[1] + b[2]) / 3f, eps);
            }
            case "DOT_PRODUCT" -> {
                return compareFloat(op, NodeMath.dot(a, b), c, eps);
            }
            case "DIRECTION" -> {
                float[] na = NodeMath.normalized(a), nb = NodeMath.normalized(b);
                float between = (float) Math.acos(Math.clamp(NodeMath.dot(na, nb), -1f, 1f));
                return compareFloat(op, between, angle, eps);
            }
            default -> {
                if ("NOT_EQUAL".equals(op)) {
                    return Math.abs(a[0] - b[0]) > eps || Math.abs(a[1] - b[1]) > eps || Math.abs(a[2] - b[2]) > eps;
                }
                return compareFloat(op, a[0], b[0], eps) && compareFloat(op, a[1], b[1], eps) && compareFloat(op, a[2], b[2], eps);
            }
        }
    }

    private static void booleanMath(NodeCall call) {
        String op = call.prop("operation", "AND");
        call.out("Boolean", Field.of(DataType.BOOL, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                boolean a = in[0].getBool(k), b = in[1].getBool(k);
                boolean r = switch (op) {
                    case "OR" -> a || b;
                    case "NOT" -> !a;
                    case "NAND" -> !(a && b);
                    case "NOR" -> !(a || b);
                    case "XNOR" -> a == b;
                    case "XOR" -> a != b;
                    case "IMPLY" -> !a || b;
                    case "NIMPLY" -> a && !b;
                    default -> a && b;
                };
                out.i[k] = r ? 1 : 0;
            }
        }, call.field("Boolean", DataType.BOOL), call.field("Boolean_001", DataType.BOOL)));
    }

    private static void integerMath(NodeCall call) {
        String op = call.prop("operation", "ADD");
        call.out("Value", Field.of(DataType.INT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                int a = in[0].getInt(k), b = in[1].getInt(k), c = in[2].getInt(k);
                out.i[k] = switch (op) {
                    case "SUBTRACT" -> a - b;
                    case "MULTIPLY" -> a * b;
                    case "DIVIDE" -> b != 0 ? a / b : 0;
                    case "MULTIPLY_ADD" -> a * b + c;
                    case "ABSOLUTE" -> Math.abs(a);
                    case "NEGATE" -> -a;
                    case "POWER" -> (int) Math.pow(a, b);
                    case "MINIMUM" -> Math.min(a, b);
                    case "MAXIMUM" -> Math.max(a, b);
                    case "SIGN" -> Integer.signum(a);
                    case "DIVIDE_ROUND" -> b != 0 ? (int) Math.round((double) a / b) : 0;
                    case "DIVIDE_FLOOR" -> b != 0 ? (int) Math.floor((double) a / b) : 0;
                    case "DIVIDE_CEIL" -> b != 0 ? (int) Math.ceil((double) a / b) : 0;
                    case "FLOORED_MODULO" -> b != 0 ? Math.floorMod(a, b) : 0;
                    case "MODULO" -> b != 0 ? a % b : 0;
                    case "GCD" -> gcd(a, b);
                    case "LCM" -> a == 0 || b == 0 ? 0 : Math.abs(a / gcd(a, b) * b);
                    default -> a + b;
                };
            }
        }, call.field("Value", DataType.INT), call.field("Value_001", DataType.INT), call.field("Value_002", DataType.INT)));
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    private static void bitMath(NodeCall call) {
        String op = call.prop("operation", "AND");
        call.out("Value", Field.of(DataType.INT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                int a = in[0].getInt(k), b = in[1].getInt(k), s = in[2].getInt(k);
                out.i[k] = switch (op) {
                    case "OR" -> a | b;
                    case "XOR" -> a ^ b;
                    case "NOT" -> ~a;
                    case "SHIFT" -> s >= 0 ? a << s : a >>> -s;
                    case "ROTATE" -> Integer.rotateLeft(a, s);
                    default -> a & b;
                };
            }
        }, call.field("A", DataType.INT), call.field("B", DataType.INT), call.field("Shift", DataType.INT)));
    }

    private static void floatToInt(NodeCall call) {
        String mode = call.prop("rounding_mode", "ROUND");
        call.out("Integer", Field.of(DataType.INT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float f = in[0].getFloat(k);
                out.i[k] = switch (mode) {
                    case "FLOOR" -> (int) Math.floor(f);
                    case "CEILING" -> (int) Math.ceil(f);
                    case "TRUNCATE" -> (int) f;
                    default -> (int) Math.floor(f + 0.5f);
                };
            }
        }, call.field("Float", DataType.FLOAT)));
    }

    // --- Randomness ----------------------------------------------------------------------------------------

    private static void randomValue(NodeCall call) {
        String type = call.prop("data_type", "FLOAT");
        Field id = call.field("ID", DataType.INT);
        Field seed = call.field("Seed", DataType.INT);
        switch (type) {
            case "INT" -> call.out("Value", Field.of(DataType.INT, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.i[k] = RandomValue.intValue(in[0].getInt(k), in[1].getInt(k), in[2].getInt(k), in[3].getInt(k));
                }
            }, call.field("Min", DataType.INT), call.field("Max", DataType.INT), id, seed));
            case "FLOAT_VECTOR" -> call.out("Value", Field.of(DataType.VECTOR, (in, out, n) -> {
                float[] min = new float[3], max = new float[3], r = new float[3];
                for (int k = 0; k < n; k++) {
                    in[0].getVector(k, min);
                    in[1].getVector(k, max);
                    RandomValue.vectorValue(min, max, in[2].getInt(k), in[3].getInt(k), r);
                    out.setVector(k, r[0], r[1], r[2]);
                }
            }, call.field("Min", DataType.VECTOR), call.field("Max", DataType.VECTOR), id, seed));
            case "BOOLEAN" -> call.out("Value", Field.of(DataType.BOOL, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.i[k] = RandomValue.boolValue(in[0].getFloat(k), in[1].getInt(k), in[2].getInt(k)) ? 1 : 0;
                }
            }, call.field("Probability", DataType.FLOAT), id, seed));
            default -> call.out("Value", Field.of(DataType.FLOAT, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.f[k] = RandomValue.floatValue(in[0].getFloat(k), in[1].getFloat(k), in[2].getInt(k), in[3].getInt(k));
                }
            }, call.field("Min", DataType.FLOAT), call.field("Max", DataType.FLOAT), id, seed));
        }
    }

    private static void hashValue(NodeCall call) {
        String type = call.prop("data_type", "INT");
        Field seed = call.field("Seed", DataType.INT);
        switch (type) {
            case "FLOAT" -> call.out("Hash", Field.of(DataType.INT, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.i[k] = RandomValue.hashFloat(in[0].getFloat(k), in[1].getInt(k));
                }
            }, call.field("Value", DataType.FLOAT), seed));
            case "VECTOR" -> call.out("Hash", Field.of(DataType.INT, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.i[k] = RandomValue.hashVector(in[0].f[3 * k], in[0].f[3 * k + 1], in[0].f[3 * k + 2], in[1].getInt(k));
                }
            }, call.field("Value", DataType.VECTOR), seed));
            default -> call.out("Hash", Field.of(DataType.INT, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.i[k] = RandomValue.hashInt(in[0].getInt(k), in[1].getInt(k));
                }
            }, call.field("Value", DataType.INT), seed));
        }
    }

    // --- Curves and ramps ----------------------------------------------------------------------------------

    private static void colorRamp(NodeCall call) {
        ColorRamp ramp = call.node.ramp;
        Field[] parts = Field.multi(new DataType[]{DataType.COLOR, DataType.FLOAT}, (in, out, n) -> {
            float[] c = new float[4];
            for (int k = 0; k < n; k++) {
                if (ramp != null) {
                    ramp.evaluate(in[0].getFloat(k), c);
                }
                System.arraycopy(c, 0, out[0].f, 4 * k, 4);
                out[1].f[k] = c[3];
            }
        }, call.field("Fac", DataType.FLOAT));
        call.out("Color", parts[0]);
        call.out("Alpha", parts[1]);
    }

    private static void floatCurve(NodeCall call) {
        CurveMapping curves = call.node.curves;
        call.out("Value", Field.of(DataType.FLOAT, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                float factor = in[0].getFloat(k);
                float value = in[1].getFloat(k);
                float mapped = curves == null ? value : curves.evaluateClipped(0, value);
                out.f[k] = value + (mapped - value) * factor;
            }
        }, call.field("Factor", DataType.FLOAT), call.field("Value", DataType.FLOAT)));
    }

    private static void rgbCurve(NodeCall call) {
        CurveMapping curves = call.node.curves;
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] c = new float[4];
            for (int k = 0; k < n; k++) {
                float factor = in[0].getFloat(k);
                in[1].getVector(k, c);
                for (int ch = 0; ch < 3; ch++) {
                    float v = c[ch];
                    if (curves != null) {
                        float level = curves.whiteLevel[ch] - curves.blackLevel[ch];
                        float x = level != 0f ? (v - curves.blackLevel[ch]) / level : v;
                        v = curves.evaluate(ch, curves.evaluate(3, x));
                    }
                    out.f[4 * k + ch] = c[ch] + (v - c[ch]) * factor;
                }
                out.f[4 * k + 3] = c[3];
            }
        }, call.field("Fac", DataType.FLOAT), call.field("Color", DataType.COLOR)));
    }

    private static void vectorCurve(NodeCall call) {
        CurveMapping curves = call.node.curves;
        call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            float[] v = new float[3];
            for (int k = 0; k < n; k++) {
                float factor = in[0].getFloat(k);
                in[1].getVector(k, v);
                for (int c = 0; c < 3; c++) {
                    float mapped = curves == null ? v[c] : curves.evaluateClipped(c, v[c]);
                    out.f[3 * k + c] = v[c] + (mapped - v[c]) * factor;
                }
            }
        }, call.field("Fac", DataType.FLOAT), call.field("Vector", DataType.VECTOR)));
    }

    private static void vectorRotate(NodeCall call) {
        String type = call.prop("rotation_type", "AXIS_ANGLE");
        boolean invert = call.propBool("invert", false);
        call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            float[] v = new float[3], c = new float[3], a = new float[3], e = new float[3], r = new float[3];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, v);
                in[1].getVector(k, c);
                in[2].getVector(k, a);
                in[4].getVector(k, e);
                NodeMath.vectorRotate(type, invert, v, c, a, in[3].getFloat(k), e, r);
                out.setVector(k, r[0], r[1], r[2]);
            }
        }, call.field("Vector", DataType.VECTOR), call.field("Center", DataType.VECTOR), call.field("Axis", DataType.VECTOR),
                call.field("Angle", DataType.FLOAT), call.field("Rotation", DataType.VECTOR)));
    }

    private static void mapping(NodeCall call) {
        String type = call.prop("vector_type", "POINT");
        Field location = call.has("Location") ? call.field("Location", DataType.VECTOR) : Field.constant(DataType.VECTOR, 0f);
        call.out("Vector", Field.of(DataType.VECTOR, (in, out, n) -> {
            float[] v = new float[3], l = new float[3], r = new float[3], s = new float[3], o = new float[3];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, v);
                in[1].getVector(k, l);
                in[2].getVector(k, r);
                in[3].getVector(k, s);
                NodeMath.mapping(type, v, l, r, s, o);
                out.setVector(k, o[0], o[1], o[2]);
            }
        }, call.field("Vector", DataType.VECTOR), location, call.field("Rotation", DataType.VECTOR),
                call.field("Scale", DataType.VECTOR)));
    }

    private static void invert(NodeCall call) {
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] c = new float[4];
            for (int k = 0; k < n; k++) {
                float f = in[0].getFloat(k);
                in[1].getVector(k, c);
                for (int ch = 0; ch < 3; ch++) {
                    out.f[4 * k + ch] = c[ch] + (1f - c[ch] - c[ch]) * f;
                }
                out.f[4 * k + 3] = c[3];
            }
        }, call.field("Fac", DataType.FLOAT), call.field("Color", DataType.COLOR)));
    }

    private static void gamma(NodeCall call) {
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] c = new float[4];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, c);
                float g = in[1].getFloat(k);
                for (int ch = 0; ch < 3; ch++) {
                    out.f[4 * k + ch] = c[ch] > 0f ? (float) Math.pow(c[ch], g) : c[ch];
                }
                out.f[4 * k + 3] = c[3];
            }
        }, call.field("Color", DataType.COLOR), call.field("Gamma", DataType.FLOAT)));
    }

    private static void brightContrast(NodeCall call) {
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] c = new float[4];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, c);
                float bright = in[1].getFloat(k), contrast = in[2].getFloat(k);
                float a = 1f + contrast, b = bright - contrast * 0.5f;
                for (int ch = 0; ch < 3; ch++) {
                    out.f[4 * k + ch] = Math.max(a * c[ch] + b, 0f);
                }
                out.f[4 * k + 3] = c[3];
            }
        }, call.field("Color", DataType.COLOR), call.field("Bright", DataType.FLOAT), call.field("Contrast", DataType.FLOAT)));
    }

    private static void hueSaturation(NodeCall call) {
        call.out("Color", Field.of(DataType.COLOR, (in, out, n) -> {
            float[] c = new float[4], hsv = new float[3], rgb = new float[3];
            for (int k = 0; k < n; k++) {
                float hue = in[0].getFloat(k), sat = in[1].getFloat(k), val = in[2].getFloat(k), fac = in[3].getFloat(k);
                in[4].getVector(k, c);
                ColorMath.rgbToHsv(c, hsv);
                hsv[0] = hsv[0] + hue + 0.5f;
                hsv[0] -= (float) Math.floor(hsv[0]);
                hsv[1] = Math.clamp(hsv[1] * sat, 0f, 1f);
                hsv[2] *= val;
                ColorMath.hsvToRgb(hsv, rgb);
                for (int ch = 0; ch < 3; ch++) {
                    out.f[4 * k + ch] = Math.max(c[ch] + (rgb[ch] - c[ch]) * fac, 0f);
                }
                out.f[4 * k + 3] = c[3];
            }
        }, call.field("Hue", DataType.FLOAT), call.field("Saturation", DataType.FLOAT), call.field("Value", DataType.FLOAT),
                call.field("Fac", DataType.FLOAT), call.field("Color", DataType.COLOR)));
    }

    // --- Rotations -----------------------------------------------------------------------------------------

    private static void rotationToAxisAngle(NodeCall call) {
        Field[] parts = Field.multi(new DataType[]{DataType.VECTOR, DataType.FLOAT}, (in, out, n) -> {
            float[] r = new float[4];
            for (int k = 0; k < n; k++) {
                Transforms.quatToAxisAngle(in[0].f, 4 * k, r);
                out[0].setVector(k, r[0], r[1], r[2]);
                out[1].f[k] = r[3];
            }
        }, call.field("Rotation", DataType.ROTATION));
        call.out("Axis", parts[0]);
        call.out("Angle", parts[1]);
    }

    private static void rotateEuler(NodeCall call) {
        boolean axisAngle = "AXIS_ANGLE".equals(call.prop("rotation_type", "EULER"));
        boolean local = "LOCAL".equals(call.prop("space", "OBJECT"));
        Field by = axisAngle ? Field.of(DataType.ROTATION, (in, out, n) -> {
            float[] a = new float[3];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, a);
                Transforms.axisAngleToQuat(a[0], a[1], a[2], in[1].getFloat(k), out.f, 4 * k);
            }
        }, call.field("Axis", DataType.VECTOR), call.field("Angle", DataType.FLOAT)) : call.field("Rotate By", DataType.ROTATION);
        call.out("Rotation", Field.of(DataType.VECTOR, (in, out, n) -> {
            float[] q = new float[4];
            for (int k = 0; k < n; k++) {
                if (local) {
                    Transforms.quatMul(in[0].f, 4 * k, in[1].f, 4 * k, q, 0);
                } else {
                    Transforms.quatMul(in[1].f, 4 * k, in[0].f, 4 * k, q, 0);
                }
                Transforms.quatToEuler(q, 0, out.f, 3 * k);
            }
        }, call.field("Rotation", DataType.ROTATION), by));
    }

    private static float[] axisVector(String axis) {
        return switch (axis) {
            case "X" -> new float[]{1f, 0f, 0f};
            case "Y" -> new float[]{0f, 1f, 0f};
            default -> new float[]{0f, 0f, 1f};
        };
    }

    private static void alignToVector(NodeCall call, boolean euler) {
        float[] mainAxis = axisVector(call.prop("axis", "X"));
        String pivot = call.prop("pivot_axis", "AUTO");
        float[] pivotAxis = "AUTO".equals(pivot) ? null : axisVector(pivot);
        Field rotation = call.field("Rotation", DataType.ROTATION);
        Field result = Field.of(DataType.ROTATION, (in, out, n) -> {
            float[] vector = new float[3];
            for (int k = 0; k < n; k++) {
                in[2].getVector(k, vector);
                align(in[0].f, 4 * k, vector, mainAxis, pivotAxis, in[1].getFloat(k), out.f, 4 * k);
            }
        }, rotation, call.field("Factor", DataType.FLOAT), call.field("Vector", DataType.VECTOR));
        call.out("Rotation", euler ? result.as(DataType.VECTOR) : result);
    }

    static void align(float[] q, int qo, float[] vector, float[] mainAxis, float[] pivotAxis, float factor,
                      float[] out, int oo) {
        System.arraycopy(q, qo, out, oo, 4);
        if (vector[0] == 0f && vector[1] == 0f && vector[2] == 0f) {
            return;
        }
        float[] oldAxis = new float[3];
        Transforms.rotate(q, qo, mainAxis, 0, oldAxis, 0);
        float[] rotationAxis;
        float angle;
        if (pivotAxis == null) {
            float[] newAxis = NodeMath.normalized(vector);
            double[] axis = crossD(oldAxis, newAxis);
            if (isZero(axis)) {
                axis = crossD(oldAxis, new float[]{1f, 0f, 0f});
                if (isZero(axis)) {
                    axis = crossD(oldAxis, new float[]{0f, 1f, 0f});
                }
            }
            double len = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            rotationAxis = new float[]{(float) (axis[0] / len), (float) (axis[1] / len), (float) (axis[2] / len)};
            float[] na = NodeMath.normalized(oldAxis);
            angle = factor * (float) Math.acos(Math.clamp(NodeMath.dot(na, newAxis), -1f, 1f));
        } else {
            if (java.util.Arrays.equals(mainAxis, pivotAxis)) {
                return;
            }
            rotationAxis = new float[3];
            Transforms.rotate(q, qo, pivotAxis, 0, rotationAxis, 0);
            float full = signedAngleOnAxis(vector, oldAxis, rotationAxis);
            if (full > Math.PI) {
                full -= (float) (2 * Math.PI);
            }
            angle = factor * full;
        }
        float[] r = new float[4];
        Transforms.axisAngleToQuat(rotationAxis[0], rotationAxis[1], rotationAxis[2], angle, r, 0);
        Transforms.quatMul(r, 0, q, qo, out, oo);
    }

    private static double[] crossD(float[] a, float[] b) {
        return new double[]{(double) a[1] * b[2] - (double) a[2] * b[1], (double) a[2] * b[0] - (double) a[0] * b[2],
                (double) a[0] * b[1] - (double) a[1] * b[0]};
    }

    private static boolean isZero(double[] v) {
        return v[0] == 0 && v[1] == 0 && v[2] == 0;
    }

    /** Blender's angle_signed_on_axis_v3v3_v3: the angle from v1 to v2 projected around the axis, in [0, 2pi). */
    static float signedAngleOnAxis(float[] v1, float[] v2, float[] axis) {
        float[] n = NodeMath.normalized(axis);
        float d1 = NodeMath.dot(v1, n), d2 = NodeMath.dot(v2, n);
        float[] p1 = {v1[0] - n[0] * d1, v1[1] - n[1] * d1, v1[2] - n[2] * d1};
        float[] p2 = {v2[0] - n[0] * d2, v2[1] - n[1] * d2, v2[2] - n[2] * d2};
        float[] a = NodeMath.normalized(p1), b = NodeMath.normalized(p2);
        double angle = Math.acos(Math.clamp(NodeMath.dot(a, b), -1f, 1f));
        double cx = p1[1] * p2[2] - p1[2] * p2[1];
        double cy = p1[2] * p2[0] - p1[0] * p2[2];
        double cz = p1[0] * p2[1] - p1[1] * p2[0];
        if (cx * n[0] + cy * n[1] + cz * n[2] < 0) {
            angle = 2 * Math.PI - angle;
        }
        return (float) angle;
    }

    private static void axesToRotation(NodeCall call) {
        int primaryAxis = "XYZ".indexOf(call.prop("primary_axis", "Z"));
        int secondaryAxis = "XYZ".indexOf(call.prop("secondary_axis", "X"));
        int tertiaryAxis = 3 - primaryAxis - secondaryAxis;
        call.out("Rotation", Field.of(DataType.ROTATION, (in, out, n) -> {
            float[] p = new float[3], s = new float[3];
            for (int k = 0; k < n; k++) {
                in[0].getVector(k, p);
                in[1].getVector(k, s);
                float[] primary = NodeMath.normalized(p);
                float[] secondary = s;
                float[] tertiary;
                boolean pz = primary[0] == 0f && primary[1] == 0f && primary[2] == 0f;
                boolean sz = s[0] == 0f && s[1] == 0f && s[2] == 0f;
                if (!pz && !sz) {
                    tertiary = cross(primary, secondary);
                    if (tertiary[0] == 0f && tertiary[1] == 0f && tertiary[2] == 0f) {
                        tertiary = orthogonal(primary);
                    }
                    tertiary = NodeMath.normalized(tertiary);
                    secondary = cross(tertiary, primary);
                } else if (!pz) {
                    secondary = NodeMath.normalized(orthogonal(primary));
                    tertiary = cross(primary, secondary);
                } else if (!sz) {
                    secondary = NodeMath.normalized(secondary);
                    primary = NodeMath.normalized(orthogonal(secondary));
                    tertiary = cross(primary, secondary);
                } else {
                    out.f[4 * k] = 1f;
                    continue;
                }
                double[] m = new double[9];
                for (int c = 0; c < 3; c++) {
                    m[primaryAxis * 3 + c] = primary[c];
                    m[secondaryAxis * 3 + c] = secondary[c];
                    m[tertiaryAxis * 3 + c] = tertiary[c];
                }
                double det = m[0] * (m[4] * m[8] - m[7] * m[5]) - m[3] * (m[1] * m[8] - m[7] * m[2])
                        + m[6] * (m[1] * m[5] - m[4] * m[2]);
                if (det < 0) {
                    for (int c = 0; c < 3; c++) {
                        m[tertiaryAxis * 3 + c] = -tertiary[c];
                    }
                }
                Transforms.mat3ToQuat(m, out.f, 4 * k);
            }
        }, call.field("Primary Axis", DataType.VECTOR), call.field("Secondary Axis", DataType.VECTOR)));
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** Blender's math::orthogonal: a vector perpendicular to v. */
    private static float[] orthogonal(float[] v) {
        float ax = Math.abs(v[0]), ay = Math.abs(v[1]), az = Math.abs(v[2]);
        int axis = ax >= ay && ax >= az ? 0 : (ay >= az ? 1 : 2);
        return switch (axis) {
            case 0 -> new float[]{-v[1] - v[2], v[0], v[0]};
            case 1 -> new float[]{v[1], -v[0] - v[2], v[1]};
            default -> new float[]{v[2], v[2], -v[0] - v[1]};
        };
    }

    private static void combineMatrix(NodeCall call) {
        Field[] inputs = new Field[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                inputs[c * 4 + r] = call.field("Column " + (c + 1) + " Row " + (r + 1), DataType.FLOAT);
            }
        }
        call.out("Matrix", Field.of(DataType.MATRIX, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                for (int e = 0; e < 16; e++) {
                    out.f[16 * k + e] = in[e].getFloat(k);
                }
            }
        }, inputs));
    }

    private static void separateMatrix(NodeCall call) {
        DataType[] types = new DataType[16];
        java.util.Arrays.fill(types, DataType.FLOAT);
        Field[] parts = Field.multi(types, (in, out, n) -> {
            for (int k = 0; k < n; k++) {
                for (int e = 0; e < 16; e++) {
                    out[e].f[k] = in[0].f[16 * k + e];
                }
            }
        }, call.field("Matrix", DataType.MATRIX));
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                call.out("Column " + (c + 1) + " Row " + (r + 1), parts[c * 4 + r]);
            }
        }
    }

    // --- Switches ------------------------------------------------------------------------------------------

    private static void switchNode(NodeCall call) {
        Socket falseSocket = call.inputSocket("False");
        SocketType type = falseSocket == null ? SocketType.CUSTOM : falseSocket.type;
        Field condition = call.field("Switch", DataType.BOOL);
        if (type.isFieldType() && !condition.isConstant()) {
            DataType data = DataType.of(type);
            call.out("Output", Field.of(data, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    out.copyElement(in[0].getBool(k) ? in[2] : in[1], k, k);
                }
            }, condition, call.field("False", data), call.field("True", data)));
            return;
        }
        call.out("Output", call.raw(condition.singleBool() ? "True" : "False"));
    }

    private static void indexSwitch(NodeCall call) {
        List<Socket> items = new java.util.ArrayList<>();
        for (Socket socket : call.node.inputs()) {
            if (socket.id.startsWith("Item_")) {
                items.add(socket);
            }
        }
        Field index = call.field("Index", DataType.INT);
        SocketType type = items.isEmpty() ? SocketType.CUSTOM : items.get(0).type;
        if (type.isFieldType() && !index.isConstant()) {
            DataType data = DataType.of(type);
            Field[] fields = new Field[items.size() + 1];
            fields[0] = index;
            for (int k = 0; k < items.size(); k++) {
                fields[k + 1] = call.field(items.get(k).id, data);
            }
            call.out("Output", Field.of(data, (in, out, n) -> {
                for (int k = 0; k < n; k++) {
                    int i = in[0].getInt(k);
                    if (i >= 0 && i < items.size()) {
                        out.copyElement(in[i + 1], k, k);
                    }
                }
            }, fields));
            return;
        }
        int i = index.singleInt();
        if (i >= 0 && i < items.size()) {
            call.out("Output", call.raw(items.get(i).id));
        }
    }

    private static void menuSwitch(NodeCall call) {
        List<Map<String, Object>> enumItems = call.node.items("enum_items");
        List<Socket> items = new java.util.ArrayList<>();
        for (Socket socket : call.node.inputs()) {
            if (socket.id.startsWith("Item_")) {
                items.add(socket);
            }
        }
        String selected = call.string("Menu");
        int chosen = -1;
        for (int k = 0; k < enumItems.size(); k++) {
            if (selected.equals(String.valueOf(enumItems.get(k).get("name")))) {
                chosen = k;
            }
        }
        if (chosen < 0) {
            for (int k = 0; k < items.size(); k++) {
                if (selected.equals(items.get(k).name)) {
                    chosen = k;
                }
            }
        }
        for (int k = 0; k < items.size(); k++) {
            Socket selection = call.node.output(items.get(k).id);
            if (selection != null && selection.type == SocketType.BOOLEAN) {
                call.out(selection.id, Field.constant(DataType.BOOL, k == chosen));
            }
        }
        if (chosen >= 0 && chosen < items.size()) {
            call.out("Output", call.raw(items.get(chosen).id));
        }
    }
}

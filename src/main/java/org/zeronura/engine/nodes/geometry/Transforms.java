package org.zeronura.engine.nodes.geometry;

/**
 * Rotation and matrix maths on flat float arrays, as Blender does it: quaternions are (w, x, y, z), Euler angles
 * are XYZ (X applied first), matrices are 4x4 column-major. Every method reads and writes at an offset so whole
 * columns can be processed without allocating per element.
 */
public final class Transforms {

    private Transforms() {
    }

    public static void identity(float[] m, int o) {
        for (int k = 0; k < 16; k++) {
            m[o + k] = (k % 5 == 0) ? 1f : 0f;
        }
    }

    public static float[] identity() {
        float[] m = new float[16];
        identity(m, 0);
        return m;
    }

    // --- Quaternions -------------------------------------------------------------------------------------

    public static void eulerToQuat(float x, float y, float z, float[] q, int o) {
        double ti = x * 0.5, tj = y * 0.5, th = z * 0.5;
        double ci = Math.cos(ti), cj = Math.cos(tj), ch = Math.cos(th);
        double si = Math.sin(ti), sj = Math.sin(tj), sh = Math.sin(th);
        double cc = ci * ch, cs = ci * sh, sc = si * ch, ss = si * sh;
        q[o] = (float) (cj * cc + sj * ss);
        q[o + 1] = (float) (cj * sc - sj * cs);
        q[o + 2] = (float) (cj * ss + sj * cc);
        q[o + 3] = (float) (cj * cs - sj * sc);
    }

    /** The 3x3 rotation of a quaternion, as columns, into {@code m} (9 floats). Normalises first. */
    public static void quatToMat3(float[] q, int o, double[] m) {
        double w = q[o], x = q[o + 1], y = q[o + 2], z = q[o + 3];
        double len = Math.sqrt(w * w + x * x + y * y + z * z);
        if (len > 1e-12) {
            w /= len;
            x /= len;
            y /= len;
            z /= len;
        } else {
            w = 1;
            x = y = z = 0;
        }
        double s = Math.sqrt(2.0);
        double q0 = s * w, q1 = s * x, q2 = s * y, q3 = s * z;
        double qda = q0 * q1, qdb = q0 * q2, qdc = q0 * q3;
        double qaa = q1 * q1, qab = q1 * q2, qac = q1 * q3;
        double qbb = q2 * q2, qbc = q2 * q3, qcc = q3 * q3;
        m[0] = 1.0 - qbb - qcc;
        m[1] = qdc + qab;
        m[2] = -qdb + qac;
        m[3] = -qdc + qab;
        m[4] = 1.0 - qaa - qcc;
        m[5] = qda + qbc;
        m[6] = qdb + qac;
        m[7] = -qda + qbc;
        m[8] = 1.0 - qaa - qbb;
    }

    public static void quatToMatrix(float[] q, int qo, float[] m, int mo) {
        double[] r = new double[9];
        quatToMat3(q, qo, r);
        identity(m, mo);
        for (int c = 0; c < 3; c++) {
            for (int row = 0; row < 3; row++) {
                m[mo + c * 4 + row] = (float) r[c * 3 + row];
            }
        }
    }

    /** Euler XYZ from a normalised 3x3 rotation (columns), choosing the smaller of Blender's two solutions. */
    public static void mat3ToEuler(double[] m, float[] e, int o) {
        double cy = Math.hypot(m[0], m[1]);
        double[] e1 = new double[3];
        double[] e2 = new double[3];
        if (cy > 16.0 * 1.1920929e-7) {
            e1[0] = Math.atan2(m[5], m[8]);
            e1[1] = Math.atan2(-m[2], cy);
            e1[2] = Math.atan2(m[1], m[0]);
            e2[0] = Math.atan2(-m[5], -m[8]);
            e2[1] = Math.atan2(-m[2], -cy);
            e2[2] = Math.atan2(-m[1], -m[0]);
        } else {
            e1[0] = Math.atan2(-m[7], m[4]);
            e1[1] = Math.atan2(-m[2], cy);
            e1[2] = 0.0;
            System.arraycopy(e1, 0, e2, 0, 3);
        }
        double[] pick = Math.abs(e1[0]) + Math.abs(e1[1]) + Math.abs(e1[2])
                > Math.abs(e2[0]) + Math.abs(e2[1]) + Math.abs(e2[2]) ? e2 : e1;
        e[o] = (float) pick[0];
        e[o + 1] = (float) pick[1];
        e[o + 2] = (float) pick[2];
    }

    public static void quatToEuler(float[] q, int qo, float[] e, int eo) {
        double[] m = new double[9];
        quatToMat3(q, qo, m);
        mat3ToEuler(m, e, eo);
    }

    /** a * b: rotate by b, then by a. */
    public static void quatMul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float aw = a[ao], ax = a[ao + 1], ay = a[ao + 2], az = a[ao + 3];
        float bw = b[bo], bx = b[bo + 1], by = b[bo + 2], bz = b[bo + 3];
        out[oo] = aw * bw - ax * bx - ay * by - az * bz;
        out[oo + 1] = aw * bx + ax * bw + ay * bz - az * by;
        out[oo + 2] = aw * by + ay * bw + az * bx - ax * bz;
        out[oo + 3] = aw * bz + az * bw + ax * by - ay * bx;
    }

    public static void quatInvert(float[] q, int o, float[] out, int oo) {
        float len2 = q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3];
        float inv = len2 > 0f ? 1f / len2 : 0f;
        out[oo] = q[o] * inv;
        out[oo + 1] = -q[o + 1] * inv;
        out[oo + 2] = -q[o + 2] * inv;
        out[oo + 3] = -q[o + 3] * inv;
    }

    public static void quatNormalize(float[] q, int o) {
        float len = (float) Math.sqrt(q[o] * q[o] + q[o + 1] * q[o + 1] + q[o + 2] * q[o + 2] + q[o + 3] * q[o + 3]);
        if (len > 1e-12f) {
            for (int k = 0; k < 4; k++) {
                q[o + k] /= len;
            }
        } else {
            q[o] = 1f;
            q[o + 1] = q[o + 2] = q[o + 3] = 0f;
        }
    }

    /** Rotates the vector {@code v[vo..vo+2]} by the quaternion, into {@code out}. */
    public static void rotate(float[] q, int qo, float[] v, int vo, float[] out, int oo) {
        double[] m = new double[9];
        quatToMat3(q, qo, m);
        float x = v[vo], y = v[vo + 1], z = v[vo + 2];
        out[oo] = (float) (m[0] * x + m[3] * y + m[6] * z);
        out[oo + 1] = (float) (m[1] * x + m[4] * y + m[7] * z);
        out[oo + 2] = (float) (m[2] * x + m[5] * y + m[8] * z);
    }

    public static void axisAngleToQuat(float ax, float ay, float az, float angle, float[] q, int o) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-12f) {
            q[o] = 1f;
            q[o + 1] = q[o + 2] = q[o + 3] = 0f;
            return;
        }
        float s = (float) Math.sin(angle * 0.5) / len;
        q[o] = (float) Math.cos(angle * 0.5);
        q[o + 1] = ax * s;
        q[o + 2] = ay * s;
        q[o + 3] = az * s;
    }

    /** Axis (3) and angle into {@code out[0..3]}. */
    public static void quatToAxisAngle(float[] q, int o, float[] out) {
        float[] n = {q[o], q[o + 1], q[o + 2], q[o + 3]};
        quatNormalize(n, 0);
        if (n[0] < 0f) {
            for (int k = 0; k < 4; k++) {
                n[k] = -n[k];
            }
        }
        float sinHalf = (float) Math.sqrt(n[1] * n[1] + n[2] * n[2] + n[3] * n[3]);
        float angle = 2f * (float) Math.atan2(sinHalf, n[0]);
        if (sinHalf < 1e-7f) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1f;
            out[3] = 0f;
            return;
        }
        out[0] = n[1] / sinHalf;
        out[1] = n[2] / sinHalf;
        out[2] = n[3] / sinHalf;
        out[3] = angle;
    }

    /** The rotation of a 4x4 (or 3x3 within it) matrix as a quaternion, scale removed. */
    public static void matrixToQuat(float[] m, int mo, float[] q, int qo) {
        double[] r = new double[9];
        for (int c = 0; c < 3; c++) {
            double len = Math.sqrt(m[mo + c * 4] * m[mo + c * 4] + m[mo + c * 4 + 1] * m[mo + c * 4 + 1]
                    + m[mo + c * 4 + 2] * m[mo + c * 4 + 2]);
            if (len < 1e-12) {
                len = 1;
            }
            for (int row = 0; row < 3; row++) {
                r[c * 3 + row] = m[mo + c * 4 + row] / len;
            }
        }
        mat3ToQuat(r, q, qo);
    }

    public static void mat3ToQuat(double[] r, float[] q, int o) {
        double m00 = r[0], m11 = r[4], m22 = r[8];
        double trace = m00 + m11 + m22;
        double w, x, y, z;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1.0) * 2;
            w = 0.25 * s;
            x = (r[5] - r[7]) / s;
            y = (r[6] - r[2]) / s;
            z = (r[1] - r[3]) / s;
        } else if (m00 > m11 && m00 > m22) {
            double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2;
            w = (r[5] - r[7]) / s;
            x = 0.25 * s;
            y = (r[3] + r[1]) / s;
            z = (r[6] + r[2]) / s;
        } else if (m11 > m22) {
            double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2;
            w = (r[6] - r[2]) / s;
            x = (r[3] + r[1]) / s;
            y = 0.25 * s;
            z = (r[7] + r[5]) / s;
        } else {
            double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2;
            w = (r[1] - r[3]) / s;
            x = (r[6] + r[2]) / s;
            y = (r[7] + r[5]) / s;
            z = 0.25 * s;
        }
        if (w < 0) {
            w = -w;
            x = -x;
            y = -y;
            z = -z;
        }
        q[o] = (float) w;
        q[o + 1] = (float) x;
        q[o + 2] = (float) y;
        q[o + 3] = (float) z;
    }

    // --- Matrices ----------------------------------------------------------------------------------------

    /** Translation, rotation (quaternion) and scale into a 4x4 matrix: T * R * S. */
    public static void compose(float tx, float ty, float tz, float[] q, int qo, float sx, float sy, float sz,
                               float[] m, int mo) {
        double[] r = new double[9];
        quatToMat3(q, qo, r);
        for (int row = 0; row < 3; row++) {
            m[mo + row] = (float) r[row] * sx;
            m[mo + 4 + row] = (float) r[3 + row] * sy;
            m[mo + 8 + row] = (float) r[6 + row] * sz;
        }
        m[mo + 3] = m[mo + 7] = m[mo + 11] = 0f;
        m[mo + 12] = tx;
        m[mo + 13] = ty;
        m[mo + 14] = tz;
        m[mo + 15] = 1f;
    }

    /** Translation (3), rotation quaternion (4) and scale (3) of a 4x4 matrix, into {@code out[0..9]}. */
    public static void decompose(float[] m, int mo, float[] out) {
        out[0] = m[mo + 12];
        out[1] = m[mo + 13];
        out[2] = m[mo + 14];
        float[] scale = new float[3];
        for (int c = 0; c < 3; c++) {
            scale[c] = (float) Math.sqrt(m[mo + c * 4] * m[mo + c * 4] + m[mo + c * 4 + 1] * m[mo + c * 4 + 1]
                    + m[mo + c * 4 + 2] * m[mo + c * 4 + 2]);
        }
        if (determinant3(m, mo) < 0f) {
            scale[0] = -scale[0];
        }
        double[] r = new double[9];
        for (int c = 0; c < 3; c++) {
            float s = scale[c] == 0f ? 1f : scale[c];
            for (int row = 0; row < 3; row++) {
                r[c * 3 + row] = m[mo + c * 4 + row] / s;
            }
        }
        float[] q = new float[4];
        mat3ToQuat(r, q, 0);
        System.arraycopy(q, 0, out, 3, 4);
        System.arraycopy(scale, 0, out, 7, 3);
    }

    public static float determinant3(float[] m, int o) {
        return m[o] * (m[o + 5] * m[o + 10] - m[o + 9] * m[o + 6])
                - m[o + 4] * (m[o + 1] * m[o + 10] - m[o + 9] * m[o + 2])
                + m[o + 8] * (m[o + 1] * m[o + 6] - m[o + 5] * m[o + 2]);
    }

    /** a * b (apply b first). */
    public static void mul(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float[] r = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[ao + k * 4 + row] * b[bo + c * 4 + k];
                }
                r[c * 4 + row] = sum;
            }
        }
        System.arraycopy(r, 0, out, oo, 16);
    }

    public static float[] mul(float[] a, float[] b) {
        float[] out = new float[16];
        mul(a, 0, b, 0, out, 0);
        return out;
    }

    public static boolean invert(float[] m, int mo, float[] out, int oo) {
        org.joml.Matrix4f matrix = new org.joml.Matrix4f().set(m, mo);
        float det = matrix.determinant();
        if (Math.abs(det) < 1e-20f) {
            identity(out, oo);
            return false;
        }
        matrix.invert();
        matrix.get(out, oo);
        return true;
    }

    public static float[] invert(float[] m) {
        float[] out = new float[16];
        invert(m, 0, out, 0);
        return out;
    }

    public static void transformPoint(float[] m, int mo, float[] p, int po, float[] out, int oo) {
        float x = p[po], y = p[po + 1], z = p[po + 2];
        out[oo] = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z + m[mo + 12];
        out[oo + 1] = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z + m[mo + 13];
        out[oo + 2] = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z + m[mo + 14];
    }

    public static void transformDirection(float[] m, int mo, float[] p, int po, float[] out, int oo) {
        float x = p[po], y = p[po + 1], z = p[po + 2];
        out[oo] = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z;
        out[oo + 1] = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z;
        out[oo + 2] = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z;
    }

    /** The matrix that transforms normals: the inverse transpose of the upper 3x3. */
    public static void normalMatrix(float[] m, int mo, float[] out) {
        float[] inv = new float[16];
        invert(m, mo, inv, 0);
        for (int c = 0; c < 3; c++) {
            for (int row = 0; row < 3; row++) {
                out[c * 3 + row] = inv[row * 4 + c];
            }
        }
    }
}

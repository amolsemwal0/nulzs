// --- Blender shader node library --------------------------------------------------------------------------
// The GLSL half of org.zeronura.engine.nodes.tex: the same hashes, noise, Voronoi and colour maths, function for
// function, so a material and a geometry tree computing the same texture agree (and both agree with Blender, which
// NodeFixture tests check on the Java side). Change one, change the other.
//
// Naming: nb_ prefix for everything, so generated node code never collides with the engine's lighting functions.

#define NB_PI 3.14159265358979

float nb_smoothstep(float e0, float e1, float x);

// --- Jenkins lookup3 ---------------------------------------------------------------------------------------

uint nb_rot(uint x, int k) { return (x << uint(k)) | (x >> uint(32 - k)); }

uint nb_final(uint a, uint b, uint c) {
    c ^= b; c -= nb_rot(b, 14);
    a ^= c; a -= nb_rot(c, 11);
    b ^= a; b -= nb_rot(a, 25);
    c ^= b; c -= nb_rot(b, 16);
    a ^= c; a -= nb_rot(c, 4);
    b ^= a; b -= nb_rot(a, 14);
    c ^= b; c -= nb_rot(b, 24);
    return c;
}

uint nb_hash1(uint kx) {
    uint i = 0xdeadbeefu + (1u << 2) + 13u;
    return nb_final(i + kx, i, i);
}

uint nb_hash2(uint kx, uint ky) {
    uint i = 0xdeadbeefu + (2u << 2) + 13u;
    return nb_final(i + kx, i + ky, i);
}

uint nb_hash3(uint kx, uint ky, uint kz) {
    uint i = 0xdeadbeefu + (3u << 2) + 13u;
    return nb_final(i + kx, i + ky, i + kz);
}

uint nb_hash4(uint kx, uint ky, uint kz, uint kw) {
    uint i = 0xdeadbeefu + (4u << 2) + 13u;
    uint a = i + kx, b = i + ky, c = i + kz;
    a -= c; a ^= nb_rot(c, 4); c += b;
    b -= a; b ^= nb_rot(a, 6); a += c;
    c -= b; c ^= nb_rot(b, 8); b += a;
    a -= c; a ^= nb_rot(c, 16); c += b;
    b -= a; b ^= nb_rot(a, 19); a += c;
    c -= b; c ^= nb_rot(b, 4); b += a;
    a += kw;
    return nb_final(a, b, c);
}

float nb_unit(uint h) { return float(h) / 4294967295.0; }

float nb_hash_f(float k) { return nb_unit(nb_hash1(floatBitsToUint(k))); }
float nb_hash_v2(vec2 k) { return nb_unit(nb_hash2(floatBitsToUint(k.x), floatBitsToUint(k.y))); }
float nb_hash_v3(vec3 k) { return nb_unit(nb_hash3(floatBitsToUint(k.x), floatBitsToUint(k.y), floatBitsToUint(k.z))); }
float nb_hash_v4(vec4 k) {
    return nb_unit(nb_hash4(floatBitsToUint(k.x), floatBitsToUint(k.y), floatBitsToUint(k.z), floatBitsToUint(k.w)));
}

// --- Perlin noise ------------------------------------------------------------------------------------------

float nb_fade(float t) { return t * t * t * (t * (t * 6.0 - 15.0) + 10.0); }
float nb_negate_if(float v, uint c) { return c != 0u ? -v : v; }

float nb_grad1(uint hash, float x) {
    uint h = hash & 15u;
    float g = float(1u + (h & 7u));
    return nb_negate_if(g, h & 8u) * x;
}

float nb_grad2(uint hash, float x, float y) {
    uint h = hash & 7u;
    float u = h < 4u ? x : y;
    float v = 2.0 * (h < 4u ? y : x);
    return nb_negate_if(u, h & 1u) + nb_negate_if(v, h & 2u);
}

float nb_grad3(uint hash, float x, float y, float z) {
    uint h = hash & 15u;
    float u = h < 8u ? x : y;
    float vt = (h == 12u || h == 14u) ? x : z;
    float v = h < 4u ? y : vt;
    return nb_negate_if(u, h & 1u) + nb_negate_if(v, h & 2u);
}

float nb_grad4(uint hash, float x, float y, float z, float w) {
    uint h = hash & 31u;
    float u = h < 24u ? x : y;
    float v = h < 16u ? y : z;
    float s = h < 8u ? z : w;
    return nb_negate_if(u, h & 1u) + nb_negate_if(v, h & 2u) + nb_negate_if(s, h & 4u);
}

float nb_tri_mix(float v0, float v1, float v2, float v3, float v4, float v5, float v6, float v7, float x, float y, float z) {
    float x1 = 1.0 - x, y1 = 1.0 - y, z1 = 1.0 - z;
    return z1 * (y1 * (v0 * x1 + v1 * x) + y * (v2 * x1 + v3 * x)) + z * (y1 * (v4 * x1 + v5 * x) + y * (v6 * x1 + v7 * x));
}

float nb_perlin1(float x) {
    int ix = int(floor(x));
    float fx = x - float(ix);
    uint X = uint(ix);
    return (1.0 - nb_fade(fx)) * nb_grad1(nb_hash1(X), fx) + nb_fade(fx) * nb_grad1(nb_hash1(X + 1u), fx - 1.0);
}

float nb_perlin2(vec2 p) {
    ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    uint X = uint(i.x), Y = uint(i.y);
    float u = nb_fade(f.x), v = nb_fade(f.y);
    float x1 = 1.0 - u;
    return (1.0 - v) * (nb_grad2(nb_hash2(X, Y), f.x, f.y) * x1 + nb_grad2(nb_hash2(X + 1u, Y), f.x - 1.0, f.y) * u)
         + v * (nb_grad2(nb_hash2(X, Y + 1u), f.x, f.y - 1.0) * x1 + nb_grad2(nb_hash2(X + 1u, Y + 1u), f.x - 1.0, f.y - 1.0) * u);
}

float nb_perlin3(vec3 p) {
    ivec3 i = ivec3(floor(p));
    vec3 f = p - vec3(i);
    uint X = uint(i.x), Y = uint(i.y), Z = uint(i.z);
    return nb_tri_mix(nb_grad3(nb_hash3(X, Y, Z), f.x, f.y, f.z),
                      nb_grad3(nb_hash3(X + 1u, Y, Z), f.x - 1.0, f.y, f.z),
                      nb_grad3(nb_hash3(X, Y + 1u, Z), f.x, f.y - 1.0, f.z),
                      nb_grad3(nb_hash3(X + 1u, Y + 1u, Z), f.x - 1.0, f.y - 1.0, f.z),
                      nb_grad3(nb_hash3(X, Y, Z + 1u), f.x, f.y, f.z - 1.0),
                      nb_grad3(nb_hash3(X + 1u, Y, Z + 1u), f.x - 1.0, f.y, f.z - 1.0),
                      nb_grad3(nb_hash3(X, Y + 1u, Z + 1u), f.x, f.y - 1.0, f.z - 1.0),
                      nb_grad3(nb_hash3(X + 1u, Y + 1u, Z + 1u), f.x - 1.0, f.y - 1.0, f.z - 1.0),
                      nb_fade(f.x), nb_fade(f.y), nb_fade(f.z));
}

float nb_perlin4(vec4 p) {
    ivec4 i = ivec4(floor(p));
    vec4 f = p - vec4(i);
    uint X = uint(i.x), Y = uint(i.y), Z = uint(i.z), W = uint(i.w);
    float u = nb_fade(f.x), v = nb_fade(f.y), t = nb_fade(f.z), s = nb_fade(f.w);
    float low = nb_tri_mix(nb_grad4(nb_hash4(X, Y, Z, W), f.x, f.y, f.z, f.w),
                           nb_grad4(nb_hash4(X + 1u, Y, Z, W), f.x - 1.0, f.y, f.z, f.w),
                           nb_grad4(nb_hash4(X, Y + 1u, Z, W), f.x, f.y - 1.0, f.z, f.w),
                           nb_grad4(nb_hash4(X + 1u, Y + 1u, Z, W), f.x - 1.0, f.y - 1.0, f.z, f.w),
                           nb_grad4(nb_hash4(X, Y, Z + 1u, W), f.x, f.y, f.z - 1.0, f.w),
                           nb_grad4(nb_hash4(X + 1u, Y, Z + 1u, W), f.x - 1.0, f.y, f.z - 1.0, f.w),
                           nb_grad4(nb_hash4(X, Y + 1u, Z + 1u, W), f.x, f.y - 1.0, f.z - 1.0, f.w),
                           nb_grad4(nb_hash4(X + 1u, Y + 1u, Z + 1u, W), f.x - 1.0, f.y - 1.0, f.z - 1.0, f.w), u, v, t);
    float high = nb_tri_mix(nb_grad4(nb_hash4(X, Y, Z, W + 1u), f.x, f.y, f.z, f.w - 1.0),
                            nb_grad4(nb_hash4(X + 1u, Y, Z, W + 1u), f.x - 1.0, f.y, f.z, f.w - 1.0),
                            nb_grad4(nb_hash4(X, Y + 1u, Z, W + 1u), f.x, f.y - 1.0, f.z, f.w - 1.0),
                            nb_grad4(nb_hash4(X + 1u, Y + 1u, Z, W + 1u), f.x - 1.0, f.y - 1.0, f.z, f.w - 1.0),
                            nb_grad4(nb_hash4(X, Y, Z + 1u, W + 1u), f.x, f.y, f.z - 1.0, f.w - 1.0),
                            nb_grad4(nb_hash4(X + 1u, Y, Z + 1u, W + 1u), f.x - 1.0, f.y, f.z - 1.0, f.w - 1.0),
                            nb_grad4(nb_hash4(X, Y + 1u, Z + 1u, W + 1u), f.x, f.y - 1.0, f.z - 1.0, f.w - 1.0),
                            nb_grad4(nb_hash4(X + 1u, Y + 1u, Z + 1u, W + 1u), f.x - 1.0, f.y - 1.0, f.z - 1.0, f.w - 1.0), u, v, t);
    return (1.0 - s) * low + s * high;
}

// C's fmod, which Blender wraps noise coordinates with (GLSL's mod floors instead).
vec4 nb_wrap(vec4 p) { return p - 100000.0 * trunc(p / 100000.0); }

// Signed noise of a 1-4 component position held in a vec4 (unused components ignored).
float nb_snoise(vec4 p, int dims) {
    p = nb_wrap(p);
    if (dims == 1) return 0.25 * nb_perlin1(p.x);
    if (dims == 2) return 0.6616 * nb_perlin2(p.xy);
    if (dims == 3) return 0.9820 * nb_perlin3(p.xyz);
    return 0.8344 * nb_perlin4(p);
}

float nb_fbm(vec4 p, int dims, float detail, float roughness, float lacunarity, bool normalize) {
    float fscale = 1.0, amp = 1.0, maxamp = 0.0, sum = 0.0;
    int octaves = int(detail);
    for (int i = 0; i <= octaves; i++) {
        float t = nb_snoise(fscale * p, dims);
        sum += t * amp;
        maxamp += amp;
        amp *= roughness;
        fscale *= lacunarity;
    }
    float rmd = detail - floor(detail);
    if (rmd != 0.0) {
        float t = nb_snoise(fscale * p, dims);
        float sum2 = sum + t * amp;
        return normalize ? mix(0.5 * sum / maxamp + 0.5, 0.5 * sum2 / (maxamp + amp) + 0.5, rmd) : mix(sum, sum2, rmd);
    }
    return normalize ? 0.5 * sum / maxamp + 0.5 : sum;
}

float nb_multi_fractal(vec4 p, int dims, float detail, float roughness, float lacunarity) {
    float value = 1.0, pwr = 1.0;
    int octaves = int(detail);
    for (int i = 0; i <= octaves; i++) {
        value *= pwr * nb_snoise(p, dims) + 1.0;
        pwr *= roughness;
        p *= lacunarity;
    }
    float rmd = detail - floor(detail);
    if (rmd != 0.0) value *= rmd * pwr * nb_snoise(p, dims) + 1.0;
    return value;
}

float nb_hetero_terrain(vec4 p, int dims, float detail, float roughness, float lacunarity, float offset) {
    float pwr = roughness;
    float value = offset + nb_snoise(p, dims);
    p *= lacunarity;
    int octaves = int(detail);
    for (int i = 1; i <= octaves; i++) {
        float increment = (nb_snoise(p, dims) + offset) * pwr * value;
        value += increment;
        pwr *= roughness;
        p *= lacunarity;
    }
    float rmd = detail - floor(detail);
    if (rmd != 0.0) value += rmd * ((nb_snoise(p, dims) + offset) * pwr * value);
    return value;
}

float nb_hybrid_multi_fractal(vec4 p, int dims, float detail, float roughness, float lacunarity, float offset, float gain) {
    float pwr = 1.0, value = 0.0, weight = 1.0;
    int octaves = int(detail);
    for (int i = 0; weight > 0.001 && i <= octaves; i++) {
        if (weight > 1.0) weight = 1.0;
        float signal = (nb_snoise(p, dims) + offset) * pwr;
        pwr *= roughness;
        value += weight * signal;
        weight *= gain * signal;
        p *= lacunarity;
    }
    float rmd = detail - floor(detail);
    if (rmd != 0.0 && weight > 0.001) {
        if (weight > 1.0) weight = 1.0;
        value += rmd * weight * ((nb_snoise(p, dims) + offset) * pwr);
    }
    return value;
}

float nb_ridged_multi_fractal(vec4 p, int dims, float detail, float roughness, float lacunarity, float offset, float gain) {
    float pwr = roughness;
    float signal = offset - abs(nb_snoise(p, dims));
    signal *= signal;
    float value = signal;
    int octaves = int(detail);
    for (int i = 1; i <= octaves; i++) {
        p *= lacunarity;
        float weight = clamp(signal * gain, 0.0, 1.0);
        signal = offset - abs(nb_snoise(p, dims));
        signal *= signal;
        signal *= weight;
        value += signal * pwr;
        pwr *= roughness;
    }
    return value;
}

// type: 0 multifractal, 1 fBm, 2 hybrid, 3 ridged, 4 hetero terrain (as org.zeronura.engine.nodes.tex.Noise)
float nb_noise_select(vec4 p, int dims, float detail, float roughness, float lacunarity, float offset, float gain,
                      int type, bool normalize) {
    if (type == 0) return nb_multi_fractal(p, dims, detail, roughness, lacunarity);
    if (type == 2) return nb_hybrid_multi_fractal(p, dims, detail, roughness, lacunarity, offset, gain);
    if (type == 3) return nb_ridged_multi_fractal(p, dims, detail, roughness, lacunarity, offset, gain);
    if (type == 4) return nb_hetero_terrain(p, dims, detail, roughness, lacunarity, offset);
    return nb_fbm(p, dims, detail, roughness, lacunarity, normalize);
}

vec4 nb_random_offset(float seed, int dims) {
    if (dims == 1) return vec4(100.0 + nb_hash_f(seed) * 100.0, 0.0, 0.0, 0.0);
    return vec4(100.0 + nb_hash_v2(vec2(seed, 0.0)) * 100.0, 100.0 + nb_hash_v2(vec2(seed, 1.0)) * 100.0,
                dims > 2 ? 100.0 + nb_hash_v2(vec2(seed, 2.0)) * 100.0 : 0.0,
                dims > 3 ? 100.0 + nb_hash_v2(vec2(seed, 3.0)) * 100.0 : 0.0);
}

// The Noise Texture node: Fac in .x, colour in .yzw. p is already vector * scale (and w * scale in .w for 4D,
// .x for 1D).
vec4 nb_noise_texture(vec4 p, int dims, float detail, float roughness, float lacunarity, float offset, float gain,
                      float distortion, int type, bool normalize) {
    detail = clamp(detail, 0.0, 15.0);
    roughness = max(roughness, 0.0);
    if (distortion != 0.0) {
        vec4 shift = vec4(0.0);
        shift.x = nb_snoise(p + nb_random_offset(0.0, dims), dims) * distortion;
        if (dims > 1) shift.y = nb_snoise(p + nb_random_offset(1.0, dims), dims) * distortion;
        if (dims > 2) shift.z = nb_snoise(p + nb_random_offset(2.0, dims), dims) * distortion;
        if (dims > 3) shift.w = nb_snoise(p + nb_random_offset(3.0, dims), dims) * distortion;
        p += shift;
    }
    float value = nb_noise_select(p, dims, detail, roughness, lacunarity, offset, gain, type, normalize);
    float g = nb_noise_select(p + nb_random_offset(float(dims), dims), dims, detail, roughness, lacunarity, offset, gain, type, normalize);
    float b = nb_noise_select(p + nb_random_offset(float(dims + 1), dims), dims, detail, roughness, lacunarity, offset, gain, type, normalize);
    return vec4(value, value, g, b);
}

// --- Voronoi ---------------------------------------------------------------------------------------------
// Cells are hashed as Blender 4.1+ hashes them: 1D by the float bits of the cell, 2D-4D by PCG (Jarzynski and
// Olano) on the integer cell, in signed form (arithmetic shifts).

uvec4 nb_pcg(ivec4 c, int dims) {
    uvec4 v = uvec4(c) * 1664525u + 1013904223u;
    if (dims == 2) {
        v.x += v.y * 1664525u;
        v.y += v.x * 1664525u;
        v.x ^= uint(int(v.x) >> 16);
        v.y ^= uint(int(v.y) >> 16);
        v.x += v.y * 1664525u;
        v.y += v.x * 1664525u;
        return v;
    }
    if (dims == 3) {
        v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
        v.x ^= uint(int(v.x) >> 16); v.y ^= uint(int(v.y) >> 16); v.z ^= uint(int(v.z) >> 16);
        v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
        return v;
    }
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    v.x ^= uint(int(v.x) >> 16); v.y ^= uint(int(v.y) >> 16); v.z ^= uint(int(v.z) >> 16); v.w ^= uint(int(v.w) >> 16);
    v.x += v.y * v.w; v.y += v.z * v.x; v.z += v.x * v.y; v.w += v.y * v.z;
    return v;
}

vec4 nb_pcg_unit(uvec4 h) { return vec4(h & 0x7fffffffu) * (1.0 / 2147483648.0); }

vec4 nb_cell_point(ivec4 c, int dims) {
    if (dims == 1) return vec4(nb_hash_f(float(c.x)), 0.0, 0.0, 0.0);
    vec4 r = nb_pcg_unit(nb_pcg(c, dims));
    if (dims == 2) return vec4(r.xy, 0.0, 0.0);
    if (dims == 3) return vec4(r.xyz, 0.0);
    return r;
}

vec3 nb_cell_color(ivec4 c, int dims) {
    if (dims == 1) {
        float x = float(c.x);
        return vec3(nb_hash_f(x), nb_hash_v2(vec2(x, 1.0)), nb_hash_v2(vec2(x, 2.0)));
    }
    if (dims == 2) return nb_pcg_unit(nb_pcg(ivec4(c.xy, 0, 0), 3)).xyz;
    return nb_pcg_unit(nb_pcg(c, dims)).xyz;
}

// metric: 0 euclidean, 1 manhattan, 2 chebychev, 3 minkowski
float nb_voronoi_distance(vec4 a, vec4 b, int dims, int metric, float exponent) {
    vec4 d = abs(a - b);
    if (dims == 1) return d.x;
    if (metric == 1) return d.x + d.y + d.z + d.w;
    if (metric == 2) return max(max(d.x, d.y), max(d.z, d.w));
    if (metric == 3) {
        float sum = pow(d.x, exponent) + pow(d.y, exponent);
        if (dims > 2) sum += pow(d.z, exponent);
        if (dims > 3) sum += pow(d.w, exponent);
        return pow(sum, 1.0 / exponent);
    }
    return length(a - b);
}

struct NbVoronoi {
    float distance;
    vec3 color;
    vec4 position;
};

// Position as Blender reports it per dimension count: 1D in w, 2D in xy, 3D in xyz.
vec4 nb_voronoi_position(vec4 p, int dims) {
    if (dims == 1) return vec4(0.0, 0.0, 0.0, p.x);
    if (dims == 2) return vec4(p.xy, 0.0, 0.0);
    if (dims == 3) return vec4(p.xyz, 0.0);
    return p;
}

// feature: 0 F1, 1 F2, 2 smooth F1
NbVoronoi nb_voronoi_octave(vec4 coord, int dims, int feature, int metric, float exponent, float randomness,
                            float smoothness) {
    vec4 cellF = floor(coord);
    vec4 local = coord - cellF;
    ivec4 cell = ivec4(cellF);
    int r = feature == 2 ? 2 : 1;
    int rx = r, ry = dims > 1 ? r : 0, rz = dims > 2 ? r : 0, rw = dims > 3 ? r : 0;
    NbVoronoi res;
    float d1 = 3.402823e38, d2 = 3.402823e38;
    ivec4 off1 = ivec4(0), off2 = ivec4(0);
    vec4 pos1 = vec4(0.0), pos2 = vec4(0.0);
    float smoothDistance = 0.0;
    vec3 smoothColor = vec3(0.0);
    vec4 smoothPosition = vec4(0.0);
    float h = -1.0;
    for (int w = -rw; w <= rw; w++)
    for (int z = -rz; z <= rz; z++)
    for (int y = -ry; y <= ry; y++)
    for (int x = -rx; x <= rx; x++) {
        ivec4 offset = ivec4(x, y, z, w);
        vec4 point = vec4(offset) + nb_cell_point(cell + offset, dims) * randomness;
        float d = nb_voronoi_distance(point, local, dims, metric, exponent);
        if (feature == 2) {
            h = h == -1.0 ? 1.0 : nb_smoothstep(0.0, 1.0, 0.5 + 0.5 * (smoothDistance - d) / smoothness);
            float correction = smoothness * h * (1.0 - h);
            smoothDistance = (1.0 - h) * smoothDistance + h * d - correction;
            correction /= 1.0 + 3.0 * smoothness;
            smoothColor = (1.0 - h) * smoothColor + h * nb_cell_color(cell + offset, dims) - correction;
            smoothPosition = (1.0 - h) * smoothPosition + h * point - correction;
        } else if (d < d1) {
            d2 = d1;
            d1 = d;
            off2 = off1;
            off1 = offset;
            pos2 = pos1;
            pos1 = point;
        } else if (d < d2) {
            d2 = d;
            off2 = offset;
            pos2 = point;
        }
    }
    if (feature == 2) {
        res.distance = smoothDistance;
        res.color = smoothColor;
        res.position = nb_voronoi_position(smoothPosition + cellF, dims);
    } else if (feature == 1) {
        res.distance = d2;
        res.color = nb_cell_color(cell + off2, dims);
        res.position = nb_voronoi_position(pos2 + cellF, dims);
    } else {
        res.distance = d1;
        res.color = nb_cell_color(cell + off1, dims);
        res.position = nb_voronoi_position(pos1 + cellF, dims);
    }
    return res;
}

float nb_voronoi_max_distance(int dims, int feature, int metric, float exponent, float randomness) {
    if (feature == 3) return 0.5 + 0.5 * randomness;
    float corner = 0.5 + 0.5 * randomness;
    float d;
    if (dims == 1) d = corner;
    else d = nb_voronoi_distance(vec4(corner, corner, dims > 2 ? corner : 0.0, dims > 3 ? corner : 0.0), vec4(0.0),
                                 dims, metric, exponent);
    return d * (feature == 1 ? 2.0 : 1.0);
}

// Pack the node's voronoi_position(w) into the Position and W outputs.
NbVoronoi nb_voronoi_fractal(vec4 coord, int dims, int feature, int metric, float scale, float detail, float roughness,
                             float lacunarity, float smoothness, float exponent, float randomness, bool normalize) {
    detail = clamp(detail, 0.0, 15.0);
    roughness = clamp(roughness, 0.0, 1.0);
    smoothness = clamp(smoothness / 2.0, 0.0, 0.5);
    randomness = clamp(randomness, 0.0, 1.0);
    if (feature == 2 && smoothness == 0.0) feature = 0;
    float maxDistance = nb_voronoi_max_distance(dims, feature, metric, exponent, randomness);
    float amplitude = 1.0, maxAmplitude = 0.0, octaveScale = 1.0;
    NbVoronoi res;
    res.distance = 0.0;
    res.color = vec3(0.0);
    res.position = vec4(0.0);
    bool zeroInput = detail == 0.0 || roughness == 0.0;
    int octaves = int(ceil(detail));
    for (int i = 0; i <= octaves; i++) {
        NbVoronoi o = nb_voronoi_octave(coord * octaveScale, dims, feature, metric, exponent, randomness, smoothness);
        if (zeroInput) {
            maxAmplitude = 1.0;
            res = o;
            break;
        }
        if (float(i) <= detail) {
            maxAmplitude += amplitude;
            res.distance += o.distance * amplitude;
            res.color += o.color * amplitude;
            res.position = mix(res.position, o.position / octaveScale, amplitude);
            octaveScale *= lacunarity;
            amplitude *= roughness;
        } else {
            float remainder = detail - floor(detail);
            if (remainder != 0.0) {
                maxAmplitude = mix(maxAmplitude, maxAmplitude + amplitude, remainder);
                res.distance = mix(res.distance, res.distance + o.distance * amplitude, remainder);
                res.color = mix(res.color, res.color + o.color * amplitude, remainder);
                res.position = mix(res.position, mix(res.position, o.position / octaveScale, amplitude), remainder);
            }
        }
    }
    if (normalize) {
        res.distance /= maxAmplitude * maxDistance;
        res.color /= maxAmplitude;
    }
    res.position = scale != 0.0 ? res.position / scale : vec4(0.0);
    return res;
}

float nb_voronoi_edge_octave(vec4 coord, int dims, float randomness) {
    if (dims == 1) {
        float cellPosition = floor(coord.x);
        float local = coord.x - cellPosition;
        float mid = nb_hash_f(cellPosition) * randomness;
        float left = -1.0 + nb_hash_f(cellPosition - 1.0) * randomness;
        float right = 1.0 + nb_hash_f(cellPosition + 1.0) * randomness;
        return min(abs((mid + left) / 2.0 - local), abs((mid + right) / 2.0 - local));
    }
    vec4 cellF = floor(coord);
    vec4 local = coord - cellF;
    ivec4 cell = ivec4(cellF);
    int ry = 1, rz = dims > 2 ? 1 : 0, rw = dims > 3 ? 1 : 0;
    vec4 toClosest = vec4(0.0);
    float minDistance = 3.402823e38;
    for (int w = -rw; w <= rw; w++)
    for (int z = -rz; z <= rz; z++)
    for (int y = -ry; y <= ry; y++)
    for (int x = -1; x <= 1; x++) {
        ivec4 offset = ivec4(x, y, z, w);
        vec4 toPoint = vec4(offset) + nb_cell_point(cell + offset, dims) * randomness - local;
        float d = dot(toPoint, toPoint);
        if (d < minDistance) {
            minDistance = d;
            toClosest = toPoint;
        }
    }
    minDistance = 3.402823e38;
    for (int w = -rw; w <= rw; w++)
    for (int z = -rz; z <= rz; z++)
    for (int y = -ry; y <= ry; y++)
    for (int x = -1; x <= 1; x++) {
        ivec4 offset = ivec4(x, y, z, w);
        vec4 toPoint = vec4(offset) + nb_cell_point(cell + offset, dims) * randomness - local;
        vec4 perpendicular = toPoint - toClosest;
        if (dot(perpendicular, perpendicular) > 0.0001) {
            minDistance = min(minDistance, dot((toClosest + toPoint) / 2.0, normalize(perpendicular)));
        }
    }
    return minDistance;
}

float nb_voronoi_edge(vec4 coord, int dims, float detail, float roughness, float lacunarity, float randomness, bool normalize) {
    detail = clamp(detail, 0.0, 15.0);
    roughness = clamp(roughness, 0.0, 1.0);
    randomness = clamp(randomness, 0.0, 1.0);
    float maxDistance = 0.5 + 0.5 * randomness;
    float amplitude = 1.0, maxAmplitude = maxDistance, octaveScale = 1.0, distance = 8.0;
    bool zeroInput = detail == 0.0 || roughness == 0.0;
    int octaves = int(ceil(detail));
    for (int i = 0; i <= octaves; i++) {
        float o = nb_voronoi_edge_octave(coord * octaveScale, dims, randomness);
        if (zeroInput) {
            distance = o;
            break;
        }
        if (float(i) <= detail) {
            maxAmplitude = mix(maxAmplitude, maxDistance / octaveScale, amplitude);
            distance = mix(distance, min(distance, o / octaveScale), amplitude);
            octaveScale *= lacunarity;
            amplitude *= roughness;
        } else {
            float remainder = detail - floor(detail);
            if (remainder != 0.0) {
                float lerpAmplitude = mix(maxAmplitude, maxDistance / octaveScale, amplitude);
                maxAmplitude = mix(maxAmplitude, lerpAmplitude, remainder);
                float lerpDistance = mix(distance, min(distance, o / octaveScale), amplitude);
                distance = mix(distance, min(distance, lerpDistance), remainder);
            }
        }
    }
    return normalize ? distance / maxAmplitude : distance;
}

float nb_voronoi_n_sphere(vec4 coord, int dims, float randomness) {
    randomness = clamp(randomness, 0.0, 1.0);
    vec4 cellF = floor(coord);
    vec4 local = coord - cellF;
    ivec4 cell = ivec4(cellF);
    int ry = dims > 1 ? 1 : 0, rz = dims > 2 ? 1 : 0, rw = dims > 3 ? 1 : 0;
    vec4 closest = vec4(0.0);
    ivec4 closestOffset = ivec4(0);
    float minDistance = 3.402823e38;
    for (int w = -rw; w <= rw; w++)
    for (int z = -rz; z <= rz; z++)
    for (int y = -ry; y <= ry; y++)
    for (int x = -1; x <= 1; x++) {
        ivec4 offset = ivec4(x, y, z, w);
        vec4 point = vec4(offset) + nb_cell_point(cell + offset, dims) * randomness;
        float d = nb_voronoi_distance(point, local, dims, 0, 1.0);
        if (d < minDistance) {
            minDistance = d;
            closest = point;
            closestOffset = offset;
        }
    }
    minDistance = 3.402823e38;
    vec4 nearest = vec4(0.0);
    for (int w = -rw; w <= rw; w++)
    for (int z = -rz; z <= rz; z++)
    for (int y = -ry; y <= ry; y++)
    for (int x = -1; x <= 1; x++) {
        if (x == 0 && y == 0 && z == 0 && w == 0) continue;
        ivec4 offset = ivec4(x, y, z, w) + closestOffset;
        vec4 point = vec4(offset) + nb_cell_point(cell + offset, dims) * randomness;
        float d = nb_voronoi_distance(closest, point, dims, 0, 1.0);
        if (d < minDistance) {
            minDistance = d;
            nearest = point;
        }
    }
    return nb_voronoi_distance(nearest, closest, dims, 0, 1.0) / 2.0;
}

// --- Smaller textures --------------------------------------------------------------------------------------

vec4 nb_white_noise(vec4 p, int dims) {
    if (dims == 1) return vec4(nb_hash_f(p.x), nb_hash_f(p.x), nb_hash_v2(vec2(p.x, 1.0)), nb_hash_v2(vec2(p.x, 2.0)));
    if (dims == 2) return vec4(nb_hash_v2(p.xy), nb_hash_v2(p.xy), nb_hash_v3(vec3(p.xy, 1.0)), nb_hash_v3(vec3(p.xy, 2.0)));
    if (dims == 3) return vec4(nb_hash_v3(p.xyz), nb_hash_v3(p.xyz), nb_hash_v4(vec4(p.xyz, 1.0)), nb_hash_v4(vec4(p.xyz, 2.0)));
    return vec4(nb_hash_v4(p), nb_hash_v4(p), nb_hash_v4(p.zxwy), nb_hash_v4(p.wzyx));
}

// type: 0 linear, 1 quadratic, 2 easing, 3 diagonal, 4 radial, 5 quadratic sphere, 6 spherical
float nb_gradient(vec3 p, int type) {
    float r;
    if (type == 1) { r = max(p.x, 0.0); r *= r; }
    else if (type == 2) { float c = clamp(p.x, 0.0, 1.0); float t = c * c; r = 3.0 * t - 2.0 * t * c; }
    else if (type == 3) r = (p.x + p.y) * 0.5;
    else if (type == 4) r = atan(p.y, p.x) / (2.0 * NB_PI) + 0.5;
    else if (type == 5) { r = max(0.999999 - length(p), 0.0); r *= r; }
    else if (type == 6) r = max(0.999999 - length(p), 0.0);
    else r = p.x;
    return clamp(r, 0.0, 1.0);
}

float nb_checker(vec3 p) {
    p = (p + 0.000001) * 0.999999;
    ivec3 i = abs(ivec3(floor(p)));
    int same = (i.x % 2 == i.y % 2) ? 1 : 0;
    return same == i.z % 2 ? 1.0 : 0.0;
}

// type 0 bands, 1 rings; direction 0-3 (x, y, z, diagonal / spherical); profile 0 sin, 1 saw, 2 tri
float nb_wave(vec3 p, int type, int direction, int profile, float distortion, float detail, float detailScale,
              float detailRoughness, float phase) {
    p = (p + 0.000001) * 0.999999;
    float n;
    if (type == 1) {
        vec3 rp = p;
        if (direction == 0) rp.x = 0.0;
        else if (direction == 1) rp.y = 0.0;
        else if (direction == 2) rp.z = 0.0;
        n = length(rp) * 20.0;
    } else {
        if (direction == 1) n = p.y * 20.0;
        else if (direction == 2) n = p.z * 20.0;
        else if (direction == 3) n = (p.x + p.y + p.z) * 10.0;
        else n = p.x * 20.0;
    }
    n += phase;
    if (distortion != 0.0) {
        n += distortion * (nb_fbm(vec4(p * detailScale, 0.0), 3, clamp(detail, 0.0, 15.0), max(detailRoughness, 0.0), 2.0, true) * 2.0 - 1.0);
    }
    if (profile == 1) { n /= 2.0 * NB_PI; return n - floor(n); }
    if (profile == 2) { n /= 2.0 * NB_PI; return abs(n - floor(n + 0.5)) * 2.0; }
    return 0.5 + 0.5 * sin(n - NB_PI / 2.0);
}

vec4 nb_magic(vec3 p, int depth, float distortion) {
    p = p - 2.0 * NB_PI * floor(p / (2.0 * NB_PI));
    float x = sin((p.x + p.y + p.z) * 5.0);
    float y = cos((-p.x + p.y - p.z) * 5.0);
    float z = -cos((-p.x - p.y + p.z) * 5.0);
    if (depth > 0) {
        x *= distortion; y *= distortion; z *= distortion;
        y = -cos(x - y + z); y *= distortion;
        if (depth > 1) { x = cos(x - y - z); x *= distortion;
        if (depth > 2) { z = sin(-x - y - z); z *= distortion;
        if (depth > 3) { x = -cos(-x + y - z); x *= distortion;
        if (depth > 4) { y = -sin(-x + y + z); y *= distortion;
        if (depth > 5) { y = -cos(-x + y + z); y *= distortion;
        if (depth > 6) { x = cos(x + y + z); x *= distortion;
        if (depth > 7) { z = sin(x + y - z); z *= distortion;
        if (depth > 8) { x = -cos(-x - y + z); x *= distortion;
        if (depth > 9) { y = -sin(x - y + z); y *= distortion; } } } } } } } } }
    }
    if (distortion != 0.0) {
        distortion *= 2.0;
        x /= distortion; y /= distortion; z /= distortion;
    }
    vec3 c = vec3(0.5 - x, 0.5 - y, 0.5 - z);
    return vec4((c.x + c.y + c.z) * (1.0 / 3.0), c);
}

float nb_brick_noise(uint n) {
    n = (n + 1013u) & 0x7fffffffu;
    n = (n >> 13u) ^ n;
    uint nn = (n * (n * n * 60493u + 19990303u) + 1376312589u) & 0x7fffffffu;
    return 0.5 * (float(nn) / 1073741824.0);
}

// Tint in .x, mortar in .y.
vec2 nb_brick(vec2 p, float mortarSize, float mortarSmooth, float bias, float brickWidth, float rowHeight,
              float offsetAmount, int offsetFrequency, float squashAmount, int squashFrequency) {
    float offset = 0.0;
    int rownum = int(floor(p.y / rowHeight));
    if (offsetFrequency != 0 && squashFrequency != 0) {
        brickWidth *= (rownum % squashFrequency) != 0 ? 1.0 : squashAmount;
        offset = (rownum % offsetFrequency) != 0 ? 0.0 : brickWidth * offsetAmount;
    }
    int bricknum = int(floor((p.x + offset) / brickWidth));
    float x = (p.x + offset) - brickWidth * float(bricknum);
    float y = p.y - rowHeight * float(rownum);
    float tint = clamp(nb_brick_noise(uint((rownum << 16) + (bricknum & 0xFFFF))) + bias, 0.0, 1.0);
    float minDist = min(min(x, y), min(brickWidth - x, rowHeight - y));
    float mortar;
    if (minDist >= mortarSize) mortar = 0.0;
    else if (mortarSmooth == 0.0) mortar = 1.0;
    else {
        minDist = 1.0 - minDist / mortarSize;
        if (minDist < mortarSmooth) { float t = minDist / mortarSmooth; mortar = t * t * (3.0 - 2.0 * t); }
        else mortar = 1.0;
    }
    return vec2(tint, mortar);
}

// --- Maths -------------------------------------------------------------------------------------------------

float nb_safe_divide(float a, float b) { return b != 0.0 ? a / b : 0.0; }
vec3 nb_safe_divide(vec3 a, vec3 b) { return vec3(nb_safe_divide(a.x, b.x), nb_safe_divide(a.y, b.y), nb_safe_divide(a.z, b.z)); }
float nb_fmod(float a, float b) { return b != 0.0 ? a - b * trunc(a / b) : 0.0; }
float nb_floored_mod(float a, float b) { return b != 0.0 ? a - floor(a / b) * b : 0.0; }
float nb_pow(float a, float b) {
    if (a < 0.0) {
        if (b != floor(b)) return 0.0;
        float r = pow(-a, b);
        return mod(abs(b), 2.0) == 1.0 ? -r : r;
    }
    return pow(a, b);
}
float nb_safe_log(float a, float b) { return (a <= 0.0 || b <= 0.0) ? 0.0 : nb_safe_divide(log(a), log(b)); }
float nb_wrapf(float value, float maxv, float minv) {
    float range = maxv - minv;
    return range != 0.0 ? value - range * floor((value - minv) / range) : minv;
}
float nb_pingpong(float a, float b) {
    if (b == 0.0) return 0.0;
    float x = (a - b) / (b * 2.0);
    return abs((x - floor(x)) * b * 2.0 - b);
}
float nb_smoothmin(float a, float b, float c) {
    if (c != 0.0) {
        float h = max(c - abs(a - b), 0.0) / c;
        return min(a, b) - h * h * h * c * (1.0 / 6.0);
    }
    return min(a, b);
}
float nb_smoothstep(float e0, float e1, float x) {
    if (x < e0) return 0.0;
    if (x >= e1) return 1.0;
    float t = (x - e0) / (e1 - e0);
    return (3.0 - 2.0 * t) * (t * t);
}
float nb_smootherstep(float e0, float e1, float x) {
    x = clamp(nb_safe_divide(x - e0, e1 - e0), 0.0, 1.0);
    return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
}

// interpolation: 0 linear, 1 stepped, 2 smoothstep, 3 smootherstep
float nb_map_range(float value, float fromMin, float fromMax, float toMin, float toMax, float steps, int interpolation, bool clampResult) {
    float factor;
    float result;
    if (interpolation == 2) {
        factor = fromMin > fromMax ? 1.0 - nb_smoothstep(fromMax, fromMin, value) : nb_smoothstep(fromMin, fromMax, value);
        return toMin + factor * (toMax - toMin);
    }
    if (interpolation == 3) {
        factor = fromMin > fromMax ? 1.0 - nb_smootherstep(fromMax, fromMin, value) : nb_smootherstep(fromMin, fromMax, value);
        return toMin + factor * (toMax - toMin);
    }
    factor = nb_safe_divide(value - fromMin, fromMax - fromMin);
    if (interpolation == 1) factor = steps > 0.0 ? floor(factor * (steps + 1.0)) / steps : 0.0;
    result = toMin + factor * (toMax - toMin);
    if (clampResult) result = toMin > toMax ? clamp(result, toMax, toMin) : clamp(result, toMin, toMax);
    return result;
}

mat3 nb_euler_matrix(vec3 e) {
    float ci = cos(e.x), cj = cos(e.y), ch = cos(e.z);
    float si = sin(e.x), sj = sin(e.y), sh = sin(e.z);
    float cc = ci * ch, cs = ci * sh, sc = si * ch, ss = si * sh;
    return mat3(cj * ch, cj * sh, -sj, sj * sc - cs, sj * ss + cc, cj * si, sj * cc + ss, sj * cs - sc, cj * ci);
}

mat3 nb_axis_matrix(vec3 axis, float angle) {
    float c = cos(angle), s = sin(angle), t = 1.0 - c;
    vec3 a = axis;
    return mat3(t * a.x * a.x + c, t * a.x * a.y + s * a.z, t * a.x * a.z - s * a.y,
                t * a.x * a.y - s * a.z, t * a.y * a.y + c, t * a.y * a.z + s * a.x,
                t * a.x * a.z + s * a.y, t * a.y * a.z - s * a.x, t * a.z * a.z + c);
}

// type: 0 point, 1 texture, 2 vector, 3 normal
vec3 nb_mapping(vec3 v, vec3 location, vec3 rotation, vec3 scale, int type) {
    mat3 r = nb_euler_matrix(rotation);
    if (type == 1) return nb_safe_divide(transpose(r) * (v - location), scale);
    if (type == 2) return r * (v * scale);
    if (type == 3) { vec3 n = r * nb_safe_divide(v, scale); float l = length(n); return l > 0.0 ? n / l : vec3(0.0); }
    return r * (v * scale) + location;
}

// --- Colour ------------------------------------------------------------------------------------------------

vec3 nb_rgb_to_hsv(vec3 rgb) {
    float cmax = max(rgb.r, max(rgb.g, rgb.b));
    float cmin = min(rgb.r, min(rgb.g, rgb.b));
    float cdelta = cmax - cmin;
    float v = cmax;
    float s = cmax != 0.0 ? cdelta / cmax : 0.0;
    float h = 0.0;
    if (s != 0.0) {
        vec3 c = (vec3(cmax) - rgb) / cdelta;
        if (rgb.r == cmax) h = c.b - c.g;
        else if (rgb.g == cmax) h = 2.0 + c.r - c.b;
        else h = 4.0 + c.g - c.r;
        h /= 6.0;
        if (h < 0.0) h += 1.0;
    }
    return vec3(h, s, v);
}

vec3 nb_hsv_to_rgb(vec3 hsv) {
    float h = hsv.x, s = hsv.y, v = hsv.z;
    if (s == 0.0) return vec3(v);
    if (h == 1.0) h = 0.0;
    h *= 6.0;
    float i = floor(h);
    float f = h - i;
    float p = v * (1.0 - s), q = v * (1.0 - s * f), t = v * (1.0 - s * (1.0 - f));
    int sector = int(i);
    if (sector == 0) return vec3(v, t, p);
    if (sector == 1) return vec3(q, v, p);
    if (sector == 2) return vec3(p, v, t);
    if (sector == 3) return vec3(p, q, v);
    if (sector == 4) return vec3(t, p, v);
    return vec3(v, p, q);
}

vec3 nb_rgb_to_hsl(vec3 rgb) {
    float cmax = max(rgb.r, max(rgb.g, rgb.b));
    float cmin = min(rgb.r, min(rgb.g, rgb.b));
    float l = min(1.0, (cmax + cmin) / 2.0);
    float h = 0.0, s = 0.0;
    if (cmax != cmin) {
        float d = cmax - cmin;
        s = l > 0.5 ? d / (2.0 - cmax - cmin) : d / (cmax + cmin);
        if (cmax == rgb.r) h = (rgb.g - rgb.b) / d + (rgb.g < rgb.b ? 6.0 : 0.0);
        else if (cmax == rgb.g) h = (rgb.b - rgb.r) / d + 2.0;
        else h = (rgb.r - rgb.g) / d + 4.0;
    }
    return vec3(h / 6.0, s, l);
}

vec3 nb_hsl_to_rgb(vec3 hsl) {
    vec3 n = clamp(vec3(abs(hsl.x * 6.0 - 3.0) - 1.0, 2.0 - abs(hsl.x * 6.0 - 2.0), 2.0 - abs(hsl.x * 6.0 - 4.0)), 0.0, 1.0);
    float chroma = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
    return (n - 0.5) * chroma + hsl.z;
}

float nb_luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

vec3 nb_srgb_to_linear(vec3 c) {
    vec3 lo = max(c, 0.0) / 12.92;
    vec3 hi = pow((c + 0.055) / 1.055, vec3(2.4));
    return mix(hi, lo, vec3(lessThan(c, vec3(0.04045))));
}

vec3 nb_linear_to_srgb(vec3 c) {
    vec3 lo = max(c, 0.0) * 12.92;
    vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, lo, vec3(lessThan(c, vec3(0.0031308))));
}

// The Mix node's colour blends, as Blender's ramp_blend (mode numbers in ShaderCompiler.BLEND_MODES order).
vec4 nb_blend(int mode, float fac, vec4 a, vec4 b) {
    float facm = 1.0 - fac;
    vec3 c1 = a.rgb, c2 = b.rgb, r;
    if (mode == 1) r = c1 + fac * c2;                                            // ADD
    else if (mode == 2) r = c1 * (facm + fac * c2);                              // MULTIPLY
    else if (mode == 3) r = c1 - fac * c2;                                       // SUBTRACT
    else if (mode == 4) r = 1.0 - (facm + fac * (1.0 - c2)) * (1.0 - c1);        // SCREEN
    else if (mode == 5) r = vec3(c2.r != 0.0 ? facm * c1.r + fac * c1.r / c2.r : c1.r,
                                 c2.g != 0.0 ? facm * c1.g + fac * c1.g / c2.g : c1.g,
                                 c2.b != 0.0 ? facm * c1.b + fac * c1.b / c2.b : c1.b);   // DIVIDE
    else if (mode == 6) r = facm * c1 + fac * abs(c1 - c2);                      // DIFFERENCE
    else if (mode == 7) r = max(facm * c1 + fac * (c1 + c2 - 2.0 * c1 * c2), 0.0); // EXCLUSION
    else if (mode == 8) r = mix(c1, min(c1, c2), fac);                           // DARKEN
    else if (mode == 9) r = mix(c1, max(c1, c2), fac);                           // LIGHTEN
    else if (mode == 10) {                                                       // OVERLAY
        r = mix(1.0 - (facm + 2.0 * fac * (1.0 - c2)) * (1.0 - c1), c1 * (facm + 2.0 * fac * c2), vec3(lessThan(c1, vec3(0.5))));
    } else if (mode == 11) {                                                     // DODGE
        r = c1;
        for (int i = 0; i < 3; i++) {
            if (r[i] != 0.0) { float t = 1.0 - fac * c2[i]; r[i] = t <= 0.0 ? 1.0 : min(r[i] / t, 1.0); }
        }
    } else if (mode == 12) {                                                     // BURN
        for (int i = 0; i < 3; i++) {
            float t = facm + fac * c2[i];
            r[i] = t <= 0.0 ? 0.0 : clamp(1.0 - (1.0 - c1[i]) / t, 0.0, 1.0);
        }
    } else if (mode == 13) {                                                     // HUE
        r = c1;
        vec3 h2 = nb_rgb_to_hsv(c2);
        if (h2.y != 0.0) { vec3 h1 = nb_rgb_to_hsv(c1); h1.x = h2.x; r = mix(c1, nb_hsv_to_rgb(h1), fac); }
    } else if (mode == 14) {                                                     // SATURATION
        r = c1;
        vec3 h1 = nb_rgb_to_hsv(c1);
        if (h1.y != 0.0) { vec3 h2 = nb_rgb_to_hsv(c2); h1.y = facm * h1.y + fac * h2.y; r = nb_hsv_to_rgb(h1); }
    } else if (mode == 15) {                                                     // VALUE
        vec3 h1 = nb_rgb_to_hsv(c1); vec3 h2 = nb_rgb_to_hsv(c2);
        h1.z = facm * h1.z + fac * h2.z;
        r = nb_hsv_to_rgb(h1);
    } else if (mode == 16) {                                                     // COLOR
        r = c1;
        vec3 h2 = nb_rgb_to_hsv(c2);
        if (h2.y != 0.0) { vec3 h1 = nb_rgb_to_hsv(c1); h1.xy = h2.xy; r = mix(c1, nb_hsv_to_rgb(h1), fac); }
    } else if (mode == 17) {                                                     // SOFT_LIGHT
        vec3 scr = 1.0 - (1.0 - c2) * (1.0 - c1);
        r = facm * c1 + fac * ((1.0 - c1) * c2 * c1 + c1 * scr);
    } else if (mode == 18) r = c1 + fac * (2.0 * c2 - 1.0);                      // LINEAR_LIGHT
    else r = facm * c1 + fac * c2;                                               // MIX
    return vec4(r, a.a);
}

vec4 nb_hue_saturation(float hue, float sat, float value, float fac, vec4 col) {
    vec3 hsv = nb_rgb_to_hsv(col.rgb);
    hsv.x = fract(hsv.x + hue + 0.5);
    hsv.y = clamp(hsv.y * sat, 0.0, 1.0);
    hsv.z *= value;
    return vec4(max(mix(col.rgb, nb_hsv_to_rgb(hsv), fac), 0.0), col.a);
}

// Approximate blackbody colour (linear Rec. 709) for a temperature in kelvin.
vec4 nb_blackbody(float t) {
    t = clamp(t, 800.0, 12000.0) / 100.0;
    float r = t <= 66.0 ? 1.0 : clamp(1.29293618606 * pow(t - 60.0, -0.1332047592), 0.0, 1.0);
    float g = t <= 66.0 ? clamp(0.39008157876 * log(t) - 0.63184144378, 0.0, 1.0)
                        : clamp(1.12989086089 * pow(t - 60.0, -0.0755148492), 0.0, 1.0);
    float b = t >= 66.0 ? 1.0 : (t <= 19.0 ? 0.0 : clamp(0.54320678911 * log(t - 10.0) - 1.19625408914, 0.0, 1.0));
    return vec4(nb_srgb_to_linear(vec3(r, g, b)), 1.0);
}

vec4 nb_wavelength(float w) {
    float t = clamp((w - 380.0) / 400.0, 0.0, 1.0);
    vec3 c = clamp(vec3(abs(t * 6.0 - 3.0) - 1.0, 2.0 - abs(t * 6.0 - 2.0), 2.0 - abs(t * 6.0 - 4.0)), 0.0, 1.0);
    return vec4(c.bgr, 1.0);
}

// Reads a curve table packed into a uniform array: n samples across [lo, hi], extended past the ends.
float nb_curve_sample(float x, float lo, float hi, float slopeIn, float slopeOut, bool extrapolate, float first, float last,
                      float sampleAt, float sampleNext, float t) {
    if (x <= lo) return extrapolate ? first + (x - lo) * slopeIn : first;
    if (x >= hi) return extrapolate ? last + (x - hi) * slopeOut : last;
    return mix(sampleAt, sampleNext, t);
}

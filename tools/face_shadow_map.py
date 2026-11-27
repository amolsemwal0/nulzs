#!/usr/bin/env python3
"""Bakes a face shadow map for character.frag from a set of painted shadow masks.

A face lit through its own normals turns the nose, the eye sockets and the lips into hard-edged blotches, so an
anime face is lit from a painted map instead (Material.setFace). That map is a THRESHOLD map: each texel holds the
light angle at which it goes into shadow, so one image answers every angle, and the boundary sweeps smoothly across
the face as the light turns. Nobody can paint that directly. What an artist can paint is what the shadow looks like
at a handful of angles, and this turns those drawings into the map.

THE MASKS
  - One image per light angle, all the same size, drawn over the face's own texture so they share its UVs.
  - White where the face is lit, black where it is in shadow. Antialiased edges are fine (cut at 50%).
  - The light comes from the character's LEFT, at an angle measured round the head from straight in front:
    0 = in front, 90 = at the side, 180 = directly behind. On a face texture laid out like a front view, the
    character's left is the RIGHT half of the image -- so the lit area starts as the whole face and shrinks toward
    the image's right as the angle grows. Light from the right is handled by the shader mirroring the map.
  - Each mask's shadow should contain the one before it: a texel that goes dark stays dark as the light moves on.
    This is checked, and a texel that breaks it is reported (the first mask it is dark in wins).
  - 8 to 16 masks is plenty: 0, 15, 30 ... 180, with extra ones where the shadow moves fastest, usually across the
    nose between 30 and 90. The boundary between two masks is morphed through signed distance fields, so it moves
    smoothly between drawings rather than stepping.
  - The angle is read from the last number in each file name (face_045.png -> 45), or given with --angles.

    python3 tools/face_shadow_map.py masks/face_*.png -o assets/hero/face_shadow.png \
        [--angles 0,15,30,...] [--size 1024] [--flip] [--mirror-u 0.5] [--preview sweep.gif]

--size      output size (square, or WxH). Paint the masks larger than the face texture and bake down: the
            threshold map is averaged as it shrinks, which antialiases the boundary at every angle.
--flip      mirror the result left to right, for a UV layout where the character's left is the image's left
            (or if the shadow shows up on the wrong side in game).
--mirror-u  where the face's centre line is, as a u coordinate -- only used by --preview, and the same number
            to pass to Material.setFace.
--preview   writes an animated GIF of the light going all the way round the head as the shader will read the
            map, mirroring included. Look at it before looking at it in game.

Then, in the game:  material.setFace(Texture.fromFile(path, GL_CLAMP_TO_EDGE, false), mirrorU)
"""
import argparse
import os
import re
import sys

import numpy as np
from PIL import Image

# A distance that stands for "there is no such pixel at all" -- a mask that is entirely lit has no shadow to
# measure to. Finite, so the interpolation below never meets inf - inf.
FAR = 1.0e6


def shifted(a, dy, dx, fill):
    """a moved so out[y, x] = a[y + dy, x + dx], with `fill` where that falls off the image."""
    h, w = a.shape
    out = np.full_like(a, fill)
    if abs(dy) >= h or abs(dx) >= w:
        return out
    out[max(0, -dy):h - max(0, dy), max(0, -dx):w - max(0, dx)] = \
        a[max(0, dy):h - max(0, -dy), max(0, dx):w - max(0, -dx)]
    return out


def distance_to(seeds):
    """Euclidean distance from every pixel centre to the nearest True pixel centre, by jump flooding.

    Jump flooding passes each pixel's best-known nearest seed to its neighbours at halving strides, which reaches
    every pixel in log2(size) passes of whole-array numpy work instead of a Python loop per pixel. It is exact in
    all but rare corner cases, where it is out by a fraction of a pixel; the two extra fine passes at the end (the
    "JFA+2" variant) mop most of those up. That is far below what moves a painted shadow boundary.
    """
    h, w = seeds.shape
    if not seeds.any():
        return np.full((h, w), FAR, np.float32)
    ys, xs = np.indices((h, w), dtype=np.int32)
    seed_y = np.where(seeds, ys, -1).astype(np.int32)
    seed_x = np.where(seeds, xs, -1).astype(np.int32)
    best = np.where(seeds, 0, np.iinfo(np.int32).max).astype(np.int32)

    strides = []
    stride = 1 << (max(h, w) - 1).bit_length() - 1
    while stride >= 1:
        strides.append(stride)
        stride //= 2
    strides += [2, 1]

    for stride in strides:
        for dy in (-stride, 0, stride):
            for dx in (-stride, 0, stride):
                if dy == 0 and dx == 0:
                    continue
                cand_y = shifted(seed_y, dy, dx, -1)
                cand_x = shifted(seed_x, dy, dx, -1)
                known = cand_y >= 0
                d = (ys - cand_y) ** 2 + (xs - cand_x) ** 2
                better = known & (d < best)
                seed_y[better] = cand_y[better]
                seed_x[better] = cand_x[better]
                best[better] = d[better]
    return np.sqrt(best.astype(np.float32))


def signed_distance(lit):
    """Positive inside the lit area, negative in shadow, zero on the boundary -- in pixels.

    The boundary lies between pixel centres, half a pixel from the last lit one and half from the first dark one,
    which is what the 0.5s are.
    """
    to_dark = distance_to(~lit)
    to_lit = distance_to(lit)
    return np.where(lit, np.minimum(to_dark, FAR) - 0.5, -(np.minimum(to_lit, FAR) - 0.5)).astype(np.float32)


def angle_from_name(path):
    numbers = re.findall(r'\d+(?:\.\d+)?', os.path.splitext(os.path.basename(path))[0])
    if not numbers:
        sys.exit(f'No angle in the file name {path!r}: name it like face_045.png, or pass --angles.')
    return float(numbers[-1])


def load_masks(paths, angles):
    masks = []
    size = None
    for path, angle in zip(paths, angles):
        image = Image.open(path)
        # Transparent paint counts as unpainted, not as black.
        if image.mode in ('RGBA', 'LA') or 'transparency' in image.info:
            image = image.convert('RGBA')
            backing = Image.new('RGBA', image.size, (0, 0, 0, 255))
            image = Image.alpha_composite(backing, image)
        grey = np.asarray(image.convert('L'), dtype=np.float32) / 255.0
        if size is None:
            size = grey.shape
        elif grey.shape != size:
            sys.exit(f'{path} is {grey.shape[1]}x{grey.shape[0]}, but the first mask is {size[1]}x{size[0]}: '
                     'every mask must be the same size.')
        if not 0.0 <= angle <= 180.0:
            sys.exit(f'{path}: angle {angle} is outside 0..180.')
        masks.append((angle, grey >= 0.5, path))
    masks.sort(key=lambda m: m[0])
    for (a, _, p), (b, _, q) in zip(masks, masks[1:]):
        if a == b:
            sys.exit(f'{p} and {q} are both at {a} degrees.')
    return masks


def bake(masks):
    """The threshold map, in degrees, and how many texels broke the shrinking-shadow rule."""
    h, w = masks[0][1].shape
    threshold = np.full((h, w), 180.0, np.float32)   # lit in every mask: lit until the light is behind
    settled = np.zeros((h, w), bool)
    relit = np.zeros((h, w), bool)

    previous_angle, previous_sdf, previous_lit = None, None, None
    for angle, lit, path in masks:
        sdf = signed_distance(lit)
        newly_dark = ~settled & ~lit
        if previous_sdf is None:
            # Dark in the first drawing: dark from that angle on, as far as anything here can say. With the first
            # mask at 0 degrees that is "always in shadow", which is what it should be.
            threshold[newly_dark] = angle
        elif newly_dark.any():
            # Lit at the previous angle, dark at this one: the boundary crossed this texel somewhere between. The
            # signed distances say how far it had to travel from each drawing's boundary, and the angle is
            # interpolated in that ratio -- so the boundary morphs between the two drawings instead of jumping.
            before = previous_sdf[newly_dark]
            after = sdf[newly_dark]
            # Except that a drawing with no shadow in it at all -- the usual one at 0 degrees -- has no boundary
            # to measure from, its distances are all "infinitely far", and every texel that goes dark before the
            # next drawing would do so at once, at that drawing's angle: a patch of shadow popping in whole. So
            # the shadow GROWS instead, from its deepest point at the next angle outward to its drawn edge.
            # The extra pixel keeps the deepest point off the previous angle itself, so the drawing with no shadow
            # in it still reads back with none.
            if previous_lit.all():
                depth = -after
                before = depth.max() - depth + 1.0
            # And the mirror image: going into a drawing that is all shadow -- the usual one at 180 -- the last
            # lit area SHRINKS, from its drawn edge in to its middle, rather than vanishing at once.
            if not lit.any():
                after = before - before.max() - 1.0
            t = np.clip(before / np.maximum(before - after, 1e-6), 0.0, 1.0)
            threshold[newly_dark] = previous_angle + t * (angle - previous_angle)
        relit |= settled & lit
        settled |= newly_dark
        previous_angle, previous_sdf, previous_lit = angle, sdf, lit
        print(f'  {angle:6.1f} deg  {os.path.basename(path):24}  lit {lit.mean() * 100:5.1f}%')
    return threshold, int(relit.sum())


def parse_size(text, shape):
    if text is None:
        return shape[1], shape[0]
    if 'x' in text:
        w, h = text.lower().split('x')
        return int(w), int(h)
    return int(text), int(text)


def mirrored(image, mirror_u):
    """The map as the shader reads it for light from the right: u -> 2 * mirror_u - u, black off the edge."""
    h, w = image.shape
    u = (np.arange(w) + 0.5) / w
    source = np.floor((2.0 * mirror_u - u) * w).astype(int)
    inside = (source >= 0) & (source < w)
    out = np.zeros_like(image)
    out[:, inside] = image[:, source[inside]]
    return out


def write_preview(path, threshold01, mirror_u, frames=72):
    """The light going once round the head: from behind on the right, through the front, to behind on the left."""
    from_right = mirrored(threshold01, mirror_u)
    images = []
    for i in range(frames + 1):
        around = -1.0 + 2.0 * i / frames            # -1 behind (right), 0 front, +1 behind (left)
        source = threshold01 if around >= 0.0 else from_right
        lit = source > abs(around)
        frame = np.where(lit, 235, 70).astype(np.uint8)
        images.append(Image.fromarray(frame, 'L').convert('P'))
    # Ping-pong, so the loop has no jump.
    images += images[-2:0:-1]
    images[0].save(path, save_all=True, append_images=images[1:], duration=40, loop=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('masks', nargs='+', help='shadow masks, white = lit, one per light angle')
    parser.add_argument('-o', '--output', required=True, help='where to write the face shadow map (PNG)')
    parser.add_argument('--angles', help='comma-separated angles in degrees, in the same order as the masks')
    parser.add_argument('--size', help='output size, N or WxH; defaults to the masks\' size')
    parser.add_argument('--flip', action='store_true', help='mirror the result left to right')
    parser.add_argument('--mirror-u', type=float, default=0.5, help='the face\'s centre line in u (for --preview)')
    parser.add_argument('--preview', help='also write an animated GIF of the light going round the head')
    args = parser.parse_args()

    if args.angles:
        angles = [float(a) for a in args.angles.split(',')]
        if len(angles) != len(args.masks):
            sys.exit(f'{len(args.masks)} masks but {len(angles)} angles.')
    else:
        angles = [angle_from_name(p) for p in args.masks]
    if len(args.masks) < 2:
        sys.exit('Give at least two masks -- the map is what happens between them.')

    masks = load_masks(args.masks, angles)
    h, w = masks[0][1].shape
    print(f'{len(masks)} masks, {w}x{h}:')
    threshold, relit = bake(masks)

    # Check the bake against the drawings it came from: at each mask's own angle, the map should give that mask
    # back. Checked on the 8-bit values that will actually be saved, read the way the shader reads them -- the
    # full-precision map is always exact at the drawn angles and would hide what the rounding does. Only boundary
    # pixels can disagree, by about one.
    stored = np.round(threshold / 180.0 * 255.0) / 255.0 * 180.0
    worst = max(((int(((stored > angle) != lit).sum()), angle) for angle, lit, _ in masks))
    print(f'Read back at each drawn angle (8-bit, as the shader reads it): worst is {worst[0]} px different, '
          f'{worst[0] / stored.size * 100:.3f}% of the image, at {worst[1]:g} deg.')
    if relit:
        print(f'WARNING: {relit} texels ({relit / threshold.size * 100:.2f}%) go dark and then lit again as the '
              'light moves on. Each mask\'s shadow should contain the previous one\'s; the first mask a texel is '
              'dark in has been used.')

    result = threshold / 180.0
    out_w, out_h = parse_size(args.size, threshold.shape)
    if (out_w, out_h) != (w, h):
        # Averaged as it shrinks (BOX), which antialiases the boundary at every angle at once.
        result = np.asarray(Image.fromarray(result.astype(np.float32), 'F').resize((out_w, out_h), Image.BOX))
    if args.flip:
        result = result[:, ::-1]

    # Rounded, not truncated: a texel whose threshold is a hair above a mask's angle has to stay lit at it.
    Image.fromarray(np.clip(np.round(result * 255.0), 0, 255).astype(np.uint8), 'L').save(args.output)
    print(f'Wrote {args.output} ({out_w}x{out_h}).')

    if args.preview:
        write_preview(args.preview, result, args.mirror_u)
        print(f'Wrote {args.preview}.')


if __name__ == '__main__':
    main()

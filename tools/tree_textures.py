#!/usr/bin/env python3
"""Paints the default pictures for trees drawn in the map editor's Trees tab, into the engine's shared assets/tree/ (the
set every project's Tree Tool starts with), or into another folder, such as a project's own.

  leaf_cluster_albedo.png  a spray of leaves on transparent, painted in pale greys so the tree's leaf colour tints it
  bark_albedo.png          brown bark in a few flat tones, tiling both ways
  bark_normal.png          the same bark's relief

    python3 tools/tree_textures.py [--out projects/MyGame/assets/tex/tree]
"""
import math
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parent.parent / "assets/tree"
SUPER = 4


def leaf_points(cx, cy, length, width, angle):
    """A pointed leaf outline, stalk at (cx, cy), reaching out along angle."""
    outline = []
    steps = 14
    for side in (1, -1):
        for k in range(steps + 1):
            t = k / steps if side == 1 else 1 - k / steps
            bulge = math.sin(math.pi * t ** 0.8) * width * 0.5
            outline.append((t * length, side * bulge))
    cos, sin = math.cos(angle), math.sin(angle)
    return [(cx + x * cos - y * sin, cy + x * sin + y * cos) for x, y in outline]


def leaf_cluster(size=512, seed=7):
    """About twenty overlapping leaves on a transparent ground, the outer ones drawn first and the inner ones over them."""
    rng = random.Random(seed)
    big = size * SUPER
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    middle = big / 2
    leaves = []
    for k in range(30):
        # Stalks gathered toward the middle, so the spray has no hole there.
        out = rng.random() ** 1.3 * big * 0.2
        spin = k * 2.39996 + rng.uniform(-0.3, 0.3)
        x = middle + math.cos(spin) * out
        y = middle + math.sin(spin) * out
        # Pointing away from the middle, more or less, with the tip kept inside the picture.
        angle = spin + rng.uniform(-0.7, 0.7)
        length = min(big * rng.uniform(0.2, 0.3), big * 0.47 - out)
        leaves.append((out, x, y, length, length * rng.uniform(0.42, 0.55), angle))
    leaves.sort(key=lambda leaf: -leaf[0])
    for _out, x, y, length, width, angle in leaves:
        tone = rng.randint(206, 255)
        outline = leaf_points(x, y, length, width, angle)
        draw.polygon(outline, fill=(tone, tone, tone, 255))
        # The half in the shade of the midrib, and the midrib itself.
        shade = int(tone * 0.88)
        half = outline[:len(outline) // 2]
        draw.polygon(half, fill=(shade, shade, shade, 255))
        tip = (x + math.cos(angle) * length, y + math.sin(angle) * length)
        vein = int(tone * 0.76)
        draw.line([(x, y), tip], fill=(vein, vein, vein, 255), width=max(2, SUPER * 2))
    return image.resize((size, size), Image.LANCZOS)


def tiling_noise(rng, size, along, across):
    """Smooth noise that wraps at the picture's edges; the higher along is beside across, the longer its features run up the rows."""
    rows = np.fft.fftfreq(size)[:, None] * along
    columns = np.fft.fftfreq(size)[None, :] * across
    radius = np.sqrt(rows * rows + columns * columns)
    radius[0, 0] = 1.0
    spectrum = (rng.normal(size=(size, size)) + 1j * rng.normal(size=(size, size))) / radius ** 1.7
    spectrum[0, 0] = 0
    field = np.real(np.fft.ifft2(spectrum))
    field -= field.min()
    return field / field.max()


def bark(size=1024, seed=11):
    """Ridges running up the trunk in flat tones. Row is y (along the trunk), column is x (round it)."""
    rng = np.random.default_rng(seed)
    ridges = tiling_noise(rng, size, along=4.0, across=1.0)
    detail = tiling_noise(rng, size, along=2.0, across=1.0)
    height = np.clip(0.75 * ridges + 0.25 * detail, 0.0, 1.0)
    height = (height - height.min()) / (height.max() - height.min())

    tones = np.array([[71, 47, 34], [104, 72, 48], [138, 99, 67], [168, 128, 91]], dtype=np.float32)
    edges = [0.34, 0.5, 0.66]
    index = np.digitize(height, edges)
    albedo = tones[index]

    # Relief, using differences that wrap so the normals tile as the colours do.
    strength = 5.0
    dx = (np.roll(height, -1, 0) - np.roll(height, 1, 0)) * size / 64.0 * strength
    dy = (np.roll(height, -1, 1) - np.roll(height, 1, 1)) * size / 64.0 * strength
    normal = np.stack([-dx, -dy, np.ones_like(dx)], axis=-1)
    normal /= np.linalg.norm(normal, axis=-1, keepdims=True)
    return (Image.fromarray(albedo.astype(np.uint8), "RGB"),
            Image.fromarray(((normal * 0.5 + 0.5) * 255).astype(np.uint8), "RGB"))


def main():
    import argparse
    parser = argparse.ArgumentParser(description="Paint the default tree pictures.")
    parser.add_argument("--out", type=Path, default=OUT, help="folder to write into (default: the engine's assets/tree)")
    out = parser.parse_args().out
    out.mkdir(parents=True, exist_ok=True)
    leaf_cluster().save(out / "leaf_cluster_albedo.png")
    colour, relief = bark()
    colour.save(out / "bark_albedo.png")
    relief.save(out / "bark_normal.png")
    print("Wrote", out)


if __name__ == "__main__":
    main()

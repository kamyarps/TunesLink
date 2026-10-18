"""Renders the original, royalty-free album covers bundled with the Google Play demo library.

Run from the repository root:  python android/app/src/demo/generate-artwork.py
Requires Pillow and NumPy. The output is deterministic.
"""

from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

SIZE = 1000
SS = 2  # supersampling for anti-aliased shapes
OUT = Path(__file__).parent / "resources" / "tuneslink-demo"


def srgb_to_linear(c):
    c = np.asarray(c, dtype=np.float64) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c):
    c = np.clip(c, 0, 1)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1 / 2.4) - 0.055)


def grid(n=SIZE * SS):
    y, x = np.mgrid[0:n, 0:n].astype(np.float64) / (n - 1)
    return x, y


def lerp_stops(t, stops):
    """Interpolates (position, rgb) colour stops in linear light."""
    t = np.clip(t, 0, 1)
    positions = [p for p, _ in stops]
    colors = [srgb_to_linear(c) for _, c in stops]
    out = np.zeros(t.shape + (3,))
    for channel in range(3):
        out[..., channel] = np.interp(t, positions, [c[channel] for c in colors])
    return out


def blob(img, x, y, cx, cy, radius, color, strength=1.0):
    d = ((x - cx) ** 2 + (y - cy) ** 2) / radius ** 2
    w = np.exp(-d)[..., None] * strength
    return img * (1 - w) + srgb_to_linear(color) * w


def finish(img, rng, grain=0.018, vignette=0.22):
    x, y = grid(img.shape[0])
    r = np.sqrt((x - 0.5) ** 2 + (y - 0.5) ** 2) / 0.7071
    img = img * (1 - vignette * r[..., None] ** 2.2)
    srgb = linear_to_srgb(img)
    srgb = srgb + rng.normal(0, grain, srgb.shape[:2])[..., None]
    pixels = (np.clip(srgb, 0, 1) * 255 + 0.5).astype(np.uint8)
    image = Image.fromarray(pixels, "RGB")
    if image.width != SIZE:
        image = image.resize((SIZE, SIZE), Image.LANCZOS)
    return image


def afterglow(rng):
    x, y = grid()
    horizon = 0.64
    sky = lerp_stops(y / horizon, [(0, (24, 14, 58)), (0.45, (88, 26, 120)),
                                   (0.8, (214, 62, 128)), (1, (255, 138, 92))])
    water = lerp_stops((y - horizon) / (1 - horizon), [(0, (120, 34, 104)), (1, (16, 10, 40))])
    img = np.where((y < horizon)[..., None], sky, water)
    # The sun: a warm disc split by horizontal slits, with a soft glow and a reflection.
    cx, cy, radius = 0.5, horizon - 0.02, 0.27
    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    glow = np.exp(-((dist - radius).clip(0) / 0.09) ** 2)[..., None] * 0.55
    img = img * (1 - glow) + srgb_to_linear((255, 120, 110)) * glow
    sun = lerp_stops((y - (cy - radius)) / (2 * radius),
                     [(0, (255, 236, 150)), (0.55, (255, 150, 96)), (1, (240, 70, 130))])
    band = (y - (cy - radius * 0.1)) / radius
    slit_width = np.clip(band, 0, 1) * 0.035
    slits = (np.mod(y - cy, 0.06) < slit_width) & (y > cy - radius * 0.1)
    inside = (dist < radius) & (y < horizon) & ~slits
    img = np.where(inside[..., None], sun, img)
    ripple = (y > horizon) & (np.abs(x - cx) < radius * (1.15 - (y - horizon) * 1.6))
    ripple &= np.mod((y - horizon) * 90 + np.sin(x * 40) * 0.6, 3.2) < 1.0
    img = np.where(ripple[..., None], img * 0.55 + srgb_to_linear((255, 150, 120)) * 0.45, img)
    return finish(img, rng)


def paper_moons(rng):
    x, y = grid()
    img = lerp_stops(y, [(0, (250, 236, 218)), (1, (240, 214, 196))])
    discs = [(0.36, 0.42, 0.25, (240, 110, 92)), (0.62, 0.52, 0.22, (120, 158, 214)),
             (0.48, 0.70, 0.16, (250, 204, 96))]
    for cx, cy, radius, color in discs:
        dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
        shadow = np.exp(-((np.sqrt((x - cx - 0.012) ** 2 + (y - cy - 0.02) ** 2) - radius)
                          .clip(0) / 0.025) ** 2)
        shadow = np.where(dist < radius, 0, shadow)[..., None] * 0.22
        img = img * (1 - shadow)
        shade = lerp_stops((y - (cy - radius)) / (2 * radius),
                           [(0, tuple(min(255, c + 22) for c in color)), (1, color)])
        img = np.where((dist < radius)[..., None], shade, img)
    fibres = rng.normal(0, 1, (SIZE * SS // 4, SIZE * SS // 4))
    fibres = np.asarray(Image.fromarray(((fibres + 3) * 40).clip(0, 255).astype(np.uint8))
                        .resize((SIZE * SS, SIZE * SS), Image.BICUBIC), dtype=np.float64)
    img = img * (1 + (fibres[..., None] / 255 - 0.47) * 0.06)
    return finish(img, rng, grain=0.012, vignette=0.12)


def wavelengths(rng):
    x, y = grid()
    img = lerp_stops(y, [(0, (10, 16, 36)), (1, (4, 8, 20))])
    img = blob(img, x, y, 0.5, 0.5, 0.45, (24, 44, 90), 0.6)
    lines = np.zeros_like(x)
    for index in range(46):
        t = index / 45
        phase = t * 2.4
        amp = 0.05 + 0.12 * np.sin(t * np.pi)
        centre = 0.5 + (t - 0.5) * 0.22
        curve = centre + amp * np.sin(x * 2 * np.pi * 1.15 + phase) * np.sin(x * np.pi)
        lines = np.maximum(lines, np.exp(-((y - curve) / 0.0016) ** 2))
    tint = lerp_stops(x, [(0, (64, 214, 210)), (0.5, (246, 214, 140)), (1, (255, 128, 96))])
    glow = np.asarray(Image.fromarray((lines * 255).astype(np.uint8))
                      .filter(ImageFilter.GaussianBlur(10)), dtype=np.float64)[..., None] / 255
    img = img + tint * (lines[..., None] * 0.95 + glow * 0.5)
    return finish(img, rng, vignette=0.3)


def tidewater(rng):
    x, y = grid()
    img = lerp_stops(y, [(0, (180, 232, 228)), (0.5, (62, 170, 182)), (1, (14, 70, 104))])
    img = blob(img, x, y, 0.72, 0.28, 0.20, (250, 252, 236), 0.85)
    img = blob(img, x, y, 0.2, 0.75, 0.35, (20, 110, 150), 0.6)
    img = blob(img, x, y, 0.85, 0.85, 0.3, (10, 44, 84), 0.7)
    for index, level in enumerate(np.linspace(0.52, 0.94, 6)):
        wave = level + 0.018 * np.sin(x * 2 * np.pi * (1.4 + index * 0.3) + index)
        edge = 1 / (1 + np.exp(-(y - wave) / 0.004))
        img = img * (1 - 0.09 * edge[..., None]) + srgb_to_linear((230, 250, 246)) * 0.0
        highlight = np.exp(-((y - wave) / 0.003) ** 2)[..., None] * 0.18
        img = img + highlight * srgb_to_linear((236, 255, 250))
    return finish(img, rng, grain=0.014)


def wildwood(rng):
    x, y = grid()
    img = lerp_stops(y, [(0, (250, 214, 150)), (0.34, (240, 160, 104)), (0.52, (196, 120, 100)),
                         (1, (40, 46, 44))])
    img = blob(img, x, y, 0.64, 0.36, 0.11, (255, 244, 210), 0.95)
    ridges = [(0.47, (190, 132, 112), 0.07), (0.58, (126, 98, 96), 0.09),
              (0.7, (70, 76, 70), 0.1), (0.82, (36, 52, 46), 0.09), (0.95, (14, 24, 22), 0.07)]
    for index, (base, color, height) in enumerate(ridges):
        noise = sum(np.sin(x * np.pi * f + index * 1.7 + f * 0.3) / f for f in (1.3, 2.7, 5.9))
        ridge = base - height * (0.55 + 0.45 * noise)
        fog = np.exp(-((y - ridge).clip(0) / 0.05))[..., None] * 0.42 * (1 - 0.19 * index)
        layer = srgb_to_linear(color) * (1 - fog) + srgb_to_linear((246, 186, 136)) * fog
        img = np.where((y > ridge)[..., None], layer, img)
    return finish(img, rng, vignette=0.24)


def velvet_hours(rng):
    x, y = grid()
    img = lerp_stops(np.sqrt((x - 0.32) ** 2 + (y - 0.28) ** 2),
                     [(0, (104, 18, 40)), (0.55, (44, 6, 22)), (1.1, (10, 3, 8))])
    palette = [(255, 196, 120), (255, 168, 112), (250, 222, 176), (232, 112, 120)]

    def layer(count, radii, alpha, blur):
        bokeh = np.zeros(x.shape + (3,))
        for _ in range(count):
            cx, cy = rng.uniform(0.0, 1.0), rng.uniform(0.15, 0.95)
            radius = rng.uniform(*radii)
            dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
            disc = np.clip((radius - dist) / 0.003, 0, 1) * (0.55 + 0.45 * (dist / radius) ** 4)
            bokeh += disc[..., None] * srgb_to_linear(palette[rng.integers(len(palette))])                 * rng.uniform(*alpha)
        pixels = (np.clip(linear_to_srgb(bokeh), 0, 1) * 255).astype(np.uint8)
        return srgb_to_linear(np.asarray(Image.fromarray(pixels)
                                         .filter(ImageFilter.GaussianBlur(blur)), dtype=np.float64))

    img = img + layer(10, (0.09, 0.16), (0.10, 0.22), 26)
    img = img + layer(16, (0.018, 0.05), (0.16, 0.34), 3)
    img = blob(img, x, y, 0.5, 1.05, 0.45, (8, 2, 6), 0.5)
    return finish(img, rng, vignette=0.32)


def static_bloom(rng):
    x, y = grid()
    img = lerp_stops(np.sqrt((x - 0.5) ** 2 + (y - 0.5) ** 2),
                     [(0, (16, 84, 92)), (0.45, (8, 44, 56)), (0.85, (4, 16, 24))])
    cx, cy = 0.5, 0.5
    angle = np.arctan2(y - cy, x - cx)
    radius = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    shade_mask = np.zeros_like(x)
    layers = [(8, 0.34, (236, 72, 140), (150, 30, 90), 0.0),
              (8, 0.25, (255, 150, 110), (210, 84, 70), np.pi / 8),
              (8, 0.15, (255, 232, 176), (246, 180, 110), 0.0)]
    for count, length, light, dark, offset in layers:
        petal = np.abs(np.cos((angle + offset) * count / 2))
        edge = length * (0.3 + 0.7 * petal ** 2.2)
        mask = np.clip((edge - radius) / 0.005, 0, 1)
        drop = np.clip((edge * 1.04 - radius) / 0.03, 0, 1) * (1 - mask)
        img = img * (1 - 0.35 * drop[..., None])
        shade = lerp_stops(radius / length, [(0, light), (1, dark)])
        img = img * (1 - mask[..., None]) + shade * mask[..., None]
    centre = np.clip((0.035 - radius) / 0.004, 0, 1)[..., None]
    img = img * (1 - centre) + srgb_to_linear((255, 248, 220)) * centre
    return finish(img, rng, vignette=0.3)


def copper_sky(rng):
    x, y = grid()
    img = lerp_stops(y, [(0, (250, 232, 206)), (1, (238, 206, 170))])
    cx, cy = 0.5, 0.78
    radius = np.sqrt((x - cx) ** 2 + (y - cy) ** 2)
    bands = [(0.62, (120, 52, 34)), (0.54, (178, 82, 44)), (0.46, (214, 120, 60)),
             (0.38, (232, 164, 84)), (0.30, (240, 200, 120)), (0.22, (250, 226, 170))]
    for outer, color in bands:
        mask = np.clip((outer - radius) / 0.004, 0, 1) * (y < cy).astype(float)
        img = img * (1 - mask[..., None]) + srgb_to_linear(color) * mask[..., None]
    ground = np.clip((y - cy) / 0.004, 0, 1)[..., None]
    img = img * (1 - ground) + lerp_stops((y - cy) / (1 - cy), [(0, (96, 44, 30)), (1, (52, 24, 18))]) * ground
    return finish(img, rng, grain=0.016, vignette=0.18)


COVERS = {
    "afterglow-avenue": afterglow,
    "paper-moons": paper_moons,
    "wavelengths": wavelengths,
    "tidewater": tidewater,
    "wildwood": wildwood,
    "velvet-hours": velvet_hours,
    "static-bloom": static_bloom,
    "copper-sky": copper_sky,
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for index, (name, render) in enumerate(COVERS.items()):
        image = render(np.random.default_rng(1000 + index))
        image.save(OUT / f"{name}.jpg", "JPEG", quality=90, optimize=True, progressive=False,
                   subsampling=0)
        print(f"{name}.jpg")


if __name__ == "__main__":
    main()

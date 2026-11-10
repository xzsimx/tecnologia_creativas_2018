#!/usr/bin/env python3
"""
Genera a partir de DotMatrix.kt (una sola fuente de verdad para glifos e íconos):

  * res/drawable/ic_tile_*.xml          íconos del tile de Ajustes rápidos
  * res/drawable/ic_launcher_fg.xml     primer plano del ícono adaptable
  * res/drawable-nodpi/widget_preview.png  imagen del selector de widgets
  * docs/preview_*.png                  mockups del widget (claro/oscuro, 4x2 y 2x2)

Uso:  python3 tools/preview.py
"""
import math
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "app/src/main/java/cl/tecnocreativas/budswidget/ui/DotMatrix.kt")
RES = os.path.join(ROOT, "app/src/main/res")
DOCS = os.path.join(ROOT, "docs")

# ───────────────────────── matriz de puntos (port de DotMatrix.kt) ─────────────────────────

src = open(KT, encoding="utf-8").read()
block = src.split("// GLYPHS-BEGIN")[1].split("// GLYPHS-END")[0]
GLYPHS = {
    m.group(1): re.findall(r'"([.#]{5})"', m.group(2))
    for m in re.finditer(r"'(.)' to arrayOf\(([^)]*)\)", block)
}
assert len(GLYPHS) > 40, "no se pudieron leer los glifos"


def text_grid(text):
    import unicodedata
    clean = "".join(c for c in unicodedata.normalize("NFD", text.upper()) if unicodedata.category(c) != "Mn")
    glyphs = [GLYPHS.get(c, GLYPHS["?"]) for c in clean]
    w = len(glyphs) * 6 - 1
    grid = [[False] * w for _ in range(7)]
    for i, g in enumerate(glyphs):
        for y in range(7):
            for x in range(5):
                if g[y][x] == "#":
                    grid[y][i * 6 + x] = True
    return grid


def icon_grid(mode, size=13):
    c = (size - 1) / 2
    outer = c
    grid = []
    for y in range(size):
        row = []
        for x in range(size):
            d = math.hypot(x - c, y - c)
            ring = abs(d - outer) < 0.55
            if mode == "ANC":
                v = ring or d <= outer * 0.42
            elif mode == "TRANSPARENCY":
                mid = abs(d - outer * 0.58) < 0.5
                v = (ring and (x + y) % 2 == 0) or (mid and (x + y) % 2 == 1) or d <= 1.0
            else:
                v = ring or (x == size - 1 - y and d < outer)
            row.append(v)
        grid.append(row)
    return grid


def draw_grid(draw, grid, ox, oy, pitch, color, ratio=0.78):
    r = pitch * ratio / 2
    for y, row in enumerate(grid):
        for x, on in enumerate(row):
            if on:
                cx = ox + x * pitch + pitch / 2
                cy = oy + y * pitch + pitch / 2
                draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)


# ───────────────────────── vectores ─────────────────────────

def circles_path(grid, pitch, ox, oy, ratio=0.78):
    r = pitch * ratio / 2
    parts = []
    for y, row in enumerate(grid):
        for x, on in enumerate(row):
            if on:
                cx = ox + x * pitch + pitch / 2
                cy = oy + y * pitch + pitch / 2
                parts.append(f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0")
    return "".join(parts)


def write_vectors():
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    for mode, name in [("ANC", "anc"), ("TRANSPARENCY", "transparency"), ("OFF", "off")]:
        path = circles_path(icon_grid(mode), 24 / 13, 0, 0, ratio=0.86)
        open(os.path.join(RES, "drawable", f"ic_tile_{name}.xml"), "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generado por tools/preview.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="{path}" />
</vector>
""")
    # Ícono adaptable: 108×108, zona segura central de 66
    pitch = 46 / 13
    o = (108 - 46) / 2
    path = circles_path(icon_grid("ANC"), pitch, o, o)
    rr = 3.2
    red = f"M{o + 46 + 2 - rr:.2f},{o - 2:.2f}a{rr},{rr} 0 1,0 {2 * rr},0a{rr},{rr} 0 1,0 {-2 * rr},0"
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generado por tools/preview.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFFFF" android:pathData="{path}" />
    <path android:fillColor="#FFD71921" android:pathData="{red}" />
</vector>
""")


# ───────────────────────── mockups ─────────────────────────

THEMES = {
    "dark": dict(bg="#1B1B1B", fg="#FFFFFF", fg2="#8A8A8A", idle="#2C2C2C", sel="#FFFFFF", on_sel="#000000",
                 dot_off="#4A4A4A", wall="#000000"),
    "light": dict(bg="#FFFFFF", fg="#000000", fg2="#8A8A8A", idle="#EFEFEF", sel="#000000", on_sel="#FFFFFF",
                  dot_off="#C8C8C8", wall="#D9D9D9"),
}
RED = "#D71921"


def font(size, bold=False):
    for name in (["DejaVuSans-Bold.ttf"] if bold else []) + ["DejaVuSans.ttf", "LiberationSans-Regular.ttf"]:
        for d in ["/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/truetype/liberation"]:
            p = os.path.join(d, name)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def mono(size):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
    return ImageFont.truetype(p, size) if os.path.exists(p) else font(size)


def spaced(draw, xy, text, fnt, fill, spacing):
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=fnt, fill=fill)
        x += draw.textlength(ch, font=fnt) + spacing


HEADLINE = {"ANC": "ANC", "TRANSPARENCY": "TRANSP", "OFF": "NORMAL"}
SUB = {"ANC": "Cancelación de ruido · Máxima", "TRANSPARENCY": "Transparencia", "OFF": "Normal · ANC apagado"}


def wide(theme, mode, s=3.0):
    t = THEMES[theme]
    W, H = int(330 * s), int(170 * s)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=28 * s, fill=t["bg"])
    p = 16 * s
    # cabecera
    d.ellipse([p, p + 4 * s, p + 8 * s, p + 12 * s], fill=RED)
    spaced(d, (p + 16 * s, p), "BUDS 4", font(int(12 * s), True), t["fg"], 1.4 * s)
    bat = "L 80  R 78"
    f = mono(int(12 * s))
    d.text((W - p - d.textlength(bat, font=f), p), bat, font=f, fill=t["fg2"])
    # titular
    draw_grid(d, text_grid(HEADLINE[mode]), p, p + 28 * s, 4.6 * s, t["fg"])
    d.text((p, p + 66 * s), SUB[mode], font=font(int(12 * s)), fill=t["fg2"])
    # botones
    bw = (W - 2 * p - 12 * s) / 3
    by = H - p - 44 * s
    for i, (m, label) in enumerate([("ANC", "ANC"), ("TRANSPARENCY", "TRANSP"), ("OFF", "NORMAL")]):
        x0 = p + i * (bw + 6 * s)
        sel = m == mode
        d.rounded_rectangle([x0, by, x0 + bw, by + 44 * s], radius=22 * s, fill=t["sel"] if sel else t["idle"])
        col = t["on_sel"] if sel else t["fg"]
        lf = font(int(11 * s), True)
        tw = 13 * 1.55 * s + 7 * s + d.textlength(label, font=lf) + len(label) * 0.9 * s
        ix = x0 + (bw - tw) / 2
        draw_grid(d, icon_grid(m), ix, by + (44 * s - 13 * 1.55 * s) / 2, 1.55 * s, col)
        spaced(d, (ix + 13 * 1.55 * s + 7 * s, by + 14.5 * s), label, lf, col, 0.9 * s)
    return img


def small(theme, mode, s=3.0):
    t = THEMES[theme]
    W = H = int(160 * s)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=28 * s, fill=t["bg"])
    p = 14 * s
    d.ellipse([p, p + 4 * s, p + 7 * s, p + 11 * s], fill=RED)
    spaced(d, (p + 13 * s, p), "BUDS 4", font(int(11 * s), True), t["fg"], 1.3 * s)
    f = mono(int(11 * s))
    d.text((W - p - d.textlength("78%", font=f), p), "78%", font=f, fill=t["fg2"])
    pitch = 4.2 * s
    draw_grid(d, icon_grid(mode), (W - 13 * pitch) / 2, 32 * s, pitch, t["fg"])
    g = text_grid(HEADLINE[mode])
    hp = 2.6 * s
    draw_grid(d, g, (W - len(g[0]) * hp) / 2, H - p - 14 * s - 7 * hp, hp, t["fg"])
    for i, m in enumerate(["ANC", "TRANSPARENCY", "OFF"]):
        cx = W / 2 + (i - 1) * 12 * s
        cy = H - p - 3 * s
        d.ellipse([cx - 3 * s, cy - 3 * s, cx + 3 * s, cy + 3 * s], fill=t["fg"] if m == mode else t["dot_off"])
    return img


def sheet(theme):
    t = THEMES[theme]
    s = 3.0
    pad = int(24 * s)
    tiles = [wide(theme, m, s) for m in ["ANC", "TRANSPARENCY", "OFF"]]
    smalls = [small(theme, m, s) for m in ["ANC", "TRANSPARENCY", "OFF"]]
    W = pad * 2 + tiles[0].width + int(16 * s) + smalls[0].width
    H = pad * 2 + sum(x.height for x in tiles) + int(16 * s) * 2
    img = Image.new("RGBA", (W, H), t["wall"])
    y = pad
    for wt, sm in zip(tiles, smalls):
        img.alpha_composite(wt, (pad, y))
        img.alpha_composite(sm, (pad + wt.width + int(16 * s), y + (wt.height - sm.height) // 2))
        y += wt.height + int(16 * s)
    return img


def main():
    write_vectors()
    os.makedirs(DOCS, exist_ok=True)
    for theme in THEMES:
        sheet(theme).convert("RGB").save(os.path.join(DOCS, f"preview_{theme}.png"), optimize=True)
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    wide("dark", "ANC", 2.0).save(os.path.join(nodpi, "widget_preview.png"), optimize=True)
    print("ok:", os.listdir(DOCS))


if __name__ == "__main__":
    sys.exit(main())

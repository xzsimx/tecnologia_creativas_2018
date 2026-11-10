#!/usr/bin/env python3
"""
Fuente única del diseño visual. Genera:

  * res/drawable/ic_mode_{anc,transparency,off}.xml   íconos de línea fina (1.5 dp)
  * res/drawable/ic_launcher_fg.xml                   ícono de la app
  * res/drawable-nodpi/widget_preview.png             imagen del selector de widgets
  * docs/preview_{dark,light}.png                     mockups de widgets
  * docs/preview_app.png                              mockup de la app

Los números de batería usan la matriz de puntos de DotMatrix.kt (se leen de ahí).

Uso:  python3 tools/preview.py
"""
import math
import os
import re
import unicodedata

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KT = os.path.join(ROOT, "app/src/main/java/cl/tecnocreativas/budswidget/ui/DotMatrix.kt")
RES = os.path.join(ROOT, "app/src/main/res")
DOCS = os.path.join(ROOT, "docs")

# ───────────────────────── íconos de línea (viewport 24) ─────────────────────────
# Primitivas: ("circle", cx, cy, r) · ("arc", cx, cy, r, a0, a1) · ("line", x1, y1, x2, y2)
# Ángulos en grados, sentido horario desde las 3 en punto (y hacia abajo), igual que PIL.

ICONS = {
    # ANC: anillo exterior + núcleo: el sonido queda sellado
    "anc": [("circle", 12, 12, 8.5), ("circle", 12, 12, 3.5)],
    # Transparencia: un punto con ondas que lo atraviesan ((•))
    "transparency": [
        ("circle", 12, 12, 2.25),
        ("arc", 12, 12, 6, -40, 40), ("arc", 12, 12, 6, 140, 220),
        ("arc", 12, 12, 9.75, -38, 38), ("arc", 12, 12, 9.75, 142, 218),
    ],
    # Normal: anillo tachado
    "off": [("circle", 12, 12, 8.5), ("line", 6, 18, 18, 6)],
}
STROKE = 1.5


def pt(cx, cy, r, a):
    return cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))


def path_data(shapes, scale=1.0, ox=0.0, oy=0.0):
    out = []
    for s in shapes:
        if s[0] == "circle":
            _, cx, cy, r = s
            cx, cy, r = ox + cx * scale, oy + cy * scale, r * scale
            out.append(f"M{cx - r:.3f},{cy:.3f}A{r:.3f},{r:.3f} 0 1,1 {cx + r:.3f},{cy:.3f}"
                       f"A{r:.3f},{r:.3f} 0 1,1 {cx - r:.3f},{cy:.3f}Z")
        elif s[0] == "arc":
            _, cx, cy, r, a0, a1 = s
            x0, y0 = pt(ox + cx * scale, oy + cy * scale, r * scale, a0)
            x1, y1 = pt(ox + cx * scale, oy + cy * scale, r * scale, a1)
            large = 1 if (a1 - a0) % 360 > 180 else 0
            out.append(f"M{x0:.3f},{y0:.3f}A{r * scale:.3f},{r * scale:.3f} 0 {large},1 {x1:.3f},{y1:.3f}")
        elif s[0] == "line":
            _, x1, y1, x2, y2 = s
            out.append(f"M{ox + x1 * scale:.3f},{oy + y1 * scale:.3f}L{ox + x2 * scale:.3f},{oy + y2 * scale:.3f}")
    return "".join(out)


def write_vectors():
    d = os.path.join(RES, "drawable")
    for name, shapes in ICONS.items():
        open(os.path.join(d, f"ic_mode_{name}.xml"), "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generado por tools/preview.py: no editar a mano -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="{path_data(shapes)}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{STROKE}"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
""")
    # Ícono de la app: 108×108, zona segura central de 66
    k = 44 / 24
    o = (108 - 44) / 2
    rr = 3.2
    red = f"M{o + 44 - rr:.2f},{o + 1:.2f}a{rr},{rr} 0 1,0 {2 * rr},0a{rr},{rr} 0 1,0 {-2 * rr},0"
    open(os.path.join(d, "ic_launcher_fg.xml"), "w").write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generado por tools/preview.py: no editar a mano -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path
        android:pathData="{path_data(ICONS['anc'], k, o, o)}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{STROKE * k:.2f}"
        android:strokeLineCap="round" />
    <path android:fillColor="#FFD71921" android:pathData="{red}" />
</vector>
""")


def draw_icon(d, name, x, y, size, color):
    """Dibuja un ícono de 24 unidades en (x, y) con lado `size` px."""
    k = size / 24
    w = max(1, round(STROKE * k))
    for s in ICONS[name]:
        if s[0] == "circle":
            _, cx, cy, r = s
            d.ellipse([x + (cx - r) * k, y + (cy - r) * k, x + (cx + r) * k, y + (cy + r) * k], outline=color, width=w)
        elif s[0] == "arc":
            _, cx, cy, r, a0, a1 = s
            d.arc([x + (cx - r) * k, y + (cy - r) * k, x + (cx + r) * k, y + (cy + r) * k], a0, a1, fill=color, width=w)
            for a in (a0, a1):
                px, py = pt(x + cx * k, y + cy * k, r * k - w / 2, a)
                d.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=color)
        elif s[0] == "line":
            _, x1, y1, x2, y2 = s
            d.line([x + x1 * k, y + y1 * k, x + x2 * k, y + y2 * k], fill=color, width=w)
            for px, py in ((x1, y1), (x2, y2)):
                d.ellipse([x + px * k - w / 2, y + py * k - w / 2, x + px * k + w / 2, y + py * k + w / 2], fill=color)


# ───────────────────────── números en matriz de puntos ─────────────────────────

src = open(KT, encoding="utf-8").read()
block = src.split("// GLYPHS-BEGIN")[1].split("// GLYPHS-END")[0]
GLYPHS = {m.group(1): re.findall(r'"([.#]{5})"', m.group(2))
          for m in re.finditer(r"'(.)' to arrayOf\(([^)]*)\)", block)}
assert len(GLYPHS) > 10, "no se pudieron leer los glifos"


def text_grid(text):
    clean = "".join(c for c in unicodedata.normalize("NFD", text.upper()) if unicodedata.category(c) != "Mn")
    glyphs = [GLYPHS.get(c, GLYPHS["?"]) for c in clean]
    w = max(1, len(glyphs) * 6 - 1)
    grid = [[False] * w for _ in range(7)]
    for i, g in enumerate(glyphs):
        for yy in range(7):
            for xx in range(5):
                if g[yy][xx] == "#":
                    grid[yy][i * 6 + xx] = True
    return grid


def draw_dots(d, text, x, y, pitch, color, ratio=0.78):
    grid = text_grid(text)
    r = pitch * ratio / 2
    for yy, row in enumerate(grid):
        for xx, on in enumerate(row):
            if on:
                cx, cy = x + xx * pitch + pitch / 2, y + yy * pitch + pitch / 2
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    return len(grid[0]) * pitch


# ───────────────────────── mockups ─────────────────────────

THEMES = {
    "dark": dict(bg="#1B1B1B", fg="#FFFFFF", fg2="#8A8A8A", idle="#2C2C2C", sel="#FFFFFF", on_sel="#000000",
                 wall="#000000", app="#000000", card="#141414"),
    "light": dict(bg="#FFFFFF", fg="#000000", fg2="#8A8A8A", idle="#EFEFEF", sel="#000000", on_sel="#FFFFFF",
                  wall="#D9D9D9", app="#F2F2F2", card="#FFFFFF"),
}
RED = "#D71921"
MODES = [("anc", "ANC"), ("transparency", "Transparencia"), ("off", "Normal")]
SS = 4  # supersampling


def F(size, weight="Regular"):
    for p in (f"/usr/share/fonts/opentype/inter/Inter-{weight}.otf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, int(size))
    return ImageFont.load_default()


def M(size):
    p = "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-Regular.ttf"
    for c in (p, "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"):
        if os.path.exists(c):
            return ImageFont.truetype(c, int(size))
    return F(size)


def spaced(d, x, y, text, font, fill, track):
    for ch in text:
        d.text((x, y), ch, font=font, fill=fill)
        x += d.textlength(ch, font=font) + track
    return x


def spaced_len(d, text, font, track):
    return sum(d.textlength(c, font=font) + track for c in text) - track


def mode_button(d, t, x, y, size, name, selected):
    d.ellipse([x, y, x + size, y + size], fill=t["sel"] if selected else t["idle"])
    ic = size * 0.46
    draw_icon(d, name, x + (size - ic) / 2, y + (size - ic) / 2, ic, t["on_sel"] if selected else t["fg"])


def header(d, t, x, y, w, s, name="BUDS 4", battery="80%", dot=True):
    """Nombre a la izquierda, batería en puntos a la derecha."""
    fx = x
    if dot:
        d.ellipse([x, y + 4 * s, x + 6 * s, y + 10 * s], fill=RED)
        fx += 12 * s
    spaced(d, fx, y, name, F(11 * s, "Medium"), t["fg"], 1.6 * s)
    pitch = 2 * s
    bw = len(text_grid(battery)[0]) * pitch
    draw_dots(d, battery, x + w - bw, y + 0.5 * s, pitch, t["fg"])


def wide(theme, active, s):
    t = THEMES[theme]
    W, H = 330 * s, 160 * s
    img = Image.new("RGBA", (int(W), int(H)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=26 * s, fill=t["bg"])
    p = 18 * s
    header(d, t, p, p, W - 2 * p, s)
    size = 54 * s
    col_w = (W - 2 * p) / 3
    by = H - p - 16 * s - 8 * s - size
    for i, (name, label) in enumerate(MODES):
        cx = p + col_w * i + col_w / 2
        sel = name == active
        mode_button(d, t, cx - size / 2, by, size, name, sel)
        f = F(11 * s, "Medium" if sel else "Regular")
        d.text((cx - d.textlength(label, font=f) / 2, by + size + 8 * s), label, font=f,
               fill=t["fg"] if sel else t["fg2"])
    return img


def small(theme, active, s):
    t = THEMES[theme]
    W = H = 160 * s
    img = Image.new("RGBA", (int(W), int(H)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=26 * s, fill=t["bg"])
    p = 16 * s
    header(d, t, p, p, W - 2 * p, s, battery="78%", dot=False)
    size = 64 * s
    mode_button(d, t, (W - size) / 2, 40 * s, size, active, True)
    label = dict(MODES)[active]
    f = F(12 * s, "Medium")
    d.text(((W - d.textlength(label, font=f)) / 2, H - p - 14 * s), label, font=f, fill=t["fg"])
    return img


def sheet(theme, s=3):
    t = THEMES[theme]
    S = s * SS
    pad, gap = 24 * S, 16 * S
    rows = [(wide(theme, m, S), small(theme, m, S)) for m, _ in MODES]
    W = pad * 2 + rows[0][0].width + gap + rows[0][1].width
    H = pad * 2 + sum(r[0].height for r in rows) + gap * (len(rows) - 1)
    img = Image.new("RGBA", (int(W), int(H)), t["wall"])
    y = pad
    for wt, sm in rows:
        img.alpha_composite(wt, (int(pad), int(y)))
        img.alpha_composite(sm, (int(pad + wt.width + gap), int(y + (wt.height - sm.height) / 2)))
        y += wt.height + gap
    return img.resize((img.width // SS, img.height // SS), Image.LANCZOS)


def app(theme="dark", s=3):
    """Pantalla principal de la app, al estilo Nothing X."""
    t = THEMES[theme]
    S = s * SS
    W, H = 360 * S, 760 * S
    img = Image.new("RGBA", (int(W), int(H)), t["app"])
    d = ImageDraw.Draw(img)
    p = 20 * S
    y = 56 * S
    d.text((p, y), "OnePlus Buds 4", font=F(30 * S, "Light"), fill=t["fg"])
    y += 44 * S
    d.ellipse([p, y + 4 * S, p + 6 * S, y + 10 * S], fill=RED)
    d.text((p + 12 * S, y), "Conectado", font=F(13 * S), fill=t["fg2"])
    # batería
    y += 40 * S
    cw = (W - 2 * p) / 3
    for i, (lab, val) in enumerate([("IZQ", "80"), ("DER", "78"), ("ESTUCHE", "64")]):
        x = p + cw * i
        draw_dots(d, val, x, y, 4.2 * S, t["fg"])
        spaced(d, x, y + 38 * S, lab, M(10 * S), t["fg2"], 1.2 * S)
    # tarjeta control de ruido
    y += 78 * S
    d.rounded_rectangle([p, y, W - p, y + 196 * S], radius=24 * S, fill=t["card"])
    spaced(d, p + 18 * S, y + 18 * S, "CONTROL DE RUIDO", M(10 * S), t["fg2"], 1.2 * S)
    size = 60 * S
    by = y + 52 * S
    inner = W - 2 * p
    for i, (name, label) in enumerate(MODES):
        cx = p + inner / 3 * i + inner / 6
        sel = name == "anc"
        mode_button(d, t, cx - size / 2, by, size, name, sel)
        f = F(12 * S, "Medium" if sel else "Regular")
        d.text((cx - d.textlength(label, font=f) / 2, by + size + 10 * S), label, font=f,
               fill=t["fg"] if sel else t["fg2"])
    # niveles (segmentado)
    ly = by + size + 40 * S
    levels = ["Máx", "Media", "Suave", "Intel.", "Adapt."]
    seg_w = (inner - 36 * S) / len(levels)
    d.rounded_rectangle([p + 18 * S, ly, W - p - 18 * S, ly + 30 * S], radius=15 * S, fill=t["idle"])
    for i, lv in enumerate(levels):
        x0 = p + 18 * S + seg_w * i
        if i == 0:
            d.rounded_rectangle([x0 + 2 * S, ly + 2 * S, x0 + seg_w - 2 * S, ly + 28 * S], radius=13 * S, fill=t["sel"])
        f = F(11 * S, "Medium")
        d.text((x0 + (seg_w - d.textlength(lv, font=f)) / 2, ly + 8 * S), lv, font=f,
               fill=t["on_sel"] if i == 0 else t["fg2"])
    # acciones
    y += 196 * S + 16 * S
    d.rounded_rectangle([p, y, W - p, y + 52 * S], radius=26 * S, fill=t["card"])
    d.text((p + 20 * S, y + 17 * S), "Añadir widget", font=F(14 * S), fill=t["fg"])
    d.text((W - p - 28 * S, y + 14 * S), "+", font=F(18 * S, "Light"), fill=t["fg"])
    y += 52 * S + 10 * S
    d.rounded_rectangle([p, y, W - p, y + 52 * S], radius=26 * S, fill=t["card"])
    d.text((p + 20 * S, y + 17 * S), "Avanzado", font=F(14 * S), fill=t["fg"])
    d.text((W - p - 30 * S, y + 15 * S), "›", font=F(18 * S, "Light"), fill=t["fg2"])
    return img.resize((img.width // SS, img.height // SS), Image.LANCZOS)


def main():
    write_vectors()
    os.makedirs(DOCS, exist_ok=True)
    for theme in THEMES:
        sheet(theme).convert("RGB").save(os.path.join(DOCS, f"preview_{theme}.png"), optimize=True)
    app("dark").convert("RGB").save(os.path.join(DOCS, "preview_app.png"), optimize=True)
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    w = wide("dark", "anc", 2 * SS)
    w.resize((w.width // SS, w.height // SS), Image.LANCZOS).save(os.path.join(nodpi, "widget_preview.png"), optimize=True)
    print("ok")


if __name__ == "__main__":
    main()

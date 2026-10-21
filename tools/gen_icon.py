#!/usr/bin/env python3
"""Generates Sortopia's launcher icon and Play Store icon (SPEC §13).

One hero glass tube holding three red hearts with faces, its lid popping off, on the warm
World 1 peach gradient. The geometry is defined once (in the 108-unit adaptive-icon viewport)
and written out as Android vector drawables, an SVG master, and PNGs rendered by rsvg-convert.

Deterministic: running it again produces identical files. Usage (inside devenv):
    python3 tools/gen_icon.py
"""
import math
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "android/src/main/res/drawable"
STORE = ROOT / "store"

# Colors match Theme.kt: ink, primary, World 1 background, item type 5 (heart) red.
INK = "3B3355"
LID = "FF8A5B"
BG_TOP = "FFF1D6"
BG_BOTTOM = "FFD9B0"
HEART = "EF5350"
HEART_SHADE = "9B3634"  # Shapes.shade(HEART, 0.35)
HEART_TINT = "F6A09F"  # Shapes.tint(HEART, 0.45)
GLASS = ("FFFFFF", 0.55)
SHINE = ("FFFFFF", 0.6)
SHADOW = ("3B3355", 0.18)

# Tube: straight walls from the rim down, round bottom; centred in the 66-unit safe circle.
TUBE_L, TUBE_R = 40.0, 68.0
TUBE_TOP = 38.0
TUBE_BOTTOM = 88.0
RIM = (37.0, 33.0, 34.0, 5.5)  # x, y, w, h
LID_BOX = (42.0, 24.5, 30.0, 7.0)
LID_ROTATION = -12.0  # degrees; the right end lifts as the lid pops
HEART_K = 8.6  # half-width of a heart
HEART_YS = (45.8, 61.6, 77.4)
# The drawing is laid out generously, then scaled about PIVOT and shifted so that everything,
# pop ticks included, sits inside the 66-unit safe circle.
SCALE = 0.84
PIVOT = (54.0, 55.0)
SHIFT_Y = -1.0


def num(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def circle(cx, cy, r):
    return f"M{num(cx - r)},{num(cy)}a{num(r)},{num(r)} 0,1 0,{num(2 * r)},0a{num(r)},{num(r)} 0,1 0,{num(-2 * r)},0z"


def rounded_rect(x, y, w, h, r):
    return (
        f"M{num(x + r)},{num(y)}h{num(w - 2 * r)}a{num(r)},{num(r)} 0,0 1,{num(r)},{num(r)}"
        f"v{num(h - 2 * r)}a{num(r)},{num(r)} 0,0 1,{num(-r)},{num(r)}h{num(-(w - 2 * r))}"
        f"a{num(r)},{num(r)} 0,0 1,{num(-r)},{num(-r)}v{num(-(h - 2 * r))}a{num(r)},{num(r)} 0,0 1,{num(r)},{num(-r)}z"
    )


# A heart in unit space (x in -1..1, y from about -0.82 at the lobes to 1 at the point, y down).
HEART_CURVES = [
    ((-0.25, -1.0), (-1.05, -0.85), (-1.0, -0.2)),
    ((-0.95, 0.3), (-0.35, 0.6), (0.0, 1.0)),
    ((0.35, 0.6), (0.95, 0.3), (1.0, -0.2)),
    ((1.05, -0.85), (0.25, -1.0), (0.0, -0.45)),
]


def heart(cx, cy, k):
    d = f"M{num(cx)},{num(cy - 0.45 * k)}"
    for (ax, ay), (bx, by), (ex, ey) in HEART_CURVES:
        d += f"C{num(cx + ax * k)},{num(cy + ay * k)} {num(cx + bx * k)},{num(cy + by * k)} {num(cx + ex * k)},{num(cy + ey * k)}"
    return d + "z"


def smile(cx, cy, ro, ri):
    """A crescent: the lower half-ring between radii ri and ro, from 20° to 160°."""
    a0, a1 = math.radians(20), math.radians(160)

    def pt(r, a):
        return num(cx + r * math.cos(a)), num(cy + r * math.sin(a))

    ox0, oy0 = pt(ro, a0)
    ox1, oy1 = pt(ro, a1)
    ix1, iy1 = pt(ri, a1)
    ix0, iy0 = pt(ri, a0)
    return (
        f"M{ox0},{oy0}A{num(ro)},{num(ro)} 0,0 1,{ox1},{oy1}"
        f"L{ix1},{iy1}A{num(ri)},{num(ri)} 0,0 0,{ix0},{iy0}z"
    )


def face(cx, cy, k):
    """Eyes and smile, a little bolder than ItemArt.drawFace so they read at launcher size."""
    fy = cy - 0.08 * k
    return [
        circle(cx - 0.3 * k, fy - 0.05 * k, 0.14 * k),
        circle(cx + 0.3 * k, fy - 0.05 * k, 0.14 * k),
        smile(cx, fy + 0.08 * k, 0.26 * k, 0.13 * k),
    ]


def tube_body():
    r = (TUBE_R - TUBE_L) / 2
    return (
        f"M{num(TUBE_L)},{num(TUBE_TOP)}V{num(TUBE_BOTTOM - r)}"
        f"A{num(r)},{num(r)} 0,0 0,{num(TUBE_R)},{num(TUBE_BOTTOM - r)}V{num(TUBE_TOP)}z"
    )


def lid_group():
    x, y, w, h = LID_BOX
    return {"rotate": (LID_ROTATION, x + w / 2, y + h / 2), "paths": [
        {"d": rounded_rect(x, y, w, h, 3.0), "fill": (LID, 1), "stroke": INK, "width": 3.0},
        {"d": rounded_rect(x + 4, y + 1.6, w - 12, 1.6, 0.8), "fill": ("FFFFFF", 0.5)},
    ]}


def pop_ticks():
    """Three short strokes radiating from the lifted end of the lid."""
    cx, cy = 75.0, 27.0
    ticks = []
    for a in (-50, -10, 30):
        r = math.radians(a)
        x0, y0 = cx + 2.5 * math.cos(r), cy + 2.5 * math.sin(r)
        x1, y1 = cx + 6.5 * math.cos(r), cy + 6.5 * math.sin(r)
        ticks.append(f"M{num(x0)},{num(y0)}L{num(x1)},{num(y1)}")
    return {"d": "".join(ticks), "stroke": INK, "width": 2.4, "cap": "round"}


def color_layers():
    """Foreground layers, drawn in order."""
    layers = [{"d": "M38,91a16,3.2 0,1 0,32,0a16,3.2 0,1 0,-32,0z", "fill": SHADOW}]
    layers.append({"d": tube_body(), "fill": GLASS})
    for y in HEART_YS:
        k = HEART_K
        layers.append({"d": heart(54, y + 0.06 * k, k), "fill": (HEART_SHADE, 1)})
        layers.append({"d": heart(54, y, 0.88 * k), "fill": (HEART, 1)})
        layers.append({"d": circle(54 - 0.42 * k, y - 0.45 * k, 0.15 * k), "fill": (HEART_TINT, 1)})
        layers.append({"d": "".join(face(54, y, k)), "fill": (INK, 1)})
    layers.append({"d": rounded_rect(TUBE_L + 3, TUBE_TOP + 3, 2.6, 30, 1.3), "fill": SHINE})
    layers.append({"d": tube_body(), "stroke": INK, "width": 3.5})
    layers.append({"d": rounded_rect(*RIM, 2.5), "fill": ("FFFFFF", 0.85), "stroke": INK, "width": 3.0})
    layers.append(lid_group())
    layers.append(pop_ticks())
    return layers


def mono_layers():
    """Themed-icon layer: alpha only, so faces are holes cut into solid hearts."""
    white = ("FFFFFF", 1)
    layers = [{"d": tube_body(), "stroke": "FFFFFF", "width": 3.5}]
    for y in HEART_YS:
        k = HEART_K * 0.94
        layers.append({"d": heart(54, y, k) + "".join(face(54, y, k)), "fill": white, "evenodd": True})
    layers.append({"d": rounded_rect(*RIM, 2.5), "fill": white})
    lid = lid_group()
    lid["paths"] = [{"d": p["d"], "fill": white} for p in lid["paths"]]
    layers.append(lid)
    tick = pop_ticks()
    tick["stroke"] = "FFFFFF"
    layers.append(tick)
    return layers


# ---- Android vector drawables ----

def argb(color, alpha):
    return f"#{round(alpha * 255):02X}{color}"


def vd_path(p, indent):
    attrs = [f'android:pathData="{p["d"]}"']
    if "fill" in p:
        attrs.append(f'android:fillColor="{argb(*p["fill"])}"')
    if p.get("evenodd"):
        attrs.append('android:fillType="evenOdd"')
    if "stroke" in p:
        attrs.append(f'android:strokeColor="#FF{p["stroke"]}"')
        attrs.append(f'android:strokeWidth="{num(p["width"])}"')
        attrs.append('android:strokeLineJoin="round"')
        attrs.append(f'android:strokeLineCap="{p.get("cap", "butt")}"')
    pad = " " * indent
    return f"{pad}<path\n" + "".join(f"{pad}    {a}\n" for a in attrs[:-1]) + f"{pad}    {attrs[-1]} />\n"


def vector_drawable(layers, comment):
    out = '<?xml version="1.0" encoding="utf-8"?>\n'
    out += f"<!-- {comment} Generated by tools/gen_icon.py; edit that, not this file. -->\n"
    out += '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
    out += '    android:width="108dp"\n    android:height="108dp"\n'
    out += '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
    out += f'    <group\n        android:pivotX="{num(PIVOT[0])}"\n        android:pivotY="{num(PIVOT[1])}"\n'
    out += f'        android:scaleX="{num(SCALE)}"\n        android:scaleY="{num(SCALE)}"\n'
    out += f'        android:translateY="{num(SHIFT_Y)}">\n'
    for layer in layers:
        if "rotate" in layer:
            angle, px, py = layer["rotate"]
            out += f'        <group\n            android:pivotX="{num(px)}"\n            android:pivotY="{num(py)}"\n'
            out += f'            android:rotation="{num(angle)}">\n'
            out += "".join(vd_path(p, 12) for p in layer["paths"])
            out += "        </group>\n"
        else:
            out += vd_path(layer, 8)
    return out + "    </group>\n</vector>\n"


def background_drawable():
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Launcher icon background: the World 1 peach gradient. Generated by tools/gen_icon.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:endX="0"
                android:endY="108"
                android:startX="0"
                android:startY="0"
                android:type="linear">
                <item android:color="#FF{BG_TOP}" android:offset="0" />
                <item android:color="#FF{BG_BOTTOM}" android:offset="1" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


# ---- SVG ----

def svg_path(p, color_override=None):
    attrs = [f'd="{p["d"]}"']
    if "fill" in p:
        c, a = p["fill"]
        attrs.append(f'fill="#{color_override or c}"')
        if a < 1:
            attrs.append(f'fill-opacity="{num(a)}"')
        if p.get("evenodd"):
            attrs.append('fill-rule="evenodd"')
    else:
        attrs.append('fill="none"')
    if "stroke" in p:
        attrs.append(f'stroke="#{color_override or p["stroke"]}" stroke-width="{num(p["width"])}"')
        attrs.append(f'stroke-linejoin="round" stroke-linecap="{p.get("cap", "butt")}"')
    return f"<path {' '.join(attrs)}/>"


def svg_layers(layers, color_override=None):
    out = []
    for layer in layers:
        if "rotate" in layer:
            angle, px, py = layer["rotate"]
            inner = "".join(svg_path(p, color_override) for p in layer["paths"])
            out.append(f'<g transform="rotate({num(angle)} {num(px)} {num(py)})">{inner}</g>')
        else:
            out.append(svg_path(layer, color_override))
    px, py = PIVOT
    transform = f"translate(0 {num(SHIFT_Y)}) translate({num(px)} {num(py)}) scale({num(SCALE)}) translate({num(-px)} {num(-py)})"
    return f'<g transform="{transform}">\n' + "\n".join(out) + "\n</g>"


SVG_GRADIENT = (
    f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="108" gradientUnits="userSpaceOnUse">'
    f'<stop offset="0" stop-color="#{BG_TOP}"/><stop offset="1" stop-color="#{BG_BOTTOM}"/></linearGradient>'
)


def master_svg():
    """Full-bleed store icon: the 72-unit area a launcher shows. Play applies its own corner mask."""
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">\n'
        f"<defs>{SVG_GRADIENT}</defs>\n"
        '<rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>\n'
        f"{svg_layers(color_layers())}\n</svg>\n"
    )


def squircle(cx, cy, r, n=4.0, steps=72):
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        c, s = math.cos(t), math.sin(t)
        x = cx + r * math.copysign(abs(c) ** (2 / n), c)
        y = cy + r * math.copysign(abs(s) ** (2 / n), s)
        pts.append(f"{num(x)},{num(y)}")
    return "M" + "L".join(pts) + "z"


def preview_svg():
    """Masks at several sizes, the full 108 canvas with the safe zone, and themed icons."""
    sizes = (192, 96, 48)
    gap = 24
    defs = [SVG_GRADIENT]
    defs.append(
        '<symbol id="color" viewBox="18 18 72 72"><rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>'
        f"{svg_layers(color_layers())}</symbol>"
    )
    defs.append(
        '<symbol id="full" viewBox="0 0 108 108"><rect x="0" y="0" width="108" height="108" fill="url(#bg)"/>'
        f"{svg_layers(color_layers())}"
        '<circle cx="54" cy="54" r="33" fill="none" stroke="#2266FF" stroke-width="0.5" stroke-dasharray="2 2"/>'
        '<rect x="18" y="18" width="72" height="72" fill="none" stroke="#FF2266" stroke-width="0.5" stroke-dasharray="2 2"/>'
        "</symbol>"
    )
    for name, fg in (("mono-light", "4A4458"), ("mono-dark", "D0BCFF")):
        defs.append(f'<symbol id="{name}" viewBox="18 18 72 72">{svg_layers(mono_layers(), fg)}</symbol>')

    body = []
    clip_id = 0

    def tile(symbol, x, y, size, mask, bg=None):
        nonlocal clip_id
        clip_id += 1
        h = size / 2
        if mask == "circle":
            shape = f'<circle cx="{x + h}" cy="{y + h}" r="{h}"/>'
        elif mask == "squircle":
            shape = f'<path d="{squircle(x + h, y + h, h)}"/>'
        else:
            shape = f'<rect x="{x}" y="{y}" width="{size}" height="{size}" rx="{num(size * 0.2)}"/>'
        defs.append(f'<clipPath id="c{clip_id}">{shape}</clipPath>')
        fill = f'<rect x="{x}" y="{y}" width="{size}" height="{size}" fill="#{bg}"/>' if bg else ""
        body.append(
            f'<g clip-path="url(#c{clip_id})">{fill}'
            f'<use href="#{symbol}" x="{x}" y="{y}" width="{size}" height="{size}"/></g>'
        )

    y = gap
    for mask in ("circle", "squircle", "rounded"):
        x = gap
        for size in sizes:
            tile("color", x, y + (192 - size) // 2, size, mask)
            x += size + gap
        y += 192 + gap
    x = gap
    for symbol, bg in (("mono-light", "EADDFF"), ("mono-dark", "2B2930")):
        tile(symbol, x, y, 192, "circle", bg)
        tile(symbol, x + 192 + gap, y + 48, 96, "circle", bg)
        x += 192 + 96 + 2 * gap
    y += 192 + gap
    body.append(f'<use href="#full" x="{gap}" y="{y}" width="324" height="324"/>')
    y += 324 + gap

    width = 2 * (192 + 96 + 2 * gap) + gap
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{y}" viewBox="0 0 {width} {y}">\n'
        f'<defs>{"".join(defs)}</defs>\n<rect width="{width}" height="{y}" fill="#F4F4F6"/>\n'
        + "\n".join(body)
        + "\n</svg>\n"
    )


def render(svg, png):
    subprocess.run(["rsvg-convert", "-o", str(png), str(svg)], check=True)


def main():
    RES.mkdir(parents=True, exist_ok=True)
    STORE.mkdir(exist_ok=True)
    (RES / "ic_launcher_foreground.xml").write_text(
        vector_drawable(color_layers(), "Launcher icon: a glass tube of hearts with its lid popping off.")
    )
    (RES / "ic_launcher_background.xml").write_text(background_drawable())
    (RES / "ic_launcher_monochrome.xml").write_text(
        vector_drawable(mono_layers(), "Themed (monochrome) launcher icon; only alpha is used.")
    )
    (STORE / "icon.svg").write_text(master_svg())
    (STORE / "icon-preview.svg").write_text(preview_svg())
    render(STORE / "icon.svg", STORE / "icon-512.png")
    render(STORE / "icon-preview.svg", STORE / "icon-preview.png")
    print(f"Wrote launcher drawables to {RES.relative_to(ROOT)} and store icons to {STORE.relative_to(ROOT)}")


if __name__ == "__main__":
    sys.exit(main())

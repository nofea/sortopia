#!/usr/bin/env python3
"""Generates Sortopia's in-game art: themed items, tube dressing, overlays, particles and
world backgrounds (SPEC §6, §13).

Everything is hand-written SVG rendered by rsvg-convert, in the same cozy flat style as the app
icon (tools/gen_icon.py): thick ink outlines, soft shading, cute faces.

Items are split into layers so the game can recolour them (SPEC §14):
- ``under``: the ink outline plus fixed-colour parts behind the body (sticks, wrappers, cones)
- ``body``: white with grey shading; the game tints it with the item's colour
- ``over``: fixed-colour details, inner lines and highlights on top
One shared face sprite (ink, plus a light variant for dark colours) is placed per item at the
anchor stored in the index, so the game can hide faces on small items.

Outputs (committed):
- ``assets/art/sheet<N>.png``: 16×16 grids of 128 px cells
- ``assets/art/atlas.properties``: ``<name>=<sheet>,<col>,<row>`` and ``face.<item>=<x>,<y>,<scale>``
- ``assets/art/bg/<theme>-top.png`` and ``-bottom.png``: background bands, transparent

Deterministic: running it again produces identical files. Usage (inside devenv):
    python3 tools/gen_art.py [--svg <dir>]   # --svg also keeps the SVG sources for inspection
"""
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "assets/art"

INK = "3B3355"
WHITE = "FFFFFF"
SHADE = "CFCFCF"
CELL = 100  # SVG units per cell
CELL_PX = 128
GRID = 16
OUTLINE = 7.0  # under-stroke width; half of it shows outside the body
LINE = 3.0  # inner detail lines

# ---------------------------------------------------------------------------------------------
# Small SVG helpers. Shapes are element strings without paint; paint() adds fill and stroke.


def n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def C(cx, cy, r):
    return f'<circle cx="{n(cx)}" cy="{n(cy)}" r="{n(r)}"/>'


def E(cx, cy, rx, ry, rot=0):
    t = f' transform="rotate({n(rot)} {n(cx)} {n(cy)})"' if rot else ""
    return f'<ellipse cx="{n(cx)}" cy="{n(cy)}" rx="{n(rx)}" ry="{n(ry)}"{t}/>'


def R(x, y, w, h, r=0, rot=0):
    t = f' transform="rotate({n(rot)} {n(x + w / 2)} {n(y + h / 2)})"' if rot else ""
    return f'<rect x="{n(x)}" y="{n(y)}" width="{n(w)}" height="{n(h)}" rx="{n(r)}"{t}/>'


def PG(*pts):
    return '<polygon points="' + " ".join(f"{n(x)},{n(y)}" for x, y in pts) + '"/>'


def P(d, evenodd=False):
    rule = ' fill-rule="evenodd"' if evenodd else ""
    return f'<path d="{d}"{rule}/>'


def S(d, width):
    """A stroked centre line used as part of a silhouette (sticks, tentacles, canes)."""
    return ("stroke", d, width)


def paint(shape, fill=None, stroke=None, width=LINE, opacity=None, cap="round", dash=None):
    attrs = f' fill="#{fill}"' if fill else ' fill="none"'
    if stroke:
        attrs += f' stroke="#{stroke}" stroke-width="{n(width)}" stroke-linejoin="round" stroke-linecap="{cap}"'
    if dash:
        attrs += f' stroke-dasharray="{dash}"'
    if opacity is not None:
        attrs += f' opacity="{n(opacity)}"'
    return shape.replace("/>", attrs + "/>", 1)


def line(d, color=INK, width=LINE, opacity=None, dash=None):
    return paint(f'<path d="{d}"/>', stroke=color, width=width, opacity=opacity, dash=dash)


def fixed(shape, color, outline=True, width=LINE):
    """A fixed-colour part with its own ink outline."""
    return paint(shape, fill=color, stroke=INK if outline else None, width=width)


def polar(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy + r * math.sin(a)


def star_pts(cx, cy, outer, inner, points, rot=-90):
    pts = []
    for i in range(points * 2):
        r = outer if i % 2 == 0 else inner
        pts.append(polar(cx, cy, r, rot + 180 * i / points))
    return pts


def wavy_circle(cx, cy, r, bump, count):
    pts = []
    for i in range(count * 4):
        a = 360 * i / (count * 4)
        rr = r + bump * math.sin(math.radians(a * count))
        pts.append(polar(cx, cy, rr, a))
    return PG(*pts)


HEART_CURVES = [
    ((-0.25, -1.0), (-1.05, -0.85), (-1.0, -0.2)),
    ((-0.95, 0.3), (-0.35, 0.6), (0.0, 1.0)),
    ((0.35, 0.6), (0.95, 0.3), (1.0, -0.2)),
    ((1.05, -0.85), (0.25, -1.0), (0.0, -0.45)),
]


def heart_d(cx, cy, k):
    d = f"M{n(cx)},{n(cy - 0.45 * k)}"
    for (ax, ay), (bx, by), (ex, ey) in HEART_CURVES:
        d += f"C{n(cx + ax * k)},{n(cy + ay * k)} {n(cx + bx * k)},{n(cy + by * k)} {n(cx + ex * k)},{n(cy + ey * k)}"
    return d + "Z"


def shine(cx, cy, rx, ry, rot=-30, opacity=0.6):
    return paint(E(cx, cy, rx, ry, rot), fill=WHITE, opacity=opacity)


# ---------------------------------------------------------------------------------------------
# Items. Each returns a dict: body (silhouette shapes), under (fixed parts behind the body, also
# outlined), over (fixed parts and details on top), clip (details clipped to the body), face
# (x, y, scale) in cell units.


def item(body, under=(), over=(), clip=(), face=(50, 54, 1.0), outline_under=True):
    return {"body": list(body), "under": list(under), "over": list(over), "clip": list(clip), "face": face,
            "outline_under": outline_under}


BROWN = "8D5A3B"
GREEN = "6BBF59"
LEAF = "7CC46A"
WOOD = "C98D55"
CREAM = "FFF4DC"
TAN = "E2A85A"
PAPER = "FFFDF8"
GOLD = "F2C14E"
RED = "E53935"
YELLOW = "FDD835"
GLASS_BLUE = "D8EEF8"


def stem_leaf(x, y, leaf_dir=1):
    return [
        line(f"M{n(x)},{n(y)} Q{n(x - 2)},{n(y - 9)} {n(x + 3)},{n(y - 15)}", color=BROWN, width=5),
        fixed(E(x + 11 * leaf_dir, y - 9, 10, 5, -25 * leaf_dir), LEAF),
    ]


def fruit_items():
    return {
        "apple": item(
            [P("M50,30 C35,18 10,26 12,52 C14,80 34,94 50,86 C66,94 86,80 88,52 C90,26 65,18 50,30Z")],
            over=stem_leaf(50, 30) + [shine(30, 46, 7, 11)],
            face=(50, 60, 1.0),
        ),
        "banana": item(
            [P("M14,34 C16,72 52,92 86,70 C90,66 88,58 82,58 C56,64 36,52 30,30 C27,22 13,24 14,34Z")],
            over=[fixed(R(13, 22, 9, 10, 3, -20), BROWN), fixed(C(85, 64, 3.5), BROWN, outline=False),
                  line("M24,40 C30,58 48,72 72,70", color=WHITE, width=3, opacity=0.6)],
            face=(54, 66, 0.75),
        ),
        "grapes": item(
            [C(36, 40, 13), C(50, 40, 13), C(64, 40, 13), C(43, 56, 13), C(57, 56, 13), C(50, 71, 13), C(29, 55, 9),
             C(71, 55, 9)],
            over=[paint(C(43, 56, 13), stroke=INK, width=2, opacity=0.3),
                  paint(C(57, 56, 13), stroke=INK, width=2, opacity=0.3),
                  paint(C(50, 40, 13), stroke=INK, width=2, opacity=0.3)]
            + stem_leaf(50, 27) + [shine(31, 36, 3, 5)],
            face=(50, 55, 0.85),
        ),
        "orange": item(
            [C(50, 56, 34)],
            over=[fixed(E(60, 20, 9, 5, -20), LEAF), fixed(C(50, 23, 3.5), BROWN, outline=False),
                  paint(C(70, 64, 1.6), fill=INK, opacity=0.2), paint(C(28, 62, 1.6), fill=INK, opacity=0.2),
                  paint(C(64, 80, 1.6), fill=INK, opacity=0.2), shine(32, 42, 6, 10)],
            face=(50, 58, 1.0),
        ),
        "pear": item(
            [P("M50,18 C40,18 38,34 35,42 C22,52 18,68 25,79 C33,93 67,93 75,79 C82,68 78,52 65,42 C62,34 60,18 50,18Z")],
            over=stem_leaf(50, 18) + [shine(36, 58, 5, 9)],
            face=(50, 67, 0.9),
        ),
        "blueberries": item(
            [C(36, 62, 20), C(64, 62, 20), C(50, 38, 20)],
            over=[paint(C(36, 62, 20), stroke=INK, width=2, opacity=0.3), paint(C(64, 62, 20), stroke=INK, width=2, opacity=0.3)]
            + [paint(PG(*star_pts(x, y, 6, 2.5, 5)), fill=INK, opacity=0.45) for x, y in ((50, 26), (24, 56), (76, 56))]
            + [shine(43, 32, 3, 5), shine(28, 58, 3, 5)],
            face=(50, 64, 0.8),
        ),
    }


def candy_items():
    return {
        "lollipop": item(
            [C(50, 40, 29)],
            under=[fixed(R(46, 60, 8, 32, 4), PAPER)],
            over=[line("M50,40 m-4,0 a4,4 0 1,1 8,0 a9,9 0 1,1 -17,0 a14,14 0 1,1 27,0 a19,19 0 1,1 -36,0", color=WHITE,
                       width=4.5, opacity=0.85), shine(36, 26, 4, 7)],
            face=(50, 44, 0.75),
        ),
        "wrapped": item(
            [E(50, 52, 24, 20), PG((30, 52), (8, 34), (14, 52), (8, 70)), PG((70, 52), (92, 34), (86, 52), (92, 70))],
            over=[line("M27,44 L33,52 L27,60", width=2.5, opacity=0.5), line("M73,44 L67,52 L73,60", width=2.5, opacity=0.5),
                  shine(40, 42, 5, 7)],
            face=(50, 54, 0.8),
        ),
        "gumdrop": item(
            [P("M20,80 C20,42 33,24 50,24 C67,24 80,42 80,80 Q50,88 20,80Z")],
            over=[paint(R(x, y, 4, 4, 1, 20), fill=WHITE, opacity=0.8) for x, y in ((34, 36), (60, 34), (44, 30), (70, 54),
                                                                                    (28, 58), (66, 72), (32, 74))]
            + [shine(36, 44, 5, 9)],
            face=(50, 60, 0.95),
        ),
        "cane": item(
            [S("M60,88 L60,36 A15,15 0 0,0 30,36 L30,44", 20)],
            over=[line("M60,88 L60,36 A15,15 0 0,0 30,36 L30,44", color=WHITE, width=16, dash="6 9")],
            face=(60, 66, 0.55),
        ),
        "chocolate": item(
            [R(26, 16, 48, 70, 6)],
            over=[line("M50,18 L50,56 M28,30 L72,30 M28,44 L72,44", width=2.5, opacity=0.35),
                  fixed(P("M24,58 L76,54 L76,84 Q76,88 72,88 L28,88 Q24,88 24,84Z"), "C9CFD8"),
                  line("M30,64 L70,61", color=WHITE, width=3, opacity=0.8)],
            face=(50, 37, 0.65),
        ),
        "jellybean": item(
            [P("M22,56 C20,34 42,26 55,35 C61,40 67,38 74,35 C86,31 92,48 86,63 C78,82 26,84 22,56Z")],
            over=[shine(36, 44, 5, 8, -60)],
            face=(55, 60, 0.85),
        ),
        "rockcandy": item(
            [PG((32, 32), (50, 22), (68, 30), (76, 52), (68, 78), (50, 88), (32, 78), (24, 54))],
            under=[fixed(R(46, 6, 8, 24, 3), WOOD)],
            over=[line("M32,32 L44,50 L68,30 M44,50 L50,88 M44,50 L24,54 M60,62 L76,52 M60,62 L68,78", color=WHITE, width=2.5,
                       opacity=0.6)],
            face=(50, 60, 0.8),
        ),
    }


def toy_items():
    return {
        "teddy": item(
            [C(28, 30, 12), C(72, 30, 12), C(50, 56, 30)],
            over=[paint(C(28, 30, 6), fill=WHITE, opacity=0.4), paint(C(72, 30, 6), fill=WHITE, opacity=0.4),
                  paint(E(50, 68, 13, 10), fill=WHITE, opacity=0.6), paint(E(50, 63, 4.5, 3.2), fill=INK)],
            face=(50, 52, 0.8),
        ),
        "duck": item(
            [E(54, 66, 32, 20), C(40, 38, 17), PG((80, 60), (94, 46), (88, 66))],
            over=[fixed(P("M22,38 Q8,40 12,46 Q18,48 24,45Z"), "FF9F43"),
                  line("M50,64 Q62,72 74,62", width=2.5, opacity=0.5), shine(34, 30, 4, 6)],
            face=(42, 38, 0.6),
        ),
        "block": item(
            [R(18, 20, 64, 64, 9)],
            over=[paint(R(28, 30, 44, 44, 6), stroke=WHITE, width=4, opacity=0.6),
                  paint(PG(*star_pts(50, 52, 11, 5, 5)), fill=WHITE, opacity=0.45)],
            face=(50, 55, 0.95),
        ),
        "ball": item(
            [C(50, 52, 34)],
            over=[paint(P("M50,18 C38,32 38,72 50,86 C30,82 18,68 16,52 C18,36 30,22 50,18Z"), fill=WHITE),
                  paint(P("M50,18 C62,32 62,72 50,86 C70,82 82,68 84,52 C82,36 70,22 50,18Z"), fill=YELLOW, opacity=0.9),
                  line("M50,18 C38,32 38,72 50,86 M50,18 C62,32 62,72 50,86", width=2.5),
                  fixed(C(50, 20, 4), WHITE)],
            face=(50, 56, 0.6),
        ),
        "car": item(
            [P("M12,66 L12,54 Q12,48 20,47 L30,46 L38,30 Q40,27 44,27 L62,27 Q66,27 68,30 L76,46 L84,48 Q90,50 90,58 L90,66Z")],
            over=[fixed(P("M41,44 L46,32 L56,32 L56,44Z"), GLASS_BLUE), fixed(P("M60,44 L60,32 L64,32 L70,44Z"), GLASS_BLUE),
                  fixed(C(30, 68, 10), "4A4458"), fixed(C(72, 68, 10), "4A4458"), paint(C(30, 68, 4), fill="C9CFD8"),
                  paint(C(72, 68, 4), fill="C9CFD8")],
            face=(50, 56, 0.55),
        ),
        "top": item(
            [P("M50,88 L20,54 C20,40 35,34 50,34 C65,34 80,40 80,54Z")],
            under=[fixed(R(45, 14, 10, 24, 4), WOOD)],
            over=[line("M22,54 Q50,64 78,54", color=WHITE, width=5, opacity=0.7), shine(34, 44, 4, 6)],
            face=(50, 52, 0.7),
        ),
        "robot": item(
            [R(22, 30, 56, 48, 10), R(12, 44, 12, 20, 4), R(76, 44, 12, 20, 4)],
            under=[line("M50,30 L50,16", width=4), fixed(C(50, 13, 6), "FF9F43")],
            over=[paint(R(30, 38, 40, 30, 8), fill=WHITE, opacity=0.65), paint(R(38, 71, 24, 4, 2), fill=INK, opacity=0.3)],
            face=(50, 53, 0.75),
        ),
        "kite": item(
            [PG((50, 10), (80, 44), (50, 76), (20, 44))],
            under=[line("M50,76 Q42,84 50,90 Q58,96 52,98", width=2.5),
                   fixed(PG((44, 84), (40, 80), (40, 88)), RED, width=2), fixed(PG((56, 92), (60, 88), (60, 96)), RED, width=2)],
            over=[line("M50,12 L50,74 M22,44 L78,44", width=2.5, opacity=0.4), shine(40, 30, 3, 7, 40)],
            face=(50, 46, 0.75),
        ),
    }


def post_items():
    return {
        "envelope": item(
            [R(14, 28, 72, 48, 6)],
            over=[line("M16,31 L50,56 L84,31", width=3), line("M16,73 L40,52 M84,73 L60,52", width=2.5, opacity=0.35)],
            face=(50, 66, 0.55),
        ),
        "parcel": item(
            [R(20, 26, 60, 54, 5)],
            over=[fixed(R(20, 34, 60, 10), "EAD8A6", outline=False), line("M20,34 L80,34 M20,44 L80,44", width=2, opacity=0.4),
                  line("M50,26 L50,80", color="B9824E", width=3, opacity=0.8), fixed(P("M44,26 Q50,18 56,26"), None)],
            face=(50, 62, 0.8),
        ),
        "stamp": item(
            [PG(*[pt for i in range(40) for pt in [_stamp_pt(i)]])],
            over=[paint(R(31, 31, 38, 38, 3), stroke=WHITE, width=3, opacity=0.8),
                  paint(P(heart_d(50, 44, 6)), fill=WHITE, opacity=0.7)],
            face=(50, 56, 0.6),
        ),
        "postcard": item(
            [R(10, 26, 80, 52, 4)],
            over=[fixed(R(66, 32, 17, 20, 2), WHITE, width=2.5), paint(PG(*star_pts(74.5, 42, 5, 2.4, 5)), fill=RED),
                  line("M56,32 L56,72", width=2, opacity=0.4),
                  line("M60,58 L84,58 M60,65 L84,65", width=2, opacity=0.4)],
            face=(32, 54, 0.75),
        ),
        "loveletter": item(
            [P(heart_d(50, 52, 38))],
            over=[line("M22,36 L50,58 L78,36", color=WHITE, width=3, opacity=0.6), shine(28, 34, 4, 7)],
            face=(50, 56, 0.8),
        ),
        "poster": item(
            [R(12, 38, 76, 28, 14)],
            over=[fixed(E(80, 52, 6, 12), WHITE, width=2.5), paint(E(80, 52, 2.5, 6), stroke=INK, width=1.5, opacity=0.5),
                  fixed(R(26, 36, 8, 32, 2), RED, width=2.5)],
            face=(54, 53, 0.6),
        ),
        "mailer": item(
            [PG(*_zigzag_top(18, 82, 24, 82))],
            over=[paint(C(x, y, 3), fill=WHITE, opacity=0.35) for x in (28, 40, 52, 64, 74) for y in (44, 58, 72)]
            + [line("M18,34 L82,34", width=2, opacity=0.35, dash="4 4")],
            face=(50, 60, 0.85),
        ),
        "waxseal": item(
            [wavy_circle(50, 52, 31, 3, 9)],
            over=[paint(C(50, 52, 21), stroke=INK, width=2.5, opacity=0.3), paint(C(50, 52, 21), stroke=WHITE, width=1.5,
                                                                                    opacity=0.4),
                  shine(36, 38, 4, 7)],
            face=(50, 54, 0.8),
        ),
    }


def _stamp_pt(i):
    """Perforated stamp edge: 10 teeth per side of a 56-unit square."""
    side, k = divmod(i, 10)
    t = k / 10
    inset = 0 if k % 2 == 0 else 3.5
    x0, y0, s = 22, 24, 56
    if side == 0:
        return x0 + s * t, y0 + inset
    if side == 1:
        return x0 + s - inset, y0 + s * t
    if side == 2:
        return x0 + s - s * t, y0 + s - inset
    return x0 + inset, y0 + s - s * t


def _zigzag_top(x0, x1, top, bottom):
    pts = [(x0, bottom - 4), (x0, top + 4)]
    steps = 8
    for i in range(1, steps):
        pts.append((x0 + (x1 - x0) * i / steps, top + (0 if i % 2 else 6)))
    pts += [(x1, top + 4), (x1, bottom - 4), (x1 - 4, bottom), (x0 + 4, bottom)]
    return pts


def bakery_items():
    return {
        "croissant": item(
            [E(50, 50, 16, 24), E(32, 56, 13, 18, 35), E(68, 56, 13, 18, -35), E(18, 66, 9, 12, 55), E(82, 66, 9, 12, -55)],
            over=[paint(E(32, 56, 13, 18, 35), stroke=INK, width=2, opacity=0.35),
                  paint(E(68, 56, 13, 18, -35), stroke=INK, width=2, opacity=0.35), shine(44, 38, 3, 6)],
            face=(50, 52, 0.65),
        ),
        "donut": item(
            [P("M50,22 C70,22 82,36 80,52 C82,70 68,84 50,82 C32,84 18,70 20,52 C18,36 30,22 50,22Z "
               "M50,42 a10,10 0 1,0 0.01,0Z", evenodd=True)],
            under=[fixed(P("M50,18 a34,34 0 1,0 0.01,0Z M50,42 a10,10 0 1,0 0.01,0Z", evenodd=True), TAN)],
            over=[paint(R(x, y, 7, 2.6, 1.3, r), fill=c) for x, y, r, c in
                  ((30, 36, 30, "FFD54F"), (62, 32, -20, "64B5F6"), (70, 56, 60, WHITE), (28, 60, -50, "81C784"),
                   (44, 28, 10, WHITE), (58, 72, 20, "FFD54F"))],
            face=(50, 70, 0.5),
        ),
        "cupcake": item(
            [C(50, 34, 15), E(50, 50, 28, 13), C(30, 50, 11), C(70, 50, 11)],
            under=[fixed(PG((26, 58), (74, 58), (66, 90), (34, 90)), PAPER),
                   line("M38,60 L41,88 M50,60 L50,88 M62,60 L59,88", width=2, opacity=0.4)],
            over=[fixed(C(50, 18, 6), RED), line("M50,13 Q52,6 58,4", color=BROWN, width=2.5), shine(40, 30, 3, 5)],
            face=(50, 46, 0.6),
        ),
        "pretzel": item(
            [S("M28,74 C6,52 24,16 46,32 L62,62 M72,74 C94,52 76,16 54,32 L38,62 M28,74 Q50,86 72,74", 12)],
            over=[paint(R(x, y, 3.5, 2.5, 1, r), fill=WHITE) for x, y, r in ((22, 40, 20), (34, 24, -30), (72, 40, -20),
                                                                              (62, 24, 30), (48, 80, 0), (80, 60, 40))],
            face=(50, 48, 0.5),
        ),
        "pie": item(
            [P("M50,18 L84,74 Q86,80 80,80 L20,80 Q14,80 16,74Z")],
            under=[],
            over=[fixed(R(14, 70, 72, 14, 7), TAN), line("M38,40 L62,40 M30,54 L70,54", color=TAN, width=5),
                  line("M44,30 L36,68 M56,30 L64,68", color=TAN, width=5), shine(44, 30, 2.5, 5)],
            face=(50, 58, 0.55),
        ),
        "muffin": item(
            [E(50, 42, 34, 20), C(34, 34, 12), C(54, 28, 14), C(70, 38, 10)],
            under=[fixed(PG((24, 52), (76, 52), (70, 88), (30, 88)), "F7E7C8"),
                   line("M36,54 L38,86 M50,54 L50,86 M64,54 L62,86", width=2, opacity=0.4)],
            over=[paint(C(x, y, 3), fill=INK, opacity=0.3) for x, y in ((36, 40), (60, 34), (66, 48), (46, 26), (28, 48))]
            + [shine(36, 30, 4, 6)],
            face=(50, 44, 0.6),
        ),
        "macaron": item(
            [E(50, 36, 32, 15), E(50, 68, 32, 15)],
            under=[fixed(R(22, 46, 56, 12, 6), CREAM)],
            over=[line("M20,42 Q50,50 80,42 M20,62 Q50,54 80,62", width=2, opacity=0.35), shine(36, 30, 6, 3, 0)],
            face=(50, 36, 0.6),
        ),
        "roll": item(
            [E(50, 56, 37, 25)],
            over=[line("M30,44 Q36,52 34,62 M48,40 Q54,48 52,62 M66,44 Q72,52 70,62", color=WHITE, width=4, opacity=0.55),
                  shine(28, 48, 4, 7)],
            face=(50, 62, 0.75),
        ),
        "tart": item(
            [C(50, 54, 26)],
            under=[fixed(wavy_circle(50, 54, 34, 2.5, 12), TAN)],
            over=[fixed(P("M60,38 a10,10 0 0,1 -12,12 Z"), "FFF59D", width=2), shine(38, 44, 4, 6)],
            face=(50, 58, 0.75),
        ),
    }


def aquarium_items():
    return {
        "clownfish": item(
            [E(44, 52, 30, 19), PG((68, 52), (90, 34), (90, 70))],
            over=[fixed(P("M36,34 Q42,52 36,70 L44,71 Q50,52 44,33Z"), WHITE, width=2.5),
                  fixed(P("M58,36 Q63,52 58,68 L64,66 Q68,52 64,38Z"), WHITE, width=2.5),
                  line("M70,52 L88,52", width=2, opacity=0.3)],
            face=(26, 50, 0.55),
        ),
        "puffer": item(
            [PG(*star_pts(50, 54, 36, 29, 14))],
            over=[paint(E(78, 56, 7, 5, -20), fill=WHITE, opacity=0.5), paint(E(50, 70, 16, 8), fill=WHITE, opacity=0.4)],
            face=(46, 52, 0.85),
        ),
        "octopus": item(
            [E(50, 38, 27, 25), S("M30,54 Q22,70 30,82", 9), S("M42,58 Q38,76 44,88", 9), S("M58,58 Q62,76 56,88", 9),
             S("M70,54 Q78,70 70,82", 9)],
            over=[paint(C(x, y, 2.5), fill=WHITE, opacity=0.5) for x, y in ((30, 76), (42, 80), (58, 80), (70, 76))]
            + [shine(38, 24, 4, 7)],
            face=(50, 42, 0.8),
        ),
        "crab": item(
            [E(50, 60, 28, 18), C(16, 36, 10), C(84, 36, 10), S("M26,54 Q18,48 18,40", 6), S("M74,54 Q82,48 82,40", 6),
             S("M26,68 L14,78", 5), S("M74,68 L86,78", 5), S("M32,74 L24,86", 5), S("M68,74 L76,86", 5)],
            over=[paint(PG((16, 30), (22, 36), (16, 40)), fill=WHITE, opacity=0.8), paint(PG((84, 30), (78, 36), (84, 40)),
                                                                                         fill=WHITE, opacity=0.8),
                  shine(38, 52, 4, 6)],
            face=(50, 60, 0.7),
        ),
        "jellyfish": item(
            [P("M18,54 C18,20 82,20 82,54 Q74,60 66,54 Q58,60 50,54 Q42,60 34,54 Q26,60 18,54Z"),
             S("M30,58 Q24,70 30,82", 4), S("M44,60 Q40,74 46,90", 4), S("M56,60 Q60,74 54,90", 4), S("M70,58 Q76,70 70,82", 4)],
            over=[paint(P("M26,44 C28,30 40,24 50,24"), stroke=WHITE, width=4, opacity=0.6)],
            face=(50, 42, 0.75),
        ),
        "turtle": item(
            [E(46, 54, 30, 22), C(82, 50, 11), E(26, 74, 7, 5), E(64, 76, 7, 5), PG((16, 54), (8, 50), (16, 60))],
            over=[line("M34,40 L46,48 L58,40 M46,48 L46,62 M30,62 L46,62 L62,62 M34,72 L36,62 M58,72 L56,62", width=2.5,
                       opacity=0.4), shine(34, 42, 4, 6)],
            face=(82, 48, 0.42),
        ),
        "tang": item(
            [P("M12,52 C24,22 58,20 72,50 C58,82 24,82 12,52Z"), PG((68, 50), (90, 32), (86, 52), (90, 72))],
            over=[fixed(PG((70, 50), (88, 36), (85, 52), (88, 68)), YELLOW, width=2.5),
                  paint(P("M30,40 C44,32 60,40 62,50 C54,48 46,56 40,66 C38,56 34,48 30,40Z"), fill=INK, opacity=0.55)],
            face=(24, 50, 0.5),
        ),
        "seahorse": item(
            [E(56, 24, 14, 11), R(62, 20, 22, 7, 3), S("M52,32 C70,44 68,62 52,66 C38,70 42,86 58,84", 16)],
            over=[line("M44,36 L38,40 M42,48 L36,52 M44,60 L38,62", width=2.5, opacity=0.45), shine(52, 18, 3, 5)],
            face=(54, 24, 0.45),
        ),
        "shell": item(
            [P("M50,86 L16,44 Q14,34 24,28 Q50,10 76,28 Q86,34 84,44Z")],
            under=[fixed(R(40, 80, 20, 10, 4), "F3D3B8")],
            over=[line("M50,84 L26,32 M50,84 L40,24 M50,84 L50,20 M50,84 L60,24 M50,84 L74,32", width=2.5, opacity=0.35)],
            face=(50, 50, 0.7),
        ),
        "angler": item(
            [C(48, 58, 28), PG((72, 58), (92, 42), (92, 74)), S("M40,32 C36,14 56,6 66,16", 4)],
            over=[fixed(C(66, 20, 6), "FFF176"), paint(C(66, 20, 10), fill="FFF176", opacity=0.3),
                  paint(PG((30, 70), (34, 64), (38, 70), (42, 64), (46, 70), (50, 64), (54, 70)), fill=WHITE)],
            face=(44, 54, 0.8),
        ),
    }


def icecream_items():
    waffle = "E2A85A"
    return {
        "cone": item(
            [C(50, 36, 23), C(32, 46, 9), C(68, 46, 9)],
            under=[fixed(PG((28, 48), (72, 48), (50, 94)), waffle),
                   line("M36,52 L54,86 M48,52 L60,72 M64,52 L46,86 M52,52 L40,72", width=2, opacity=0.4)],
            over=[shine(40, 26, 4, 7)],
            face=(50, 38, 0.7),
        ),
        "popsicle": item(
            [R(30, 12, 40, 62, 18)],
            under=[fixed(R(44, 66, 12, 26, 5), WOOD)],
            over=[line("M38,22 L38,62", color=WHITE, width=4, opacity=0.5)],
            face=(50, 44, 0.8),
        ),
        "softserve": item(
            [E(50, 62, 28, 9), E(50, 49, 22, 9), E(50, 37, 15, 8), PG((44, 30), (56, 30), (52, 16))],
            under=[fixed(PG((30, 64), (70, 64), (63, 92), (37, 92)), waffle)],
            over=[line("M24,62 Q50,70 76,62 M30,49 Q50,56 70,49", width=2, opacity=0.35)],
            face=(50, 50, 0.55),
        ),
        "sundae": item(
            [C(36, 44, 15), C(64, 44, 15), C(50, 32, 15)],
            under=[fixed(P("M18,52 L82,52 Q80,72 58,74 L56,84 L66,90 L34,90 L44,84 L42,74 Q20,72 18,52Z"), GLASS_BLUE)],
            over=[fixed(C(50, 15, 5.5), RED), line("M50,10 Q52,4 57,3", color=BROWN, width=2.5)],
            face=(50, 38, 0.55),
        ),
        "sandwich": item(
            [R(18, 38, 64, 26, 6)],
            over=[fixed(R(14, 24, 72, 15, 6), "6D4C41"), fixed(R(14, 62, 72, 15, 6), "6D4C41"),
                  paint(C(30, 31, 1.6), fill=INK, opacity=0.5), paint(C(50, 31, 1.6), fill=INK, opacity=0.5),
                  paint(C(70, 31, 1.6), fill=INK, opacity=0.5)],
            face=(50, 51, 0.5),
        ),
        "sorbet": item(
            [P("M24,52 C22,30 36,22 50,22 C64,22 78,30 76,52Z")],
            under=[fixed(PG((22, 52), (78, 52), (70, 88), (30, 88)), PAPER),
                   line("M34,54 L38,86 M50,54 L50,86 M66,54 L62,86", color=RED, width=3)],
            over=[fixed(R(64, 10, 6, 30, 3, 25), "BDBDBD"), shine(36, 34, 4, 6)],
            face=(48, 40, 0.6),
        ),
        "bombpop": item(
            [P("M50,8 L66,26 L64,36 L70,46 L68,58 L72,70 Q72,76 66,76 L34,76 Q28,76 28,70 L32,58 L30,46 L36,36 L34,26Z")],
            under=[fixed(R(44, 70, 12, 22, 5), WOOD)],
            over=[line("M33,36 L67,36 M31,58 L69,58", color=WHITE, width=3, opacity=0.7)],
            face=(50, 48, 0.65),
        ),
        "creamsicle": item(
            [R(14, 16, 72, 56, 18)],
            under=[fixed(R(30, 64, 10, 26, 4), WOOD), fixed(R(60, 64, 10, 26, 4), WOOD)],
            over=[paint(R(26, 26, 48, 36, 12), fill=WHITE, opacity=0.4)],
            face=(50, 44, 0.8),
        ),
        "mochi": item(
            [P("M16,66 C14,40 32,26 50,26 C68,26 86,40 84,66 C84,82 16,82 16,66Z")],
            over=[paint(C(x, y, 1.8), fill=WHITE, opacity=0.8) for x, y in ((30, 44), (64, 36), (72, 60), (26, 66), (48, 32))]
            + [shine(32, 46, 5, 8)],
            face=(50, 58, 0.9),
        ),
        "bowl": item(
            [P("M16,58 C14,34 34,26 50,28 C66,26 86,34 84,58Z")],
            under=[fixed(P("M10,56 L90,56 L80,84 Q50,92 20,84Z"), waffle),
                   line("M24,60 L28,82 M38,60 L40,86 M50,60 L50,88 M62,60 L60,86 M76,60 L72,82", width=2, opacity=0.4)],
            over=[shine(34, 40, 5, 7)],
            face=(50, 44, 0.6),
        ),
        "affogato": item(
            [R(18, 40, 52, 44, 12), S("M70,52 C86,52 86,72 70,72", 7)],
            over=[fixed(E(44, 40, 24, 9), CREAM), fixed(C(44, 32, 12), CREAM), shine(28, 56, 3, 7)],
            face=(44, 64, 0.6),
        ),
    }


def facets(*segments, opacity=0.5):
    return line(" ".join(segments), color=WHITE, width=2.5, opacity=opacity)


def gem_items():
    return {
        "ruby": item(
            [P(heart_d(50, 54, 36))],
            over=[facets("M50,38 L34,48 L50,84 L66,48 L50,38 M14,46 L34,48 M86,46 L66,48")],
            face=(50, 56, 0.75),
        ),
        "emerald": item(
            [PG((34, 12), (66, 12), (78, 24), (78, 78), (66, 90), (34, 90), (22, 78), (22, 24))],
            over=[facets("M38,24 L62,24 L66,30 L66,72 L62,78 L38,78 L34,72 L34,30Z M34,12 L38,24 M66,12 L62,24 M34,90 L38,78 "
                         "M66,90 L62,78")],
            face=(50, 52, 0.85),
        ),
        "sapphire": item(
            [E(50, 52, 27, 36)],
            over=[facets("M50,16 L36,40 L50,52 L64,40 Z M36,40 L36,64 L50,88 L64,64 L64,40 M36,64 L50,52 L64,64")],
            face=(50, 56, 0.8),
        ),
        "amethyst": item(
            [PG((40, 84), (40, 30), (50, 12), (60, 30), (60, 84)), PG((22, 84), (22, 50), (30, 38), (38, 50), (38, 84)),
             PG((62, 84), (62, 46), (71, 34), (80, 46), (80, 84))],
            under=[fixed(P("M12,84 Q14,76 24,78 L76,78 Q86,76 88,84 Q88,92 80,92 L20,92 Q12,92 12,84Z"), "9E9AA8")],
            over=[facets("M50,12 L50,84 M30,38 L30,84 M71,34 L71,84")],
            face=(50, 58, 0.6),
        ),
        "topaz": item(
            [P("M50,10 C62,30 82,46 82,64 C82,82 68,92 50,92 C32,92 18,82 18,64 C18,46 38,30 50,10Z")],
            over=[facets("M50,10 L38,56 L50,74 L62,56 Z M18,64 L38,56 M82,64 L62,56 M50,74 L50,92")],
            face=(50, 64, 0.8),
        ),
        "citrine": item(
            [R(18, 20, 64, 64, 18)],
            over=[facets("M34,36 L66,36 L66,68 L34,68 Z M18,38 L34,36 L36,20 M82,38 L66,36 L64,20 M18,66 L34,68 L36,84 "
                         "M82,66 L66,68 L64,84")],
            face=(50, 54, 0.85),
        ),
        "diamond": item(
            [PG((22, 38), (36, 22), (64, 22), (78, 38), (50, 86))],
            over=[facets("M22,38 L78,38 M36,22 L44,38 L50,22 L56,38 L64,22 M44,38 L50,86 L56,38 M30,38 L50,86 L70,38",
                         opacity=0.85), paint(PG(*star_pts(74, 18, 7, 2, 4, 0)), fill=WHITE)],
            face=(50, 50, 0.55),
        ),
        "pearl": item(
            [C(50, 46, 26)],
            under=[fixed(P("M14,74 Q50,94 86,74 Q80,66 50,66 Q20,66 14,74Z"), "F3D3B8")],
            over=[paint(E(40, 36, 9, 6, -35), fill=WHITE, opacity=0.85), paint(C(62, 58, 3), fill=WHITE, opacity=0.6)],
            face=(50, 50, 0.75),
        ),
        "quartz": item(
            [PG(*star_pts(50, 54, 40, 20, 5))],
            over=[facets("M50,14 L50,54 M88,42 L50,54 M74,86 L50,54 M26,86 L50,54 M12,42 L50,54", opacity=0.45)],
            face=(50, 56, 0.6),
        ),
        "turquoise": item(
            [P("M24,40 C30,20 62,16 74,30 C88,44 86,72 70,82 C52,92 24,86 18,68 C14,56 20,48 24,40Z")],
            over=[line("M30,36 L40,46 L36,58 M60,26 L56,40 L66,48 L78,46 M44,74 L54,66 L66,72", width=2, opacity=0.4)],
            face=(50, 56, 0.85),
        ),
        "onyx": item(
            [PG(*[polar(50, 52, 37, a) for a in range(-90, 270, 60)])],
            over=[facets("M50,15 L50,32 L67,42 L67,62 L50,72 L33,62 L33,42 L50,32 M82,33.5 L67,42 M82,70.5 L67,62 M50,89 L50,72 "
                         "M18,70.5 L33,62 M18,33.5 L33,42", opacity=0.35)],
            face=(50, 52, 0.75),
        ),
        "nugget": item(
            [C(36, 56, 20), C(60, 44, 22), C(64, 66, 16), C(44, 72, 14), C(28, 40, 12)],
            over=[paint(C(56, 34, 4), fill=WHITE, opacity=0.7), paint(C(28, 50, 2.5), fill=WHITE, opacity=0.6),
                  paint(C(70, 62, 2.5), fill=WHITE, opacity=0.6)],
            face=(50, 56, 0.8),
        ),
        "garnet": item(
            [P("M50,12 Q54,12 56,16 L88,74 Q90,82 82,84 L18,84 Q10,82 12,74 L44,16 Q46,12 50,12Z")],
            over=[facets("M50,34 L34,68 L66,68 Z M50,12 L50,34 M14,80 L34,68 M86,80 L66,68")],
            face=(50, 62, 0.65),
        ),
    }


ITEMS = {
    "fruit": fruit_items,
    "candy": candy_items,
    "toys": toy_items,
    "post": post_items,
    "bakery": bakery_items,
    "aquarium": aquarium_items,
    "icecream": icecream_items,
    "gems": gem_items,
}


def _shape_part(s):
    """Splits a silhouette entry into (svg for filling, svg for an outline stroke of extra width)."""
    if isinstance(s, tuple):
        _, d, w = s
        fill = f'<path d="{d}" fill="none" stroke="#{WHITE}" stroke-width="{n(w)}" stroke-linecap="round" stroke-linejoin="round"/>'
        out = f'<path d="{d}" fill="none" stroke="#{INK}" stroke-width="{n(w + OUTLINE)}" stroke-linecap="round" stroke-linejoin="round"/>'
        return fill, out, None
    out = paint(s, fill=INK, stroke=INK, width=OUTLINE)
    return paint(s, fill=WHITE), out, s


def item_layers(uid, it):
    fills, outs, clips = [], [], []
    for s in it["body"]:
        f, o, c = _shape_part(s)
        fills.append(f)
        outs.append(o)
        if c:
            clips.append(c)
    # Fixed parts behind the body get their outline in the same pass so they merge cleanly.
    under_out = []
    for u in it["under"]:
        if "fill=\"none\"" in u:
            continue
        under_out.append(_reoutline(u))
    under = "".join(under_out) + "".join(outs) + "".join(it["under"])
    clip_id = f"c{uid}"
    shade = ""
    if clips:
        shade = (f'<clipPath id="{clip_id}">{"".join(clips)}</clipPath>'
                 f'<g clip-path="url(#{clip_id})"><circle cx="78" cy="88" r="52" fill="#{SHADE}"/>'
                 f'<circle cx="64" cy="70" r="46" fill="#{WHITE}"/></g>')
    body = "".join(fills) + shade
    over = "".join(it["over"])
    return under, body, over


def _reoutline(svg):
    """A copy of a fixed part as a thick ink stroke, so it gets the same outer outline as the body."""
    import re

    s = re.sub(r' (fill|stroke|stroke-width|opacity|stroke-linejoin|stroke-linecap|stroke-dasharray)="[^"]*"', "", svg)
    return paint(s, fill=INK, stroke=INK, width=OUTLINE)


def face_svg(light):
    eye = WHITE if light else INK
    cheek = "F48FB1"

    def smile(cx, cy, ro, ri):
        a0, a1 = math.radians(20), math.radians(160)
        o0, o1 = polar(cx, cy, ro, 20), polar(cx, cy, ro, 160)
        i1, i0 = polar(cx, cy, ri, 160), polar(cx, cy, ri, 20)
        _ = a0, a1
        return (f'<path d="M{n(o0[0])},{n(o0[1])}A{n(ro)},{n(ro)} 0,0 1,{n(o1[0])},{n(o1[1])}'
                f'L{n(i1[0])},{n(i1[1])}A{n(ri)},{n(ri)} 0,0 0,{n(i0[0])},{n(i0[1])}Z" fill="#{eye}"/>')

    return (paint(E(30, 56, 6, 3.6), fill=cheek, opacity=0.55) + paint(E(70, 56, 6, 3.6), fill=cheek, opacity=0.55)
            + paint(C(39, 46, 5), fill=eye) + paint(C(61, 46, 5), fill=eye)
            + paint(C(37.5, 44.5, 1.6), fill=INK if light else WHITE) + paint(C(59.5, 44.5, 1.6), fill=INK if light else WHITE)
            + smile(50, 52, 8, 4.2))


# ---------------------------------------------------------------------------------------------
# Tube dressing: lid (fixed), lid tag (tinted with the sealed item's colour), collar at the rim
# and a base under the tube. Each is drawn in a square cell, centred on its anchor.

DRESSING = {}


def dressing(theme):
    def deco(f):
        DRESSING[theme] = f
        return f

    return deco


def lid_band(color, y=42, h=18):
    return fixed(R(10, y, 80, h, 7), color, width=3.5) + paint(R(16, y + 3, 50, 3.5, 1.75), fill=WHITE, opacity=0.5)


@dressing("fruit")
def _fruit():
    lid = lid_band("B9814F") + line("M20,50 Q40,47 60,51 M30,55 Q50,53 80,56", color="8D5A3B", width=2, opacity=0.6)
    return {
        "lid": lid + fixed(R(42, 30, 16, 13, 4), "8D5A3B", width=3),
        "lidtag": fixed(C(50, 51, 6), WHITE, width=2.5),
        "collar": line("M14,50 Q50,58 86,50", color="C9A26B", width=6) + fixed(E(30, 54, 6, 4, 30), "C9A26B", width=2)
        + fixed(E(38, 56, 6, 4, -30), "C9A26B", width=2),
        "base": fixed(R(8, 42, 84, 18, 4), "C98D55", width=3.5) + line("M8,51 L92,51", color="8D5A3B", width=2, opacity=0.5)
        + fixed(R(14, 58, 10, 8, 2), "8D5A3B", width=2.5) + fixed(R(76, 58, 10, 8, 2), "8D5A3B", width=2.5),
    }


@dressing("candy")
def _candy():
    stripes = "".join(paint(R(16 + i * 14, 42, 7, 18), fill="F06292") for i in range(5))
    return {
        "lid": fixed(P("M10,60 Q10,40 50,36 Q90,40 90,60Z"), WHITE, width=3.5) + stripes.replace("42", "44")
        + line("M10,60 Q10,40 50,36 Q90,40 90,60Z", width=3.5) + fixed(C(50, 30, 9), "F06292", width=3),
        "lidtag": fixed(C(50, 30, 5), WHITE, width=2),
        "collar": fixed(R(10, 44, 80, 12, 6), "FFE3F1", width=3) + "".join(paint(R(14 + i * 12, 45, 5, 10, 0, 20), fill="E8559A")
                                                                           for i in range(6)),
        "base": fixed(P("M20,40 L80,40 L88,62 L12,62Z"), "FFE3F1", width=3.5)
        + "".join(paint(R(18 + i * 14, 44, 6, 14, 0, 20), fill="F06292", opacity=0.8) for i in range(5)),
    }


@dressing("toys")
def _toys():
    studs = "".join(fixed(R(18 + i * 22, 30, 14, 9, 3), "E53935", width=2.5) for i in range(3))
    return {
        "lid": studs + fixed(R(10, 38, 80, 22, 5), "E53935", width=3.5) + paint(R(16, 42, 50, 4, 2), fill=WHITE, opacity=0.5),
        "lidtag": fixed(C(50, 49, 6), WHITE, width=2.5),
        "collar": fixed(R(12, 44, 76, 12, 6), "FFD54F", width=3),
        "base": fixed(R(8, 38, 84, 26, 5), "42A5F5", width=3.5)
        + "".join(paint(C(24 + i * 26, 51, 5), fill=WHITE, opacity=0.4) for i in range(3)),
    }


@dressing("post")
def _post():
    return {
        "lid": fixed(R(10, 40, 80, 20, 8), "D4A54A", width=3.5) + fixed(R(20, 32, 60, 10, 4), "B88A2E", width=3)
        + paint(R(16, 44, 50, 3.5, 1.75), fill=WHITE, opacity=0.6),
        "lidtag": fixed(R(40, 46, 20, 10, 3), WHITE, width=2.5),
        "collar": line("M74,50 L84,66", width=2) + fixed(R(76, 64, 18, 12, 2, 20), PAPER, width=2.5)
        + fixed(R(12, 45, 76, 9, 4), "D4A54A", width=3),
        "base": fixed(P("M6,44 L94,44 L88,64 L12,64Z"), "A1693C", width=3.5) + line("M14,52 L86,52", color="6D4C41", width=2,
                                                                                    opacity=0.5),
    }


@dressing("bakery")
def _bakery():
    check = "".join(paint(R(14 + i * 12, 36 + (i % 2) * 8, 8, 8), fill="E53935", opacity=0.8) for i in range(6))
    return {
        "lid": fixed(P("M6,62 Q8,38 50,34 Q92,38 94,62 Q72,56 50,60 Q28,56 6,62Z"), WHITE, width=3.5) + check
        + fixed(C(50, 30, 6), "E53935", width=2.5),
        "lidtag": fixed(C(50, 30, 3.5), WHITE, width=1.5),
        "collar": fixed(R(10, 46, 80, 8, 4), "E53935", width=3) + fixed(P("M44,50 L34,40 L34,60Z M56,50 L66,40 L66,60Z"),
                                                                         "E53935", width=2.5),
        "base": fixed(PG(*[polar(50, 50, 44 + 4 * math.sin(math.radians(a * 12)), a) for a in range(180, 361, 6)]
                   + [(94, 56), (6, 56)]), WHITE, width=3),
    }


@dressing("aquarium")
def _aquarium():
    pebbles = "".join(fixed(E(x, y, rx, ry), c, width=2.5) for x, y, rx, ry, c in
                      ((18, 54, 10, 7, "90A4AE"), (36, 56, 11, 7, "B0BEC5"), (56, 55, 12, 8, "78909C"), (76, 54, 11, 7, "B0BEC5"),
                       (88, 56, 7, 5, "90A4AE")))
    return {
        "lid": fixed(R(10, 42, 80, 18, 9), "4DB6AC", width=3.5) + paint(R(16, 45, 50, 3.5, 1.75), fill=WHITE, opacity=0.5),
        "lidtag": fixed(C(50, 51, 6), WHITE, width=2.5),
        "collar": paint(C(30, 52, 3), stroke=WHITE, width=2) + paint(C(70, 48, 4), stroke=WHITE, width=2),
        "base": line("M22,50 Q16,36 24,24 Q30,14 24,6", color="4CAF50", width=6) + line("M80,50 Q86,38 80,28", color="66BB6A",
                                                                                        width=5) + pebbles,
    }


@dressing("icecream")
def _icecream():
    return {
        "lid": fixed(P("M10,60 Q12,46 24,46 Q30,34 42,40 Q50,28 58,40 Q70,34 76,46 Q88,46 90,60Z"), CREAM, width=3.5)
        + fixed(C(50, 30, 8), "E53935", width=3) + line("M50,22 Q52,12 60,10", color=BROWN, width=3),
        "lidtag": paint(C(50, 30, 3.5), fill=WHITE),
        "collar": fixed(R(10, 44, 80, 12, 4), "E2A85A", width=3) + line("M18,44 L26,56 M30,44 L38,56 M42,44 L50,56 M54,44 L62,56 "
                                                                         "M66,44 L74,56 M78,44 L84,52", width=1.5, opacity=0.5),
        "base": fixed(E(50, 52, 44, 10), "F8BBD0", width=3.5) + paint(E(50, 50, 30, 5), fill=WHITE, opacity=0.5),
    }


@dressing("gems")
def _gems():
    rivets = "".join(fixed(C(18 + i * 16, 51, 2.6), "B0BEC5", width=1.5) for i in range(5))
    return {
        "lid": fixed(R(10, 42, 80, 18, 4), "5D6470", width=3.5) + rivets + fixed(P("M34,42 L40,28 L60,28 L66,42Z"), "4A505B",
                                                                                 width=3),
        "lidtag": fixed(PG((50, 30), (56, 36), (50, 42), (44, 36)), WHITE, width=2),
        "collar": fixed(R(10, 44, 80, 12, 3), "5D6470", width=3) + "".join(
            fixed(C(20 + i * 20, 50, 2.2), "B0BEC5", width=1.2) for i in range(4)),
        "base": fixed(P("M8,62 L14,42 L86,42 L92,62Z"), "5D6470", width=3.5) + rivets.replace("cy=\"51\"", "cy=\"52\""),
    }


@dressing("neutral")
def _neutral():
    return {
        "lid": lid_band("FF8A5B"),
        "lidtag": fixed(C(50, 51, 6), WHITE, width=2.5),
        "collar": "",
        "base": "",
    }


# ---------------------------------------------------------------------------------------------
# Mechanic overlays (the same in every world, SPEC §13), particles and the tap pointer.

OVERLAYS = {
    "ov.hidden": fixed(R(14, 14, 72, 72, 18), "8E86A8", width=4) + paint(R(20, 20, 60, 60, 14), stroke=WHITE, width=2.5,
                                                                         opacity=0.4)
    + line("M38,38 Q38,26 50,26 Q62,26 62,38 Q62,46 50,50 L50,58", color=WHITE, width=7) + paint(C(50, 70, 4.5), fill=WHITE),
    "ov.lock": line("M32,46 L32,32 Q32,14 50,14 Q68,14 68,32 L68,46", width=13) + line(
        "M32,46 L32,32 Q32,14 50,14 Q68,14 68,32 L68,46", color="C9CFD8", width=7)
    + fixed(R(16, 42, 68, 50, 12), "8E86A8", width=4) + fixed(C(50, 67, 17), PAPER, width=2.5),
    "ov.label": fixed(R(6, 30, 88, 40, 14), PAPER, width=3.5) + paint(C(14, 50, 3), fill=INK, opacity=0.3),
    "ov.glue": fixed(P("M30,40 Q30,30 40,32 Q50,26 60,32 Q70,30 70,40 L70,60 Q70,70 60,68 Q50,74 40,68 Q30,70 30,60Z"), CREAM,
                     width=3) + paint(E(42, 40, 4, 2.5, -20), fill=WHITE),
    "ov.ice": paint(R(6, 6, 88, 88, 20), fill="C7E8FF", opacity=0.72) + paint(R(6, 6, 88, 88, 20), stroke="7FB8E0", width=3.5)
    + paint(R(16, 16, 24, 7, 3.5, -20), fill=WHITE, opacity=0.9) + paint(R(66, 72, 14, 5, 2.5, -20), fill=WHITE, opacity=0.7),
    "ov.pointer": fixed(P("M50,92 L22,58 L38,58 L38,10 L62,10 L62,58 L78,58Z"), "FF8A5B", width=4),
}

PARTICLES = {
    "p.leaf": fixed(P("M20,70 Q20,24 74,22 Q80,72 20,70Z"), "9CCC65", width=4) + line("M24,66 L62,34", color="558B2F", width=3),
    "p.sparkle": paint(PG(*star_pts(50, 50, 40, 9, 4, 0)), fill="FFFFFF") + paint(C(50, 50, 8), fill="FFE3F1"),
    "p.star": fixed(PG(*star_pts(50, 54, 38, 17, 5)), "FFD54F", width=4),
    "p.envelope": fixed(R(16, 28, 68, 46, 6), PAPER, width=4) + line("M18,31 L50,54 L82,31", width=3.5),
    "p.flour": paint(C(50, 50, 30), fill=WHITE, opacity=0.9) + paint(C(36, 40, 16), fill=WHITE) + paint(C(64, 58, 18), fill=WHITE),
    "p.bubble": paint(C(50, 50, 36), fill=WHITE, opacity=0.25) + paint(C(50, 50, 36), stroke=WHITE, width=4)
    + paint(E(38, 36, 9, 5, -40), fill=WHITE),
    "p.snowflake": line("M50,10 L50,90 M15,30 L85,70 M15,70 L85,30 M42,18 L50,26 L58,18 M42,82 L50,74 L58,82", color=WHITE,
                        width=6),
    "p.glint": paint(PG(*star_pts(50, 50, 42, 8, 4, -90)), fill=WHITE) + paint(PG(*star_pts(50, 50, 22, 5, 4, -45)), fill=WHITE,
                                                                               opacity=0.7),
    "p.dot": paint(C(50, 50, 30), fill=WHITE),
}


# ---------------------------------------------------------------------------------------------
# Backgrounds: a band anchored to the top of the screen and one to the bottom, both faded so
# they never compete with the items (SPEC §13). The game fills between them with the gradient.

BG_W = 480
TOP_H = 200
BOTTOM_H = 300


def scallops(y, color, r=20, w=BG_W, opacity=1.0):
    d = f"M0,0 L{w},0 L{w},{y}"
    x = w
    while x > 0:
        d += f" A{r},{r} 0 0,1 {n(x - 2 * r)},{y}"
        x -= 2 * r
    d += " Z"
    return paint(P(d), fill=color, opacity=opacity)


def awning(y, a, b, stripe=40):
    parts = []
    for i in range(BG_W // stripe):
        parts.append(paint(R(i * stripe, 0, stripe, y), fill=a if i % 2 == 0 else b))
        parts.append(paint(P(f"M{i * stripe},{y} A{stripe / 2},{stripe / 2} 0 0,0 {(i + 1) * stripe},{y}Z"),
                           fill=a if i % 2 == 0 else b))
    return "".join(parts) + line(f"M0,{y} L{BG_W},{y}", color=INK, width=2, opacity=0.15)


def bunting(y, colors):
    parts = [line(f"M0,{y} Q{BG_W / 2},{y + 30} {BG_W},{y}", color=INK, width=2, opacity=0.25)]
    for i in range(12):
        x = 20 + i * 40
        yy = y + 30 * (1 - ((x - BG_W / 2) / (BG_W / 2)) ** 2) - 2
        parts.append(paint(PG((x - 12, yy), (x + 12, yy), (x, yy + 22)), fill=colors[i % len(colors)], opacity=0.8))
    return "".join(parts)


def faded(svg, opacity):
    return f'<g opacity="{n(opacity)}">{svg}</g>'


BACKGROUNDS = {}


def background(theme):
    def deco(f):
        BACKGROUNDS[theme] = f
        return f

    return deco


@background("fruit")
def _bg_fruit():
    top = faded(awning(70, "F28C38", "FFF1D6"), 0.55) + faded(bunting(100, ["E53935", "FDD835", "9CCC65", "8E44AD"]), 0.6)
    crate = ("".join(fixed(R(x, 190, 120, 70, 6), "D9A066", width=4) + line(f"M{x},{214} L{x + 120},{214} M{x},{238} L{x + 120},{238}",
                                                                            color="8D5A3B", width=3, opacity=0.5)
                     for x in (-20, 380)))
    fruits = "".join(fixed(C(x, y, 16), c, width=3) for x, y, c in
                     ((10, 180, "E53935"), (40, 176, "FB8C00"), (25, 160, "9CCC65"), (400, 178, "FDD835"), (440, 176, "E53935"),
                      (470, 182, "8E44AD"), (420, 160, "FB8C00")))
    bottom = faded(paint(R(0, 250, BG_W, 50), fill="C98D55") + fruits + crate, 0.5)
    return top, bottom


@background("candy")
def _bg_candy():
    top = faded(scallops(60, "F8C8E6", 24) + "".join(paint(C(40 + i * 80, 120 + (i % 2) * 30, 6), fill="FFFFFF", opacity=0.8)
                                                       for i in range(6)), 0.7) + faded(
        line("M0,150 L60,150 Q80,150 80,170 L80,200 M480,140 L420,140 Q400,140 400,160 L400,200", color="E8559A", width=18),
        0.35)
    lolli = "".join(fixed(R(x - 4, 150, 8, 150, 3), PAPER, width=3) + fixed(C(x, 150, 34), c, width=4)
                    + line(f"M{x},150 m-6,0 a6,6 0 1,1 12,0 a12,12 0 1,1 -24,0 a18,18 0 1,1 36,0", color=WHITE, width=5)
                    for x, c in ((40, "F06292"), (440, "29B6F6")))
    bottom = faded(paint(P("M0,240 Q120,220 240,240 Q360,260 480,240 L480,300 L0,300Z"), fill="F48FB1") + lolli, 0.5)
    return top, bottom


@background("toys")
def _bg_toys():
    top = faded("".join(fixed(PG(*star_pts(x, y, 14, 6, 5)), c, width=3) for x, y, c in
                        ((40, 40, "FFD54F"), (140, 80, "42A5F5"), (260, 30, "E53935"), (360, 90, "66BB6A"), (450, 40, "AB47BC"),
                         (90, 150, "FF8A3D"), (400, 160, "FFD54F"))), 0.45)
    blocks = "".join(fixed(R(x, y, 50, 50, 6), c, width=4) for x, y, c in
                     ((10, 200, "E53935"), (60, 200, "42A5F5"), (30, 150, "FFD54F"), (380, 200, "66BB6A"), (430, 200, "AB47BC"),
                      (405, 150, "FF8A3D")))
    bottom = faded(paint(R(0, 250, BG_W, 50), fill="8D6E63") + line("M0,252 L480,252", color=INK, width=3, opacity=0.3) + blocks,
                   0.5)
    return top, bottom


@background("post")
def _bg_post():
    holes = "".join(paint(R(10 + c * 78, 10 + r * 52, 66, 40, 4), fill="E7D19A") for c in range(6) for r in range(3))
    stripe = "".join(paint(PG((i * 40, 0), (i * 40 + 20, 0), (i * 40 + 6, 12), (i * 40 - 14, 12)), fill="E53935" if i % 2 else "42A5F5")
                     for i in range(14))
    top = faded(stripe, 0.6) + faded(holes, 0.45)
    parcels = "".join(fixed(R(x, y, w, h, 4), "C99A6A", width=4) + line(f"M{x + w / 2},{y} L{x + w / 2},{y + h}", color="8D5A3B",
                                                                         width=3, opacity=0.6)
                      for x, y, w, h in ((-10, 200, 90, 70), (20, 150, 60, 50), (390, 190, 100, 80), (410, 140, 60, 50)))
    bottom = faded(paint(R(0, 260, BG_W, 40), fill="A1693C") + parcels, 0.5)
    return top, bottom


@background("bakery")
def _bg_bakery():
    tiles = "".join(paint(R(c * 60 + (r % 2) * 30 - 30, r * 30, 56, 26, 3), fill="FFFFFF", opacity=0.5) for c in range(10)
                    for r in range(5))
    lamps = "".join(line(f"M{x},0 L{x},80", color=INK, width=2, opacity=0.4) + fixed(P(f"M{x - 26},110 Q{x},70 {x + 26},110Z"), "F2A22C",
                                                                                       width=3)
                    + paint(C(x, 112, 6), fill="FFF59D") for x in (120, 360))
    top = faded(tiles, 0.6) + faded(lamps, 0.55)
    loaves = "".join(fixed(E(x, 218, 44, 22), "E3A23B", width=4) + line(f"M{x - 20},210 Q{x - 14},218 {x - 18},226 M{x},206 Q{x + 6},216 {x + 2},228",
                                                                        color=WHITE, width=3, opacity=0.6)
                     for x in (40, 440))
    bottom = faded(paint(R(0, 240, BG_W, 60), fill="B4703C") + paint(R(0, 236, BG_W, 10), fill="D9A066") + loaves, 0.5)
    return top, bottom


@background("aquarium")
def _bg_aquarium():
    rays = "".join(paint(PG((x, 0), (x + 40, 0), (x + 100, 200), (x + 40, 200)), fill=WHITE, opacity=0.25) for x in (40, 200, 340))
    top = faded(rays, 0.8) + faded("".join(paint(C(x, y, r), stroke=WHITE, width=3) for x, y, r in
                                           ((60, 140, 8), (80, 110, 5), (420, 160, 10), (400, 120, 6))), 0.6)
    weeds = "".join(line(f"M{x},300 Q{x - 20},{240} {x},{200} Q{x + 20},{160} {x},{120}", color=c, width=12) for x, c in
                    ((30, "4CAF50"), (70, "66BB6A"), (420, "4CAF50"), (455, "81C784")))
    bottom = faded(paint(P("M0,260 Q120,240 240,258 Q360,276 480,256 L480,300 L0,300Z"), fill="F3E0C8") + weeds
                   + fixed(P("M180,272 L210,250 L240,272Z"), "F48FB1", width=3), 0.55)
    return top, bottom


@background("icecream")
def _bg_icecream():
    top = faded(awning(64, "F8BBD0", "FFFFFF", 48), 0.7) + faded("".join(paint(C(30 + (i * 67) % 460, 110 + (i * 41) % 80, 7),
                                                                                fill=c, opacity=0.7)
                                                                          for i, c in enumerate(["8A63D2", "F48FB1", "9EE6C8", "FFE24D",
                                                                                                 "5C8DE0", "FF9F43", "F48FB1"])),
                                                                 0.6)
    checks = "".join(paint(R(c * 40, 240 + r * 30, 40, 30), fill="DCCEFA" if (c + r) % 2 else "FFFFFF") for c in range(12)
                     for r in range(2))
    bottom = faded(checks, 0.6)
    return top, bottom


@background("gems")
def _bg_gems():
    rock_l = "M0,0 L70,0 Q50,40 64,80 Q40,120 56,160 Q30,190 40,200 L0,200Z"
    rock_r = "M480,0 L410,0 Q430,50 414,90 Q440,130 420,170 L440,200 L480,200Z"
    top = faded(paint(P(rock_l), fill="8C90B8") + paint(P(rock_r), fill="8C90B8"), 0.5) + faded(
        line("M240,0 L240,40", color=INK, width=2, opacity=0.4) + fixed(R(226, 40, 28, 36, 6), "FFD54F", width=3), 0.55)
    crystals = "".join(fixed(PG((x - 14, 300), (x - 14, 300 - h), (x, 300 - h - 18), (x + 14, 300 - h), (x + 14, 300)), c, width=4)
                       for x, h, c in ((24, 80, "9B59D0"), (52, 50, "2EC4C4"), (430, 70, "E0245A"), (458, 46, "2EAD5B")))
    bottom = faded(paint(P("M0,250 Q100,230 200,252 Q320,270 480,246 L480,300 L0,300Z"), fill="5257A8") + crystals, 0.5)
    return top, bottom


@background("neutral")
def _bg_neutral():
    clouds = "".join(paint(E(x, y, 50, 18), fill=WHITE, opacity=0.6) + paint(C(x - 14, y - 10, 18), fill=WHITE, opacity=0.6)
                     for x, y in ((70, 60), (380, 110), (240, 30)))
    dots = "".join(paint(C(x, y, 5), fill=c, opacity=0.5) for x, y, c in
                   ((30, 150, "EF5350"), (150, 120, "FFD54F"), (300, 170, "42A5F5"), (450, 60, "AB47BC"), (200, 90, "66BB6A")))
    top = faded(clouds + dots, 0.8)
    hills = paint(P("M0,240 Q120,200 240,236 Q360,270 480,226 L480,300 L0,300Z"), fill="BFE8C6") + paint(
        P("M0,270 Q160,250 300,272 Q400,286 480,266 L480,300 L0,300Z"), fill="9ED8A8")
    bottom = faded(hills, 0.7)
    return top, bottom


# ---------------------------------------------------------------------------------------------
# Output.


def svg_doc(w, h, body, defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">'
            f"{defs}{body}</svg>\n")


def render(svg_path, png_path, w, h):
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), "-o", str(png_path), str(svg_path)], check=True)


def collect_cells():
    """All cells in a stable order: (name, svg body)."""
    cells = []
    faces = []
    uid = 0
    for theme, make in ITEMS.items():
        for name, it in make().items():
            under, body, over = item_layers(uid, it)
            uid += 1
            key = f"{theme}.{name}"
            cells += [(f"{key}.under", under), (f"{key}.body", body), (f"{key}.over", over)]
            faces.append((key, it["face"]))
    cells += [("face.ink", face_svg(False)), ("face.light", face_svg(True))]
    for theme, make in DRESSING.items():
        for part, svg in make().items():
            if svg:
                cells.append((f"{theme}.{part}", svg))
    cells += list(OVERLAYS.items()) + list(PARTICLES.items())
    return cells, faces


def main(argv):
    keep_svg = None
    if "--svg" in argv:
        keep_svg = Path(argv[argv.index("--svg") + 1])
        keep_svg.mkdir(parents=True, exist_ok=True)
    if not shutil.which("rsvg-convert"):
        sys.exit("rsvg-convert not found; run inside the devenv shell")
    cells, faces = collect_cells()
    per_sheet = GRID * GRID
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "bg").mkdir(exist_ok=True)
    for old in OUT.glob("sheet*.png"):
        old.unlink()
    index = [
        "# Generated by tools/gen_art.py; do not edit.",
        f"cell={CELL_PX}",
        f"grid={GRID}",
    ]
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(keep_svg) if keep_svg else Path(tmp)
        for sheet in range((len(cells) + per_sheet - 1) // per_sheet):
            chunk = cells[sheet * per_sheet:(sheet + 1) * per_sheet]
            parts = []
            for i, (name, svg) in enumerate(chunk):
                col, row = i % GRID, i // GRID
                parts.append(f'<g transform="translate({col * CELL},{row * CELL})">{svg}</g>')
                index.append(f"{name}={sheet},{col},{row}")
            size = GRID * CELL
            path = tmp / f"sheet{sheet}.svg"
            path.write_text(svg_doc(size, size, "".join(parts)))
            render(path, OUT / f"sheet{sheet}.png", GRID * CELL_PX, GRID * CELL_PX)
        for key, (fx, fy, fs) in faces:
            index.append(f"face.{key}={n(fx / CELL)},{n(fy / CELL)},{n(fs)}")
        for theme, make in BACKGROUNDS.items():
            top, bottom = make()
            for part, svg, h in (("top", top, TOP_H), ("bottom", bottom, BOTTOM_H)):
                path = tmp / f"bg-{theme}-{part}.svg"
                path.write_text(svg_doc(BG_W, h, svg))
                render(path, OUT / "bg" / f"{theme}-{part}.png", BG_W * 3 // 2, h * 3 // 2)
    (OUT / "atlas.properties").write_text("\n".join(index) + "\n")
    print(f"{len(cells)} cells in {(len(cells) + per_sheet - 1) // per_sheet} sheet(s), {len(BACKGROUNDS)} backgrounds")


if __name__ == "__main__":
    main(sys.argv[1:])

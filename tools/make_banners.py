"""Dock tiles for both TVs in one "tech" style: near-black glass, the brand's light rising from the bottom-right
corner, the brand's symbol as a neon outline, a lit bottom edge and the official logo (Wikimedia Commons SVGs) in its
own colours. Netflix, YouTube and RaiPlay keep their light in the corner and their symbol clear of the letters.

    python tools/make_banners.py tools/logos launcher/app/src/main/res/font/outfit.ttf \\
        --android launcher/app/src/main/res/drawable --web <LG fork>/launcher/casa-tiles.js [--preview <dir>]

--android writes banner_<name>.xml (VectorDrawables, 160x90 viewport) for the Metz; --web writes casa-tiles.js
(SVG data URIs by webOS app id) for the LG C3; --preview writes one SVG per tile. Needs svgelements and fonttools.
Logos: Netflix 2015, Prime Video 2024, YouTube 2017, DAZN boxed, Now, RaiPlay 2023, Mediaset Infinity 2023,
Disney+ (2019), HBO Max (2025)."""
import argparse, base64, json, os, re
from svgelements import SVG, Shape, Path, Matrix
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ap = argparse.ArgumentParser()
ap.add_argument("logos")
ap.add_argument("font")
ap.add_argument("--android")
ap.add_argument("--web")
ap.add_argument("--preview")
ARGS = ap.parse_args()
VW, VH = 160, 90


def shapes(name):
    """[(Path, fill hex or None for a gradient, element id)] with transforms applied."""
    out = []
    for e in SVG.parse(os.path.join(ARGS.logos, name + ".svg")).elements():
        if isinstance(e, Shape):
            p = Path(e)
            p.reify()
            raw = e.values.get("fill") or ""
            out.append((p, None if raw.startswith("url(") else str(e.fill), e.id))
    return out


def fit(parts, max_w, max_h, cx=VW / 2, cy=VH / 2):
    """Scale and centre parts [(Path, colour, rule)] into max_w x max_h around (cx, cy): [(d, colour, rule)]."""
    xs0, ys0, xs1, ys1 = zip(*[p.bbox() for p, _, _ in parts])
    x0, y0, x1, y1 = min(xs0), min(ys0), max(xs1), max(ys1)
    s = min(max_w / (x1 - x0), max_h / (y1 - y0))
    m = Matrix("translate(%f,%f) scale(%f) translate(%f,%f)" % (cx, cy, s, -(x0 + x1) / 2, -(y0 + y1) / 2))
    res = []
    for p, c, rule in parts:
        q = Path(p) * m
        q.reify()
        res.append((q.d(), c, rule))
    return res


def place(paths, height, anchor, target):
    """A symbol: paths scaled to height, the source point anchor landing on target in the tile: [d]."""
    bbs = [p.bbox() for p in paths]
    s = height / (max(b[3] for b in bbs) - min(b[1] for b in bbs))
    m = Matrix("translate(%f,%f) scale(%f) translate(%f,%f)" % (target[0], target[1], s, -anchor[0], -anchor[1]))
    res = []
    for p in paths:
        q = Path(p) * m
        q.reify()
        res.append(q.d())
    return res


def center(paths):
    bbs = [Path(p).bbox() for p in paths]
    return ((min(b[0] for b in bbs) + max(b[2] for b in bbs)) / 2, (min(b[1] for b in bbs) + max(b[3] for b in bbs)) / 2)


def one(paths):
    """Several subpaths as one path (holes stay holes under evenodd)."""
    return Path("".join(Path(p).d() for p in paths))


def text_paths(text, size, weight, cx, cy, tracking=0.0):
    """Outfit glyphs as one path d, centred on (cx, cy), cap height = size."""
    font = TTFont(ARGS.font)
    if "fvar" in font:
        font = instantiateVariableFont(font, {"wght": weight})
    gs, cmap, upm = font.getGlyphSet(), font.getBestCmap(), font["head"].unitsPerEm
    s = size / (getattr(font["OS/2"], "sCapHeight", 0) or upm * 0.7)
    widths = [gs[cmap[ord(ch)]].width for ch in text]
    x = cx / s - (sum(widths) + tracking * upm * (len(text) - 1)) / 2
    pen = SVGPathPen(gs)
    for ch, w in zip(text, widths):
        gs[cmap[ord(ch)]].draw(TransformPen(pen, (s, 0, 0, -s, x * s, cy + size / 2)))
        x += w + tracking * upm
    return pen.getCommands()


def lerp(a, b, t):
    a, b = int(a[1:], 16), int(b[1:], 16)
    return "#%02X%02X%02X" % tuple(round(((a >> k) & 255) * (1 - t) + ((b >> k) & 255) * t) for k in (16, 8, 0))


def ramp(stops, t):
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        if t <= t1:
            return lerp(c0, c1, (t - t0) / (t1 - t0) if t1 > t0 else 0)
    return stops[-1][1]


# ---- The tiles: logo [(d, colour, rule)], accent (neon, light), symbol [d], light (radius in widths, opacity) ----

T = {}


def tile(name, logo, accent, symbol, light=(1.05, 0.55)):
    T[name] = {"logo": logo, "accent": accent, "symbol": symbol, "light": light}


nf = shapes("netflix")
nf_n = Path(list(nf[0][0].as_subpaths())[0])
tile("netflix", fit([(p, "#E50914", "nonZero") for p, _, _ in nf], 120, 36), ("#FF1F2C", "#FF1F2C"),
     place([one([nf_n])], 118, center([nf_n]), (180, 56)), (0.62, 0.42))

pv = shapes("prime")
smile = [Path(s) for s in pv[0][0].as_subpaths() if Path(s).bbox()[1] >= 14]
tile("prime", fit([(p, "#FFFFFF", "nonZero") for p, _, _ in pv], 112, 40), ("#2EA6FF", "#2EA6FF"),
     place([one(smile)], 88, center(smile), (114, 72)))

yt = shapes("youtube")
button = [p for p, c, _ in yt if c in ("#ff0000", "#ffffff")]
tile("youtube", fit([(p, "#FF0033" if k == 0 else "#FFFFFF", "nonZero") for k, (p, c, _) in enumerate(yt)], 116, 30),
     ("#FF2848", "#FF2848"), place([one(button)], 74, center(button), (178, 84)), (0.62, 0.42))

dz = [p for p, c, _ in shapes("dazn") if c == "#fafafa"]
frame = [q for q in (Path(x) for p in dz for x in p.as_subpaths()) if q.bbox()[2] - q.bbox()[0] > 200]
tile("dazn", fit([(p, "#FAFAFA", "nonZero") for p in dz], 64, 64), ("#F4FF2E", "#F4FF2E"),
     place([one(frame)], 190, center(frame), (136, 60)))

nw = shapes("now")
nw_o = Path(list(nw[0][0].as_subpaths())[1])
tile("now", fit([(p, "#FFFFFF", "nonZero") for p, _, _ in nw], 100, 34), ("#22E0C8", "#22E0C8"),
     place([one(list(nw[0][0].as_subpaths()))], 320, center([nw_o]), (136, 48)))

rai = {i: p for p, _, i in shapes("raiplay")}
sq = list(rai["rect36"].as_subpaths())
tile("raiplay", fit([(Path(sq[0]), "#1268F0", "nonZero"), (Path("".join(Path(s).d() for s in sq[1:])), "#FFFFFF", "evenOdd"),
                     (rai["path42"], "#19D3F0", "evenOdd")], 112, 46),
     ("#2E9BFF", "#21D4F0"), place([Path(sq[0])], 120, center([Path(sq[0])]), (206, 70)), (0.7, 0.45))

inf = shapes("infinity")
word = [p for p, c, _ in inf if c is None][0]
letters = [Path(s) for s in word.as_subpaths()]
wx0, wx1 = min(s.bbox()[0] for s in letters), max(s.bbox()[2] for s in letters)
stops = [(0.0, "#8B2BE2"), (0.55, "#4F5BFF"), (1.0, "#14D39A")]
parts = [([p for p, c, _ in inf if c is not None][0], "#FFFFFF", "evenOdd")]
parts += [(s, ramp(stops, ((s.bbox()[0] + s.bbox()[2]) / 2 - wx0) / (wx1 - wx0)), "evenOdd") for s in letters]
inf_y = [s for s in letters if s.bbox()[0] > 440]
tile("infinity", fit(parts, 116, 58), ("#9A4DFF", "#18D69C"), place([one(inf_y)], 210, center(inf_y), (140, 46)))

screen = Path("M52,16 h56 a7,7 0 0 1 7,7 v34 a7,7 0 0 1 -7,7 h-56 a7,7 0 0 1 -7,-7 v-34 a7,7 0 0 1 7,-7 z "
              "M53.5,21.5 v37 h53 v-37 z M68,69 h24 v4.5 h-24 z")
tile("canali_tv", [(screen.d(), "#FFFFFF", "evenOdd"), (text_paths("TV", 20, 600, 80, 40, 0.04), "#FFFFFF", "nonZero")],
     ("#4C9BFF", "#4C9BFF"), place([screen], 190, center([screen]), (134, 58)))

ds = {i: (p, c) for p, c, i in shapes("disney")}
tile("disney", fit([(ds["Path"][0], "#FFFFFF", "nonZero"), (ds["Path-1"][0], "#FFFFFF", "nonZero"),
                    (ds["Path-3"][0], "#4FC3FF", "nonZero")], 112, 50),
     ("#2E7BFF", "#3BD1FF"), place([ds["Path-1"][0]], 110, center([ds["Path-1"][0]]), (172, 80)))

hb = shapes("hbomax")
dot = [p for p, c, i in hb if p.bbox()[2] - p.bbox()[0] < 210 and p.bbox()[1] > 100 and p.bbox()[3] < 400]
tile("hbomax", fit([(p, "#FFFFFF", "evenOdd") for p, _, _ in hb], 100, 52), ("#7B4DFF", "#4F6BFF"),
     place(dot, 120, center(dot), (176, 74)))

ANDROID = ["netflix", "prime", "youtube", "dazn", "now", "raiplay", "canali_tv", "infinity"]
WEBOS = {"netflix": "netflix", "amazon": "prime", "com.disney.disneyplus-prod": "disney", "com.wbd.stream": "hbomax",
         "youtube.leanback.v4": "youtube", "dazn": "dazn", "com.atlas.tv": "now", "it.rainet.raiplay": "raiplay",
         "com.5412449.198417": "infinity"}


# ---- Writers ----

def short(d):
    """Two decimals are a hundredth of a 160-wide viewport, far below a pixel."""
    return re.sub(r"-?\d*\.\d+(?:[eE][-+]?\d+)?", lambda m: ("%.2f" % float(m.group())).rstrip("0").rstrip("."), d)


def argb(colour, alpha):
    return "#%02X%s" % (round(alpha * 255), colour[1:].upper())


def to_svg(t):
    a1, a2 = t["accent"]
    radius, light = t["light"]
    stroke = "".join('<path fill="none" stroke="%s" stroke-opacity=".55" stroke-width="1.4" stroke-linejoin="round" d="%s"/>'
                     % (a1, short(d)) for d in t["symbol"])
    logo = "".join('<path fill="%s" fill-rule="%s" d="%s"/>' % (c, "evenodd" if r == "evenOdd" else "nonzero", short(d))
                   for d, c, r in t["logo"])
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 90"><defs>'
            '<linearGradient id="b" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#050608"/><stop offset="1" stop-color="#0E1016"/></linearGradient>'
            '<radialGradient id="l" cx="1" cy="1" r="%s"><stop offset="0" stop-color="%s" stop-opacity="%s"/>'
            '<stop offset=".45" stop-color="%s" stop-opacity=".16"/><stop offset="1" stop-color="%s" stop-opacity="0"/></radialGradient>'
            '<linearGradient id="e" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="%s" stop-opacity="0"/>'
            '<stop offset=".6" stop-color="%s" stop-opacity=".9"/><stop offset="1" stop-color="%s" stop-opacity=".3"/></linearGradient>'
            '<linearGradient id="s" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".1"/>'
            '<stop offset=".4" stop-color="#fff" stop-opacity="0"/></linearGradient></defs>'
            '<rect width="160" height="90" fill="url(#b)"/><rect width="160" height="90" fill="url(#l)"/>%s'
            '<rect width="160" height="90" fill="url(#s)"/><rect y="88.6" width="160" height="1.4" fill="url(#e)"/>%s</svg>'
            % (radius, a2, light, a1, a1, a1, a2, a2, stroke, logo))


def gradient(kind, attrs, items):
    return ('        <aapt:attr name="android:fillColor">\n            <gradient android:type="%s" %s>\n%s'
            '            </gradient>\n        </aapt:attr>\n'
            % (kind, attrs, "".join('                <item android:offset="%s" android:color="%s" />\n' % (o, c) for o, c in items)))


def to_vector(t):
    a1, a2 = t["accent"]
    radius, light = t["light"]
    x = ['<?xml version="1.0" encoding="utf-8"?>',
         '<!-- Fork lorenzoma97: dock tile in the "tech" style (glass, the brand\'s light, its symbol in neon, the official',
         '     logo as vector paths from Wikimedia Commons). Generated by tools/make_banners.py: edit that, not this. -->',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    xmlns:aapt="http://schemas.android.com/aapt"',
         '    android:width="320dp" android:height="180dp"',
         '    android:viewportWidth="160" android:viewportHeight="90">',
         '    <path android:pathData="M0,0h160v90h-160z">',
         gradient("linear", 'android:startX="0" android:startY="0" android:endX="160" android:endY="90"',
                  [(0, "#FF050608"), (1, "#FF0E1016")]).rstrip("\n"),
         '    </path>',
         # The SVG's light is an ellipse over the tile; here a circle squashed by the group to the same shape.
         '    <group android:pivotX="160" android:pivotY="90" android:scaleY="0.5625">',
         '    <path android:pathData="M0,-70h160v160h-160z">',
         gradient("radial", 'android:centerX="160" android:centerY="90" android:gradientRadius="%s"' % round(160 * radius, 1),
                  [(0, argb(a2, light)), (0.45, argb(a1, 0.16)), (1, argb(a1, 0))]).rstrip("\n"),
         '    </path>',
         '    </group>']
    for d in t["symbol"]:
        x.append('    <path android:strokeColor="%s" android:strokeAlpha="0.55" android:strokeWidth="1.4"\n'
                 '        android:strokeLineJoin="round" android:pathData="%s" />' % (a1.upper(), short(d)))
    x += ['    <path android:pathData="M0,0h160v90h-160z">',
          gradient("linear", 'android:startX="0" android:startY="0" android:endX="0" android:endY="90"',
                   [(0, "#1AFFFFFF"), (0.4, "#00FFFFFF"), (1, "#00FFFFFF")]).rstrip("\n"),
          '    </path>',
          '    <path android:pathData="M0,88.6h160v1.4h-160z">',
          gradient("linear", 'android:startX="0" android:startY="0" android:endX="160" android:endY="0"',
                   [(0, argb(a1, 0)), (0.6, argb(a2, 0.9)), (1, argb(a2, 0.3))]).rstrip("\n"),
          '    </path>']
    for d, c, r in t["logo"]:
        x.append('    <path android:fillColor="%s" android:fillType="%s"\n        android:pathData="%s" />' % (c.upper(), r, short(d)))
    return "\n".join(x + ["</vector>", ""])


if ARGS.android:
    for name in ANDROID:
        with open(os.path.join(ARGS.android, "banner_" + name + ".xml"), "w", encoding="utf-8", newline="\n") as f:
            f.write(to_vector(T[name]))
if ARGS.web:
    uris = {app: "data:image/svg+xml;base64," + base64.b64encode(to_svg(T[name]).encode("utf-8")).decode("ascii")
            for app, name in WEBOS.items()}
    with open(ARGS.web, "w", encoding="utf-8", newline="\n") as f:
        f.write("/* Fork lorenzoma97: the dock's tiles by webOS app id, the same \"tech\" set as the Metz's.\n"
                "   Generated by tools/make_banners.py in lorenzoma97/cleanyourtv: edit that, not this. */\n")
        f.write("window.CASA_TILES = " + json.dumps(uris, indent=0) + ";\n")
if ARGS.preview:
    os.makedirs(ARGS.preview, exist_ok=True)
    for name, t in T.items():
        with open(os.path.join(ARGS.preview, name + ".svg"), "w", encoding="utf-8") as f:
            f.write(to_svg(t))
print(", ".join(T), "done")

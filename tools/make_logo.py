"""
琉璃 Launcher 圖示產生器（設計：design/logo-concepts-b2.html 的「變化 1：半浸在浪裡」）。

同一份規格同時輸出：
  - app/src/main/res/drawable/ic_launcher.xml   108×108 向量圖示
  - app/src/main/res/drawable/banner.xml        320×180 電視首頁橫幅（含「琉璃」字標）
  - design/logo-final.html                      用同一份幾何畫的 SVG 預覽，對照用

只用漸層、不用模糊：VectorDrawable 不支援濾鏡，這樣預覽看到的就是裝置上的樣子。
字標用 Noto Sans TC（Light 300）轉成輪廓，裝置上不需要字型。

用法：python tools/make_logo.py <fontTools 所在目錄（可省略）>
"""
import io
import os
import sys

if len(sys.argv) > 1:
    sys.path.insert(0, sys.argv[1])

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = r'C:\Windows\Fonts\NotoSansTC-VF.ttf'

# ───────────── 共用元素（座標為 108 單位） ─────────────


def rrect(x, y, w, h, r):
    if r == 0:
        return f'M{x},{y}h{w}v{h}h-{w}z'
    return (f'M{x + r},{y}h{w - 2 * r}a{r},{r} 0 0 1 {r},{r}v{h - 2 * r}a{r},{r} 0 0 1 -{r},{r}'
            f'h-{w - 2 * r}a{r},{r} 0 0 1 -{r},-{r}v-{h - 2 * r}a{r},{r} 0 0 1 {r},-{r}z')


def circle(cx, cy, r):
    return f'M{cx - r},{cy}a{r},{r} 0 1 0 {2 * r},0a{r},{r} 0 1 0 -{2 * r},0z'


def lin(x1, y1, x2, y2, *stops):
    return ('linear', (x1, y1, x2, y2), stops)


def rad(cx, cy, r, *stops):
    return ('radial', (cx, cy, r), stops)


W1 = 'M0,64C18,56 30,58 44,62S70,70 84,60S100,50 108,54V108H0Z'
W2 = 'M0,74C16,70 30,66 46,70S68,76 82,68S98,62 108,66V108H0Z'
W3 = 'M0,86C20,80 34,78 50,82S74,88 88,80S102,76 108,78V108H0Z'

G_W1 = lin(0, 0, 108, 0, (0, '#3d5bff', .78), (1, '#8f5cff', .62))
G_W2 = lin(0, 0, 108, 0, (0, '#b44bd8', .78), (1, '#ff6f7d', .70))
G_W3 = lin(0, 0, 108, 0, (0, '#ff7a6b', .86), (1, '#ffc56a', .90))
G_SKY = rad(58, 50, 40, (0, '#6d5cff', .35), (1, '#6d5cff', 0))
G_VIG = rad(54, 54, 56, (0, '#000000', 0), (.6, '#000000', 0), (1, '#000000', .55))

TILE = rrect(30, 44, 48, 27, 7)
TILE_FILLS = [
    lin(30, 44, 78, 72, (0, '#ddd6ff', .90), (.5, '#b394f0', .84), (1, '#ff9a96', .86)),  # 磨砂：模糊過的浪色
    rad(66, 68, 26, (0, '#7d4dff', .62), (1, '#7d4dff', 0)),                              # 後浪透上來的紫
    rad(34, 44, 24, (0, '#ffffff', .88), (.45, '#ffffff', .22), (1, '#ffffff', 0)),       # 左上過曝
    lin(30, 44, 62, 76, (0, '#ffffff', .45), (.45, '#ffffff', 0)),                        # 焦點斜向光澤
]
# 落在後浪上的影子：圓形放射漸層，Y 壓成 1/4 變成橢圓
SHADOW = ('group', dict(pivot=(0, 74), scale=(1, .25)),
          [(circle(56, 74, 28), rad(56, 74, 28, (0, '#05061a', .65), (1, '#05061a', 0)))])


def tile_group(tx, ty, s):
    return ('group', dict(translate=(tx, ty), scale=(s, s)), [(TILE, f) for f in TILE_FILLS])


def icon_spec():
    return [
        (rrect(0, 0, 108, 108, 0), lin(0, 0, 108, 108, (0, '#0a0e1c', 1), (1, '#171c38', 1))),
        (circle(58, 50, 40), G_SKY),
        (W1, G_W1),
        ('group', dict(translate=(0, 3)), [SHADOW]),
        tile_group(0, 3, 1),
        (W2, G_W2),
        (W3, G_W3),
        (rrect(0, 0, 108, 108, 0), G_VIG),
    ]


def banner_spec(text_paths):
    waves = lambda paths: ('group', dict(scale=(320 / 108, 180 / 108)), paths)
    return [
        (rrect(0, 0, 320, 180, 0), lin(0, 0, 320, 180, (0, '#0a0e1c', 1), (1, '#171c38', 1))),
        ('group', dict(translate=(150, 11), scale=(1.6, 1.6)), [(circle(58, 50, 40), G_SKY), SHADOW]),
        waves([(W1, G_W1)]),
        tile_group(150, 11, 1.6),
        waves([(W2, G_W2), (W3, G_W3)]),
    ] + text_paths


# ───────────── 字標：Noto Sans TC Light 轉輪廓 ─────────────

def text_paths(text, x, y, size, spacing, weight, color, alpha):
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    font = TTFont(FONT)
    font = instancer.instantiateVariableFont(font, {'wght': weight})
    gs = font.getGlyphSet()
    cmap = font.getBestCmap()
    upm = font['head'].unitsPerEm
    k = size / upm
    out = []
    pen_x = x
    for ch in text:
        name = cmap[ord(ch)]
        g = gs[name]
        sp = SVGPathPen(gs, ntos=lambda v: ('%.2f' % v).rstrip('0').rstrip('.'))
        g.draw(TransformPen(sp, (k, 0, 0, -k, pen_x, y)))
        d = sp.getCommands()
        if d:
            out.append((d, ('solid', color, alpha)))
        pen_x += g.width * k + spacing
    return out


# ───────────── 輸出：VectorDrawable ─────────────

def hexa(color, a):
    return '#%02X%s' % (round(a * 255), color.lstrip('#').upper())


def vd_fill(fill, ind):
    if fill[0] == 'solid':
        return f' android:fillColor="{hexa(fill[1], fill[2])}"', ''
    kind, geo, stops = fill
    items = ''.join(f'{ind}            <item android:offset="{o}" android:color="{hexa(c, a)}"/>\n' for o, c, a in stops)
    if kind == 'linear':
        g = f'android:type="linear" android:startX="{geo[0]}" android:startY="{geo[1]}" android:endX="{geo[2]}" android:endY="{geo[3]}"'
    else:
        g = f'android:type="radial" android:centerX="{geo[0]}" android:centerY="{geo[1]}" android:gradientRadius="{geo[2]}"'
    body = (f'{ind}    <aapt:attr name="android:fillColor">\n{ind}        <gradient {g}>\n{items}'
            f'{ind}        </gradient>\n{ind}    </aapt:attr>\n')
    return '', body


def vd_nodes(nodes, ind='    '):
    s = ''
    for n in nodes:
        if n[0] == 'group':
            _, t, kids = n
            attrs = ''
            if 'translate' in t:
                attrs += f' android:translateX="{t["translate"][0]}" android:translateY="{t["translate"][1]}"'
            if 'scale' in t:
                attrs += f' android:scaleX="{t["scale"][0]:.4f}" android:scaleY="{t["scale"][1]:.4f}"'
            if 'pivot' in t:
                attrs += f' android:pivotX="{t["pivot"][0]}" android:pivotY="{t["pivot"][1]}"'
            s += f'{ind}<group{attrs}>\n{vd_nodes(kids, ind + "    ")}{ind}</group>\n'
        else:
            d, fill = n
            inline, body = vd_fill(fill, ind)
            if body:
                s += f'{ind}<path android:pathData="{d}">\n{body}{ind}</path>\n'
            else:
                s += f'{ind}<path android:pathData="{d}"{inline} />\n'
    return s


def vd(w, h, nodes, comment):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- {comment}（由 tools/make_logo.py 產生，請改產生器不要手改） -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp" android:height="{h}dp"\n'
            f'    android:viewportWidth="{w}" android:viewportHeight="{h}">\n'
            + vd_nodes(nodes) + '</vector>\n')


# ───────────── 輸出：SVG 預覽 ─────────────

_gid = [0]


def svg_nodes(nodes, defs):
    s = ''
    for n in nodes:
        if n[0] == 'group':
            _, t, kids = n
            tr = ''
            if 'translate' in t:
                tr += f'translate({t["translate"][0]} {t["translate"][1]}) '
            if 'pivot' in t:
                px, py = t['pivot']
                sx, sy = t.get('scale', (1, 1))
                tr += f'translate({px} {py}) scale({sx} {sy}) translate({-px} {-py})'
            elif 'scale' in t:
                tr += f'scale({t["scale"][0]} {t["scale"][1]})'
            s += f'<g transform="{tr}">{svg_nodes(kids, defs)}</g>'
        else:
            d, fill = n
            if fill[0] == 'solid':
                s += f'<path d="{d}" fill="{fill[1]}" fill-opacity="{fill[2]}"/>'
                continue
            _gid[0] += 1
            gid = f'g{_gid[0]}'
            kind, geo, stops = fill
            st = ''.join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in stops)
            if kind == 'linear':
                defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{geo[0]}" y1="{geo[1]}" x2="{geo[2]}" y2="{geo[3]}">{st}</linearGradient>')
            else:
                defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{geo[0]}" cy="{geo[1]}" r="{geo[2]}">{st}</radialGradient>')
            s += f'<path d="{d}" fill="url(#{gid})"/>'
    return s


def svg(w, h, nodes, css=''):
    defs = []
    body = svg_nodes(nodes, defs)
    return f'<svg viewBox="0 0 {w} {h}" style="{css}"><defs>{"".join(defs)}</defs>{body}</svg>'


def main():
    words = text_paths('琉璃', 30, 70, 42, 6, 300, '#f2f3f7', 1)
    words += text_paths('LIULI LAUNCHER', 32, 92, 10, 3, 400, '#c9cbd6', 1)
    icon = icon_spec()
    banner = banner_spec(words)
    res = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable')
    io.open(os.path.join(res, 'ic_launcher.xml'), 'w', encoding='utf-8').write(vd(108, 108, icon, '琉璃 Launcher 圖示：浪中琉璃'))
    io.open(os.path.join(res, 'banner.xml'), 'w', encoding='utf-8').write(vd(320, 180, banner, '琉璃 Launcher 電視首頁橫幅'))
    page = ('<!doctype html><html lang="zh-Hant"><head><meta charset="utf-8"><title>琉璃 Logo 定稿</title>'
            '<style>body{margin:0;background:#0b0d14;color:#a9adbb;font-family:"Noto Sans TC",sans-serif;padding:40px;}'
            '.r{display:flex;gap:28px;align-items:flex-end;flex-wrap:wrap;margin-bottom:28px}'
            '.l{font-size:12px;text-align:center;margin-top:6px}</style></head><body>'
            '<h2 style="color:#f2f3f7;font-weight:300">琉璃 Logo 定稿（與 App 內向量檔同一份幾何）</h2><div class="r">'
            f'<div>{svg(108, 108, icon, "width:216px;height:216px;border-radius:48px")}<div class="l">方形</div></div>'
            f'<div>{svg(108, 108, icon, "width:120px;height:120px;border-radius:50%")}<div class="l">圓形</div></div>'
            f'<div>{svg(108, 108, icon, "width:48px;height:48px;border-radius:12px")}<div class="l">48px</div></div>'
            f'</div><div class="r"><div>{svg(320, 180, banner, "width:640px;height:360px;border-radius:20px")}<div class="l">電視橫幅 320×180（放大 2 倍）</div></div>'
            f'<div>{svg(320, 180, banner, "width:160px;height:90px;border-radius:8px")}<div class="l">實際大小約</div></div></div></body></html>')
    io.open(os.path.join(ROOT, 'design', 'logo-final.html'), 'w', encoding='utf-8').write(page)
    # 給 README 用的獨立 SVG（GitHub 可直接顯示，任何尺寸都清晰）
    logo_dir = os.path.join(ROOT, 'docs', 'logo')
    os.makedirs(logo_dir, exist_ok=True)
    for name, w, h, nodes, radius in (('icon.svg', 108, 108, icon, 24), ('banner.svg', 320, 180, banner, 10)):
        s = svg(w, h, nodes).replace('<svg viewBox=', f'<svg xmlns="http://www.w3.org/2000/svg" width="{w * 4}" height="{h * 4}" viewBox=', 1)
        # 圓角：用 clipPath 裁掉四角（README 上看起來和電視上的遮罩一致）
        s = s.replace('<defs>', f'<defs><clipPath id="r"><rect width="{w}" height="{h}" rx="{radius}"/></clipPath>', 1)
        s = s.replace('</defs>', '</defs><g clip-path="url(#r)">', 1).replace('</svg>', '</g></svg>')
        io.open(os.path.join(logo_dir, name), 'w', encoding='utf-8').write(s)
    print('ok')


if __name__ == '__main__':
    main()

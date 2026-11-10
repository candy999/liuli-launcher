# 紅線抽成折線：每一欄找紅色段的中心，左右相鄰欄依「延續方向」接起來（交叉時照斜率走，不亂跳）
import numpy as np, json
from PIL import Image, ImageDraw, ImageFont
red = np.asarray(Image.open('redmask.png')) > 0
H, W = red.shape
cols = []
for x in range(W):
    c = red[:, x]; ys = []; y = 0
    while y < H:
        if c[y]:
            y0 = y
            while y < H and c[y]: y += 1
            if y - y0 <= 40: ys.append((y0 + y - 1) / 2)
            else: ys.append(None)      # 太長的一段＝線幾乎是垂直的，這一欄先略過
        else: y += 1
    cols.append([v for v in ys if v is not None])
tracks = []   # 每條：點列表
active = []
for x in range(W):
    pts = list(cols[x]); used = [False] * len(pts)
    new_active = []
    for tr in active:
        (x1, y1) = tr[-1]
        slope = 0
        if len(tr) >= 6: slope = (tr[-1][1] - tr[-6][1]) / (tr[-1][0] - tr[-6][0])
        pred = y1 + slope * (x - x1)
        best, bd = None, 1e9
        for i, y in enumerate(pts):
            dd = abs(y - pred)
            if dd < bd: bd, best = dd, i
        if best is not None and bd < 7 and x - x1 <= 3:
            tr.append((x, pts[best])); used[best] = True; new_active.append(tr)
        elif x - x1 <= 3:
            new_active.append(tr)
        else:
            tracks.append(tr)
    for i, y in enumerate(pts):
        if not used[i]:
            new_active.append([(x, y)])
    active = new_active
tracks += active
tracks = [t for t in tracks if len(t) >= 25]
tracks.sort(key=lambda t: t[0][0])
print(len(tracks))
out = []
for i, t in enumerate(tracks):
    out.append([[int(p[0]), round(p[1], 1)] for p in t[::4]] + [[int(t[-1][0]), round(t[-1][1], 1)]])
    print(i, 'from', tuple(int(v) for v in t[0]), 'to', tuple(int(v) for v in t[-1]), 'n', len(t))
json.dump(out, open('tracks.json', 'w'))
src = Image.open(r'C:\Users\Administrator\.claude\uploads\4e0d3858-12a6-4189-b036-3e51027f6a94\6255bf9d-image.jpg').convert('RGB')
im = Image.fromarray((np.asarray(src) * .5).astype(np.uint8)); d = ImageDraw.Draw(im)
f = ImageFont.truetype(r'C:\Windows\Fonts\msjh.ttc', 16)
rng = np.random.default_rng(1)
for i, t in enumerate(out):
    col = tuple(int(v) for v in rng.integers(110, 255, 3))
    d.line([tuple(p) for p in t], fill=col, width=3)
    m = t[len(t) // 2]
    d.text((m[0] + 3, m[1] - 18), str(i), font=f, fill=col, stroke_width=2, stroke_fill=(0, 0, 0))
im.save('tracks.png')

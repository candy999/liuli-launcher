# 依使用者手繪的稜線分塊（第二張風格圖）：每一條稜線＝它下面那塊山體的上緣，近的蓋住遠的
import json, base64, io, numpy as np
from PIL import Image, ImageDraw
SRC = r'C:\Users\Administrator\AppData\Local\Comfy-Desktop\ComfyUI-Shared\output\YUSHAN_TMP\gpt_style2fix_00002_.png'
src = Image.open(SRC).convert('RGB').resize((1280, 720), Image.LANCZOS)
a = np.asarray(src).astype(float)
HS, WS = a.shape[:2]
H, W = HS, WS
R, G, B = a[..., 0], a[..., 1], a[..., 2]
lum = .3 * R + .59 * G + .11 * B
def box_blur(v, k):
    p = np.pad(v, k, mode='edge'); c = p.cumsum(0).cumsum(1); c = np.pad(c, ((1, 0), (1, 0)))
    n = 2 * k + 1
    return (c[n:, n:] - c[:-n, n:] - c[n:, :-n] + c[:-n, :-n]) / (n * n)

# ── 天空與遠山 ──
yy0 = np.arange(H)[:, None]
sky = np.where(yy0 < 212, ((B - R > 40) & (lum > 118)) | (lum > 212), ((B - R > 25) & (lum > 185)) | (lum > 222))
top = np.zeros(W, int)
for x in range(W):
    m = sky[:, x]; y = 0
    while y < H and m[y]: y += 1
    top[x] = y
k = 10; tp = np.pad(top, k, mode='edge'); top = np.array([int(np.median(tp[i:i + 2 * k + 1])) for i in range(W)])
mount = np.arange(H)[:, None] >= top[None, :]
farc = mount & (box_blur(((B >= 180) & (lum > 105)).astype(float), 3) > .5) & (yy0 < 460)
seedm = np.zeros((H, W), bool)
for x in range(W): seedm[top[x]:top[x] + 4, x] = True
reach = seedm & farc
for _ in range(300):
    nxt = farc & (reach | np.roll(reach, 1, 0) | np.roll(reach, -1, 0) | np.roll(reach, 1, 1) | np.roll(reach, -1, 1))
    if (nxt == reach).all(): break
    reach = nxt
far = reach

# ── 通道 ──
BODY = np.array([21, 62, 106.]); HAZE = np.array([147, 197, 248.]); LIGHT = np.array([125, 64, 0.])
g = np.clip((B - BODY[2]) / (HAZE[2] - BODY[2]), 0, 1)
base = BODY + (HAZE - BODY) * g[..., None]
r = np.clip(((R - base[..., 0]) / LIGHT[0] + (G - base[..., 1]) / LIGHT[1]) / 2, 0, 1)

# ── 稜線：手繪紅線追出來的折線（加上第二次的修正線），再吸附到圖上真正的稜線邊緣 ──
T = json.load(open('tracks.json')); T2 = json.load(open('tracks2.json'))
def seg(t, x0=-1e9, x1=1e9): return [p for p in t if x0 <= p[0] <= x1]
# 邊緣強度：模糊後上下相差多少（顏色＋明暗），稜線是山體與後方之間最明顯的那條交界
blur = np.stack([box_blur(a[..., c], 1) for c in range(3)], -1)
E = np.zeros((H, W))
E[2:-2] = np.sqrt(((blur[4:] - blur[:-4]) ** 2).sum(-1))
E = np.clip(E / np.percentile(E[mount], 97), 0, 1)
# 邊緣改用向量色塊的交界（顏色類別不同的地方）：稜線吸附到向量本身的邊上
CM = np.load('svgcls.npy')
EV = np.zeros((H, W)); EV[1:-1] = ((CM[2:] != CM[:-2]) & (CM[2:] >= 0) & (CM[:-2] >= 0)).astype(float)
EV = np.maximum(EV, .5 * np.pad(((CM[:, 2:] != CM[:, :-2]) & (CM[:, 2:] >= 0)).astype(float), ((0, 0), (1, 1))))
E = np.clip(box_blur(EV, 1) * 2.5, 0, 1)
def snap(pts, R=26, lam=.05, mu=.0015):
    """在手繪線上下 R 像素內，找一條連續、平順、而且沿著最強邊緣走的路徑（動態規劃）。"""
    pts = sorted(pts, key=lambda p: p[0])
    px = np.array([p[0] for p in pts], float); py = np.array([p[1] for p in pts], float)
    xs = np.arange(int(np.ceil(px[0])), int(np.floor(px[-1])) + 1)
    xs = xs[(xs >= 0) & (xs < W)]
    y0 = np.interp(xs, px, py)
    off = np.arange(-R, R + 1)
    n = len(xs); cost = np.zeros((n, len(off))); back = np.zeros((n, len(off)), int)
    def unary(i):
        yy = np.clip(np.round(y0[i] + off).astype(int), 0, H - 1)
        return -E[yy, xs[i]] + mu * off ** 2
    cost[0] = unary(0)
    for i in range(1, n):
        # 前後兩欄的位移（相對手繪線）變化越小越好
        d = off[None, :] - off[:, None]
        tot = cost[i - 1][:, None] + lam * np.abs(d) + np.where(np.abs(d) > 4, 1e3, 0)
        back[i] = np.argmin(tot, 0); cost[i] = tot[back[i], np.arange(len(off))] + unary(i)
    j = int(np.argmin(cost[-1])); path = []
    for i in range(n - 1, -1, -1):
        path.append([int(xs[i]), float(y0[i] + off[j])]); j = back[i][j]
    path = path[::-1]
    return path[::3] + [path[-1]]
T3 = json.load(open('tracks3.json'))
SKYL = seg(T3[0])                                   # 天際線（東峰、鞍部、主峰、右側山腹）
SADDLE = 545
LINES = [
    # 名稱, 這一層的上緣, 視差係數, （不用）
    ('東峰山體', SKYL, .016, .006),
    ('主峰山體', snap(seg(T3[1], -1e9, SADDLE)) + seg(SKYL, SADDLE + 1), .022, .016),   # 主峰的西稜在東峰前面
    ('右側稜', snap(seg(T3[7])), .034, .022),
    ('近稜（北峰→風口）', snap(seg(T3[2])), .048, .034),
    ('近稜下方的稜', snap(seg(T3[3])), .064, .048),
    ('最前面的稜', snap(seg(T3[5])), .085, .064),
]
SKYNAMES = ('東峰山體', '主峰山體')
json.dump({l[0]: l[1] for l in LINES}, open('lines8.json', 'w'))
SS = 4
def poly_mask(pts, up=0):
    pts = sorted(pts, key=lambda p: p[0])
    xs = [p[0] for p in pts]; ys = [p[1] - up for p in pts]
    P = list(zip(xs, ys))
    if P[0][0] < 50: P = [(-20, P[0][1])] + P
    if P[-1][0] > W - 50: P = P + [(W + 20, P[-1][1])]
    P = P + [(P[-1][0], H + 80), (P[0][0], H + 80)]
    im = Image.new('L', (W * SS, H * SS), 0)
    ImageDraw.Draw(im).polygon([(x * SS, y * SS) for x, y in P], fill=255)
    return np.asarray(im.resize((W, H), Image.BOX)).astype(float) / 255
# 每一塊的覆蓋（可重疊；近的蓋住遠的）
cov_full = []
for name, pts, kk, kb in LINES:
    m = poly_mask(pts, up=8 if name in SKYNAMES else 0)   # 天際線的線畫得稍低：往上多包一點，再用天空切齊
    m = m * mount * (~far if name in SKYNAMES else 1)
    cov_full.append(m)
farcov = (mount & ~(cov_full[0] > .5) & ~(cov_full[1] > .5)).astype(float) * mount
NAMES = ['遠山'] + [l[0] for l in LINES]
KP = [.006] + [l[2] for l in LINES]
ALL = [farcov] + cov_full
# 可見的歸屬：最近（索引最大）而且覆蓋的那塊
vis = np.zeros((len(ALL), H, W))
rest = np.ones((H, W))
for i in range(len(ALL) - 1, -1, -1):
    c = np.minimum(ALL[i], rest); vis[i] = c; rest = rest - c
ridge_y = [np.mean([p[1] for p in l[1]]) for l in LINES]
np.savez_compressed('layers8.npz', ALL=np.array(ALL, np.float16), vis=np.array(vis, np.float16), r=r.astype(np.float16), g=g.astype(np.float16), names=np.array(NAMES), KP=np.array(KP), box=np.array([(WS - (WS / 1.3) * 1.3) / 2, 165 - .1 * ((WS / 1.3) * 9 / 16), (WS / 1.3) * 1.3, (WS / 1.3) * 9 / 16]))

# ── 裁成視窗（主峰峰頂在 10%）──
CW = WS / 1.3; CH = CW * 9 / 16; Y0 = 165 - .1 * CH
OUTW, OUTH, PADB = 936, 405, 24
x0c = (WS - CW * 1.3) / 2
box = (x0c, Y0, x0c + CW * 1.3, Y0 + CH * (OUTH + PADB) / OUTH)
def crop(v):
    vv = np.concatenate([v, np.repeat(v[-1:], 80, 0)], 0)
    im = Image.fromarray(vv.astype(np.float32), 'F').crop(tuple(int(round(b)) for b in box))
    return np.asarray(im.resize((OUTW, OUTH + PADB), Image.BILINEAR))
Hc, Wc = OUTH + PADB, OUTW
covs = [np.clip(crop(v), 0, 1) for v in vis]
rc = crop(r); gc = crop(g)
yfull = crop(np.repeat(np.arange(H, dtype=float)[:, None], W, 1))
xfull = crop(np.repeat(np.arange(W, dtype=float)[None, :], H, 0))
pieces = []; prev = np.zeros((Hc, Wc, 3)) + np.array([180, 210, 240.])
ORDER = list(range(len(ALL)))
fulls = [np.clip(crop(v), 0, 1) for v in ALL]      # 每一層的完整範圍（含被前面擋住的部分）
REVEAL = 34                                          # 被擋住的部分只留可能露出來的那一圈（最大的視差差距＋餘裕），其餘不存
for oi, i in enumerate(ORDER):
    vis_c = covs[i]
    if vis_c.max() < .02: continue
    full = np.maximum(fulls[i], vis_c)
    if i == 0:   # 遠山：被主峰擋住的山腳往下補一段
        dn = vis_c.copy()
        for d in range(1, 41): dn = np.maximum(dn, np.roll(vis_c, d, 0))
        full = np.maximum(full, dn)
    near_vis = box_blur((vis_c > .02).astype(float), REVEAL) > 0
    full = full * near_vis
    # 真正的分層：這一層被擋住的地方用它自己的平塗底色補滿（這一層最常見的陰影色），不從邊緣抹顏色
    m = vis_c > .6
    baseL = float(np.percentile(rc[m], 25)) if m.any() else 0.0
    baseF = float(np.median(gc[m])) if m.any() else .5
    hid = np.clip(full - vis_c, 0, 1)
    den = np.maximum(full, 1e-4)
    L = (rc * vis_c + baseL * hid) / den; Fg = (gc * vis_c + baseF * hid) / den
    cov = full
    ys, xs = np.where(cov > .002)
    y0, y1, xa, xb = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    bw, bh = xb - xa, y1 - y0
    GRID = 48                                        # 整塊平移、不變形：網格只要很粗
    gw = bw // GRID + 2; gh = bh // GRID + 2
    K = np.full((gh, gw), KP[i])
    A_ = np.clip(cov[y0:y1, xa:xb] * 255 + .5, 0, 255).astype(np.uint8)
    R_ = np.clip(L[y0:y1, xa:xb] * 255 + .5, 0, 255).astype(np.uint8)
    G_ = np.clip(Fg[y0:y1, xa:xb] * 255 + .5, 0, 255).astype(np.uint8)
    rgba = np.stack([R_, G_, np.zeros_like(A_), A_], -1)
    buf = io.BytesIO(); Image.fromarray(rgba, 'RGBA').save(buf, 'PNG', optimize=True)
    col_top = [np.argmax(covs[i][:, x] > .5) for x in range(Wc) if (covs[i][:, x] > .5).any()]
    topY = float(np.mean(col_top) / 1.5) if col_top else float(y0 / 1.5)
    pieces.append(dict(name=NAMES[i], topY=topY, x0=int(xa), y0=int(y0), w=int(bw), h=int(bh), gw=int(gw), gh=int(gh), depth=0, meanY=topY,
                       k=[round(float(v), 5) for v in K.ravel()], png='data:image/png;base64,' + base64.b64encode(buf.getvalue()).decode()))
    print(len(pieces) - 1, NAMES[i], 'box', xa, y0, bw, bh, 'K', round(float(K.min()), 3), round(float(K.max()), 3), 'topY', round(topY))
    col = BODY + (HAZE - BODY) * Fg[..., None] + LIGHT * L[..., None]
    prev = prev * (1 - cov[..., None]) + col * cov[..., None]
json.dump(dict(scale=1.5, x0=-480 * .15, pieces=pieces), open('pieces8.json', 'w'))
# 分塊對照圖（原圖疊色、白線＝稜線）
pal = np.array([[90, 150, 255], [120, 200, 255], [255, 165, 50], [240, 120, 60], [255, 210, 90], [200, 140, 60], [70, 215, 120], [235, 75, 160], [165, 95, 235], [80, 230, 230], [255, 230, 70]], float)
owner = np.argmax(vis, 0)
img = np.where(mount[..., None], a * .45 + pal[owner % len(pal)] * .55, a * .55)
im = Image.fromarray(img.clip(0, 255).astype(np.uint8)); d = ImageDraw.Draw(im)
for name, pts, kk, kb in LINES:
    d.line([tuple(p) for p in sorted(pts, key=lambda p: p[0])], fill=(255, 255, 255), width=2)
im.save('seg_lines8.png')

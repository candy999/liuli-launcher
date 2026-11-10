# 使用者的 SVG 色塊分層：整塊分到它可見面積最多的那一層；跨好幾層的大背景色塊依層的範圍切開
import re, json, numpy as np
from PIL import Image, ImageDraw
exec(open('svgsplit.py', encoding='utf-8').read().split('# ── 圖層')[0])   # cls、paths、flatten
src_vec = open('vec.py', encoding='utf-8').read()
exec(src_vec[src_vec.index('def box_blur'):src_vec.index('def kmeans')])    # box_blur、trace、simplify、area
H, W = 720, 1280
z = np.load('layers8.npz')
VIS = z['vis'].astype(float); ALL = z['ALL'].astype(float); NAMES = [str(n) for n in z['names']]; KP = z['KP']
bx0, by0, bw, bh = z['box']
SX = 624 / (2 * bw); OX = -bx0 / bw * 624 - 72; OY = -by0 / bh * 270      # SVG（2560×1440）→ 邏輯座標
def to_logical(x, y): return (x - bx0) / bw * 624 - 72, (y - by0) / bh * 270
owner = np.where(VIS.sum(0) > .5, VIS.argmax(0), -1)
CM = np.load('svgcls.npy')
NL = len(NAMES)
def raster(subs):
    im = Image.new('L', (W, H), 0)
    for s in subs:
        if len(s) < 3: continue
        tmp = Image.new('L', (W, H), 0); ImageDraw.Draw(tmp).polygon([(px / 2, py / 2) for px, py in s], fill=255)
        im = Image.fromarray(np.bitwise_xor(np.asarray(im), np.asarray(tmp)))
    return np.asarray(im) > 127
rs = [raster(flatten(d)) for _, d in paths]
covered = np.zeros((H, W), bool); vis = [None] * len(rs)
for i in range(len(rs) - 1, -1, -1):
    vis[i] = rs[i] & ~covered; covered |= rs[i]
assign = []
for i in range(len(paths)):
    v = vis[i]
    if not v.any(): assign.append(None); continue
    o = owner[v]
    if (o < 0).mean() > .6: assign.append(None); continue            # 天空
    cnt = np.bincount(o[o >= 0], minlength=NL); top = int(cnt.argmax()); share = cnt[top] / max(1, cnt.sum())
    if share >= .75 or v.sum() < 1500: assign.append([top])
    else: assign.append([int(L) for L in np.nonzero(cnt > .04 * cnt.sum())[0]] + ['split'])
# 細化後每一層的範圍：整塊分層的色塊照畫的順序蓋上去
own2 = owner.copy()
for i, a_ in enumerate(assign):
    if a_ and a_[-1] != 'split': own2[rs[i]] = a_[0]
own2[owner < 0] = -1
# 色塊顏色 → 受光 r、遠近 g（和之前同一套換算）
BODY = np.array([21, 62, 106.]); HAZE = np.array([147, 197, 248.]); LIGHT = np.array([125, 64, 0.])
def rg(hexc):
    c = np.array([int(hexc[k:k + 2], 16) for k in (1, 3, 5)], float)
    g = float(np.clip((c[2] - BODY[2]) / (HAZE[2] - BODY[2]), 0, 1)); base = BODY + (HAZE - BODY) * g
    r = float(np.clip(((c[0] - base[0]) / LIGHT[0] + (c[1] - base[1]) / LIGHT[1]) / 2, 0, 1))
    return [round(r, 3), round(g, 3)]
CRG = {k: rg(v) for k, v in cls.items()}
def loops_of(m, eps=.8, min_area=20):
    m = box_blur(m.astype(float), 1) > .5
    out = []
    for lp in trace(m):
        s = simplify(lp, eps)
        if len(s) >= 3 and area(s) >= min_area:
            lx, ly = to_logical(s[:, 0], s[:, 1]); out.append([round(v, 2) for v in np.stack([lx, ly], 1).ravel()])
    return out
layers = []
for L in range(NL):
    V = own2 == L
    if V.sum() < 50: continue
    S = (ALL[L] > .5) | V
    if L == 0:   # 遠山：底色剪影一路到畫面底
        has = V.any(0); topF = np.where(has, V.argmax(0), -1).astype(float)
        xs_ = np.arange(W); topF = np.interp(xs_, xs_[has], topF[has])
        k_ = 14; tp_ = np.pad(topF, k_, mode='edge'); topF = np.array([np.median(tp_[j:j + 2 * k_ + 1]) for j in range(W)])   # 去掉偵測殘留的直條
        S = np.arange(H)[:, None] >= topF[None, :]
    items = []
    for i, a_ in enumerate(assign):
        if not a_: continue
        c, d = paths[i]
        if a_[-1] == 'split':
            if L in a_: items.append(dict(c=c, d=d, clip=1))
        elif a_[0] == L: items.append(dict(c=c, d=d))
    # 底色＝這一層可見範圍裡最多的那個顏色
    cnt = {}
    for i, a_ in enumerate(assign):
        if a_ and (L in a_[:-1] if a_[-1] == 'split' else a_[0] == L):
            cnt[paths[i][0]] = cnt.get(paths[i][0], 0) + int((vis[i] & V).sum())
    basec = max(cnt, key=cnt.get) if cnt else 'st4'
    # 被前面擋住的地方：把這一層看得到的色塊顏色往裡延伸（每一點填離它最近、看得到的色塊顏色），
    # 前景移開時露出來的是後面山坡原本的延續，不是一條深色的縫；只延伸視差可能露出的那一圈，再往裡才是底色
    CMv = np.where(V, CM, -1)
    ext = CMv.copy(); hidden = S & ~V
    for _ in range(40):
        un = (ext < 0) & hidden
        if not un.any(): break
        best = np.full((H, W), -1)
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            sh = np.roll(np.roll(ext, dy, 0), dx, 1)
            best = np.where((best < 0) & (sh >= 0), sh, best)
        ext = np.where(un & (best >= 0), best, ext)
    extg = []
    for c_ in range(10):
        m_ = (ext == c_) & hidden
        if m_.sum() < 8: continue
        lp_ = loops_of(m_ | (box_blur(m_.astype(float), 1) > .2) & hidden, .8, 4)
        if lp_: extg.append(dict(c='st%d' % c_, loops=lp_))
    # 圖的範圍只留「視差可能露出來」的地方：看得到的範圍往外 50 像素（約 24 個邏輯單位）
    need = S & (box_blur(V.astype(float), 50) > 0)
    ys, xs = np.where(need)
    x0, y0 = to_logical(xs.min(), ys.min()); x1, y1 = to_logical(xs.max() + 1, ys.max() + 1)
    vt = [np.argmax(V[:, x]) for x in range(W) if V[:, x].any()]
    layers.append(dict(name=NAMES[L], K=float(KP[L]), topY=float(to_logical(0, np.mean(vt))[1]),
                       bbox=[round(x0 - 1, 1), round(max(y0 - 1, -10), 1), round(x1 + 1, 1), round(min(y1 + 1, 290), 1)],
                       base=dict(c=basec, loops=loops_of(S, 1.0, 30)), ext=extg,
                       clip=loops_of(V, .6, 6) if any(it.get('clip') for it in items) else [], items=items))
    print(L, NAMES[L], 'items', len(items), 'split', sum(1 for it in items if it.get('clip')), 'base', basec)
json.dump(dict(scale=1.5, x0=-72, svg=dict(sx=SX, ox=OX, oy=OY), colors=CRG, layers=layers),
          open('svgvec.json', 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
import os; print('KB', os.path.getsize('svgvec.json') // 1024, 'colors', CRG)
pal = np.array([[90, 150, 255], [120, 200, 255], [255, 165, 50], [240, 120, 60], [255, 210, 90], [200, 140, 60], [70, 215, 120], [235, 75, 160], [165, 95, 235], [80, 230, 230]], float)
srcimg = np.asarray(Image.open(r'C:\Users\Administrator\AppData\Local\Comfy-Desktop\ComfyUI-Shared\output\YUSHAN_TMP\gpt_style2fix_00002_.png').convert('RGB').resize((W, H))).astype(float)
img = np.where(own2[..., None] >= 0, srcimg * .45 + pal[np.clip(own2, 0, 9)] * .55, srcimg * .55)
e = np.zeros((H, W), bool); e[:-1] |= own2[:-1] != own2[1:]; e[:, :-1] |= own2[:, :-1] != own2[:, 1:]
img[e & (own2 >= 0)] = [255, 255, 255]
Image.fromarray(img.clip(0, 255).astype(np.uint8)).save('svgvec_layers.png')

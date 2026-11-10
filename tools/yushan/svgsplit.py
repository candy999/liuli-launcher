# 用使用者轉好的向量（Illustrator 的 SVG）拆層：每個色塊整塊分到它大部分可見面積所在的那一層，邊界就是向量本身的邊
import re, json, numpy as np
from PIL import Image, ImageDraw
SVG = r'C:\Users\Administrator\AppData\Local\Comfy-Desktop\ComfyUI-Shared\output\YUSHAN_TMP\gpt_style2fix_00002_.svg'
txt = open(SVG, encoding='utf-8').read()
cls = dict(re.findall(r'\.(st\d+)\s*\{\s*fill:\s*(#[0-9a-fA-F]{6});', txt))
paths = re.findall(r'<path class="(st\d+)" d="([^"]+)"', txt)
print('classes', cls, 'paths', len(paths))

# ── 路徑解析（Illustrator 的寫法：1.93.29 是兩個數）→ 折線 ──
TOK = re.compile(r'[MmCcSsLlHhVvZzQqTt]|-?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?')
def flatten(d):
    toks = TOK.findall(d); i = 0; cmd = None
    subs = []; cur = []; x = y = 0.0; sx = sy = 0.0; lc = None
    def num():
        nonlocal i; v = float(toks[i]); i += 1; return v
    def cubic(p0, p1, p2, p3, n=8):
        t = np.linspace(0, 1, n + 1)[1:, None]
        return ((1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t ** 2 * p2 + t ** 3 * p3).tolist()
    while i < len(toks):
        if re.match(r'[A-Za-z]', toks[i]): cmd = toks[i]; i += 1
        if cmd in 'Mm':
            if cur: subs.append(cur)
            nx, ny = num(), num()
            if cmd == 'm': nx += x; ny += y
            x, y = nx, ny; sx, sy = x, y; cur = [(x, y)]; cmd = 'l' if cmd == 'm' else 'L'; lc = None
        elif cmd in 'Ll':
            nx, ny = num(), num()
            if cmd == 'l': nx += x; ny += y
            x, y = nx, ny; cur.append((x, y)); lc = None
        elif cmd in 'Hh':
            nx = num(); x = nx + (x if cmd == 'h' else 0); cur.append((x, y)); lc = None
        elif cmd in 'Vv':
            ny = num(); y = ny + (y if cmd == 'v' else 0); cur.append((x, y)); lc = None
        elif cmd in 'CcSs':
            if cmd in 'Cc':
                x1, y1, x2, y2, x3, y3 = [num() for _ in range(6)]
                if cmd == 'c': x1 += x; y1 += y; x2 += x; y2 += y; x3 += x; y3 += y
            else:
                x2, y2, x3, y3 = [num() for _ in range(4)]
                if cmd == 's': x2 += x; y2 += y; x3 += x; y3 += y
                x1, y1 = (2 * x - lc[0], 2 * y - lc[1]) if lc else (x, y)
            cur += cubic(np.array([x, y]), np.array([x1, y1]), np.array([x2, y2]), np.array([x3, y3]))
            lc = (x2, y2); x, y = x3, y3
        elif cmd in 'Zz':
            if cur: subs.append(cur)
            cur = []; x, y = sx, sy; lc = None
        else:
            i += 1
    if cur: subs.append(cur)
    return subs

# ── 圖層（之前依手繪稜線分好的 10 層，1280×720）──
z = np.load('layers6.npz')
VIS = z['vis'].astype(float); ALL = z['ALL'].astype(float); NAMES = [str(n) for n in z['names']]; KP = z['KP']
bx0, by0, bw, bh = z['box']
H, W = 720, 1280
owner = np.where(VIS.sum(0) > .5, VIS.argmax(0), -1)      # -1＝天空
def raster(subs):
    im = Image.new('L', (W, H), 0)
    for s in subs:
        if len(s) < 3: continue
        tmp = Image.new('L', (W, H), 0); ImageDraw.Draw(tmp).polygon([(px / 2, py / 2) for px, py in s], fill=255)
        im = Image.fromarray(np.bitwise_xor(np.asarray(im), np.asarray(tmp)))   # evenodd
    return np.asarray(im) > 127
rs = [raster(flatten(d)) for _, d in paths]
# 每個色塊實際看得到的部分（後畫的蓋住先畫的）
covered = np.zeros((H, W), bool); visible = [None] * len(rs)
for i in range(len(rs) - 1, -1, -1):
    visible[i] = rs[i] & ~covered; covered |= rs[i]
NL = len(NAMES)
assign = []   # 每個色塊：整塊屬於哪一層（-1＝天空，-2＝跨好幾層、要依層切開）
for i, (c, d) in enumerate(paths):
    v = visible[i]
    if v.sum() == 0: assign.append(-1); continue
    o = owner[v]
    sky = (o < 0).mean()
    if sky > .6: assign.append(-1); continue
    cnt = np.bincount(o[o >= 0], minlength=NL)
    top = int(cnt.argmax()); share = cnt[top] / max(1, cnt.sum())
    assign.append(top if (share >= .8 or v.sum() < 800) else -2)
print('whole', sum(a >= 0 for a in assign), 'sky', assign.count(-1), 'split', assign.count(-2))
# 細化後的歸屬：整塊分層的色塊照畫的順序蓋上去（邊界＝向量的邊）
own2 = owner.copy()
for i, a_ in enumerate(assign):
    if a_ >= 0: own2[rs[i]] = a_
own2[owner < 0] = -1
np.save('own2.npy', own2)
json.dump(dict(cls=cls, assign=assign, paths=[[c, d] for c, d in paths]), open('svgassign.json', 'w'))
pal = np.array([[90, 150, 255], [120, 200, 255], [255, 165, 50], [240, 120, 60], [255, 210, 90], [200, 140, 60], [70, 215, 120], [235, 75, 160], [165, 95, 235], [80, 230, 230]], float)
src = np.asarray(Image.open(r'C:\Users\Administrator\AppData\Local\Comfy-Desktop\ComfyUI-Shared\output\YUSHAN_TMP\gpt_style2fix_00002_.png').convert('RGB').resize((W, H))).astype(float)
img = np.where(own2[..., None] >= 0, src * .45 + pal[np.clip(own2, 0, 9)] * .55, src * .55)
Image.fromarray(img.clip(0, 255).astype(np.uint8)).save('own2.png')

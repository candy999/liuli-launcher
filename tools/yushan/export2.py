# 第三步：依遮擋邊＋使用者畫的主稜切割線分塊 → 每塊一張 (覆蓋, 受光, 霧) 圖 + 網格頂點的視差係數
import sys, gzip, struct, numpy as np
from PIL import Image, ImageDraw
TAU = .15
z = np.load('full3.npz'); dist = z['dist']; shade = z['shade']; elev = z['elev']
H, W = dist.shape          # 3 倍取樣（邏輯座標 ×3）
valid = ~np.isnan(dist)
ld = np.log(np.where(valid, dist, 1.0))

# 主稜切割線（在 936×405 的預覽座標上描的：主峰峰頂 → 右肩 → 往下到風口一帶的近稜 → 向右拐到畫面底）
CUT = [(505, 70), (512, 95), (520, 115), (530, 150), (545, 195), (545, 215), (530, 245), (490, 270), (450, 295), (420, 315),
       (410, 335), (425, 350), (470, 365), (540, 375), (620, 385), (700, 420)]
block = Image.new('L', (W, H), 0)
ImageDraw.Draw(block).line([(x * 2, y * 2) for x, y in CUT], fill=255, width=3)
block = np.array(block) > 0

okR = valid[:, :-1] & valid[:, 1:] & (np.abs(ld[:, :-1] - ld[:, 1:]) < TAU) & ~block[:, :-1] & ~block[:, 1:]
okD = valid[:-1, :] & valid[1:, :] & (np.abs(ld[:-1, :] - ld[1:, :]) < TAU) & ~block[:-1, :] & ~block[1:, :]
N = H * W
lab = np.arange(N).reshape(H, W); lab[~valid] = N
while True:
    old = lab; l = lab.copy()
    m = np.minimum(l[:, :-1], l[:, 1:]); l[:, :-1] = np.where(okR, m, l[:, :-1]); l[:, 1:] = np.where(okR, np.minimum(l[:, 1:], m), l[:, 1:])
    m = np.minimum(l[:-1, :], l[1:, :]); l[:-1, :] = np.where(okD, m, l[:-1, :]); l[1:, :] = np.where(okD, np.minimum(l[1:, :], m), l[1:, :])
    f = l.ravel()
    for _ in range(6): f = np.minimum(f, np.append(f, N)[f])
    lab = f.reshape(H, W)
    if np.array_equal(lab, old): break

# 小碎塊併進相鄰、距離最接近的大塊
MIN = 6000
def stats():
    ids, cnt = np.unique(lab[valid], return_counts=True)
    return dict(zip(ids.tolist(), cnt.tolist()))
for _ in range(30):
    st = stats(); small = [i for i, c in st.items() if c < MIN]
    if not small: break
    med = {i: np.median(ld[lab == i]) for i in st}
    for i in small:
        m = lab == i
        ring = (np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)) & ~m & valid
        nb = [j for j in np.unique(lab[ring]).tolist() if j != i and j != N]
        if not nb: lab[m] = N; valid[m] = False; continue
        j = min(nb, key=lambda j: abs(med[j] - med[i]))
        lab[m] = j
st = stats()
layers = sorted(st, key=lambda i: -np.median(dist[lab == i]))
print('layers', len(layers), [(int(np.median(dist[lab == i])), st[i]) for i in layers])

# 霧：空氣透視（距離）＋谷底積霧（越低越濃）
aer = 1 - np.exp(-np.nan_to_num(dist, nan=1e6) / 14000)
vf = np.clip((3300 - np.nan_to_num(elev, nan=4000)) / 1100, 0, 1) * .55 * np.clip((np.nan_to_num(dist) - 800) / 2500, 0, 1)
fog = 1 - (1 - aer) * (1 - vf)
lit = np.nan_to_num(shade) * (1 - fog)

def down(a):   # 3 倍 → 1.5 倍（2×2 平均）
    return a.reshape(H // 2, 2, W // 2, 2).mean((1, 3))
h, w = H // 2, W // 2
covs = [down((lab == i).astype(float)) for i in layers]
GRID = 12
out = bytearray(b'YSR2') + struct.pack('>if', len(layers), 1.5)
prev = np.zeros((h, w, 3)) + np.array([14, 18, 30])
for k, i in enumerate(layers):
    m = (lab == i).astype(float)
    cov = covs[k]
    den = np.maximum(down(m), 1e-6)
    L = down(lit * m) / den; F = down(fog * m) / den; LD = down(ld * m) / den
    have = cov > 0
    # 被更近的塊蓋住的地方往外延伸（視差移動時露出來的縫有東西補）
    nearer = np.zeros((h, w))
    for c in covs[k + 1:]: nearer = np.maximum(nearer, c)
    grow = have.copy(); vals = [L, F, LD]
    for _ in range(14):
        nb = np.zeros((h, w)); acc = [np.zeros((h, w)) for _ in vals]
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            g = np.roll(np.roll(grow, dy, 0), dx, 1)
            nb += g
            for a, v in zip(acc, vals): a += np.roll(np.roll(np.where(grow, v, 0), dy, 0), dx, 1)
        new = (nb > 0) & ~grow & (nearer > .02)
        for a, v in zip(acc, vals): v[new] = a[new] / nb[new]
        cov = np.where(new, 1.0, cov); grow |= new
    cov = np.where(grow & (nearer > .5), 1.0, cov)
    ys, xs = np.where(cov > .002)
    y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    bw, bh = x1 - x0, y1 - y0
    # 網格頂點的視差係數：依那一點的距離（真實視差與距離成反比；整塊的距離用擴散補滿再平滑）
    ldf = np.where(grow, LD, np.nan)[y0:y1, x0:x1]
    for _ in range(400):
        if not np.isnan(ldf).any(): break
        p = np.pad(ldf, 1, mode='edge'); stk = np.stack([p[:-2, 1:-1], p[2:, 1:-1], p[1:-1, :-2], p[1:-1, 2:]])
        fill = np.nanmean(stk, 0)
        ldf = np.where(np.isnan(ldf), fill, ldf)
    ldf = np.nan_to_num(ldf, nan=np.log(5000))
    gw = bw // GRID + 2; gh = bh // GRID + 2
    gx = np.clip(np.linspace(0, bw - 1, gw).round().astype(int), 0, bw - 1)
    gy = np.clip(np.linspace(0, bh - 1, gh).round().astype(int), 0, bh - 1)
    d = np.exp(ldf[np.ix_(gy, gx)])
    K = .004 + .11 * (150 / np.maximum(d, 150)) ** .75
    med = float(np.median(dist[lab == i]))
    cy = np.where(cov > .5)[0]
    meanY = float(cy.mean() / 1.5) if len(cy) else float(y0 / 1.5)
    A = np.clip(cov[y0:y1, x0:x1] * 255 + .5, 0, 255).astype(np.uint8)
    R_ = np.clip(L[y0:y1, x0:x1] * 255 + .5, 0, 255).astype(np.uint8)
    G_ = np.clip(F[y0:y1, x0:x1] * 255 + .5, 0, 255).astype(np.uint8)
    out += struct.pack('>iiiiiiff', x0, y0, bw, bh, gw, gh, med, meanY) + K.astype('>f4').tobytes() + A.tobytes() + R_.tobytes() + G_.tobytes()
    print(k, 'depth', int(med), 'box', x0, y0, bw, bh, 'grid', gw, gh, 'K', round(float(K.min()), 3), round(float(K.max()), 3))
    # 預覽合成
    body = np.array([10, 13, 20]); haze = np.array([95, 108, 135]); lc = np.array([215, 190, 160])
    col = body + (haze - body) * F[..., None] + lc * L[..., None]
    a = cov[..., None]
    prev = prev * (1 - a) + col * a
gz = gzip.compress(bytes(out), 9)
open('yushan.bin', 'wb').write(gz)
print('raw', len(out), 'gz', len(gz))
Image.fromarray(prev.clip(0, 255).astype(np.uint8)).save('prev2.png')
# 分塊視覺化
rng = np.random.default_rng(5); img = np.zeros((H, W, 3))
for i in layers: msk = lab == i; img[msk] = rng.integers(60, 255, 3) * (.4 + .6 * np.nan_to_num(shade)[msk])[:, None]
img[block] = [255, 40, 40]
Image.fromarray(img.astype(np.uint8)).resize((936, 405)).save('cc2.png')

# 玉山北峰視角的分層渲染：10 m 侵蝕細節地形 + 側光明暗
import sys, math, os, numpy as np
from load import load
DEM = load(sys.argv[1])
LAT0, LON0 = 23.4875, 120.95944
_r = int(round((24 - LAT0) * 3600)); _c = int(round((LON0 - 120) * 3600))
EYE = DEM[_r-1:_r+2, _c-1:_c+2].max() + 3
KX = 111320 * math.cos(math.radians(LAT0)); KY = 110570
AZ, FOV, MARG, PXDEG = 185.0, 60.0, .15, 8.2
TOPY = .2   # 主峰峰頂在畫面高度的 20%
TOTAL = FOV * (1 + 2 * MARG)
SC = float(os.environ.get('RSC', '1.5')); WL, HL = 480 * 1.3, 270.0
WPX, HPX = int(WL * SC), int(HL * SC)
SUN_AZ, SUN_EL = 95.0, 18.0
rng = np.random.default_rng(7)

def bilin(g, r, c):
    r = np.clip(r, 0, g.shape[0] - 1.001); c = np.clip(c, 0, g.shape[1] - 1.001)
    r0 = r.astype(int); c0 = c.astype(int); fr = r - r0; fc = c - c0
    return (g[r0, c0] * (1 - fc) + g[r0, c0 + 1] * fc) * (1 - fr) + (g[r0 + 1, c0] * (1 - fc) + g[r0 + 1, c0 + 1] * fc) * fr

def cubic_up(a, f):
    # Catmull-Rom 放大（可分離）
    def up1(a, axis):
        n = a.shape[axis]; t = (np.arange(n * f) + .5) / f - .5
        i = np.floor(t).astype(int); u = t - i
        idx = [np.clip(i + k, 0, n - 1) for k in (-1, 0, 1, 2)]
        w = [(-u**3 + 2*u**2 - u) / 2, (3*u**3 - 5*u**2 + 2) / 2, (-3*u**3 + 4*u**2 + u) / 2, (u**3 - u**2) / 2]
        sh = [1] * a.ndim; sh[axis] = -1
        return sum(np.take(a, idx[k], axis=axis) * w[k].reshape(sh) for k in range(4))
    return up1(up1(a, 0), 1)

# ── 高解析區（10 m）：玉山主峰、東峰、北峰、南峰一帶 ──
LATN, LATS, LONW, LONE = 23.505, 23.415, 120.915, 121.005
r0 = int((24 - LATN) * 3600); r1 = int((24 - LATS) * 3600); c0 = int((LONW - 120) * 3600); c1 = int((LONE - 120) * 3600)
F = 5   # 每格 1/5 弧秒 ≈ 6 m
Q = F / 3
hi = cubic_up(DEM[r0:r1, c0:c1].astype(np.float64), F)   # 每格 1/3 弧秒 ≈ 10 m
def box(h, k):
    c = np.cumsum(np.cumsum(np.pad(h, ((k + 1, k), (k + 1, k)), mode='edge'), 0), 1)
    n = 2 * k + 1
    return (c[n:, n:] - c[:-n, n:] - c[n:, :-n] + c[:-n, :-n]) / (n * n)
# SRTM 把尖峰與稜線磨圓了：用反銳化把稜脊與峰頂收尖（約 70 m 尺度）
hi = hi + np.clip(.8 * (hi - box(box(hi, int(3 * Q)), int(3 * Q))), -20, 20)   # 限幅：不讓單點雜訊變成針
cell_y = KY / 3600 / F; cell_x = KX / 3600 / F
def grad(h):
    gy, gx = np.gradient(h); return gx / cell_x, -gy / cell_y   # 東、北方向的坡度
gx, gy = grad(hi)
slope = np.hypot(gx, gy)
# 下坡方向（格點座標：列往南為正）
dirc = -gx / (slope + 1e-6); dirr = gy / (slope + 1e-6)

def lic(noise, steps):
    # 沿下坡方向的線積分卷積：白雜訊被拉成順著坡面流下的條紋
    H_, W_ = noise.shape
    rr, cc = np.mgrid[0:H_, 0:W_].astype(np.float64)
    acc = noise.copy(); cnt = 1
    for sgn in (1, -1):
        r, c = rr.copy(), cc.copy()
        for _ in range(steps):
            dr = bilin(dirr, r, c); dc = bilin(dirc, r, c)
            r += sgn * dr; c += sgn * dc
            acc += bilin(noise, r, c); cnt += 1
    v = acc / cnt
    return (v - v.mean()) / v.std()

def blob(cell, shape):
    return cubic_up(rng.random((shape[0] // cell + 2, shape[1] // cell + 2)), cell)[:shape[0], :shape[1]]
fine = lic(rng.random(hi.shape), int(8 * Q))
mid = lic(blob(int(3 * Q), hi.shape), int(16 * Q))
coarse = lic(blob(int(12 * Q), hi.shape), int(30 * Q))
# 侵蝕強弱在大範圍內也有變化（有的坡面溝深、有的平順）
var = blob(int(40 * Q), hi.shape); var = np.clip((var - var.min()) / (var.max() - var.min()) * 1.6 - .3, 0, 1)
steep = np.clip((slope - .35) / .7, 0, 1)
ero = steep * (16.0 * (.45 - np.abs(np.tanh(coarse))) * (.4 + .6 * var) + 5.0 * (.45 - np.abs(np.tanh(mid))) * var + .8 * fine)
# 平緩的稜頂與鞍部：岩塊與玉山圓柏灌叢的起伏（不分方向的小顆粒），近看才不會是一片光滑
lump = blob(2, hi.shape) - .5
ero += (1 - steep) * (2.4 * lump + .8 * (rng.random(hi.shape) - .5))
hi_e = hi + ero
# 眼睛的高度以收尖、侵蝕後的地形為準（北峰山頂 30 m 內的最高點再高 4 m）
_vr = int((LATN - LAT0) * 3600 * F); _vc = int((LON0 - LONW) * 3600 * F)
EYE = hi_e[_vr - 3:_vr + 4, _vc - 3:_vc + 4].max() + 4

def shade_of(h, cx, cy):
    gy_, gx_ = np.gradient(h); sx = gx_ / cx; sy = -gy_ / cy
    n = np.stack([-sx, -sy, np.ones_like(h)]); n /= np.linalg.norm(n, axis=0)
    a, e = math.radians(SUN_AZ), math.radians(SUN_EL)
    s = np.array([math.sin(a) * math.cos(e), math.cos(a) * math.cos(e), math.sin(e)])
    lam = np.clip(np.tensordot(s, n, 1), 0, 1)
    amb = n[2]
    lap = (np.roll(h, 1, 0) + np.roll(h, -1, 0) + np.roll(h, 1, 1) + np.roll(h, -1, 1) - 4 * h) / (cx * cy)
    cav = np.clip(lap * 25, -1, 1)          # 凹處（溝）> 0
    v = (.78 * lam + .22 * amb) * (1 - .45 * np.clip(cav, 0, 1)) + .12 * np.clip(-cav, 0, 1)
    return np.clip(v, 0, 1)
shade_hi = shade_of(hi_e, cell_x, cell_y)
shade_lo = shade_of(DEM.astype(np.float64), KX / 3600, KY / 3600)

def sample(x, y, which):
    lat = LAT0 + y / KY; lon = LON0 + x / KX
    inside = (lat < LATN) & (lat > LATS) & (lon > LONW) & (lon < LONE)
    rh = (LATN - lat) * 3600 * F - .5; ch = (lon - LONW) * 3600 * F - .5
    rl = (24 - lat) * 3600; cl = (lon - 120) * 3600
    g_hi, g_lo = (hi_e, DEM) if which == 'h' else (shade_hi, shade_lo)
    out = bilin(g_lo, rl, cl)
    if inside.any(): out[inside] = bilin(g_hi, rh[inside], ch[inside])
    return out

# 射線取樣：近處 4 m 一步，越遠越疏
D = 60 * 1.0025 ** np.arange(0, 2700); D = D[D < 50000]
az = np.radians(AZ - TOTAL / 2 + (np.arange(WPX) + .5) / WPX * TOTAL)
X = np.outer(np.sin(az), D); Y = np.outer(np.cos(az), D)
Hh = sample(X, Y, 'h')
PHI = np.degrees(np.arctan2(Hh - EYE - D**2 / (2 * 6371000) * .87, D))
vis = slice(int(WPX * MARG / (1 + 2 * MARG)), WPX - int(WPX * MARG / (1 + 2 * MARG)))
TOP = PHI[vis].max()
THETA = TOP - ((np.arange(HPX) + .5) / SC - HL * TOPY) / PXDEG   # 每一列對應的仰角

WITH_ELEV = False
def render(a, b):
    """距離帶 [a, b) 內的地形：每像素的明暗（沒打到 = nan）、每欄的稜線 y（邏輯座標）、命中距離。"""
    m = (D >= a) & (D < b)
    ph = np.where(m[None, :], PHI, -90.0)
    cm = np.maximum.accumulate(ph, axis=1)
    shade = np.full((HPX, WPX), np.nan); dist = np.full((HPX, WPX), np.nan); elev = np.full((HPX, WPX), np.nan)
    for j in range(WPX):
        idx = np.searchsorted(cm[j], THETA[::-1])[::-1]   # cm 遞增；THETA 由上往下遞減
        hit = idx < len(D)
        ii = idx[hit]
        sh = sample(X[j, ii], Y[j, ii], 's')
        shade[hit, j] = sh; dist[hit, j] = D[ii]; elev[hit, j] = Hh[j, ii]
    sil = HL * TOPY + (TOP - cm[:, -1]) * PXDEG
    return (shade, sil, dist, elev) if WITH_ELEV else (shade, sil, dist)

if __name__ == '__main__':
    from PIL import Image
    shade, sil, dist = render(0, 1e9)
    np.save('full_dist.npy', dist); np.save('full_shade.npy', shade)
    img = np.where(np.isnan(shade), 0, shade)
    # 空氣透視預覽
    fog = 1 - np.exp(-np.nan_to_num(dist, nan=1e9) / 9000)
    rgb = np.stack([img * (1 - fog) * 230 + fog * 120 + (np.isnan(shade)) * 20] * 3, -1)
    Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8)).save('full.png')
    print('EYE', EYE, 'TOP', TOP)

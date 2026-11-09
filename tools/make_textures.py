"""
產生背景紋理的無接縫貼圖：app/src/main/res/drawable-nodpi/tex_<id>.png（灰階＋透明度）。

每種紋理都由「高度場」（打光後的浮雕）與「反照率」（本身的明暗）組成，全部是週期性的（上下左右可以無縫重複）。
每個像素存成「白色＋透明度」或「黑色＋透明度」：疊在背景上＝亮處提亮、暗處壓暗，
只需要一般的透明度混合（不用 soft-light 之類昂貴的混合模式）。執行時再乘上使用者設定的不透明度。

  fiber      纖維紙：細顆粒＋細短纖維＋淡淡的斑駁（預設，最安靜）
  cold       冷壓水彩紙：一顆顆隆起的紙紋（tooth），浮雕最明顯
  washi      和紙：長而半透明的纖維、幾乎沒有起伏、雲狀的厚薄
  linen      亞麻畫布：細密的經緯編織，紗線粗細不均
  film       底片顆粒：純顆粒、沒有浮雕（順便打散深色漸層的色帶）
  frost      磨砂玻璃：細小的凹點與零星的閃光（最接近「琉璃」）
  recycled   再生紙：短纖維＋深淺不一的雜質斑點＋明顯的斑駁

用法：python tools/make_textures.py [id ...]   （需要 numpy、Pillow；不給 id 就全部產生）
"""
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi')


# ───────── 共用 ─────────

def blur(a, sigma):
    """週期邊界的高斯模糊（FFT）：結果天生無接縫。"""
    n, m = a.shape
    fy = np.fft.fftfreq(n)[:, None]
    fx = np.fft.fftfreq(m)[None, :]
    g = np.exp(-2 * (np.pi * sigma) ** 2 * (fx ** 2 + fy ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(a) * g))


def norm(a):
    a = a - a.mean()
    return a / (np.abs(a).max() + 1e-9)


def noise(rng, n, sigma):
    return norm(blur(rng.standard_normal((n, n)), sigma))


def emboss(h, k=0.7):
    """左上方光源的浮雕明暗（-1～1）。"""
    p = np.pad(h, 1, mode='wrap')
    gy, gx = np.gradient(p)
    s = -(gx[1:-1, 1:-1] * k + gy[1:-1, 1:-1] * k)
    return s / (np.percentile(np.abs(s), 99.5) + 1e-9)


def strokes(rng, n, count, length, bend, width_sigma, sign_p=0.62, amp=(0.2, 1.0)):
    """隨機方向的細弧線（纖維），雙線性拆到像素上（反鋸齒），座標取模＝無接縫。"""
    out = np.zeros((n, n))
    for _ in range(count):
        x, y = rng.uniform(0, n, 2)
        a = rng.uniform(0, np.pi)
        L = rng.uniform(*length)
        b = rng.uniform(-bend, bend)
        sign = 1.0 if rng.uniform() < sign_p else -0.7
        w = sign * rng.uniform(*amp) ** 2
        for _ in range(int(L * 2)):
            a += b / 2
            x += np.cos(a) / 2
            y += np.sin(a) / 2
            ix, iy = int(np.floor(x)), int(np.floor(y))
            fx, fy = x - ix, y - iy
            out[iy % n, ix % n] += w * (1 - fx) * (1 - fy) / 2
            out[iy % n, (ix + 1) % n] += w * fx * (1 - fy) / 2
            out[(iy + 1) % n, ix % n] += w * (1 - fx) * fy / 2
            out[(iy + 1) % n, (ix + 1) % n] += w * fx * fy / 2
    return norm(blur(out, width_sigma))


def save(name, dev, max_alpha=0.5):
    """dev：-1～1 的明暗偏移 → 白／黑＋透明度。"""
    dev = np.clip(dev, -1, 1)
    alpha = np.abs(dev) * max_alpha
    lum = np.where(dev > 0, 255, 0)
    img = np.stack([lum, (alpha * 255).round()], axis=-1).astype(np.uint8)
    path = os.path.join(OUT_DIR, f'tex_{name}.png')
    Image.fromarray(img, 'LA').save(path, optimize=True)
    print(f'{name:9s} {img.shape[0]}×{img.shape[1]}  {os.path.getsize(path) // 1024} KB', file=sys.stderr)


# ───────── 各種紋理 ─────────

def fiber():
    n, r = 512, np.random.default_rng(20261109)
    grain = noise(r, n, 0.6)
    fib = strokes(r, n, 520, (18, 90), 0.02, 0.45)
    mottle = noise(r, n, 24)
    h = 0.5 * grain + 0.55 * fib + 0.2 * mottle
    return 0.55 * emboss(h) + 0.3 * fib + 0.22 * mottle + 0.12 * grain


def cold():
    n, r = 512, np.random.default_rng(31)
    # 紙紋：中頻雜訊經過 tanh 變成一顆顆平頂的隆起，再加一層更細的
    bumps = np.tanh(1.6 * noise(r, n, 2.2))
    fine = noise(r, n, 0.9)
    mottle = noise(r, n, 40)
    h = 0.7 * bumps + 0.35 * fine + 0.1 * mottle
    return 0.5 * emboss(h, 0.8) + 0.08 * bumps + 0.1 * mottle


def washi():
    n, r = 512, np.random.default_rng(47)
    long_fib = strokes(r, n, 170, (90, 240), 0.004, 0.45, sign_p=0.92, amp=(0.25, 0.9))
    thin = strokes(r, n, 700, (12, 45), 0.01, 0.35, sign_p=0.85, amp=(0.1, 0.5))
    cloud = noise(r, n, 26)
    h = 0.2 * long_fib + 0.15 * thin
    # 和紙幾乎是平的：主要是纖維比較亮（半透明）＋厚薄不均的雲狀明暗
    return 0.2 * emboss(h) + 0.4 * long_fib + 0.3 * thin + 0.16 * cloud


def linen():
    n, r = 512, np.random.default_rng(59)
    yy, xx = np.mgrid[0:n, 0:n].astype(float)
    threads = 80  # 512／80＝6.4 像素一條紗（整除週期，無接縫）
    # 每條紗的位置與粗細有一點不規則
    jx = 1.8 * noise(r, n, 14)
    jy = 1.8 * noise(r, n, 14)
    thick_h = 0.6 + 0.4 * noise(r, n, 6)
    thick_v = 0.6 + 0.4 * noise(r, n, 6)
    py = 2 * np.pi * threads * (yy + jy) / n
    px = 2 * np.pi * threads * (xx + jx) / n
    weft = np.clip(np.cos(py), 0, 1) ** 0.6 * thick_h   # 橫紗的截面
    warp = np.clip(np.cos(px), 0, 1) ** 0.6 * thick_v   # 直紗的截面
    # 上下交錯：棋盤格決定這一格是橫紗在上還是直紗在上
    cell = (np.floor(threads * (xx + jx) / n) + np.floor(threads * (yy + jy) / n)) % 2
    h = np.where(cell == 0, np.maximum(weft, 0.55 * warp), np.maximum(warp, 0.55 * weft))
    h = blur(h, 0.5) + 0.25 * noise(r, n, 0.8) + 0.2 * noise(r, n, 30)
    slub = noise(r, n, 3)  # 紗線上粗細不均的「竹節」
    return 0.42 * emboss(h, 0.75) + 0.3 * slub * (0.5 + 0.5 * h) + 0.12 * noise(r, n, 30)


def film():
    n, r = 256, np.random.default_rng(71)
    # 顆粒：極細的雜訊，亮暗兩種顆粒；在中頻上稍微成團
    g = blur(r.standard_normal((n, n)), 0.55)
    clump = 0.7 + 0.3 * noise(r, n, 3)
    return norm(g * clump) * 1.1


def frost():
    n, r = 512, np.random.default_rng(83)
    # 細小的凹點：稀疏的負高度小坑，打光後是一邊亮一邊暗的小點
    pits = np.zeros((n, n))
    idx = r.integers(0, n, (3200, 2))
    pits[idx[:, 0], idx[:, 1]] = -r.uniform(0.4, 1.0, 3200)
    pits = norm(blur(pits, 1.4))
    sand = noise(r, n, 0.9)
    # 零星的閃光（亮點）
    sparkle = np.zeros((n, n))
    idx = r.integers(0, n, (420, 2))
    sparkle[idx[:, 0], idx[:, 1]] = r.uniform(0.4, 1.0, 420)
    sparkle = blur(sparkle, 0.75)
    sparkle = np.clip(sparkle / (np.percentile(sparkle[sparkle > 0], 99) + 1e-9), 0, 1)
    h = 0.8 * pits + 0.3 * sand
    return 0.95 * emboss(h, 0.7) + 0.2 * sand + 1.0 * sparkle


def recycled():
    n, r = 512, np.random.default_rng(97)
    fib = strokes(r, n, 900, (6, 30), 0.05, 0.45, sign_p=0.55)
    grain = noise(r, n, 0.7)
    mottle = noise(r, n, 18)
    # 雜質：大小不一的深色與淺色斑點
    flecks = np.zeros((n, n))
    for count, s, val in ((220, 0.9, -1.0), (90, 1.6, -0.8), (320, 1.0, 1.0), (60, 1.8, 0.7)):
        part = np.zeros((n, n))
        idx = r.integers(0, n, (count, 2))
        part[idx[:, 0], idx[:, 1]] = val * r.uniform(0.5, 1.0, count)
        flecks += blur(part, s) * (s * s * 6)
    flecks = np.clip(flecks, -1, 1)
    h = 0.4 * grain + 0.5 * fib + 0.25 * mottle
    return 0.45 * emboss(h) + 0.25 * fib + 0.14 * mottle + 1.3 * flecks + 0.1 * grain


ALL = {'fiber': fiber, 'cold': cold, 'washi': washi, 'linen': linen, 'film': film, 'frost': frost, 'recycled': recycled}

if __name__ == '__main__':
    os.makedirs(OUT_DIR, exist_ok=True)
    for name in (sys.argv[1:] or ALL):
        save(name, ALL[name]())

"""
產生「紙張紋理」無接縫貼圖：app/src/main/res/drawable-nodpi/paper_tile.png（512×512，灰階＋透明度）。

紙的質感來自三層高度場，全部是週期性的（上下左右可以無縫重複）：
- 細顆粒：σ≈0.6px 的雜訊（紙漿）
- 纖維：隨機方向的細短弧線，有的亮（浮起）有的暗（凹陷）
- 斑駁：σ≈24px 的低頻起伏，很淡（手工紙的厚薄不均）
再以左上方光源做浮雕打光。每個像素存成「白色＋透明度」或「黑色＋透明度」：
疊在背景上＝亮處提亮、暗處壓暗，不需要 soft-light 之類昂貴的混合模式。

用法：python tools/make_paper.py   （需要 numpy、Pillow）
"""
import os
import sys

import numpy as np
from PIL import Image

N = 512
rng = np.random.default_rng(20261109)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'drawable-nodpi', 'paper_tile.png')


def blur_periodic(a, sigma):
    """週期邊界的高斯模糊（FFT），結果天生無接縫。"""
    f = np.fft.fftfreq(N)
    g = np.exp(-2 * (np.pi * sigma) ** 2 * (f[:, None] ** 2 + f[None, :] ** 2))
    return np.real(np.fft.ifft2(np.fft.fft2(a) * g))


def norm(a):
    a = a - a.mean()
    return a / (np.abs(a).max() + 1e-9)


# 1. 細顆粒
grain = norm(blur_periodic(rng.standard_normal((N, N)), 0.6))

# 2. 纖維：細短弧線，用超取樣畫在週期座標上
fib = np.zeros((N, N))
for _ in range(520):
    x, y = rng.uniform(0, N, 2)
    ang = rng.uniform(0, np.pi)
    length = rng.uniform(18, 90)
    bend = rng.uniform(-0.02, 0.02)
    sign = 1.0 if rng.uniform() < 0.62 else -0.7  # 多數纖維浮起，少數凹陷
    amp = sign * rng.uniform(0.2, 1.0) ** 2
    steps = int(length * 3)
    a = ang
    for s in range(steps):
        a += bend / 3
        x += np.cos(a) / 3
        y += np.sin(a) / 3
        # 雙線性拆到四個像素（反鋸齒），座標取模＝無接縫
        ix, iy = int(np.floor(x)), int(np.floor(y))
        fx, fy = x - ix, y - iy
        for dx, dy, w in ((0, 0, (1 - fx) * (1 - fy)), (1, 0, fx * (1 - fy)), (0, 1, (1 - fx) * fy), (1, 1, fx * fy)):
            fib[(iy + dy) % N, (ix + dx) % N] += amp * w / 3
fib = norm(blur_periodic(fib, 0.45))

# 3. 斑駁
mottle = norm(blur_periodic(rng.standard_normal((N, N)), 24))

height = 0.5 * grain + 0.55 * fib + 0.2 * mottle

# 浮雕打光：光從左上來，法線與光的夾角決定明暗；再加上纖維本身略亮的反照率
gy, gx = np.gradient(np.pad(height, 1, mode='wrap'))
gx = gx[1:-1, 1:-1]
gy = gy[1:-1, 1:-1]
shade = -(gx * 0.7 + gy * 0.7)
shade = shade / (np.percentile(np.abs(shade), 99.5) + 1e-9)
albedo = 0.3 * fib + 0.22 * mottle + 0.12 * grain
dev = np.clip(0.55 * shade + albedo, -1, 1)

# 白＋α 或 黑＋α；最大不透明度 0.5（執行時再乘上強度，最強約 0.16）
alpha = np.abs(dev) * 0.5
lum = np.where(dev > 0, 255, 0)
img = np.stack([lum, (alpha * 255).round()], axis=-1).astype(np.uint8)
os.makedirs(os.path.dirname(OUT), exist_ok=True)
Image.fromarray(img, 'LA').save(OUT, optimize=True)
print(OUT, os.path.getsize(OUT), 'bytes', file=sys.stderr)

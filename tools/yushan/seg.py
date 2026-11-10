# 第二步：全景渲染（3 倍取樣）→ 依「遮擋邊」切成幾塊連續的山體
import sys, os, numpy as np
os.environ['RSC'] = '3'
import render as R
R.WITH_ELEV = True
from PIL import Image
shade, sil, dist, elev = R.render(0, 1e9)
np.savez_compressed('full3.npz', shade=shade, dist=dist, elev=elev, sil=sil)
print('saved', shade.shape)

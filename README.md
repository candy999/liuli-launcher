<p align="center">
  <img src="docs/logo/banner.svg" width="640" alt="琉璃 Launcher">
</p>

<p align="center">
  <b>為 Google TV／Android TV 打造的主畫面：玻璃質感、跟著焦點流動的背景、內建農民曆，流暢第一。</b>
</p>

<p align="center">
  <a href="https://github.com/candy999/liuli-launcher/releases/latest">下載 1.0.0 穩定版</a> ·
  <a href="docs/releases/v1.0.0.md">版本說明</a> ·
  <a href="docs/ux-design.md">UX 設計</a> ·
  <a href="LICENSE">MIT 授權</a>
</p>

![主畫面](docs/screenshots/home.png)

> **1.0.0 是第一個穩定版。** 已在 AOC 4K 電視（Realtek RTD6748、Android 11、1.7 GB RAM）上完整驗收，也在 MEmu 模擬器（Android 9）上測過。其他機型歡迎[回報問題](https://github.com/candy999/liuli-launcher/issues)。

## 特色

### 主畫面

時間、日期、農曆、時辰、初一十五提示、今日宜忌放在左上；天氣在右上；網路狀態（無線／有線／斷線）與設定在右上角。中間是訊號源，下面是常用 App，按下鍵展開所有 App。

- 文字直接放在背景上，**只有選到的時候才浮出玻璃**；App 磚不加框，選到時放大、浮起、帶一道斜向光澤（tvOS 的做法）
- 時鐘按確定開月曆、天氣按確定開天氣頁，返回鍵回到原本的位置

| 時鐘被選到（浮出玻璃） | 所有 App |
|---|---|
| ![時鐘焦點](docs/screenshots/focus-clock.png) | ![所有 App](docs/screenshots/drawer.png) |

### 跟著焦點流動的背景

選到哪個 App，背景就慢慢染成那個 App 的主題色。顏色與形狀的變化經過「電容式」緩衝：停留 0.18 秒才開始反應、兩級 RC 平滑、不過衝，快速掃過也不會閃來閃去；連續按著方向時最多 0.45 秒跟一次。

12 種向量背景（設定 → 背景樣式，上下移動即時預覽）：

![12 種背景樣式](docs/screenshots/styles.png)

### 液態玻璃

不用框線、不用高光邊，邊緣靠光：玻璃取用背景的**高斯模糊**（三次盒狀模糊）再加上**過曝**與提高飽和度。模糊在背景執行緒計算，不佔用畫面的主執行緒；Android 13 以上再加邊緣透鏡折射。

### 背景質感：模糊與紙張紋理

設定 → 背景質感（左右鍵即時調整）：

- **背景模糊**（6 級，σ 8～48 像素）：模糊越強反而**越省效能**。背景改在背景執行緒畫成很小的圖、在小圖上模糊，GPU 只放大一張圖，向量完全不上 GPU。
  能縮多小有實驗依據：小圖上的模糊量 ≥ 2 個小圖像素時，和原生解析度高斯模糊的差異 ≤ 3／255，看不出來、也不會有鋸齒（[實驗紀錄](docs/blur-shortcut.md)）。
- **紙張紋理**（5 級）：預先產生的無接縫纖維紙貼圖（細顆粒＋纖維＋淡淡的斑駁，左上打光的浮雕），以**原生解析度**疊在背景上、所有介面之下，不受背景降解析度或模糊影響。
  焦點移動時紋理跟著輕輕反向位移（視差），像背景畫在一張紙上；位移取整數像素，紋理不會被抹糊。只用一般透明度混合（不用 soft-light），每幀只多一個貼圖四邊形。

### 農民曆與月曆

| 月曆（宜忌最多的日子也放得下） | 天氣 |
|---|---|
| ![月曆](docs/screenshots/calendar.png) | ![天氣](docs/screenshots/weather.png) |

- 農曆日期、干支生肖、節氣、宜忌、沖煞、**吉時**，主畫面顯示**目前時辰**，全部離線計算（[lunar-java](https://github.com/6tail/lunar-java)）
- **農曆以子時換日**：晚上 11 點起，宜忌與初一十五提示就算下一天（國曆節日仍照國曆）
- 全部用字**正體中文**：1901～2099 每一天的宜忌、月份、節氣、生肖、沖煞、時辰都稽核過，沒有殘留簡體字；閏月正確顯示為「閏」
- 宜忌以詞為單位換行、依字數自動選字級：最長的一天（宜 31 項、合計 107 字）也完整顯示
- 台灣節日 30 多個（春節、元宵、清明、端午、中元、中秋、冬至、尾牙、除夕…），初一十五以暖金色標示
- 月曆用方向鍵選日期，越過月份邊界自動翻月

### 天氣

[Open-Meteo](https://open-meteo.com/)，免金鑰。目前天氣、逐 3 小時、7 日預報。地點在**設定 → 地點**選縣市，只用縣市中心座標，不讀取裝置定位。

### 邊框

設定 → 邊框：黑框粗細、圓角半徑、往內漸變範圍（左右鍵即時調整；粗細 0 dp＝只要圓角）。黑框蓋在背景與左右捲動的列上，**不會遮住**時鐘、天氣等固定介面，所有 App 與設定面板則蓋在黑框上面。黑框永遠以原生解析度繪製。

| 邊框 24 dp・圓角 48 dp・漸變 64 dp | 設定面板 |
|---|---|
| ![邊框](docs/screenshots/frame.png) | ![設定](docs/screenshots/settings.png) |

### App 管理

- **常用 App**：在所有 App 長按 → 加入常用；在常用列長按 → **移動位置**（抓住後左右移動、確定放下、返回取消）或從常用移除。常用全部移除後會出現「＋ 加入常用」磚，不會卡住
- **顯示／隱藏 App**：原廠主畫面不列出的手機版 App 也會出現（標示「手機版」），可以自己決定顯示或隱藏
- 長按選單也有應用程式資訊（強制停止、清除快取）與解除安裝

![移動位置](docs/screenshots/move.png)

### 遙控器按鍵與主畫面保護

- **改綁按鍵**：任何按鍵的短按或長按都可以改成開某個 App、回到琉璃、開訊號源、返回、最近使用、系統設定…（例如把 Netflix 鍵改成開別的 App）
- **受保護的基本鍵**：方向、確定、返回、Home、音量、電源不能被改綁，學習模式也會放行，不會把遙控器綁壞
- **主畫面保護**：原廠主畫面跳出來時自動切回琉璃
- **不用電腦也能開啟**：很多電視沒有無障礙設定畫面，琉璃內建網路 ADB 用戶端，可以對自己下指令開啟按鍵服務（設定 → 遙控器按鍵 → 啟用按鍵服務）

這個服務只做兩件事：攔截按鍵、取得前景 App 的套件名稱（偵測原廠主畫面）。不讀取畫面內容，也不連網。

## 流暢度

在入門電視晶片（PowerVR BXE-4-32）上實測調校過：

| | |
|---|---|
| **靜止時零耗電** | 背景追上目標就停止重畫，畫面靜止時 GPU 閒置 |
| **操作優先** | 按鍵後 0.32 秒內，背景改用省力模式（÷3、15fps、速度不變），GPU 留給焦點動畫與捲動 |
| **自適應畫質** | 背景解析度＝原生 ÷1～÷5（整數倍，每個像素對齊 d×d 個實體像素），依實際的背景格間隔自動升降；降解析度沒用的樣式改降更新率（30／20／15fps） |
| **提前對焦** | 依剩餘動畫時間估算，停止前就逐檔升回原生解析度，不會一停下來突然變清楚 |
| **慢速播放** | 背景低於 15fps 時每格最多推進 1/15 秒，變慢立即、變快緩慢，不會忽快忽慢 |
| **效能驗收** | 12 種背景在 ÷2 全部穩定 30fps；最重的光軌從每格 200ms 優化到 12ms |

## 安裝

1. 下載 [Releases](https://github.com/candy999/liuli-launcher/releases/latest) 上的 APK。
2. 安裝到電視，二擇一：
   - **用電腦 ADB**：
     ```bash
     adb connect <電視IP>:5555
     ```
     ```bash
     adb install -r LiuliLauncher-1.0.0.apk
     ```
   - **不用電腦**：在電視上裝 Downloader，輸入 Release 頁面上的 APK 網址。
3. 設為預設主畫面：**設定 → 預設主畫面**。很多 Google TV 沒有這個選項，可以改用以下任一方式：
   - 開啟**主畫面保護**（設定 → 遙控器按鍵），按 Home 鍵就會回到琉璃。
   - 用 ADB **停用**原廠主畫面（不是刪除，隨時可以還原）：
     ```bash
     adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
     ```
     還原：
     ```bash
     adb shell pm enable com.google.android.apps.tv.launcherx
     ```

### 開啟遙控器按鍵服務

**設定 → 遙控器按鍵 → 啟用按鍵服務**：琉璃會透過電視的網路 ADB 自己開啟服務，電視第一次會跳出「允許 USB 偵錯」，選允許即可（需要先在開發人員選項開啟網路偵錯）。也可以用電腦：

```bash
adb shell settings put secure enabled_accessibility_services tw.liuli.launcher/.keys.KeyRemapService
```

### 從舊的預覽版（0.1.x）升級

0.1.x 預覽版每次建置的簽章都不同，請先解除安裝再安裝 1.0.0（常用 App、背景樣式、地點等設定需要重新設定一次）：

```bash
adb uninstall tw.liuli.launcher
```

## 從原始碼建置（不用安裝開發環境）

Fork 這個 repo，**Actions** 會自動用雲端機器編出 APK：

- 每次推到 `main`：更新「nightly」預覽版，固定網址 `https://github.com/candy999/liuli-launcher/releases/download/nightly/LiuliLauncher-nightly.apk`
- 推 `v` 開頭的 tag（例如 `v1.0.0`）：發布正式版，版本號取自 tag

想用自己的簽章（之後的版本才能直接覆蓋更新）：

```bash
pip install cryptography
```

```bash
python tools/make_keystore.py
```

照畫面上的說明，把四個 Secret 填進 repo 的 **Settings → Secrets and variables → Actions**。金鑰要好好保存，弄丟就無法覆蓋更新已安裝的 App。

圖示與電視橫幅由 `tools/make_logo.py` 產生（同一份幾何輸出向量圖示、橫幅與預覽頁）。

## 專案結構

```
app/src/main/java/tw/liuli/launcher/
├── MainActivity.kt          進入點、頁面切換（主畫面／月曆／天氣）、Home 鍵
├── adb/                     內建網路 ADB 用戶端（自己開啟無障礙服務）
├── context/                 農民曆（子時換日、時辰、吉時、正體字對照）、台灣節日、天氣
├── data/                    App 清單與主題色、常用列、隱藏清單、訊號源
├── keys/                    無障礙按鍵服務、按鍵對應、受保護的基本鍵
├── settings/                啟動器設定
├── system/                  系統設定捷徑
└── ui/
    ├── bg/                  背景引擎（電容式緩衝、自適應畫質）、12 種樣式
    ├── glass/               液態玻璃材質（高斯模糊＋過曝，Android 13+ 透鏡折射）
    ├── components/          焦點、App 磚、側邊面板
    ├── home/                主畫面、所有 App、邊框
    ├── calendar/            月曆＋農民曆
    ├── weather/             天氣預報
    └── theme/               顏色、字體、圖示
design/                      HTML 原型與 Logo 提案（用瀏覽器開啟，方向鍵操作）
docs/                        UX 設計、版本說明、截圖、Logo
tools/                       簽章金鑰、Logo 產生器
```

## 隱私

不需要任何帳號；不讀取裝置定位（天氣只用你選的縣市中心座標）；按鍵服務不讀取畫面內容、不連網。唯一的網路連線是向 Open-Meteo 取得天氣。

## 授權

[MIT](LICENSE)。使用到的開源專案：[lunar-java](https://github.com/6tail/lunar-java)（MIT）、AndroidX／Jetpack Compose（Apache-2.0）；天氣資料來自 [Open-Meteo](https://open-meteo.com/)（CC BY 4.0）；字標使用 [Noto Sans TC](https://fonts.google.com/noto/specimen/Noto+Sans+TC)（OFL）轉成輪廓。

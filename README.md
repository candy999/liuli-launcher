# 琉璃 Launcher

**真正的 Liquid Glass 風格 Google TV／Android TV 主畫面，以流暢為第一優先。**

時鐘、農民曆（初一十五、節氣、宜忌）、天氣、常用 App、訊號源切換，全部放在一片片液態玻璃裡。玻璃後面是程式產生的同色系桌布，**只在換色時模糊一次**，玻璃直接取用模糊好的背景，Android 13 以上再加上邊緣透鏡折射。畫面靜止時 GPU 完全閒置，焦點動畫只動圖層屬性。

- 🧭 **UX 設計與效能分析**：[docs/ux-design.md](docs/ux-design.md)
- 🖼️ **視覺原型**（用瀏覽器開啟）：[design/prototype.html](design/prototype.html)
- 📋 **完整規格表**：[規格表.md](規格表.md)

> ⚠️ 目前是早期開發版（0.1），還沒在多款實機上測試。歡迎回報問題。

## 功能

| | |
|---|---|
| **Liquid Glass** | 預先模糊的桌布取樣＋薄霧＋光澤＋邊緣內光＋高光邊；Android 13+ 邊緣透鏡折射與細微色散 |
| **桌布** | 深海、暮光、晴空、夜林四組同色系配色，或依時間自動切換（白天、傍晚、晚上） |
| **農民曆** | 農曆日期、干支生肖、節氣、宜忌、沖煞，完全離線計算（[lunar-java](https://github.com/6tail/lunar-java)）；月曆頁可查任何一天 |
| **台灣節日** | 春節、元宵、清明、端午、中元、中秋、冬至、尾牙、除夕等 30 多個，離線計算；初一十五以暖金色標示 |
| **天氣** | [Open-Meteo](https://open-meteo.com/)，免金鑰；天氣頁有逐 3 小時與 7 日預報。只用縣市中心座標，不讀取裝置定位 |
| **App** | 常用列（玻璃層架）＋全部 App；手機版 App 也會列出，可自己決定顯示或隱藏 |
| **訊號源切換** | HDMI／AV／電視直播（電視機才有，電視盒通常沒有） |
| **遙控器按鍵** | 無障礙服務重新對應任何按鍵的短按或長按，例如把 Netflix 鍵改成開別的 App |
| **主畫面保護** | 攔截 Home 鍵，原廠主畫面跳出來時自動切回琉璃 |

## 安裝

1. 到 [Releases](https://github.com/candy999/liuli-launcher/releases) 下載最新 APK（或到 [Actions](https://github.com/candy999/liuli-launcher/actions) 下載每次建置的版本）。
2. 安裝到電視，以下兩種方式擇一：
   - **用電腦 ADB**：
     ```bash
     adb connect <電視IP>:5555
     ```
     ```bash
     adb install -r LiuliLauncher.apk
     ```
   - **不用電腦**：在電視上安裝 Downloader，輸入 Release 頁面上的 APK 網址。
3. 設為預設主畫面：**設定 → 預設主畫面**。很多 Google TV 沒有這個選項，可以改用以下任一方式：
   - **開啟主畫面保護**（下一節），按 Home 鍵就會回到琉璃。
   - **用 ADB 停用原廠主畫面**。這是停用、不是刪除，隨時可以還原：
     ```bash
     adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
     ```
     還原：
     ```bash
     adb shell pm enable com.google.android.apps.tv.launcherx
     ```

### 遙控器按鍵與主畫面保護

**設定 → 遙控器按鍵 → 啟用按鍵服務**，在無障礙設定裡打開「琉璃 遙控器按鍵」。部分 Google TV 的無障礙選單不列出第三方服務，這時可以用 ADB 啟用：

```bash
adb shell settings put secure enabled_accessibility_services tw.liuli.launcher/.keys.KeyRemapService
```

這個服務只會做兩件事：攔截按鍵，以及取得前景 App 的套件名稱（用來偵測原廠主畫面）。它不讀取畫面內容，也不連網。

## 從原始碼建置（不用安裝開發環境）

Fork 這個 repo，**Actions** 會自動用雲端機器編出 APK。想自己簽章的話：

```bash
pip install cryptography
```

```bash
python tools/make_keystore.py
```

照著畫面上的說明，把四個 Secret 填進 repo 的 **Settings → Secrets and variables → Actions**。推一個 `v` 開頭的 tag，就會自動發布 Release。

## 專案結構

```
app/src/main/java/tw/liuli/launcher/
├── MainActivity.kt            進入點、桌布、頁面切換、Home 鍵
├── context/                   農民曆、節日、天氣
├── settings/                  啟動器設定
├── keys/                      無障礙按鍵服務、按鍵對應
├── data/                      App 清單、常用列、隱藏清單、訊號源
├── system/                    系統設定捷徑
└── ui/
    ├── glass/                 桌布產生與預模糊、Liquid Glass 材質（含 AGSL 折射）
    ├── components/            焦點、App 圖塊、側邊面板
    ├── theme/                 顏色、字體、圖示
    ├── home/                  主畫面
    ├── calendar/              月曆＋農民曆
    └── weather/               天氣預報
design/prototype.html          視覺原型
```

## 授權

[MIT](LICENSE)。使用到的開源專案：[lunar-java](https://github.com/6tail/lunar-java)（MIT）、AndroidX／Jetpack Compose（Apache-2.0）；天氣資料來自 [Open-Meteo](https://open-meteo.com/)（CC BY 4.0）。

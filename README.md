# 琉璃 Launcher

**Liquid Glass 風格的 Google TV／Android TV 主畫面，桌布是會「看情境」自己變化的網頁。**

白天亮、晚上暗；下雨了，桌布就變成玻璃上的雨滴；初一、十五底部亮起暖光與香煙；春節掛燈籠、中秋出大月亮。Launcher 不需要任何切換規則：琉璃把**時間、天氣、農民曆、節日、大約位置**帶進網頁的網址參數，網頁自己決定怎麼變化。任何人都能用 AI 寫一張網頁特效，設成自己的電視桌布。

- 🎨 **桌布遊樂場（即時預覽、下載離線包）**：<https://candy999.github.io/liuli-launcher/>
- 📐 **網頁桌布參數規格**：[docs/wallpaper-api.md](docs/wallpaper-api.md)
- 🤖 **用 AI 做桌布的提示詞**：[docs/ai-wallpaper-prompt.md](docs/ai-wallpaper-prompt.md)
- 📋 **完整規格表**：[規格表.md](規格表.md)

> ⚠️ 目前是早期開發版（0.1），還沒在實機上完整測試。歡迎回報問題。

## 功能

| | |
|---|---|
| **網頁桌布** | 線上網址或離線 zip 包；自動帶入 60 多個情境參數；遙控器按鍵也會以事件送進網頁 |
| **內建桌布** | 琉璃天空（日夜、天氣、月相、拜拜日、節日全自動）、玻璃雨滴、光暈極光、海浪 |
| **農民曆** | 農曆日期、干支生肖、節氣、宜忌、沖煞，完全離線計算（[lunar-java](https://github.com/6tail/lunar-java)） |
| **台灣節日** | 春節、元宵、清明、端午、中元、中秋、冬至、尾牙、除夕等 30 多個，離線計算 |
| **天氣** | [Open-Meteo](https://open-meteo.com/)，免金鑰；只用縣市中心座標，不讀取裝置定位 |
| **App 列表** | 常用列＋全部 App；手機版 App（原廠主畫面不顯示的）也會列出，可自己決定顯示或隱藏 |
| **訊號源切換** | HDMI／AV／電視直播（電視機才有，電視盒通常沒有） |
| **遙控器按鍵** | 無障礙服務重新對應任何按鍵的短按或長按，例如把 Netflix 鍵改成開別的 App |
| **主畫面保護** | 攔截 Home 鍵，原廠主畫面跳出來時自動切回琉璃 |
| **Liquid Glass** | 背景模糊、高光邊、彈簧焦點動畫；依電視效能自動分成高、中、低三級 |

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

## 自己做桌布

1. 把 [docs/ai-wallpaper-prompt.md](docs/ai-wallpaper-prompt.md) 的提示詞貼給 AI，描述你想要的效果。
2. 把 AI 產生的 `index.html` 和 [`liuli.js`](wallpaper-sdk/liuli.js) 放進同一個資料夾，在電腦瀏覽器開啟測試，例如在網址後面加上 `?wx=rain&bai=1&hour=21`。
3. 擇一種方式套用到電視：
   - **用網址**：把網頁放到任何網址，在電視選「設定 → 桌布 → 輸入網頁網址」。
   - **用 zip 離線包**：把資料夾壓成 zip，在電視選「從網址下載 zip 離線包」，或用 ADB 推到電視後選「匯入資料夾裡的 zip」。

做好的桌布歡迎發 PR 加進 `app/src/main/assets/wallpapers/`。

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
├── MainActivity.kt            進入點、Home 鍵、按鍵轉送給桌布
├── context/                   情境：農民曆、節日、天氣、網址參數
├── wallpaper/                 網頁桌布（WebView）、zip 離線包、設定
├── keys/                      無障礙按鍵服務、按鍵對應
├── data/                      App 清單、常用列、隱藏清單、訊號源
├── system/                    系統設定捷徑
└── ui/glass, ui/home          液態玻璃元件、主畫面
app/src/main/assets/wallpapers/  內建網頁桌布
wallpaper-sdk/liuli.js           網頁桌布 SDK
site/                            GitHub Pages 遊樂場
```

## 授權

[MIT](LICENSE)。使用到的開源專案：[lunar-java](https://github.com/6tail/lunar-java)（MIT）、AndroidX／Jetpack Compose（Apache-2.0）；天氣資料來自 [Open-Meteo](https://open-meteo.com/)（CC BY 4.0）。

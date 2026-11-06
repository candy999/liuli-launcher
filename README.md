# 琉璃 Launcher

Google TV／Android TV 主畫面，iOS 26 Liquid Glass 風格。完整規格見 [規格表.md](規格表.md)。

## 目前進度（M1）

- ✅ 時間與日期（跟隨系統 12／24 小時制）
- ✅ 常用列（Dock）＋所有應用程式格線，遙控器長按確定鍵開啟選單：加入／移出常用列、調整順序、應用程式資訊、解除安裝
- ✅ 訊號源切換（HDMI／AV／電視直播，依機型而定；電視盒通常沒有）
- ✅ 系統設定、網路設定、預設主畫面捷徑
- ✅ 液態玻璃元件：背景模糊、高光邊、彈簧焦點動畫；依機器效能自動分成高、中、低三級
- ⏳ 月曆＋農民曆（M2）、天氣（M3）、網頁桌布（M5）

## 用 GitHub 雲端編譯（不用在電腦上安裝開發環境）

1. 在 GitHub 建一個新的 repo（建議設為 Private），把這個資料夾推上去：
   ```bash
   git init -b main
   git add .
   git commit -m "琉璃 Launcher 初版"
   git remote add origin https://github.com/<你的帳號>/liuli-launcher.git
   git push -u origin main
   ```
2. 推上去後，到 repo 的 **Actions** 分頁，「編譯 APK」會自動開始，大約 5 分鐘。
3. 完成後點進該次執行，在 **Artifacts** 下載 APK（下載下來是 zip，解壓縮後就是 APK）。
4. 想讓電視直接下載：推一個 tag，例如 `git tag v0.1.0 && git push --tags`，APK 會出現在 **Releases**。

### 固定簽章（建議第一次就設好）

沒設的話，每次編出的 APK 簽章都不同，更新前必須先解除安裝舊版。

```bash
pip install cryptography
```

```bash
python tools/make_keystore.py
```

照畫面指示，到 repo 的 **Settings → Secrets and variables → Actions** 新增四個 Secret：`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
`keystore/` 資料夾不會上傳（已列在 .gitignore），**請另外備份**，弄丟就無法覆蓋更新。

## 安裝到電視

- **用電腦 ADB**：電視開啟開發人員選項與 USB／網路偵錯，然後：
  ```bash
  adb connect <電視IP>:5555
  ```
  ```bash
  adb install -r LiuliLauncher-0.1.N.apk
  ```
- **不用電腦**：在電視安裝 Downloader，輸入 Release 頁面上的 APK 網址。

### 設為預設主畫面

設定 → 琉璃 Launcher → 「預設主畫面」。很多 Google TV 沒有這個選項，需要用 ADB 停用原廠主畫面（停用、不是刪除，可隨時還原）：

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

還原：

```bash
adb shell pm enable com.google.android.apps.tv.launcherx
```

## 專案結構

```
app/src/main/java/tw/liuli/launcher/
├── MainActivity.kt          進入點、Home 鍵處理
├── data/AppRepository.kt    App 清單、常用列儲存
├── data/TvSources.kt        訊號源列表與切換
├── system/SystemActions.kt  系統設定／App 設定捷徑
└── ui/
    ├── glass/Glass.kt       液態玻璃面板、效能分級
    ├── glass/Wallpaper.kt   流動色塊桌布
    ├── glass/Components.kt  遙控器點擊、焦點彈簧、膠囊按鈕、玻璃選單
    └── home/HomeScreen.kt   主畫面
```

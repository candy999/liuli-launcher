# 網頁桌布 API（v1）

琉璃 Launcher 的桌布可以是**任何網頁**。琉璃會把「現在的情境」以網址參數帶進網頁，讓網頁自己決定要怎麼變化：白天亮、晚上暗，下雨就變成雨景，初一、十五換成拜拜的暖光，春節掛燈籠……Launcher 不需要寫任何切換規則。

- 線上網頁：在 **設定 → 桌布 → 輸入網頁網址** 填入網址
- 離線 zip 包：**設定 → 桌布 → 從網址下載 zip 離線包**，或把 zip 放進 `Android/data/tw.liuli.launcher/files/wallpapers/` 再選 **匯入資料夾裡的 zip**
- 範例與即時預覽：<https://candy999.github.io/liuli-launcher/>

---

## 1. 參數怎麼進到網頁

1. **載入時**：參數直接加在網址後面
   `https://你的網址/index.html?v=1&hour=21&daylight=0&wx=rain&bai=1&lday=15&...`
2. **之後每分鐘、天氣更新時**：琉璃**不會重新載入**網頁（動畫不中斷），而是：
   - 用 `history.replaceState` 更新網址上的參數
   - 更新 `window.LIULI`（所有參數的物件）
   - 觸發 `liuli:context` 事件，`event.detail` 是所有參數
3. **你自己寫在網址上的參數優先**：例如設定網址 `https://.../?wx=rain`，琉璃就不會覆蓋 `wx`，方便測試「一直下雨」的樣子。

所有參數都是字串。建議直接用 SDK [`liuli.js`](../wallpaper-sdk/liuli.js)，它會幫你轉成數字、布林、陣列，並在一般瀏覽器裡用電腦時間補上預設值。

```html
<script src="liuli.js"></script>
<script>
  Liuli.onChange(function (c) {
    document.body.classList.toggle('night', c.isNight);
    document.body.classList.toggle('rain', c.isRainy);
    document.body.classList.toggle('baibai', c.bai);            // 初一、十五
    document.body.classList.toggle('spring', c.hasFestival('spring'));
  });
  Liuli.onKey(function (k) {
    if (k.key === 'DPAD_UP' && k.action === 'down') { /* 往上鍵 */ }
  });
</script>
```

---

## 2. 參數一覽

> 鍵名一旦公開就不會改名或刪除，只會新增。`v` 是 API 版本。

### 時間

| 參數 | 範例 | 說明 |
|---|---|---|
| `v` | `1` | API 版本；在一般瀏覽器開啟時沒有這個參數（SDK 的 `c.inLauncher` 為 false） |
| `mode` | `home` | `home`＝主畫面；之後的螢幕保護程式會是 `screensaver` |
| `ts` | `1793941685635` | 目前時間（毫秒） |
| `tz` | `Asia/Taipei` | 時區 |
| `date` | `2026-11-06` | 國曆日期 |
| `year` `month` `day` | `2026` `11` `6` | |
| `hour` `minute` | `21` `08` | 24 小時制 |
| `weekday` | `4` | 1＝星期一 … 7＝星期日 |
| `weekend` | `0` | 週末 1 |
| `period` | `night` | `midnight`(0–3) `dawn`(4–5) `morning`(6–10) `noon`(11–12) `afternoon`(13–16) `dusk`(17–18) `night`(19–23) |
| `daylight` | `0` | 日出到日落之間為 1（有天氣資料時用實際日出日落時間） |
| `theme` | `dark` | `light`／`dark`，等於 daylight 的簡寫 |
| `sun` | `0.420` | 日出到日落的進度 0～1；夜間為 `-1`。可用來畫太陽位置與天空漸層 |
| `sunrise` `sunset` | `06:05` `17:12` | 有天氣資料時才有 |
| `season` | `autumn` | `spring` `summer` `autumn` `winter`（依立春、立夏、立秋、立冬切換） |

### 農民曆

| 參數 | 範例 | 說明 |
|---|---|---|
| `lyear` | `丙午` | 干支年 |
| `zodiac` | `馬` | 生肖 |
| `lmonth` `lday` | `9` `16` | 農曆月、日 |
| `lleap` | `0` | 閏月為 1 |
| `ltext` | `九月十六` | 中文農曆日期 |
| `bai` | `1` | **初一、十五為 1（拜拜日）** |
| `zuoya` | `0` | 初二、十六為 1（商家做牙） |
| `moon` | `0.508` | 月相：0＝新月、0.5＝滿月、接近 1＝殘月 |
| `term` | `立冬` | 今天剛好是節氣時才有 |
| `nextterm` `nexttermdate` | `小雪` `2026-11-22` | 下一個節氣 |
| `yi` `ji` | `祭祀,祈福,出行` | 今日宜、忌（逗號分隔，最多 8 項） |
| `chong` `sha` | `沖鼠` `煞北` | |
| `ghostmonth` | `0` | 農曆七月為 1 |

### 節日

| 參數 | 範例 | 說明 |
|---|---|---|
| `festival` | `spring,lichun` | 今天的節日代號，逗號分隔；沒有就是空字串 |
| `festivalname` | `春節,立春` | 對應的中文名稱 |
| `holiday` | `1` | 今天有任何節日為 1 |

節日代號：

| 代號 | 節日 | 代號 | 節日 |
|---|---|---|---|
| `newyear` | 元旦 | `spring` | 春節（初一） |
| `spring2` | 初二回娘家 | `tiangong` | 天公生（正月初九） |
| `lantern` | 元宵節 | `tudigong` | 土地公生（二月初二） |
| `valentine` | 西洋情人節 | `peace` | 和平紀念日 |
| `women` | 婦女節 | `arbor` | 植樹節 |
| `mazu` | 媽祖生（三月廿三） | `children` | 兒童節 |
| `qingming` | 清明節 | `labor` | 勞動節 |
| `mother` | 母親節（五月第二個週日） | `dragonboat` | 端午節 |
| `ghostgate` | 開鬼門（七月初一） | `qixi` | 七夕 |
| `zhongyuan` | 中元節 | `ghostclose` | 關鬼門（七月最後一天） |
| `father` | 父親節 | `midautumn` | 中秋節 |
| `teacher` | 教師節 | `chongyang` | 重陽節 |
| `national` | 國慶日 | `retrocession` | 臺灣光復節 |
| `halloween` | 萬聖節 | `dongzhi` | 冬至 |
| `xmaseve` | 平安夜 | `christmas` | 聖誕節 |
| `nye` | 跨年夜 | `laba` | 臘八 |
| `weiya` | 尾牙（十二月十六） | `songshen` | 送神（十二月廿四） |
| `chuxi` | 除夕 | `lichun` | 立春 |

### 天氣（Open-Meteo，每 30 分鐘更新）

| 參數 | 範例 | 說明 |
|---|---|---|
| `wx` | `rain` | `clear` `partly` `cloudy` `fog` `drizzle` `rain` `snow` `thunder`；還沒取得時為 `unknown` |
| `wxtext` | `雨` | 中文 |
| `wxcode` | `61` | WMO 天氣代碼（想做更細的判斷時用） |
| `temp` `feels` | `22` `24` | 氣溫、體感（°C） |
| `tmax` `tmin` | `25` `19` | 今日高低溫 |
| `humidity` | `80` | 濕度 % |
| `rain` | `1.2` | 目前降雨量 mm |
| `pop` | `70` | 今日降雨機率 % |
| `cloud` | `90` | 雲量 % |
| `wind` | `12` | 風速 km/h |

### 大約位置

| 參數 | 範例 | 說明 |
|---|---|---|
| `city` | `臺北市` | 使用者在設定裡選的縣市 |
| `cityid` | `taipei` | |
| `lat` `lon` | `25.04` `121.56` | 縣市中心座標（小數兩位），**不是**裝置的精確位置 |

### 裝置

| 參數 | 範例 | 說明 |
|---|---|---|
| `w` `h` | `1920` `1080` | 螢幕像素 |
| `glass` | `high` | 琉璃判斷的效能等級 `high`／`medium`／`low` |
| `motion` | `full` | `reduce` 時請降低粒子數量、幀率（低效能電視） |
| `online` | `1` | 是否有網路 |
| `lang` | `zh-TW` | |

---

## 3. 事件

| 事件 | detail | 何時觸發 |
|---|---|---|
| `liuli:context` | 全部參數（字串） | 載入完成後一次，之後每分鐘、天氣更新時 |
| `liuli:key` | `{ key: "DPAD_UP", code: 19, action: "down", long: false }` | 使用者在主畫面按遙控器（可在設定裡關閉） |

`key` 是 Android 的按鍵名稱去掉 `KEYCODE_`，例如 `DPAD_LEFT`、`DPAD_CENTER`、`BACK`、`MEDIA_PLAY_PAUSE`、`PROG_RED`。`long` 為 true 代表使用者按住不放。

> 桌布不會拿到焦點，按鍵仍然由主畫面處理（移動焦點、開 App）。`liuli:key` 只是讓網頁「也知道」使用者按了什麼，可以做出跟著游標漂移的光暈、按確定鍵時的漣漪等效果。

---

## 4. 製作 zip 離線包

```
my-wallpaper.zip
├── index.html      ← 必要（放在根目錄，或唯一的一層資料夾裡）
├── liuli.js
├── style.css
└── assets/
    ├── bg.jpg
    └── rain.mp4
```

- 所有路徑請用**相對路徑**（`assets/bg.jpg`，不要用 `/assets/bg.jpg`）。
- 離線包裡的網頁從 `https://appassets.androidplatform.net/local/<包名>/` 載入，是一般的 https 網頁：`fetch`、`localStorage`、影片、WebGL 都能用。
- 解壓後上限 200MB、5000 個檔案。同名的包重新匯入會直接覆蓋（方便更新）。
- 可以引用線上資源（字型、CDN），但離線時就讀不到，建議把需要的東西都放進 zip。

## 5. 效能建議（電視 GPU 很弱）

- Canvas 用**一半解析度**渲染再放大（範例都是 `0.5`，`motion=reduce` 時 `0.35`），畫面柔和的桌布看不出差別，效能差 4 倍。
- 背景模糊之類的重運算只做一次存在離屏 canvas，不要每幀做。
- 慢速動畫 30fps 就夠：`Liuli.fps()` 在低效能電視回傳 30。
- 使用者離開主畫面時琉璃會暫停網頁（`requestAnimationFrame` 停止），不用自己處理。

## 6. 測試

- 直接在電腦瀏覽器開啟你的 `index.html`，在網址後面加參數模擬情境：
  `index.html?v=1&hour=21&daylight=0&sun=-1&wx=rain&bai=1&moon=0.5&festival=midautumn`
- 鍵盤方向鍵與 Enter 會被 SDK 轉成 `liuli:key` 事件。
- 線上遊樂場可以用滑桿調整所有參數：<https://candy999.github.io/liuli-launcher/>

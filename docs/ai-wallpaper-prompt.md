# 用 AI 做網頁桌布

把下面整段貼給 ChatGPT、Claude、Gemini 等 AI，把「我想要的效果」換成你的點子，就能得到一張可以直接用的琉璃桌布。

---

## 提示詞範本

```
請幫我做一張「琉璃 Launcher」電視桌布網頁，規格如下：

【我想要的效果】
（例：雨水滑落在玻璃窗上，窗外是模糊的台北夜景；白天窗外變成灰白天光；
 初一、十五時窗台上有一盞小燈慢慢亮起；中秋節窗外出現大月亮。）

【技術限制】
1. 單一 index.html，CSS 與 JavaScript 都寫在裡面；不要使用任何外部 CDN 或網路資源。
2. 引用同資料夾的 liuli.js：<script src="liuli.js"></script>
   用 Liuli.onChange(function (c) { ... }) 取得情境，c 的欄位：
   - 時間：c.hour, c.minute, c.period（midnight/dawn/morning/noon/afternoon/dusk/night）,
     c.daylight（布林）, c.isNight, c.sun（日出→日落 0～1，夜間 -1）, c.season, c.weekend
   - 農曆：c.lmonth, c.lday, c.bai（初一十五，布林）, c.moon（月相 0 新月～0.5 滿月）,
     c.term（今天的節氣，可能沒有）, c.yi / c.ji（宜忌陣列）, c.ghostmonth
   - 節日：c.festival（陣列，例如 ['spring','lichun']）, c.hasFestival('midautumn')
     代號：spring 春節, chuxi 除夕, lantern 元宵, dragonboat 端午, qixi 七夕,
     zhongyuan 中元, midautumn 中秋, dongzhi 冬至, christmas 聖誕, nye 跨年…
   - 天氣：c.wx（clear/partly/cloudy/fog/drizzle/rain/snow/thunder）, c.isRainy,
     c.temp, c.cloud（雲量%）, c.wind（km/h）, c.pop（降雨機率%）
   - 裝置：c.motion（'reduce' 代表低效能電視，要降低粒子數與幀率）
   onChange 會在載入時呼叫一次，之後每分鐘再呼叫；請平滑過渡，不要重新載入頁面。
3. 這是電視背景：全螢幕、不能有捲軸、不需要任何按鈕或互動，文字盡量少（主畫面上方會疊時鐘與 App）。
   畫面下半部會被 App 列表蓋住，重點視覺放在上半部或整體氛圍。
4. 效能：電視 GPU 很弱。Canvas 請用 0.5 倍解析度渲染再用 CSS 拉滿全螢幕；
   模糊等重運算只算一次存到離屏 canvas；幀率上限用 Liuli.fps()（低效能時回傳 30）。
5. 在一般瀏覽器開啟也要能看（liuli.js 會補預設值），
   並在檔案開頭註解說明這張桌布會對哪些參數產生什麼變化。

請直接給我完整的 index.html。
```

---

## 打包與安裝

1. 建一個資料夾，放入 AI 給你的 `index.html`，以及 [`liuli.js`](../wallpaper-sdk/liuli.js)。
2. 先在電腦瀏覽器開啟測試，網址後面加參數模擬情境，例如：
   `index.html?v=1&hour=21&daylight=0&sun=-1&wx=rain&bai=1`
3. 把資料夾壓成 zip，用下面任一種方式裝到電視：
   - 放到網路上（GitHub Releases、Google Drive 直接下載連結等），在電視選 **設定 → 桌布 → 從網址下載 zip 離線包**
   - 用 ADB 推到電視，再選 **匯入資料夾裡的 zip**：
     ```bash
     adb push my-wallpaper.zip /sdcard/Android/data/tw.liuli.launcher/files/wallpapers/
     ```
4. 也可以不打包，直接把網頁放到任何網址（GitHub Pages、Netlify…），選 **輸入網頁網址**。

## 點子清單

| 主題 | 會用到的參數 |
|---|---|
| 玻璃雨滴、雷雨閃電 | `wx`, `rain`, `daylight` |
| 四季植物（櫻花、蟬、楓葉、雪） | `season`, `term` |
| 月亮陰晴圓缺、中秋賞月 | `moon`, `festival=midautumn` |
| 初一十五神明燈、香煙 | `bai`, `lday` |
| 春節鞭炮、元宵燈籠、端午龍舟 | `festival` |
| 日出日落的天空漸層 | `sun`, `sunrise`, `sunset` |
| 週末慵懶貓咪、平日上班模式 | `weekend`, `period` |
| 寒流時窗戶結霜、熱浪時空氣扭曲 | `temp`, `tmin`, `tmax` |
| 依縣市顯示地標剪影 | `cityid` |
| 跟著遙控器移動的光暈 | `liuli:key` 事件 |

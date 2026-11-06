/*!
 * 琉璃 Launcher 網頁桌布 SDK v1（MIT）
 * https://github.com/candy999/liuli-launcher/blob/main/docs/wallpaper-api.md
 *
 * 用法：
 *   <script src="liuli.js"></script>
 *   Liuli.onChange(function (c) { if (c.bai) ...; if (c.wx === 'rain') ...; });
 *   Liuli.onKey(function (k) { if (k.key === 'DPAD_UP' && k.action === 'down') ... });
 *
 * 在一般瀏覽器開啟時沒有琉璃帶入的參數，SDK 會用電腦時間補上預設值，
 * 並允許在網址上手動測試，例如 index.html?wx=rain&bai=1&hour=21
 */
(function () {
  'use strict';

  var NUMBERS = ['v', 'ts', 'year', 'month', 'day', 'hour', 'minute', 'weekday', 'sun', 'lmonth', 'lday',
    'moon', 'wxcode', 'temp', 'feels', 'humidity', 'rain', 'pop', 'cloud', 'wind', 'tmax', 'tmin',
    'lat', 'lon', 'w', 'h'];
  var FLAGS = ['weekend', 'daylight', 'lleap', 'bai', 'zuoya', 'ghostmonth', 'holiday', 'online'];
  var LISTS = ['yi', 'ji', 'festival', 'festivalname'];

  function periodOf(h) {
    if (h <= 3) return 'midnight';
    if (h <= 5) return 'dawn';
    if (h <= 10) return 'morning';
    if (h <= 12) return 'noon';
    if (h <= 16) return 'afternoon';
    if (h <= 18) return 'dusk';
    return 'night';
  }

  /** 不在琉璃裡（例如用電腦瀏覽器預覽）時的預設值。 */
  function defaults() {
    var d = new Date();
    var h = d.getHours();
    var day = h >= 6 && h < 18;
    var minutes = h * 60 + d.getMinutes();
    return {
      v: '0', mode: 'preview', ts: String(d.getTime()),
      date: d.toISOString().slice(0, 10), year: String(d.getFullYear()), month: String(d.getMonth() + 1),
      day: String(d.getDate()), hour: String(h), minute: String(d.getMinutes()),
      weekday: String(d.getDay() === 0 ? 7 : d.getDay()), weekend: (d.getDay() % 6 === 0) ? '1' : '0',
      period: periodOf(h), daylight: day ? '1' : '0', theme: day ? 'light' : 'dark',
      sun: day ? String(((minutes - 360) / 720).toFixed(3)) : '-1',
      season: ['winter', 'winter', 'spring', 'spring', 'spring', 'summer', 'summer', 'summer', 'autumn', 'autumn', 'autumn', 'winter'][d.getMonth()],
      lday: '0', lmonth: '0', bai: '0', zuoya: '0', moon: '0.5', festival: '', festivalname: '', holiday: '0',
      wx: 'clear', temp: '24', cloud: '20', rain: '0', pop: '0', wind: '5',
      city: '臺北市', lat: '25.04', lon: '121.56',
      w: String(window.innerWidth), h: String(window.innerHeight), glass: 'high', motion: 'full',
      online: '1', lang: 'zh-TW'
    };
  }

  function readUrl() {
    var raw = {};
    try {
      new URL(location.href).searchParams.forEach(function (v, k) { raw[k] = v; });
    } catch (e) { /* 舊瀏覽器 */ }
    return raw;
  }

  function parse(raw) {
    var base = defaults();
    var src = {};
    var k;
    for (k in base) src[k] = base[k];
    for (k in raw) src[k] = raw[k];
    var c = { raw: src };
    for (k in src) {
      var v = src[k];
      if (NUMBERS.indexOf(k) >= 0) c[k] = v === '' ? NaN : Number(v);
      else if (FLAGS.indexOf(k) >= 0) c[k] = v === '1' || v === 'true';
      else if (LISTS.indexOf(k) >= 0) c[k] = v ? String(v).split(',').filter(Boolean) : [];
      else c[k] = v;
    }
    c.inLauncher = !!raw.v;
    c.isNight = !c.daylight;
    c.isRainy = ['drizzle', 'rain', 'thunder'].indexOf(c.wx) >= 0;
    c.hasFestival = function (id) { return c.festival.indexOf(id) >= 0; };
    c.yiHas = function (word) { return c.yi.some(function (y) { return y.indexOf(word) >= 0; }); };
    return c;
  }

  var changeHandlers = [];
  var keyHandlers = [];

  var Liuli = {
    version: 1,
    ctx: parse(readUrl()),
    /** 立刻呼叫一次，之後每次情境更新（每分鐘、天氣更新時）再呼叫。 */
    onChange: function (fn) {
      changeHandlers.push(fn);
      try { fn(Liuli.ctx); } catch (e) { console.error(e); }
    },
    /** 遙控器按鍵：{ key: 'DPAD_UP', code: 19, action: 'down'|'up', long: false } */
    onKey: function (fn) { keyHandlers.push(fn); },
    /** 依情境回傳較合適的動畫幀率（低效能電視降到 30fps）。 */
    fps: function () { return Liuli.ctx.motion === 'reduce' ? 30 : 60; }
  };

  window.addEventListener('liuli:context', function (e) {
    Liuli.ctx = parse(e.detail || readUrl());
    changeHandlers.forEach(function (fn) { try { fn(Liuli.ctx); } catch (err) { console.error(err); } });
  });
  window.addEventListener('liuli:key', function (e) {
    keyHandlers.forEach(function (fn) { try { fn(e.detail); } catch (err) { console.error(err); } });
  });
  // 在電腦瀏覽器預覽時，用鍵盤方向鍵模擬遙控器
  if (!Liuli.ctx.inLauncher) {
    var map = { ArrowUp: 'DPAD_UP', ArrowDown: 'DPAD_DOWN', ArrowLeft: 'DPAD_LEFT', ArrowRight: 'DPAD_RIGHT', Enter: 'DPAD_CENTER' };
    ['keydown', 'keyup'].forEach(function (type) {
      window.addEventListener(type, function (e) {
        if (!map[e.key]) return;
        var detail = { key: map[e.key], code: 0, action: type === 'keydown' ? 'down' : 'up', long: e.repeat };
        keyHandlers.forEach(function (fn) { fn(detail); });
      });
    });
  }

  window.Liuli = Liuli;
})();

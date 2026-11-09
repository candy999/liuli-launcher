package tw.liuli.launcher.keys

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 螢幕小時鐘：看 Netflix、YouTube 等其他 App 時，角落仍然看得到時間。
 *
 * - 由按鍵服務（無障礙服務）放一個「無障礙疊加視窗」：不需要額外的「顯示在其他應用程式上層」權限
 *   （很多電視沒有那個設定頁），不搶焦點、不攔截按鍵、點不到。
 * - 純 TextView，每分鐘整點更新一次文字，幾乎不耗效能。
 * - 琉璃主畫面在前景時自動隱藏（主畫面本來就有大時鐘）；設定面板開著時例外，方便即時預覽。
 * - 防烙印：每分鐘在 0～3 像素內微移一次，OLED 長時間顯示也不會在同一個位置留下殘影。
 * 設定存在 LauncherSettings 同一份 SharedPreferences，改了立刻套用。
 */
class ClockOverlay(private val service: AccessibilityService) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = service.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            tick++
            render()
            scheduleTick()
        }
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(this)
        update()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacksAndMessages(null)
        remove()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == null || key.startsWith("clock_")) update()
    }

    /** 依設定與「琉璃是否在前景」決定顯示或隱藏，並套用位置、大小、背景。 */
    fun update() {
        val show = prefs.getBoolean(KEY_ON, false) && (preview || !liuliResumed)
        if (!show) {
            remove()
            return
        }
        val v = view ?: TextView(service).also { tv ->
            tv.setTextColor(0xFFFFFFFF.toInt())
            tv.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            tv.includeFontPadding = false
            view = tv
        }
        val dm = service.resources.displayMetrics
        fun dp(x: Int) = (x * dm.density).toInt()
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.getInt(KEY_SIZE, DEF_SIZE).toFloat())
        if (prefs.getInt(KEY_BG, 0) == 1) {
            // 半透明底：亮的畫面（雪景、白底節目）上也讀得到
            v.background = GradientDrawable().apply {
                setColor(0x8C000000.toInt())
                cornerRadius = dp(14).toFloat()
            }
            v.setPadding(dp(12), dp(5), dp(12), dp(6))
            v.setShadowLayer(0f, 0f, 0f, 0)
        } else {
            v.background = null
            v.setPadding(dp(4), dp(2), dp(4), dp(2))
            v.setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
        }
        val params = lp ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).also { lp = it }
        params.gravity = when (prefs.getInt(KEY_CORNER, DEF_CORNER)) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.TOP or Gravity.END
            2 -> Gravity.BOTTOM or Gravity.START
            else -> Gravity.BOTTOM or Gravity.END
        }
        // 位移＝從角落往內推多少（靠右的角落 x 從右邊量、靠下的 y 從下面量）；加上防烙印微移
        params.x = dp(prefs.getInt(KEY_X, DEF_X)) + JITTER[tick % JITTER.size].first
        params.y = dp(prefs.getInt(KEY_Y, DEF_Y)) + JITTER[tick % JITTER.size].second
        render()
        if (v.parent == null) {
            runCatching { wm.addView(v, params) }
            scheduleTick()
        } else {
            runCatching { wm.updateViewLayout(v, params) }
        }
    }

    private fun render() {
        val v = view ?: return
        val now = LocalTime.now()
        val is24h = android.text.format.DateFormat.is24HourFormat(service)
        val text = SpannableStringBuilder(now.format(DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm")))
        if (!is24h) {
            val start = text.length
            text.append(if (now.hour < 12) " 上午" else " 下午")
            text.setSpan(RelativeSizeSpan(0.5f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        v.text = text
        // 每分鐘換一個微移位置
        val params = lp ?: return
        if (v.parent != null) {
            val dm = service.resources.displayMetrics
            params.x = (prefs.getInt(KEY_X, DEF_X) * dm.density).toInt() + JITTER[tick % JITTER.size].first
            params.y = (prefs.getInt(KEY_Y, DEF_Y) * dm.density).toInt() + JITTER[tick % JITTER.size].second
            runCatching { wm.updateViewLayout(v, params) }
        }
    }

    /** 下一次在整分鐘（多 50ms）更新。 */
    private fun scheduleTick() {
        handler.removeCallbacks(ticker)
        val now = System.currentTimeMillis()
        handler.postDelayed(ticker, 60_000 - now % 60_000 + 50)
    }

    private fun remove() {
        handler.removeCallbacks(ticker)
        val v = view ?: return
        if (v.parent != null) runCatching { wm.removeView(v) }
    }

    companion object {
        const val KEY_ON = "clock_on"
        const val KEY_CORNER = "clock_corner"
        const val KEY_SIZE = "clock_size"
        const val KEY_X = "clock_x"
        const val KEY_Y = "clock_y"
        const val KEY_BG = "clock_bg"
        const val DEF_CORNER = 1
        const val DEF_SIZE = 22
        const val DEF_X = 40
        const val DEF_Y = 28
        val CORNERS = listOf("左上", "右上", "左下", "右下")

        /** 防烙印微移：每分鐘換一個位置（像素），繞一圈約 8 分鐘。 */
        private val JITTER = listOf(0 to 0, 2 to 0, 3 to 2, 2 to 3, 0 to 3, -1 to 2, 0 to 1, 1 to 1)

        /** 琉璃主畫面是否在前景（MainActivity onResume／onPause 設定）。 */
        @Volatile var liuliResumed = false
        /** 小時鐘設定面板開著：主畫面上也顯示，方便即時預覽。 */
        @Volatile var preview = false

        /** 在主執行緒呼叫：狀態改變後重新決定顯示與否。 */
        fun refresh() {
            KeyRemapService.instance?.clock?.update()
        }
    }
}

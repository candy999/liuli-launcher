package tw.liuli.launcher.ui.home

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tw.liuli.launcher.geek.FrameStats
import tw.liuli.launcher.geek.NetKind
import tw.liuli.launcher.geek.Stats
import tw.liuli.launcher.ui.bg.BgColors
import tw.liuli.launcher.ui.glass.liquidGlass
import tw.liuli.launcher.ui.theme.Ink
import tw.liuli.launcher.ui.theme.LIcon
import tw.liuli.launcher.ui.theme.LIcons
import kotlin.math.roundToInt

/*
 * 技術宅模式的儀表列（常用列下方）。設計語彙跟主畫面一致（docs/ux-design.md）：
 * - 只有白色的透明度層次＋一個暖金：正常時沒有燈號，出狀況的那張卡數字變暖金、多一個暖金小標
 * - 卡片是同一種玻璃（不描邊、不發光），圓角與常用列一致
 * - 大數字用細字（跟時鐘同一個字族），等寬數字，跳動時不左右晃
 * 卡片不拿焦點：只是看的，不打斷遙控器的上下移動。
 */

private val Tnum = "tnum"

@Composable
private fun T(
    text: String, size: TextUnit, color: Color = Ink.Secondary, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) = BasicText(
    text, modifier,
    style = TextStyle(color = color, fontSize = size, fontWeight = weight, fontFeatureSettings = Tnum),
    maxLines = 1, overflow = TextOverflow.Ellipsis,
)

/** 大數字＋單位（單位小一號、較淡）。 */
@Composable
private fun Big(value: String, unit: String, warn: Boolean, dim: Boolean = false) {
    val c = when { warn -> Ink.Gold; dim -> Ink.Tertiary; else -> Ink.Primary }
    BasicText(
        buildAnnotatedString {
            append(value)
            withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = if (warn) Ink.Gold.copy(alpha = .7f) else Ink.Tertiary)) { append(" $unit") }
        },
        style = TextStyle(color = c, fontSize = 20.sp, fontWeight = FontWeight.Light, fontFeatureSettings = Tnum),
        maxLines = 1,
    )
}

/** 暖金小標（跟天氣卡「明天回暖」同一種膠囊）。 */
@Composable
private fun Badge(text: String) {
    T(text, 9.sp, Ink.Gold, FontWeight.Medium, Modifier.background(Ink.GoldSoft, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp))
}

/** 卡片標題列：名稱、（出狀況時）暖金小標、右邊的型號或補充。 */
@Composable
private fun Header(title: String, sub: String, badge: String?, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (icon != null) LIcon(icon, 11.dp, Ink.Secondary)
        T(title, 11.sp, Ink.Secondary, FontWeight.Medium)
        if (badge != null) Badge(badge)
        Spacer(Modifier.weight(1f))
        T(sub, 9.sp, Ink.Tertiary)
    }
}

/** 最近 60 秒的折線（白色、很淡），貼在卡片下半部當底紋。 */
private fun Modifier.spark(data: List<Float>, lo: Float, hi: Float): Modifier = drawBehind {
    if (data.size < 2) return@drawBehind
    val h = size.height * .5f; val top = size.height - h
    val path = Path(); val fill = Path()
    val n = data.size
    for (i in 0 until n) {
        val x = i / (n - 1f) * size.width
        val y = top + h - ((data[i] - lo) / (hi - lo)).coerceIn(0f, 1f) * h * .85f - 1.5f
        if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) } else { path.lineTo(x, y); fill.lineTo(x, y) }
    }
    fill.lineTo(size.width, size.height); fill.close()
    drawPath(fill, Brush.verticalGradient(listOf(Color.White.copy(alpha = .13f), Color.Transparent), startY = top, endY = size.height))
    drawPath(path, Color.White.copy(alpha = .38f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

@Composable
private fun RowScope.Card(weight: Float, mod: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.weight(weight).fillMaxHeight().liquidGlass(shape).clip(shape).then(mod)
            .padding(start = 10.dp, end = 10.dp, top = 5.dp),
    ) { content() }
}

private fun gb(b: Long) = "%.2f".format(b / 1073741824f)
private fun f1(v: Float) = "%.1f".format(v)

@Composable
fun GeekDashboard(s: Stats, freed: String?, modifier: Modifier = Modifier) {
    FrameMetricsHook()
    Row(modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // ── CPU ──
        val cpuWarn = s.cpuPct > 85f
        Card(1.2f, Modifier.spark(s.cpuHist, 0f, 100f)) {
            Column {
                Header("CPU", s.cpuName.replace("Cortex-", ""), if (cpuWarn) "偏高" else null)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        Big(if (s.cpuPct < 0) "—" else s.cpuPct.roundToInt().toString(), "%", cpuWarn)
                        T((if (s.cpuReal) "" else "頻率負載・") + "%.2f GHz".format(s.cpuGhz), 9.sp, Ink.Tertiary)
                    }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.height(26.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (c in s.cores.take(8)) {
                            Box(Modifier.width(4.dp).fillMaxHeight().background(Color.White.copy(alpha = .14f), RoundedCornerShape(2.dp))) {
                                Box(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(c.coerceIn(.06f, 1f))
                                        .background(if (cpuWarn && c > .85f) Ink.Gold else Color.White.copy(alpha = .85f), RoundedCornerShape(2.dp)),
                                )
                            }
                        }
                    }
                }
            }
        }
        // ── GPU ──
        val janky = s.fps > 0 && s.frameMs > 25f
        val gpuWarn = s.gpuPct > 90f || janky
        Card(1f, Modifier.spark(s.fpsHist, 20f, 62f)) {
            Column {
                Header("GPU", s.gpuName, if (janky) "掉幀" else if (gpuWarn) "偏高" else null)
                Column(Modifier.padding(top = 2.dp)) {
                    if (s.gpuPct >= 0) {
                        Big(s.gpuPct.roundToInt().toString(), "%", gpuWarn)
                        T((if (s.fps > 0) "${s.fps.roundToInt()} fps・每幀 ${f1(s.frameMs)} ms" else "畫面靜止") + bgText(s), 9.sp, Ink.Tertiary)
                    } else {
                        Big(if (s.fps > 0) s.fps.roundToInt().toString() else "—", "fps", janky, dim = s.fps <= 0)
                        T((if (s.fps > 0) "每幀 ${f1(s.frameMs)} ms" else "畫面靜止") + bgText(s), 9.sp, Ink.Tertiary)
                    }
                }
            }
        }
        // ── 記憶體 ──
        val avail = if (s.memTotal > 0) s.memAvail.toFloat() / s.memTotal else 1f
        val memWarn = avail < .15f || s.memLow
        Card(1f) {
            Column {
                Header("記憶體", "共 %.1f GB".format(s.memTotal / 1073741824f), if (memWarn) "吃緊" else null)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        Big(gb(s.memUsed), "GB", memWarn)
                        if (freed != null) T(freed, 9.sp, Ink.Primary, FontWeight.Medium)
                        else T("可用 ${gb(s.memAvail)} GB", 9.sp, Ink.Tertiary)
                    }
                    Spacer(Modifier.weight(1f))
                    val used = 1f - avail
                    Box(Modifier.size(26.dp).padding(bottom = 3.dp).drawBehind {
                        val sw = 2.6.dp.toPx(); val r = size.minDimension / 2 - sw / 2
                        val tl = Offset(size.width / 2 - r, size.height / 2 - r); val sz = Size(r * 2, r * 2)
                        drawArc(Color.White.copy(alpha = .14f), 0f, 360f, false, tl, sz, style = Stroke(sw))
                        drawArc(if (memWarn) Ink.Gold else Color.White.copy(alpha = .85f), -90f, 360f * used, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                    })
                }
            }
        }
        // ── 網路 ──
        val off = s.net == NetKind.NONE
        val netWarn = off || s.pingMs > 80f
        Card(1.3f, Modifier.spark(s.netHist, 0f, 120f)) {
            Column {
                val (name, icon) = when (s.net) {
                    NetKind.WIFI -> "Wi-Fi" to LIcons.Wifi
                    NetKind.ETHERNET -> "有線網路" to LIcons.Ethernet
                    NetKind.NONE -> "未連線" to LIcons.NoNet
                }
                Header(name, if (off) "Wi-Fi 與網路線都沒有連線" else s.netDetail, if (off) "離線" else if (netWarn) "延遲高" else null, icon)
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Column {
                        Big(if (s.pingMs < 0) "—" else s.pingMs.roundToInt().toString(), "ms", netWarn && !off, dim = off)
                        T(
                            when {
                                off -> "斷線 " + dur(s.offlineSec)
                                else -> "閘道 " + (if (s.gatewayMs < 0) "—" else s.gatewayMs.roundToInt().toString()) + " ms" +
                                    (if (s.jitterMs >= 0) "・抖動 ${f1(s.jitterMs)} ms" else "")
                            },
                            9.sp, Ink.Tertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 1.dp)) {
                        T("↓ ${f1(s.rxMbps)} Mbps", 9.sp, Ink.Secondary)
                        T("↑ ${f1(s.txMbps)} Mbps", 9.sp, Ink.Secondary)
                    }
                }
            }
        }
        // ── 系統 ──
        Card(1.25f) {
            Column {
                Header("系統", s.device, null)
                T("${s.android}・開機 ${dur(s.uptimeSec)}", 9.sp, Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("IP " + s.ip.ifEmpty { "—" }, 9.sp, Ink.Tertiary)
                    Spacer(Modifier.weight(1f))
                    T("儲存 %.1f / %.0f GB".format(s.storUsed / 1e9f, s.storTotal / 1e9f), 9.sp, Ink.Tertiary)
                }
                Box(Modifier.padding(top = 3.dp).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .14f), RoundedCornerShape(2.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(if (s.storTotal > 0) (s.storUsed.toFloat() / s.storTotal).coerceIn(0f, 1f) else 0f).background(Color.White.copy(alpha = .8f), RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

private fun bgText(s: Stats) = if (s.bgFps > 0) "・背景 ${s.bgFps} fps" else ""

private fun dur(sec: Long): String {
    val d = sec / 86400; val h = sec % 86400 / 3600; val m = sec % 3600 / 60
    return when {
        d > 0 -> "$d 天 %02d:%02d".format(h, m)
        h > 0 -> "$h 小時 $m 分"
        m > 0 -> "$m 分鐘"
        else -> "$sec 秒"
    }
}

/** 收集琉璃自己每一幀的總時間（FrameMetrics，Android 7 起），給 GPU 卡算 fps 與每幀時間。 */
@Composable
private fun FrameMetricsHook() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        val window: Window = activity.window
        val thread = HandlerThread("liuli-frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            // 只算真正花在這一幀的時間（扣掉排隊等主執行緒的延遲）：閒置一陣子後的第一幀不會被算成幾百毫秒
            FrameStats.add((m.getMetric(FrameMetrics.TOTAL_DURATION) - m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)) / 1e6f)
        }
        runCatching { window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        onDispose {
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }
}

/** 火箭（齒輪正下方）：一鍵清理背景程式。按下時圖示往右上飛出、淡掉，再從下方浮回來。 */
@Composable
fun RocketButton(requester: FocusRequester?, onLaunch: suspend () -> Unit, modifier: Modifier = Modifier) {
    val fly = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    RoundIconButton(
        LIcons.Rocket, BgColors.GEAR, requester,
        onClick = {
            if (fly.isRunning) return@RoundIconButton
            scope.launch {
                fly.snapTo(0f)
                launch { onLaunch() }
                fly.animateTo(1f, tween(650, easing = androidx.compose.animation.core.CubicBezierEasing(.55f, 0f, .75f, .2f)))
                fly.snapTo(-1f)
                fly.animateTo(0f, tween(380))
            }
        },
        modifier = modifier,
        iconModifier = Modifier.graphicsLayer {
            val v = fly.value
            if (v >= 0f) { translationX = v * 14.dp.toPx(); translationY = -v * 40.dp.toPx(); alpha = 1f - v }
            else { translationY = -v * 6.dp.toPx(); alpha = 1f + v }
        },
    )
}

package tw.liuli.launcher.geek

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.liuli.launcher.adb.AdbClient
import tw.liuli.launcher.adb.AdbKey
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * 技術宅模式的儀表列資料：每秒取樣一次（只在技術宅模式、主畫面看得到時）。
 *
 * Android 8 之後一般 App 讀不到 /proc/stat（整體 CPU 使用率），所以：
 * 1. 先試著直接讀；
 * 2. 讀不到而且「無線偵錯」已授權琉璃，就透過本機 ADB（127.0.0.1:5555）用 shell 身分讀；
 * 3. 都不行就退回「目前頻率 ÷ 最高頻率」當負載估計（標示為「頻率」）。
 * GPU 使用率：試幾個常見驅動的檔案，讀不到就只顯示型號與琉璃自己的每幀時間。
 */
@Immutable
data class Stats(
    // CPU
    val cpuName: String = "",
    val cpuPct: Float = -1f,
    /** true＝真正的使用率；false＝用頻率估的負載。 */
    val cpuReal: Boolean = false,
    val cores: List<Float> = emptyList(),
    val cpuGhz: Float = 0f,
    val cpuHist: List<Float> = emptyList(),
    // GPU
    val gpuName: String = "",
    val gpuPct: Float = -1f,
    val fps: Float = -1f,
    val frameMs: Float = -1f,
    /** 背景引擎目前的更新率（自動在 30／20／15 之間調整）。 */
    val bgFps: Int = 0,
    val fpsHist: List<Float> = emptyList(),
    // 記憶體
    val memTotal: Long = 0, val memAvail: Long = 0, val memLow: Boolean = false,
    // 網路
    val net: NetKind = NetKind.NONE,
    val netDetail: String = "",
    val pingMs: Float = -1f, val gatewayMs: Float = -1f, val jitterMs: Float = -1f,
    val rxMbps: Float = 0f, val txMbps: Float = 0f,
    val netHist: List<Float> = emptyList(),
    val offlineSec: Long = 0,
    // 系統
    val device: String = "", val android: String = "",
    val uptimeSec: Long = 0, val ip: String = "",
    val storUsed: Long = 0, val storTotal: Long = 0,
) {
    val memUsed: Long get() = memTotal - memAvail
}

enum class NetKind { WIFI, ETHERNET, NONE }

/** 琉璃自己的畫面更新率（由 FrameMetrics 回報；在主執行緒外寫、取樣執行緒讀）。 */
object FrameStats {
    @Volatile private var frames = 0
    @Volatile private var sumMs = 0f
    fun add(ms: Float) { frames++; sumMs += ms }
    /** 取走上次以來的幀數與平均每幀時間。 */
    fun take(): Pair<Int, Float> { val f = frames; val s = sumMs; frames = 0; sumMs = 0f; return f to (if (f > 0) s / f else -1f) }
}

class StatsSampler(private val context: Context) {
    private val hist = 60
    private val cpuHist = ArrayDeque<Float>(); private val fpsHist = ArrayDeque<Float>(); private val netHist = ArrayDeque<Float>()
    private var lastStat: Map<String, LongArray>? = null
    @Volatile private var adb: AdbClient? = null
    private var adbTryAt = 0L
    private var adbTries = 0
    @Volatile private var adbDenied = false
    private var lastRx = -1L; private var lastTx = -1L; private var lastAt = 0L
    private var pingAt = 0L; private var lastPing = -1f; private var lastGw = -1f
    private val pings = ArrayDeque<Float>()
    private var offlineSince = 0L
    private var lastFrameAt = 0L
    private val cpuName by lazy { cpuModel() }
    private val gpuName by lazy { GpuInfo.renderer() }
    private var cur = Stats()

    suspend fun sample(): Stats = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        // ── CPU ──
        val freqs = (0 until 16).mapNotNull { i ->
            val cur = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: return@mapNotNull null
            val max = readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") ?: return@mapNotNull null
            cur.toFloat() / max.coerceAtLeast(1)
        }
        val maxGhz = (readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq") ?: 0L) / 1e6f
        val curGhz = (readLong("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq") ?: 0L) / 1e6f
        val statLines = procStat()
        var real = false
        var cores = freqs
        var pct = if (freqs.isNotEmpty()) freqs.average().toFloat() * 100f else -1f
        if (statLines != null) {
            // 以名稱對應（cpu、cpu0、cpu1…）：這類晶片會動態關掉核心，/proc/stat 的行數會變
            val cur2 = HashMap<String, LongArray>()
            for (l in statLines) {
                val f = l.trim().split(Regex("\\s+"))
                val v = f.drop(1).mapNotNull { it.toLongOrNull() }
                if (v.size >= 5) cur2[f[0]] = longArrayOf(v.take(8).sum(), v[3] + v[4])
            }
            val prev = lastStat
            fun use(k: String): Float? {
                val a = cur2[k] ?: return null
                val b = prev?.get(k) ?: return null
                val tot = a[0] - b[0]; val idle = a[1] - b[1]
                return if (tot > 0) (1f - idle.toFloat() / tot).coerceIn(0f, 1f) else null
            }
            use("cpu")?.let { pct = it * 100f; real = true }
            if (real) cores = (0 until maxOf(freqs.size, 1)).map { i -> use("cpu$i") ?: 0f }   // 關掉的核心＝0
            lastStat = cur2
        }
        push(cpuHist, pct.coerceAtLeast(0f))
        // ── 畫面 ──
        val (frames, ms) = FrameStats.take()
        val dt = if (lastFrameAt == 0L) 1f else ((now - lastFrameAt) / 1000f).coerceAtLeast(.2f)
        lastFrameAt = now
        val fps = if (frames == 0) 0f else (frames / dt).coerceAtMost(120f)
        push(fpsHist, fps)
        // ── 記憶體 ──
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // ── 網路 ──
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val an = cm.activeNetwork
        val caps = an?.let { cm.getNetworkCapabilities(it) }
        val lp = an?.let { cm.getLinkProperties(it) }
        val kind = when {
            caps == null -> NetKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetKind.WIFI
            else -> NetKind.NONE
        }
        if (kind == NetKind.NONE) { if (offlineSince == 0L) offlineSince = now } else offlineSince = 0L
        var detail = ""
        if (kind == NetKind.WIFI) {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION") val wi = wm?.connectionInfo
            if (wi != null) {
                val band = if (wi.frequency > 4900) "5 GHz" else if (wi.frequency > 0) "2.4 GHz" else ""
                detail = listOf(band, "${wi.rssi} dBm", if (wi.linkSpeed > 0) "${wi.linkSpeed} Mbps" else "").filter { it.isNotEmpty() }.joinToString("・")
            }
        } else if (kind == NetKind.ETHERNET) detail = "乙太網路" + (lp?.interfaceName?.let { "・$it" } ?: "")
        val ip = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: ""
        // 延遲：每 2 秒量一次 TCP 連線時間（到路由器、到網際網路）。被拒絕也算——RST 回來的時間就是來回時間
        if (kind != NetKind.NONE && now - pingAt >= 2000) {
            pingAt = now
            val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            lastGw = if (gw != null) tcpRtt(InetSocketAddress(gw, 53)) else -1f
            lastPing = tcpRtt(InetSocketAddress("1.1.1.1", 443))
            if (lastPing >= 0) push(pings, lastPing, 10)
        }
        if (kind == NetKind.NONE) { lastPing = -1f; lastGw = -1f; pings.clear() }
        val jitter = if (pings.size >= 2) pings.zipWithNext { a, b -> abs(a - b) }.average().toFloat() else -1f
        push(netHist, lastPing.coerceAtLeast(0f))
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
        var rxM = 0f; var txM = 0f
        if (lastRx >= 0 && now > lastAt) {
            val s = (now - lastAt) / 1000f
            rxM = ((rx - lastRx) * 8 / 1e6f / s).coerceAtLeast(0f); txM = ((tx - lastTx) * 8 / 1e6f / s).coerceAtLeast(0f)
        }
        lastRx = rx; lastTx = tx; lastAt = now
        // ── 儲存 ──
        val sf = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val total = sf?.totalBytes ?: 0L; val free = sf?.availableBytes ?: 0L

        cur = Stats(
            cpuName = cpuName, cpuPct = pct, cpuReal = real, cores = cores, cpuGhz = if (curGhz > 0) curGhz else maxGhz, cpuHist = cpuHist.toList(),
            gpuName = gpuName, gpuPct = GpuInfo.utilization(), fps = fps, frameMs = ms, bgFps = tw.liuli.launcher.ui.bg.BackgroundEngine.bgFps, fpsHist = fpsHist.toList(),
            memTotal = mi.totalMem, memAvail = mi.availMem, memLow = mi.lowMemory,
            net = kind, netDetail = detail, pingMs = lastPing, gatewayMs = lastGw, jitterMs = jitter, rxMbps = rxM, txMbps = txM,
            netHist = netHist.toList(), offlineSec = if (offlineSince > 0) (now - offlineSince) / 1000 else 0,
            device = deviceName(), android = "Android ${Build.VERSION.RELEASE}",
            uptimeSec = now / 1000, ip = ip, storUsed = total - free, storTotal = total,
        )
        cur
    }

    private fun push(q: ArrayDeque<Float>, v: Float, n: Int = hist) { q.addLast(v); while (q.size > n) q.removeFirst() }

    /** /proc/stat 的 cpu 開頭幾行：先直接讀，不行再走本機 ADB（只試一次連線，失敗就不再打擾）。 */
    private fun procStat(): List<String>? {
        runCatching { File("/proc/stat").readLines().filter { it.startsWith("cpu") } }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        // 另開執行緒連線（第一次可能要等電視上的授權對話框），不卡住每秒的取樣。
        // 連不上（剛開機、剛重裝、ADB 還沒起來）就每 20 秒再試，最多 6 次；電視明確拒絕這把金鑰就不再打擾
        val now = SystemClock.elapsedRealtime()
        if (adb == null && !adbDenied && adbTries < 6 && now - adbTryAt > 20_000) {
            adbTryAt = now; adbTries++
            Thread({
                try {
                    adb = AdbClient("127.0.0.1", 5555, AdbKey.load(context)).apply { connect(4000) }
                } catch (e: AdbClient.AdbException) {
                    if (e.message?.contains("未授權") == true) adbDenied = true
                } catch (_: Exception) { }
            }, "liuli-geek-adb").apply { isDaemon = true; start() }
        }
        val a = adb ?: return null
        return runCatching { a.shell("grep '^cpu' /proc/stat").lines().filter { it.startsWith("cpu") } }
            .onFailure { runCatching { a.close() }; adb = null }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun close() { runCatching { adb?.close() }; adb = null }

    private fun tcpRtt(addr: InetSocketAddress): Float {
        val t0 = System.nanoTime()
        return try {
            Socket().use { it.connect(addr, 1500) }
            (System.nanoTime() - t0) / 1e6f
        } catch (e: java.net.ConnectException) {
            val ms = (System.nanoTime() - t0) / 1e6f
            if (ms < 1400f) ms else -1f
        } catch (e: Exception) { -1f }
    }

    private fun readLong(path: String): Long? = runCatching { File(path).readText().trim().toLong() }.getOrNull()

    private fun cpuModel(): String {
        val info = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
        val hw = info.firstOrNull { it.startsWith("Hardware") }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.HARDWARE.uppercase()
        val parts = info.filter { it.startsWith("CPU part") }.map { it.substringAfter(':').trim().lowercase() }
        val counts = parts.groupingBy { it }.eachCount()
        val coreText = counts.entries.joinToString(" + ") { (p, n) -> "$n× ${CORE_NAMES[p] ?: "ARM $p"}" }
        val vendor = when {
            hw.startsWith("M7") || hw.startsWith("MT") || hw.startsWith("MSD") -> "MediaTek "
            hw.startsWith("AML") || hw.startsWith("S9") -> "Amlogic "
            hw.startsWith("RK") -> "Rockchip "
            else -> ""
        }
        return vendor + hw + if (coreText.isNotEmpty()) "・$coreText" else ""
    }

    private fun deviceName(): String {
        val man = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(man, ignoreCase = true)) model else "$man $model"
    }

    private companion object {
        val CORE_NAMES = mapOf(
            "0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55", "0xd07" to "Cortex-A57",
            "0xd08" to "Cortex-A72", "0xd09" to "Cortex-A73", "0xd0a" to "Cortex-A75", "0xd0b" to "Cortex-A76",
            "0xd0d" to "Cortex-A77", "0xd41" to "Cortex-A78", "0xd46" to "Cortex-A510", "0xd47" to "Cortex-A710",
            "0xc07" to "Cortex-A7", "0xc09" to "Cortex-A9", "0xc0f" to "Cortex-A15", "0xc0d" to "Cortex-A17",
        )
    }
}

/** GPU 型號（OpenGL 的 GL_RENDERER，開一個 1×1 的離屏 EGL 環境問一次）與使用率（讀得到才有）。 */
object GpuInfo {
    @Volatile private var name: String? = null

    fun renderer(): String = name ?: query().also { name = it }

    private fun query(): String = runCatching {
        val d = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); android.opengl.EGL14.eglInitialize(d, v, 0, v, 1)
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1); val n = IntArray(1)
        android.opengl.EGL14.eglChooseConfig(d, intArrayOf(android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT, android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
        val ctx = android.opengl.EGL14.eglCreateContext(d, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, android.opengl.EGL14.EGL_NONE), 0)
        val surf = android.opengl.EGL14.eglCreatePbufferSurface(d, cfg[0], intArrayOf(android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE), 0)
        android.opengl.EGL14.eglMakeCurrent(d, surf, surf, ctx)
        val r = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER) ?: ""
        android.opengl.EGL14.eglMakeCurrent(d, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT)
        android.opengl.EGL14.eglDestroySurface(d, surf); android.opengl.EGL14.eglDestroyContext(d, ctx)
        r.trim()
    }.getOrDefault("").ifEmpty { "GPU" }

    private val paths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",          // Qualcomm Adreno
        "/sys/class/misc/mali0/device/utilization",              // Mali Midgard／Bifrost（部分核心）
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/mali-utgard.0/utilization",
        "/sys/module/mali/parameters/mali_gpu_utilization",
        "/sys/kernel/debug/mali0/gpu_utilization",
    )
    private var path: String? = null
    private var probed = false

    /** 0～100；讀不到回 -1。 */
    fun utilization(): Float {
        if (!probed) { probed = true; path = paths.firstOrNull { p -> runCatching { File(p).readText() }.isSuccess } }
        val p = path ?: return -1f
        val s = runCatching { File(p).readText().trim() }.getOrNull() ?: return -1f
        val n = Regex("\\d+").find(s)?.value?.toFloatOrNull() ?: return -1f
        return if (n > 100f) n / 2.56f else n   // 有些驅動回 0～255
    }
}

/** 火箭的結果：清理前後的可用記憶體與總量（位元組）。 */
@Immutable
data class RocketResult(val before: Long, val after: Long, val total: Long) {
    val freed: Long get() = (after - before).coerceAtLeast(0L)
}

/** 火箭：結束所有背景程序（不動前景與琉璃自己），回傳清理前後的可用記憶體。 */
object Rocket {
    suspend fun launch(context: Context): RocketResult = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun avail() = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        val pm = context.packageManager
        val self = context.packageName
        @Suppress("DEPRECATION")
        for (p in pm.getInstalledPackages(0)) {
            if (p.packageName == self) continue
            runCatching { am.killBackgroundProcesses(p.packageName) }
        }
        kotlinx.coroutines.delay(900)   // 系統回收記憶體要一點時間
        val total = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        RocketResult(before, avail(), total)
    }
}

package tw.liuli.launcher.ui.bg

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 效能量測用開關（只能用 adb 觸發：ACTION=probe:bg,home,glass,tiles；probe:none 全部恢復）。
 * 一層一層關掉，量出 GPU 時間花在哪裡。一般使用永遠是全部開著。
 */
object PerfProbe {
    var noBg by mutableStateOf(false)
    var noHome by mutableStateOf(false)
    var noGlass by mutableStateOf(false)
    var noTiles by mutableStateOf(false)

    fun apply(arg: String) {
        val f = arg.split(',').map { it.trim() }.toSet()
        noBg = "bg" in f; noHome = "home" in f; noGlass = "glass" in f; noTiles = "tiles" in f
    }
}

package tw.liuli.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 琉璃是不是在前景（Activity resumed）。看 YouTube、切到 HDMI 時琉璃在背景，
 * 主畫面的計時（閒置自動進入簡潔模式）與技術宅模式的取樣都要停下來；回到前景時重新開始計時。
 */
object AppForeground {
    /** true＝琉璃在前景。 */
    var resumed by mutableStateOf(false)
        internal set
    /** 每次回到前景加一（用來重設閒置計時）。 */
    var resumeCount by mutableIntStateOf(0)
        internal set
}

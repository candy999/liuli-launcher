package tw.liuli.launcher.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import tw.liuli.launcher.ui.theme.GText
import tw.liuli.launcher.ui.theme.Ink

/**
 * 「關於琉璃」標頭：專案網址的 QR code（用手機掃描打開 GitHub 頁面）＋網址。
 * QR code 每個模組是一個像素的小圖，放大時用最近鄰取樣，邊緣保持銳利、好掃。
 */
@Composable
fun AboutHeader(url: String) {
    val qr = remember(url) {
        val m = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF111318.toInt() else 0xFFFFFFFF.toInt() }
        Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(132.dp)) {
            val pad = 8.dp.toPx()
            drawRoundRect(Color.White, cornerRadius = CornerRadius(12.dp.toPx()))
            val side = (size.minDimension - pad * 2).toInt()
            drawImage(
                qr, dstOffset = androidx.compose.ui.unit.IntOffset(pad.toInt(), pad.toInt()), dstSize = IntSize(side, side),
                filterQuality = FilterQuality.None,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            GText("用手機掃描", size = 14.sp)
            GText("打開專案頁面", size = 14.sp)
            Spacer(Modifier.padding(top = 6.dp))
            GText("原始碼、說明、問題回報", size = 11.sp, color = Ink.Tertiary, maxLines = 2)
        }
    }
}

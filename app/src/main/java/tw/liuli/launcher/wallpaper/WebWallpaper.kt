package tw.liuli.launcher.wallpaper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import tw.liuli.launcher.context.ContextParams

/** 送進網頁的按鍵事件。 */
data class WallpaperKey(val key: String, val code: Int, val action: String, val long: Boolean)

/**
 * 網頁桌布。
 * - 載入時把情境參數加在網址上（?hour=21&wx=rain&bai=1…）
 * - 之後每次情境改變：不重新載入，改用 history.replaceState 更新網址，並觸發 `liuli:context` 事件
 * - 遙控器按鍵以 `liuli:key` 事件送進網頁
 * - 網址上原本就寫死的參數優先（方便測試，例如 ?wx=rain 強制下雨）
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebWallpaper(
    baseUrl: String,
    params: Map<String, String>,
    keys: Flow<WallpaperKey>?,
    snapshot: MutableState<ImageBitmap?>?,
    onFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentParams = rememberUpdatedState(params)
    val failed = rememberUpdatedState(onFailed)

    val fixedKeys = remember(baseUrl) { Uri.parse(baseUrl).queryParameterNames.toSet() }
    val loader = remember {
        WebViewAssetLoader.Builder()
            .setDomain(WallpaperPacks.HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/local/", WebViewAssetLoader.InternalStoragePathHandler(context, WallpaperPacks.packsDir(context)))
            .build()
    }

    val webView = remember(baseUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            // 桌布只是背景：不能搶遙控器焦點
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failed.value()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    push(view, currentParams.value, fixedKeys)
                }
            }
            loadUrl(buildUrl(baseUrl, currentParams.value, fixedKeys))
        }
    }

    // 情境改變 → 推給網頁（不重新載入，動畫不中斷）
    LaunchedEffect(webView, params) { push(webView, params, fixedKeys) }

    // 遙控器按鍵 → liuli:key
    LaunchedEffect(webView, keys) {
        keys?.collect { k ->
            val detail = JSONObject()
                .put("key", k.key).put("code", k.code).put("action", k.action).put("long", k.long)
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('liuli:key',{detail:$detail}));",
                null,
            )
        }
    }

    // 玻璃面板需要「背後的畫面」：每 2.5 秒擷取一張 1/4 解析度的截圖給它模糊
    if (snapshot != null) {
        LaunchedEffect(webView) {
            while (true) {
                delay(2500)
                if (webView.width > 0 && webView.height > 0 && webView.isShown) {
                    snapshot.value = capture(webView)
                }
            }
        }
    }

    // 離開主畫面時暫停網頁（省電、省 GPU）
    DisposableEffect(lifecycleOwner, webView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { webView.onResume(); webView.resumeTimers() }
                Lifecycle.Event.ON_PAUSE -> { webView.onPause(); webView.pauseTimers() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView.destroy()
        }
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private fun buildUrl(base: String, params: Map<String, String>, fixed: Set<String>): String {
    val uri = Uri.parse(base)
    val builder = uri.buildUpon()
    for ((k, v) in params) if (k !in fixed) builder.appendQueryParameter(k, v)
    return builder.build().toString()
}

private fun push(view: WebView, params: Map<String, String>, fixed: Set<String>) {
    val live = params.filterKeys { it !in fixed }
    val json = ContextParams.toJson(live)
    view.evaluateJavascript(
        """
        (function(c){
          try{var u=new URL(location.href);for(var k in c){u.searchParams.set(k,c[k]);}
              history.replaceState(history.state,'',u.toString());}catch(e){}
          var all={};try{new URL(location.href).searchParams.forEach(function(v,k){all[k]=v;});}catch(e){all=c;}
          window.LIULI=all;
          window.dispatchEvent(new CustomEvent('liuli:context',{detail:all}));
        })($json);
        """.trimIndent(),
        null,
    )
}

private fun capture(view: WebView): ImageBitmap? = runCatching {
    val scale = 0.25f
    val bmp = Bitmap.createBitmap(
        (view.width * scale).toInt().coerceAtLeast(1),
        (view.height * scale).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(bmp)
    canvas.scale(scale, scale)
    view.draw(canvas)
    bmp.asImageBitmap()
}.getOrNull()

package com.webviewapp

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: TopProgressBar
    private lateinit var overlay: View
    private lateinit var spinner: IOSSpinnerView
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var overlayVisible = false

    // 视频全屏相关
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private val dotsFrames = arrayOf("", ".", "..", "...")
    private var dotsIndex = 0
    private val dotsRunnable = object : Runnable {
        override fun run() {
            loadingText.text = "加载中${dotsFrames[dotsIndex]}"
            dotsIndex = (dotsIndex + 1) % dotsFrames.size
            handler.postDelayed(this, 500)
        }
    }

    private val timeoutRunnable  = Runnable { hideOverlay() }
    private val delayHideRunnable = Runnable { hideOverlay() }

    private val EDGE_SWIPE_MIN_X = 80f
    private val EDGE_SWIPE_MAX_Y = 120f
    private val EDGE_SWIPE_MIN_V = 200f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        setContentView(R.layout.activity_main)
        webView     = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        overlay     = findViewById(R.id.overlay)
        spinner     = findViewById(R.id.spinner)
        loadingText = findViewById(R.id.loadingText)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setColorSchemeColors(
            android.graphics.Color.parseColor("#6366F1")
        )
        val density = resources.displayMetrics.density
        val triggerDp = 120
        try {
            val field = androidx.swiperefreshlayout.widget.SwipeRefreshLayout::class.java
                .getDeclaredField("mTotalDragDistance")
            field.isAccessible = true
            field.setFloat(swipeRefresh, triggerDp * density)
        } catch (_: Exception) {}
        swipeRefresh.setProgressViewOffset(false, 0, (triggerDp * density).toInt())
        swipeRefresh.setOnRefreshListener {
            forceShowOverlay()
            handler.postDelayed({ webView.reload() }, 50)
        }
        showOverlay()
        setupWebView()
        setupEdgeSwipe()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            useWideViewPort                  = true
            loadWithOverviewMode             = true
            setSupportZoom(false)
            builtInZoomControls              = false
            displayZoomControls              = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowContentAccess               = true
            allowFileAccess                  = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
        }
        try {
            android.webkit.ServiceWorkerController.getInstance().serviceWorkerWebSettings?.apply {
                allowContentAccess = true
                allowFileAccess    = true
            }
            android.webkit.ServiceWorkerController.getInstance()
                .setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest) = null
                })
        } catch (_: Exception) {}
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                handler.removeCallbacks(delayHideRunnable)
                forceShowOverlay()
            }

            override fun onPageFinished(view: WebView, url: String) {
                swipeRefresh.isRefreshing = false
                fetchThemeColor(view)
                handler.removeCallbacks(delayHideRunnable)
                handler.postDelayed(delayHideRunnable, 1200)
                view.evaluateJavascript("""
                    (function(){
                        function done(){
                            requestAnimationFrame(function(){
                                requestAnimationFrame(function(){
                                    try{ window._pakrBridge.onPageReady(); }catch(e){}
                                });
                            });
                        }
                        if(document.readyState==='complete'){ done(); }
                        else { window.addEventListener('load', done, {once:true}); }
                    })();
                """.trimIndent(), null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    swipeRefresh.isRefreshing = false
                    handler.removeCallbacks(delayHideRunnable)
                    hideOverlay()
                    view.loadData(errorHtml(), "text/html", "UTF-8")
                }
            }

            @Suppress("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.proceed()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                if (request.isForMainFrame && (errorResponse.statusCode >= 400)) {
                    swipeRefresh.isRefreshing = false
                    handler.removeCallbacks(delayHideRunnable)
                    hideOverlay()
                    view.loadData(errorHtml(), "text/html", "UTF-8")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.setProgress(newProgress)
                if (newProgress <= 5) showOverlay()
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: android.os.Message
            ): Boolean {
                val href = view.handler.obtainMessage()
                view.requestFocusNodeHref(href)
                val url = href.data?.getString("url")
                if (!url.isNullOrEmpty()) view.loadUrl(url)
                else {
                    val child = WebView(view.context)
                    child.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView, u: String, f: android.graphics.Bitmap?) {
                            view.loadUrl(u)
                            child.destroy()
                        }
                    }
                    val transport = msg.obj as? WebView.WebViewTransport
                    transport?.webView = child
                    msg.sendToTarget()
                }
                return true
            }
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                fileChooserCallbackRef?.onReceiveValue(null)
                fileChooserCallbackRef = filePathCallback
                webView.settings.userAgentString = DESKTOP_UA
                try {
                    val photoFile = java.io.File(
                        cacheDir,
                        "webview_uploads/camera_${System.currentTimeMillis()}.jpg"
                    ).also { it.parentFile?.mkdirs() }
                    cameraImageUri = androidx.core.content.FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.fileprovider",
                        photoFile
                    )
                    val cameraIntent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraImageUri)
                        addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    val fileIntent = fileChooserParams.createIntent()
                    val chooser = android.content.Intent.createChooser(fileIntent, "选择图片").apply {
                        putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
                } catch (e: Exception) {
                    filePathCallback.onReceiveValue(null)
                    fileChooserCallbackRef = null
                }
                return true
            }

            // ========== 视频全屏支持 ==========
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // 如果已有全屏 View，先关闭旧的
                if (fullscreenView != null) {
                    onHideCustomView()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback

                // 隐藏 WebView，把全屏 View 加到根布局
                webView.visibility = View.GONE
                overlay.visibility = View.GONE
                val root = findViewById<ViewGroup>(android.R.id.content)
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                root.addView(view, params)

                // 强制横屏 + 沉浸式全屏
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

            override fun onHideCustomView() {
                val view = fullscreenView ?: return
                fullscreenView = null
                // 移除全屏 View
                (view.parent as? ViewGroup)?.removeView(view)
                // 恢复 WebView
                webView.visibility = View.VISIBLE
                overlay.visibility = View.GONE
                // 恢复竖屏
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                // 恢复系统栏（保持原来的隐藏行为）
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                // 回调通知 WebView
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val uri = Uri.parse(url)
                val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                val req = DownloadManager.Request(uri).apply {
                    setMimeType(mimetype)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("正在下载...")
                    setTitle(filename)
                    allowScanningByMediaScanner()
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(req)
                android.widget.Toast.makeText(this, "开始下载：$filename", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(swipeRefresh) { view, insets ->
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            val lp = view.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.bottomMargin = imeInsets.bottom
            view.layoutParams = lp
            webView.setPadding(0, 0, 0, 0)
            insets
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onThemeColor(hex: String) {
                try {
                    val color = android.graphics.Color.parseColor(hex)
                    runOnUiThread { progressBar.setBarColor(color) }
                } catch (e: Exception) {}
            }
        }, "ThemeBridge")
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onPageReady() {
                handler.post {
                    handler.removeCallbacks(delayHideRunnable)
                    hideOverlay()
                }
            }
        }, "_pakrBridge")
        webView.settings.userAgentString = MOBILE_UA
        var lastScrollY = 0
        var isTouching = false
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            lastScrollY = scrollY
            if (!isTouching) {
                if (scrollY > 0) swipeRefresh.isEnabled = false
            }
        }
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isTouching = true
                    swipeRefresh.isEnabled = (lastScrollY == 0)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (lastScrollY > 0) swipeRefresh.isEnabled = false
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isTouching = false
                    swipeRefresh.isEnabled = false
                    handler.postDelayed({
                        swipeRefresh.isEnabled = (lastScrollY == 0)
                    }, 300)
                }
            }
            false
        }
        webView.loadUrl(APP_URL)
    }

    private fun fetchThemeColor(view: WebView) {
        val js = """
            (function() {
                var m = document.querySelector('meta[name="theme-color"]');
                if (m && m.content) { ThemeBridge.onThemeColor(m.content); return; }
                var el = document.elementFromPoint(window.innerWidth/2, 1);
                if (el) {
                    var bg = getComputedStyle(el).backgroundColor;
                    var r = bg.match(/rgba?\((\d+),(\d+),(\d+)/);
                    if (r) ThemeBridge.onThemeColor(
                        '#' + [r[1],r[2],r[3]].map(function(x){
                            return ('0' + parseInt(x).toString(16)).slice(-2);
                        }).join('')
                    );
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    private fun showOverlay() {
        if (overlayVisible) return
        overlayVisible = true
        overlay.animate().cancel()
        overlay.alpha = 1f
        overlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.setProgress(0)
        spinner.start()
        dotsIndex = 0
        handler.removeCallbacks(dotsRunnable)
        handler.post(dotsRunnable)
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, 30_000L)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupEdgeSwipe() {
        val edgeLeft  = findViewById<View>(R.id.edgeLeft)
        val edgeRight = findViewById<View>(R.id.edgeRight)

        fun makeGesture(onSwipeRight: (() -> Unit)? = null, onSwipeLeft: (() -> Unit)? = null)
            = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    if (abs(dx) < EDGE_SWIPE_MIN_X) return false
                    if (abs(dy) > EDGE_SWIPE_MAX_Y) return false
                    if (abs(vx) < EDGE_SWIPE_MIN_V) return false
                    return if (dx > 0) { onSwipeRight?.invoke(); onSwipeRight != null }
                    else               { onSwipeLeft?.invoke();  onSwipeLeft  != null }
                }
            })

        val leftGesture  = makeGesture(onSwipeRight = {
            if (webView.canGoBack()) {
                forceShowOverlay()
                handler.postDelayed({ webView.goBack() }, 50)
                handler.postDelayed({ if (overlayVisible) hideOverlay() }, 1500)
            }
        })
        val rightGesture = makeGesture(onSwipeLeft = {
            if (webView.canGoForward()) {
                forceShowOverlay()
                handler.postDelayed({ webView.goForward() }, 50)
                handler.postDelayed({ if (overlayVisible) hideOverlay() }, 1500)
            }
        })

        edgeLeft.setOnTouchListener  { _, e -> leftGesture.onTouchEvent(e) }
        edgeRight.setOnTouchListener { _, e -> rightGesture.onTouchEvent(e) }
    }

    private fun forceShowOverlay() {
        overlayVisible = false
        showOverlay()
    }

    private fun hideOverlay() {
        if (!overlayVisible) return
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(dotsRunnable)
        overlayVisible = false
        overlay.animate().cancel()
        overlay.animate().alpha(0f).setDuration(300).withEndAction {
            if (!overlayVisible) {
                overlay.visibility = View.GONE
                spinner.stop()
                progressBar.visibility = View.GONE
            }
        }.start()
    }

    private fun errorHtml() = """
        <html><body style="margin:0;display:flex;align-items:center;justify-content:center;
        height:100vh;font-family:sans-serif;flex-direction:column;background:#fff;color:#333;">
        <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="#999" stroke-width="1.5">
          <circle cx="12" cy="12" r="10"/>
          <line x1="12" y1="8" x2="12" y2="12"/>
          <line x1="12" y1="16" x2="12.01" y2="16"/>
        </svg>
        <p style="margin-top:16px;font-size:15px;">网络连接失败</p>
        <button onclick="location.reload()"
          style="margin-top:12px;padding:10px 24px;border:none;border-radius:999px;
          background:#000;color:#fff;font-size:14px;cursor:pointer;">重试</button>
        </body></html>
    """.trimIndent()

    private var backPressedTime = 0L
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 全屏状态下按返回键先退出全屏
        if (fullscreenView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            } else {
                backPressedTime = now
                android.widget.Toast.makeText(this, "再按一次退出", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        fileChooserCallbackRef?.onReceiveValue(null)
        fileChooserCallbackRef = null
        // 清理全屏 View
        fullscreenView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullscreenView = null
        fullscreenCallback = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private var fileChooserCallbackRef: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            val results: Array<Uri>? = if (resultCode == RESULT_OK) {
                when {
                    (data == null || data.data == null) && cameraImageUri != null -> {
                        arrayOf(cameraImageUri!!)
                    }
                    data?.clipData != null -> {
                        val clip = data.clipData!!
                        Array(clip.itemCount) { i ->
                            clip.getItemAt(i).uri.also { uri ->
                                try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                            }
                        }
                    }
                    data?.data != null -> {
                        val uri = data.data!!
                        try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                        arrayOf(uri)
                    }
                    else -> null
                }
            } else null
            fileChooserCallbackRef?.onReceiveValue(results)
            fileChooserCallbackRef = null
            cameraImageUri = null
            webView.settings.userAgentString = MOBILE_UA
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    companion object {
        const val APP_URL   = "{{APP_URL}}"
        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.6422.165 Mobile Safari/537.36"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private const val FILE_CHOOSER_REQUEST = 1001
    }
}

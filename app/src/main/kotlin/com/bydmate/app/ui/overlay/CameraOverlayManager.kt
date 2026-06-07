package com.bydmate.app.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.bydmate.app.R

object CameraOverlayManager {

    private const val TAG = "CameraOverlay"
    private const val BASE_SCREEN_FRACTION = 2f / 3f
    private const val CAMERA_SIZE_MULTIPLIER = 1.4f
    private val navyDark = Color.rgb(10, 22, 40)
    private val cardSurface = Color.rgb(22, 32, 50)
    private val cardSurfaceElevated = Color.rgb(29, 41, 64)
    private val cardBorder = Color.rgb(42, 58, 82)
    private val accentGreen = Color.rgb(74, 222, 128)
    private val accentTeal = Color.rgb(45, 212, 191)
    private val textPrimary = Color.rgb(226, 232, 240)
    private var currentView: View? = null
    private var currentWebView: WebView? = null

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, rawUrl: String): Boolean {
        if (!canShow(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted")
            return false
        }
        val url = normalizeUrl(rawUrl) ?: return false
        Handler(Looper.getMainLooper()).post {
            try {
                render(context.applicationContext, url)
            } catch (e: Exception) {
                Log.e(TAG, "show failed: ${e.message}")
            }
        }
        return true
    }

    fun close(context: Context) {
        Handler(Looper.getMainLooper()).post {
            dismiss(context.applicationContext)
        }
    }

    private fun render(context: Context, url: String) {
        dismiss(context)

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = context.resources.displayMetrics
        val overlayFraction = BASE_SCREEN_FRACTION * CAMERA_SIZE_MULTIPLIER
        val overlayWidth = (displayMetrics.widthPixels * overlayFraction).toInt()
        val overlayHeight = (displayMetrics.heightPixels * overlayFraction).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cardSurface)
                cornerRadius = dp(context, 12).toFloat()
                setStroke(dp(context, 1), accentTeal)
            }
            elevation = dp(context, 12).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 8), dp(context, 8), dp(context, 10), dp(context, 8))
            setBackgroundColor(cardSurface)
        }

        val refresh = actionButton(context, context.getString(R.string.camera_overlay_refresh)).apply {
            setOnClickListener { currentWebView?.reload() }
        }
        header.addView(refresh, LinearLayout.LayoutParams(
            dp(context, 92),
            dp(context, 34),
        ))

        val close = actionButton(context, context.getString(R.string.camera_overlay_close), destructive = true).apply {
            setOnClickListener { dismiss(context) }
        }
        header.addView(close, LinearLayout.LayoutParams(
            dp(context, 82),
            dp(context, 34),
        ).apply { leftMargin = dp(context, 6) })

        val title = TextView(context).apply {
            text = context.getString(R.string.camera_overlay_title)
            setTextColor(textPrimary)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { leftMargin = dp(context, 12) })

        val webView = WebView(context).apply {
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            loadUrl(url)
        }

        val webFrame = FrameLayout(context).apply {
            setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 8))
            setBackgroundColor(cardSurface)
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(webFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        currentWebView = webView

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }

        wm.addView(root, params)
        currentView = root
    }

    private fun dismiss(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        currentView?.let { view ->
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "dismiss failed: ${e.message}")
            }
        }
        currentWebView?.let { webView ->
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "webview destroy failed: ${e.message}")
            }
        }
        currentView = null
        currentWebView = null
    }

    private fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.contains("://")) trimmed else "https://$trimmed"
    }

    private fun actionButton(context: Context, label: String, destructive: Boolean = false): TextView =
        TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            setTextColor(if (destructive) textPrimary else navyDark)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 8).toFloat()
                if (destructive) {
                    setColor(cardSurfaceElevated)
                    setStroke(dp(context, 1), cardBorder)
                } else {
                    setColor(accentGreen)
                    setStroke(dp(context, 1), accentGreen)
                }
            }
            setPadding(dp(context, 10), 0, dp(context, 10), 0)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

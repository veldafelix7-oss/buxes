package app.buxes.island

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.compose.ui.graphics.asAndroidBitmap
import app.buxes.AppGraph
import app.buxes.MainActivity
import app.buxes.player.api.PlayIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/**
 * The Island outside the app (Buxes): the same capsule, drawn over other apps while music plays and
 * Buxes itself is off the display. It needs Android's "draw over other apps" permission, which only
 * the user can give ([askPermission] opens the page); without it there is only the in-app Island.
 * The view is a plain Android view, owned by the application, so it costs nothing of Compose.
 */
class IslandOverlay(private val app: Application, private val graph: AppGraph) {

    private class Shown(val title: String, val subtitle: String, val artworkUri: String?, val playing: Boolean)

    private val foreground = MutableStateFlow(false)
    private var started = 0
    private var view: IslandView? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { started++; foreground.value = started > 0 }
            override fun onActivityStopped(activity: Activity) { started--; foreground.value = started > 0 }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) { refresh() }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        graph.appScope.launch {
            combine(graph.playbackController.snapshot, foreground, graph.deviceSettings.islandOverlay) { s, fg, on ->
                val item = s.item
                if (on && !fg && item != null) Shown(item.title, item.artistDisplay, item.artworkUri, s.intent == PlayIntent.PLAY) else null
            }.distinctUntilChanged { a, b -> a?.title == b?.title && a?.subtitle == b?.subtitle && a?.artworkUri == b?.artworkUri && a?.playing == b?.playing }
                .collect { shown ->
                    if (shown == null || !canDraw(app)) hide() else show().bind(shown.title, shown.subtitle, shown.artworkUri, shown.playing)
                }
        }
    }

    /** Re-reads the permission (back from its page) without waiting for a playback change. */
    fun refresh() {
        if (!canDraw(app)) hide()
    }

    private fun show(): IslandView {
        view?.let { return it }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = app.resources.displayMetrics.density
        val v = IslandView(app, graph, density)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            (40 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, lp)
            view = v
        } catch (_: Exception) {
        }
        return v
    }

    private fun hide() {
        val v = view ?: return
        view = null
        v.stop()
        try { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    companion object {
        fun canDraw(context: Context) = Settings.canDrawOverlays(context)

        /** Android's own page where the user lets Buxes draw over other apps. */
        fun askPermission(activity: Activity) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
            } catch (_: Exception) {
                try { activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } catch (_: Exception) {}
            }
        }
    }
}

@SuppressLint("ViewConstructor")
private class IslandView(context: Context, private val graph: AppGraph, private val density: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * density; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 11 * density }
    private var title = ""
    private var subtitle = ""
    private var artUri: String? = null
    private var art: Bitmap? = null
    private var playing = false
    private var phase = 0f
    private var artJob: Job? = null
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    private var downX = 0f
    private var downT = 0L
    private val widthPx: Int get() {
        val text = maxOf(titlePaint.measureText(title), subPaint.measureText(subtitle)).coerceIn(60 * density, 170 * density)
        return (5 * density + 30 * density + 10 * density + text + 10 * density + 18 * density + 14 * density).toInt()
    }

    fun bind(title: String, subtitle: String, artworkUri: String?, playing: Boolean) {
        val changed = title != this.title || subtitle != this.subtitle
        this.title = title; this.subtitle = subtitle; this.playing = playing
        if (artworkUri != artUri || (art == null && artJob?.isActive != true)) {
            artUri = artworkUri
            art = null
            artJob?.cancel()
            artJob = artworkUri?.let { uri ->
                graph.appScope.launch {
                    art = runCatching { graph.artworkLoader.load(uri, (30 * density).toInt())?.asAndroidBitmap() }.getOrNull()
                    invalidate()
                }
            }
        }
        if (playing && !anim.isRunning) anim.start() else if (!playing && anim.isRunning) anim.cancel()
        if (changed) requestLayout()
        invalidate()
    }

    fun stop() { anim.cancel(); artJob?.cancel() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(widthPx, (40 * density).toInt())

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(225, 18, 18, 22)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRoundRect(RectF(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density), r, r, paint)
        paint.style = Paint.Style.FILL
        // The cover, round.
        val artSize = 30 * density
        val artLeft = 5 * density; val artTop = (h - artSize) / 2f
        val bmp = art
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            val scale = artSize / minOf(bmp.width, bmp.height)
            m.setScale(scale, scale)
            m.postTranslate(artLeft - (bmp.width * scale - artSize) / 2f, artTop - (bmp.height * scale - artSize) / 2f)
            shader.setLocalMatrix(m)
            paint.shader = shader
            paint.color = Color.WHITE
        } else {
            paint.color = Color.argb(255, 60, 60, 70)
        }
        canvas.drawCircle(artLeft + artSize / 2f, h / 2f, artSize / 2f, paint)
        paint.shader = null
        // Title and artist, cut to fit.
        val textLeft = artLeft + artSize + 10 * density
        val textRight = w - 14 * density - 18 * density - 10 * density
        val maxW = textRight - textLeft
        canvas.drawText(fit(title, titlePaint, maxW), textLeft, h / 2f - 2 * density, titlePaint)
        canvas.drawText(fit(subtitle, subPaint, maxW), textLeft, h / 2f + 11 * density, subPaint)
        // The bars.
        val barsLeft = w - 14 * density - 18 * density
        val barsH = 16 * density; val barW = 18 * density / 5f
        paint.color = Color.WHITE
        for (i in 0 until 3) {
            val level = if (playing) 0.3f + 0.7f * (0.5f + 0.5f * sin((phase + i * 0.33f) * 2f * Math.PI.toFloat() * (1f + i * 0.2f))) else 0.3f
            val bh = barsH * level
            val x = barsLeft + i * barW * 2f
            val bottom = h / 2f + barsH / 2f
            canvas.drawRoundRect(RectF(x, bottom - bh, x + barW, bottom), barW / 2f, barW / 2f, paint)
        }
    }

    private fun fit(text: String, p: Paint, maxW: Float): String {
        if (p.measureText(text) <= maxW) return text
        var n = text.length
        while (n > 1 && p.measureText(text.substring(0, n) + "…") > maxW) n--
        return text.substring(0, n) + "…"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downT = System.currentTimeMillis(); return true }
            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - downX
                when {
                    abs(dx) > 48 * density -> if (dx < 0) graph.playbackController.next() else graph.playbackController.previous()
                    System.currentTimeMillis() - downT < 400 -> open()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun open() {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN, "now-playing")
        }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }
}

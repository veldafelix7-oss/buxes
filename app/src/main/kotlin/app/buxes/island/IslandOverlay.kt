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
import android.graphics.Path
import android.graphics.Rect
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
 * the user can give ([askPermission] opens the page); without it there is no Island.
 * The view is a plain Android view, owned by the application, so it costs nothing of Compose.
 */
class IslandOverlay(private val app: Application, private val graph: AppGraph) {

    private class Shown(val title: String, val subtitle: String, val artworkUri: String?, val playing: Boolean)

    private val foreground = MutableStateFlow(false)
    /** Whether anything has played since the app's task began: a restored, paused queue shows nothing. */
    private val played = MutableStateFlow(false)
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
            graph.playbackController.snapshot.collect { if (it.intent == PlayIntent.PLAY && it.item != null) played.value = true }
        }
        graph.appScope.launch {
            // Swiped away from Recents: the Island goes with it, until the next play.
            graph.taskRemoved.collect { played.value = false; hide() }
        }
        graph.appScope.launch {
            combine(graph.playbackController.snapshot, foreground, graph.deviceSettings.islandOverlay, played) { s, fg, on, p ->
                val item = s.item
                if (on && !fg && p && item != null) Shown(item.title, item.artistDisplay, item.artworkUri, s.intent == PlayIntent.PLAY) else null
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
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (8 * density).toInt()
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Glass (Buxes): what's behind shows through, blurred where the phone can.
            if (Build.VERSION.SDK_INT >= 31) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                blurBehindRadius = (18 * density).toInt()
            }
        }
        val v = IslandView(app, graph, density) { x, y ->
            // The capsule sits where the camera is (or just under the status bar); moved once it knows.
            if (lp.x != x || lp.y != y) {
                lp.x = x; lp.y = y
                view?.let { runCatching { wm.updateViewLayout(it, lp) } }
            }
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

/**
 * Where the front camera is, read from the display's cutout: in the middle (the capsule opens out
 * from both its sides), in a corner (the capsule slides in from the other side), or nowhere (it
 * drops down from the top).
 */
private enum class Camera { CENTRE, LEFT, RIGHT, NONE }

@SuppressLint("ViewConstructor")
private class IslandView(
    context: Context,
    private val graph: AppGraph,
    private val density: Float,
    private val place: (x: Int, y: Int) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private var title = ""
    private var subtitle = ""
    private var artUri: String? = null
    private var art: Bitmap? = null
    private var playing = false
    private var artJob: Job? = null

    private var camera = Camera.NONE
    private var holeLeft = 0f
    private var holeRight = 0f
    private var screenW = context.resources.displayMetrics.widthPixels
    private var cutout: Rect? = null
    private var placed = false
    private var revealFrom = 0L
    private var startedAt = 0L
    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private var downX = 0f
    private var downT = 0L

    // The capsule is as tall as the camera's hole (Buxes); everything inside scales with that.
    private var h = 32 * density
    private val artSize: Float get() = h - 6 * density
    private val textBox: Float get() = 112 * density
    private val iconBox: Float get() = h * 0.6f
    private val heartBox: Float get() = h * 0.34f
    private val pad: Float get() = 3 * density
    private val gap: Float get() = 7 * density
    private val holeGap: Float get() = if (camera == Camera.CENTRE) (cutout?.width() ?: 0) + 8 * density else 0f
    private val leftPart: Float get() = pad + artSize + gap + textBox + gap
    private val widthPx: Int get() = (leftPart + holeGap + iconBox + 5 * density + heartBox + 10 * density).toInt()

    fun bind(title: String, subtitle: String, artworkUri: String?, playing: Boolean) {
        if (title != this.title) startedAt = System.currentTimeMillis()
        this.title = title; this.subtitle = subtitle; this.playing = playing
        if (artworkUri != artUri || (art == null && artJob?.isActive != true)) {
            artUri = artworkUri
            art = null
            artJob?.cancel()
            artJob = artworkUri?.let { uri ->
                graph.appScope.launch {
                    art = runCatching { graph.artworkLoader.load(uri, (40 * density).toInt())?.asAndroidBitmap() }.getOrNull()
                    invalidate()
                }
            }
        }
        invalidate()
    }

    fun stop() { clock.cancel(); artJob?.cancel() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!clock.isRunning) clock.start()
        startedAt = System.currentTimeMillis()
        post { readCamera() }
    }

    private fun readCamera() {
        screenW = context.resources.displayMetrics.widthPixels
        val rect = if (Build.VERSION.SDK_INT >= 28) rootWindowInsets?.displayCutout?.boundingRects?.minByOrNull { it.top } else null
        cutout = rect
        camera = when {
            rect == null || rect.top > 48 * density || rect.height() < 12 * density -> Camera.NONE
            rect.centerX() in (screenW * 0.35f).toInt()..(screenW * 0.65f).toInt() -> Camera.CENTRE
            rect.centerX() < screenW / 2 -> Camera.LEFT
            else -> Camera.RIGHT
        }
        h = (rect?.height()?.toFloat() ?: (32 * density)).coerceIn(26 * density, 48 * density)
        textPaint.textSize = (h * 0.38f).coerceIn(11 * density, 15 * density)
        val x: Int
        val y: Int
        when (camera) {
            Camera.CENTRE -> {
                x = (rect!!.centerX() - (leftPart + holeGap / 2f)).toInt()
                y = (rect.centerY() - h / 2f).toInt().coerceAtLeast(0)
                holeLeft = rect.left - x - 4 * density
                holeRight = rect.right - x + 4 * density
            }
            Camera.LEFT, Camera.RIGHT -> {
                x = (screenW - widthPx) / 2
                y = (rect!!.centerY() - h / 2f).toInt().coerceAtLeast(0)
            }
            Camera.NONE -> {
                x = (screenW - widthPx) / 2
                val status = if (Build.VERSION.SDK_INT >= 30) rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0 else 0
                y = status + (4 * density).toInt()
            }
        }
        placed = true
        revealFrom = System.currentTimeMillis()
        place(x, y)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(widthPx, h.toInt())

    override fun onDraw(canvas: Canvas) {
        if (!placed) return
        val now = System.currentTimeMillis()
        val reveal = ((now - revealFrom) / 420f).coerceIn(0f, 1f)
        val e = 1f - (1f - reveal) * (1f - reveal) * (1f - reveal)
        val w = width.toFloat(); val r = h / 2f
        val alpha = e
        canvas.save()
        when (camera) {
            Camera.CENTRE -> {
                val cx = (holeLeft + holeRight) / 2f
                val half = (holeRight - holeLeft) / 2f + (maxOf(cx, w - cx) - (holeRight - holeLeft) / 2f) * e
                canvas.clipRect(cx - half, 0f, cx + half, h)
            }
            Camera.LEFT -> canvas.translate(-(1f - e) * (w + screenW / 2f), 0f)
            Camera.RIGHT -> canvas.translate((1f - e) * (w + screenW / 2f), 0f)
            Camera.NONE -> canvas.translate(0f, -(1f - e) * (h + 12 * density))
        }
        // The glass: dark and see-through, with a thin bright rim.
        val blurred = Build.VERSION.SDK_INT >= 31
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(((if (blurred) 120 else 170) * alpha).toInt(), 10, 10, 14)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.argb((70 * alpha).toInt(), 255, 255, 255)
        canvas.drawRoundRect(RectF(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density), r, r, paint)
        paint.style = Paint.Style.FILL

        // The cover, round.
        var x = pad
        val artTop = (h - artSize) / 2f
        val bmp = art
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix()
            val scale = artSize / minOf(bmp.width, bmp.height)
            m.setScale(scale, scale)
            m.postTranslate(x - (bmp.width * scale - artSize) / 2f, artTop - (bmp.height * scale - artSize) / 2f)
            shader.setLocalMatrix(m)
            paint.shader = shader
            paint.color = Color.WHITE
            paint.alpha = (255 * alpha).toInt()
        } else {
            paint.color = Color.argb((255 * alpha).toInt(), 70, 70, 80)
        }
        canvas.drawCircle(x + artSize / 2f, h / 2f, artSize / 2f, paint)
        paint.shader = null
        x += artSize + gap

        // One line: the title runs through, then the artist, in turns.
        textPaint.alpha = (255 * alpha).toInt()
        canvas.save()
        canvas.clipRect(x, 0f, x + textBox, h)
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        runningLine(canvas, x, baseline, now)
        canvas.restore()
        x += textBox + gap + holeGap

        // Play or pause, breathing while the music plays.
        val t = ((now % 1400L) / 1400f)
        val breath = if (playing) 1f + 0.08f * sin(t * 2f * Math.PI.toFloat()) else 1f
        paint.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        val icx = x + iconBox / 2f; val icy = h / 2f
        canvas.save()
        canvas.scale(breath, breath, icx, icy)
        if (playing) {
            val bw = iconBox * 0.16f; val bh = iconBox * 0.6f; val sp = iconBox * 0.12f
            canvas.drawRoundRect(RectF(icx - sp / 2 - bw, icy - bh / 2, icx - sp / 2, icy + bh / 2), bw / 2, bw / 2, paint)
            canvas.drawRoundRect(RectF(icx + sp / 2, icy - bh / 2, icx + sp / 2 + bw, icy + bh / 2), bw / 2, bw / 2, paint)
        } else {
            val s = iconBox * 0.32f
            val tri = Path().apply {
                moveTo(icx - s * 0.8f, icy - s); lineTo(icx + s, icy); lineTo(icx - s * 0.8f, icy + s); close()
            }
            canvas.drawPath(tri, paint)
        }
        canvas.restore()
        x += iconBox + 5 * density

        // A small heart that beats with it.
        val beat = if (playing) 0.55f + 0.45f * (0.5f + 0.5f * sin((t * 2f + 0.5f) * Math.PI.toFloat())) else 0.5f
        paint.color = Color.argb((255 * alpha * beat).toInt(), 255, 82, 120)
        val hs = heartBox * (if (playing) 0.85f + 0.15f * beat else 0.85f)
        drawHeart(canvas, x + heartBox / 2f, h / 2f, hs, paint)
        canvas.restore()
    }

    /**
     * The title, then the artist, each shown whole: a short one rests for a while, a long one rests
     * a moment, scrolls through to its end, rests again, then gives way to the other.
     */
    private fun runningLine(canvas: Canvas, left: Float, baseline: Float, now: Long) {
        val speed = 30 * density / 1000f
        val rest = 1500L
        fun spanOf(text: String): Long {
            val tw = textPaint.measureText(text)
            return if (tw <= textBox) 2800L else rest + ((tw - textBox) / speed).toLong() + rest
        }
        val a = spanOf(title); val b = spanOf(subtitle.ifEmpty { title })
        val total = a + b
        var at = (now - startedAt) % total
        val text: String
        val span: Long
        if (at < a) { text = title; span = a } else { at -= a; text = subtitle.ifEmpty { title }; span = b }
        val tw = textPaint.measureText(text)
        val offset = if (tw <= textBox) 0f else ((at - rest).coerceIn(0L, span - 2 * rest) * speed).coerceAtMost(tw - textBox)
        // A soft fade at the change.
        val edge = 250f
        val fade = minOf(at / edge, (span - at) / edge, 1f)
        textPaint.alpha = (textPaint.alpha * fade.coerceIn(0f, 1f)).toInt()
        canvas.drawText(text, left - offset, baseline, textPaint)
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float, p: Paint) {
        val s = size / 2f
        val path = Path().apply {
            moveTo(cx, cy + s * 0.9f)
            cubicTo(cx - s * 1.6f, cy - s * 0.2f, cx - s * 0.9f, cy - s * 1.2f, cx, cy - s * 0.4f)
            cubicTo(cx + s * 0.9f, cy - s * 1.2f, cx + s * 1.6f, cy - s * 0.2f, cx, cy + s * 0.9f)
            close()
        }
        canvas.drawPath(path, p)
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

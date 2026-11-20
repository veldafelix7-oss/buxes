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
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12.5f * density; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 10.5f * density }
    private var title = ""
    private var subtitle = ""
    private var artUri: String? = null
    private var art: Bitmap? = null
    private var playing = false
    private var artJob: Job? = null

    private var camera = Camera.NONE
    /** The camera's hole, in this view's coordinates (centre case): left and right edge. */
    private var holeLeft = 0f
    private var holeRight = 0f
    private var screenW = context.resources.displayMetrics.widthPixels
    private var cutout: Rect? = null
    private var placed = false

    /** 0 → hidden, 1 → fully out; the entrance. */
    private var reveal = 0f
    private var startedAt = 0L
    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }
    private var downX = 0f
    private var downT = 0L

    // Fixed sizes (dp): nothing grows with the title; a long title scrolls instead.
    private val h = 36 * density
    private val artSize = 28 * density
    private val textBox = 104 * density
    private val iconBox = 22 * density
    private val heartBox = 12 * density
    private val pad = 4 * density
    private val gap = 8 * density
    private val holeGap: Float get() = if (camera == Camera.CENTRE) (cutout?.width() ?: 0) + 10 * density else 0f

    private val widthPx: Int get() = (pad + artSize + gap + textBox + gap + holeGap + iconBox + 6 * density + heartBox + 12 * density).toInt()

    fun bind(title: String, subtitle: String, artworkUri: String?, playing: Boolean) {
        if (title != this.title) startedAt = System.currentTimeMillis()
        this.title = title; this.subtitle = subtitle; this.playing = playing
        if (artworkUri != artUri || (art == null && artJob?.isActive != true)) {
            artUri = artworkUri
            art = null
            artJob?.cancel()
            artJob = artworkUri?.let { uri ->
                graph.appScope.launch {
                    art = runCatching { graph.artworkLoader.load(uri, artSize.toInt())?.asAndroidBitmap() }.getOrNull()
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
            rect == null || rect.top > 48 * density -> Camera.NONE
            rect.centerX() in (screenW * 0.35f).toInt()..(screenW * 0.65f).toInt() -> Camera.CENTRE
            rect.centerX() < screenW / 2 -> Camera.LEFT
            else -> Camera.RIGHT
        }
        val x: Int
        val y: Int
        when (camera) {
            Camera.CENTRE -> {
                // The capsule centred on the camera, a little taller than its hole.
                val cx = rect!!.centerX()
                x = (cx - (pad + artSize + gap + textBox + gap + holeGap / 2f)).toInt()
                y = (rect.centerY() - h / 2f).toInt().coerceAtLeast(0)
                holeLeft = rect.left - x - 5 * density
                holeRight = rect.right - x + 5 * density
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
        reveal = 0f
        revealFrom = System.currentTimeMillis()
        place(x, y)
        requestLayout()
        invalidate()
    }

    private var revealFrom = 0L

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(widthPx, h.toInt())

    override fun onDraw(canvas: Canvas) {
        if (!placed) return
        val now = System.currentTimeMillis()
        reveal = ((now - revealFrom) / 420f).coerceIn(0f, 1f)
        val e = 1f - (1f - reveal) * (1f - reveal) * (1f - reveal) // ease out
        val w = width.toFloat(); val r = h / 2f
        val alpha = e
        canvas.save()
        when (camera) {
            Camera.CENTRE -> {
                // Opens out from both sides of the camera.
                val cx = (holeLeft + holeRight) / 2f
                val half = (holeRight - holeLeft) / 2f + (maxOf(cx, w - cx) - (holeRight - holeLeft) / 2f) * e
                canvas.clipRect(cx - half, 0f, cx + half, h)
            }
            Camera.LEFT -> canvas.translate(-(1f - e) * (w + screenW / 2f), 0f)
            Camera.RIGHT -> canvas.translate((1f - e) * (w + screenW / 2f), 0f)
            Camera.NONE -> canvas.translate(0f, -(1f - e) * (h + 12 * density))
        }
        // The capsule.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((228 * alpha).toInt(), 16, 16, 20)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.argb((55 * alpha).toInt(), 255, 255, 255)
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
            paint.color = Color.argb((255 * alpha).toInt(), 60, 60, 70)
        }
        canvas.drawCircle(x + artSize / 2f, h / 2f, artSize / 2f, paint)
        paint.shader = null
        x += artSize + gap

        // Title and artist in a fixed box; a long one scrolls, resting a moment at its start.
        titlePaint.alpha = (255 * alpha).toInt(); subPaint.alpha = (200 * alpha).toInt()
        canvas.save()
        canvas.clipRect(x, 0f, x + textBox, h)
        marquee(canvas, title, titlePaint, x, h / 2f - 2.5f * density, now)
        marquee(canvas, subtitle, subPaint, x, h / 2f + 10 * density, now)
        canvas.restore()
        x += textBox + gap + holeGap

        // Play or pause, breathing while the music plays (the Now Playing key's own motion).
        val t = ((now % 1400L) / 1400f)
        val breath = if (playing) 1f + 0.08f * sin(t * 2f * Math.PI.toFloat()) else 1f
        paint.color = Color.argb((255 * alpha).toInt(), 255, 255, 255)
        val icx = x + iconBox / 2f; val icy = h / 2f
        canvas.save()
        canvas.scale(breath, breath, icx, icy)
        if (playing) {
            val bw = 3.2f * density; val bh = 12 * density; val sp = 2.4f * density
            canvas.drawRoundRect(RectF(icx - sp / 2 - bw, icy - bh / 2, icx - sp / 2, icy + bh / 2), bw / 2, bw / 2, paint)
            canvas.drawRoundRect(RectF(icx + sp / 2, icy - bh / 2, icx + sp / 2 + bw, icy + bh / 2), bw / 2, bw / 2, paint)
        } else {
            val s = 6.5f * density
            val tri = Path().apply {
                moveTo(icx - s * 0.8f, icy - s); lineTo(icx + s, icy); lineTo(icx - s * 0.8f, icy + s); close()
            }
            canvas.drawPath(tri, paint)
        }
        canvas.restore()
        x += iconBox + 6 * density

        // A small heart that beats with it.
        val beat = if (playing) 0.55f + 0.45f * (0.5f + 0.5f * sin((t * 2f + 0.5f) * Math.PI.toFloat())) else 0.5f
        paint.color = Color.argb((255 * alpha * beat).toInt(), 255, 82, 120)
        val hs = heartBox * (if (playing) 0.85f + 0.15f * beat else 0.85f)
        drawHeart(canvas, x + heartBox / 2f, h / 2f, hs, paint)
        canvas.restore()
    }

    private fun marquee(canvas: Canvas, text: String, p: Paint, left: Float, baseline: Float, now: Long) {
        val tw = p.measureText(text)
        if (tw <= textBox) { canvas.drawText(text, left, baseline, p); return }
        val space = 28 * density
        val cycle = tw + space
        val speed = 28 * density / 1000f // px per ms
        val rest = 1600L
        val elapsed = now - startedAt
        val travelled = ((elapsed - rest).coerceAtLeast(0L) * speed) % (cycle + rest * speed)
        val offset = travelled.coerceAtMost(cycle)
        canvas.drawText(text, left - offset, baseline, p)
        canvas.drawText(text, left - offset + cycle, baseline, p)
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

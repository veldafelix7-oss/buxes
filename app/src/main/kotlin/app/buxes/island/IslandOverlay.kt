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
import app.buxes.core.designsystem.orb.OrbFace
import app.buxes.feature.nowplaying.IslandOrbDriver
import app.buxes.player.api.PlayIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Island outside the app (Buxes): a small dark glass capsule at the top of the display with the
 * cover, one running line of title and artist, and Now Playing's own live orb dancing to the music.
 * It appears a moment after Buxes leaves the display, opening out from its middle, while music plays
 * and only once something has played; a tap opens Now Playing, a swipe skips. It needs Android's
 * "draw over other apps" permission, which only the user can give ([askPermission] opens the page).
 */
class IslandOverlay(private val app: Application, private val graph: AppGraph) {

    private class Shown(val title: String, val subtitle: String, val artworkUri: String?, val playing: Boolean)

    private val foreground = MutableStateFlow(false)
    /** Whether anything has played since the app's task began: a restored, paused queue shows nothing. */
    private val played = MutableStateFlow(false)
    private var started = 0
    private var view: IslandView? = null
    private var pending: Job? = null
    private var listening: AutoCloseable? = null

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
                    pending?.cancel()
                    if (shown == null || !canDraw(app)) {
                        hide()
                    } else if (view != null) {
                        view?.bind(shown.title, shown.subtitle, shown.artworkUri, shown.playing)
                    } else {
                        // A breath after Buxes leaves the display, so its entrance is seen.
                        pending = launch {
                            delay(APPEAR_DELAY_MS)
                            show().bind(shown.title, shown.subtitle, shown.artworkUri, shown.playing)
                        }
                    }
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (6 * density).toInt()
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = IslandView(app, graph, density) { y ->
            if (lp.y != y) {
                lp.y = y
                view?.let { runCatching { wm.updateViewLayout(it, lp) } }
            }
        }
        try {
            wm.addView(v, lp)
            view = v
            // The music is measured while the orb dances to it (as Now Playing does while it shows).
            listening = runCatching { graph.audioPulse.listen() }.getOrNull()
        } catch (_: Exception) {
            v.stop()
        }
        return v
    }

    private fun hide() {
        pending?.cancel()
        val v = view ?: return
        view = null
        listening?.let { runCatching { it.close() } }
        listening = null
        v.stop()
        try { (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    companion object {
        private const val APPEAR_DELAY_MS = 800L

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
private class IslandView(
    context: Context,
    private val graph: AppGraph,
    private val density: Float,
    private val place: (y: Int) -> Unit,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private var title = ""
    private var subtitle = ""
    private var artUri: String? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null
    private var playing = false
    private val orb = IslandOrbDriver(graph.playbackController, graph.audioPulse)
    private var lastFrame = -1L

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

    // Fixed sizes: nothing grows with the title; a long one runs instead.
    private var h = 36 * density
    private val artSize: Float get() = h - 8 * density
    private val textBox: Float get() = 104 * density
    private val orbSize: Float get() = h - 8 * density
    private val pad: Float get() = 4 * density
    private val gap: Float get() = 8 * density
    private val widthPx: Int get() = (pad + artSize + gap + textBox + gap + orbSize + 6 * density).toInt()

    fun bind(title: String, subtitle: String, artworkUri: String?, playing: Boolean) {
        if (title != this.title) startedAt = System.currentTimeMillis()
        this.title = title; this.subtitle = subtitle
        this.playing = playing
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
        post { settle() }
    }

    /** Level with the camera when there is one up there; otherwise just under the status bar. */
    private fun settle() {
        val cut = if (Build.VERSION.SDK_INT >= 28) rootWindowInsets?.displayCutout?.boundingRects?.minByOrNull { it.top } else null
        val y = if (cut != null && cut.top < 48 * density && cut.height() in (12 * density).toInt()..(60 * density).toInt()) {
            h = cut.height().toFloat().coerceIn(30 * density, 40 * density)
            (cut.centerY() - h / 2f).toInt().coerceAtLeast(0)
        } else {
            h = 36 * density
            val status = if (Build.VERSION.SDK_INT >= 30) rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top ?: 0 else (24 * density).toInt()
            status + (4 * density).toInt()
        }
        textPaint.textSize = (h * 0.37f).coerceIn(11.5f * density, 14 * density)
        placed = true
        revealFrom = System.currentTimeMillis()
        place(y)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(widthPx, h.toInt())

    private fun reveal(now: Long): Float {
        val r = ((now - revealFrom) / 460f).coerceIn(0f, 1f)
        return 1f - (1f - r) * (1f - r) * (1f - r)
    }

    override fun onDraw(canvas: Canvas) {
        if (!placed) return
        // Whatever goes wrong in the Island must never take the music with it: it goes quiet instead.
        try {
            draw(canvas)
        } catch (e: Exception) {
            placed = false
            clock.cancel()
            setWillNotDraw(true)
        }
    }

    private fun draw(canvas: Canvas) {
        val now = System.currentTimeMillis()
        // The orb's mind moves on by the time since the last frame (as Now Playing's does).
        val dt = if (lastFrame < 0L) 0f else (now - lastFrame).toFloat().coerceAtMost(100f)
        lastFrame = now
        orb.tick(dt, playing)
        val e = reveal(now)
        val w = width.toFloat(); val r = h / 2f
        // Opens out from its middle to both sides.
        canvas.save()
        canvas.clipRect(w / 2f - w / 2f * e, 0f, w / 2f + w / 2f * e, h)
        // Dark glass.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(222, 14, 14, 18)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.argb(60, 255, 255, 255)
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
        } else {
            paint.color = Color.argb(255, 70, 70, 80)
        }
        canvas.drawCircle(x + artSize / 2f, h / 2f, artSize / 2f, paint)
        paint.shader = null
        x += artSize + gap

        // One line that keeps running: title, a space, artist, a space, round again.
        canvas.save()
        canvas.clipRect(x, 0f, x + textBox, h)
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        val line = if (subtitle.isEmpty()) title else "$title   •   $subtitle"
        val tw = textPaint.measureText(line)
        if (tw <= textBox) {
            canvas.drawText(line, x, baseline, textPaint)
        } else {
            val space = 36 * density
            val cycle = tw + space
            val speed = 30 * density / 1000f
            val rest = 1200L
            val elapsed = (now - startedAt - rest).coerceAtLeast(0L)
            val offset = (elapsed * speed) % cycle
            canvas.drawText(line, x - offset, baseline, textPaint)
            canvas.drawText(line, x - offset + cycle, baseline, textPaint)
        }
        canvas.restore()

        // The live orb, inside the same opening.
        x += textBox + gap
        drawOrb(canvas, x, (h - orbSize) / 2f, orbSize, orb.face)
        canvas.restore()
        if (e < 1f) invalidate()
    }

    /**
     * Now Playing's live orb, drawn by hand: its body (round while the music plays, a settled pebble
     * when it is stopped) squashed, bobbed, rolled and leaned as its face says, its two eyes, and
     * the stars it throws off. The same geometry as the Compose orb, in the same shares of its size.
     */
    private fun drawOrb(canvas: Canvas, left: Float, top: Float, d: Float, f: OrbFace) {
        val cx = left + d / 2f; val cy = top + d / 2f
        canvas.save()
        canvas.translate(f.shiftX * d * OrbFace.RollTravel, -f.bob * d * OrbFace.BobTravel)
        canvas.rotate(f.tilt, cx, cy)
        canvas.scale(f.scaleX, f.scaleY, cx, cy)
        // The body: frosted white glass with a bright rim.
        val target = if (playing) d / 2f else d * 0.34f
        bodyCorner = if (bodyCorner < 0f) target else bodyCorner + (target - bodyCorner) * 0.18f
        val body = RectF(left, top, left + d, top + d)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(214, 244, 240, 232)
        canvas.drawRoundRect(body, bodyCorner, bodyCorner, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = density; paint.color = Color.argb(140, 255, 255, 255)
        canvas.drawRoundRect(body, bodyCorner, bodyCorner, paint)
        paint.style = Paint.Style.FILL
        canvas.restore()
        // The eyes ride the body's lean, roll and bob in full and its squash halfway.
        val give = OrbFace.EyeFollow
        canvas.save()
        canvas.scale(1f + (f.scaleX - 1f) * give, 1f + (f.scaleY - 1f) * give, cx, cy)
        canvas.rotate(f.tilt, cx, cy)
        val gapX = d * OrbFace.EyeGap
        val gx = f.gazeX.coerceIn(-1f, 1f) * d * OrbFace.GazeReachX + f.shiftX * d * OrbFace.RollTravel
        val gy = f.gazeY.coerceIn(-1f, 1f) * d * OrbFace.GazeReachY
        val y = cy - d * OrbFace.EyeAbove - f.bob * d * OrbFace.BobTravel + gy
        val skew = f.eyeSkew.coerceIn(-1f, 1f)
        val bend = f.eyeBend.coerceIn(-1f, 1f)
        inkPaint.strokeCap = Paint.Cap.ROUND
        for (side in -1..1 step 2) {
            val ax = cx + side * gapX + gx
            val w = d * OrbFace.EyeWidth * f.eyeWidth.coerceIn(0.04f, 3f)
            val open = f.eyeHeight * (1f + side * skew * OrbFace.SkewReach)
            val eh = d * OrbFace.EyeHeight * open.coerceIn(0.02f, 3f)
            val thick = min(w, eh)
            val along = (max(w, eh) - thick) / 2f
            canvas.save()
            canvas.rotate(-side * f.eyeLean, ax, y)
            if (along <= 0.25f) {
                inkPaint.style = Paint.Style.FILL
                canvas.drawCircle(ax, y, thick / 2f, inkPaint)
            } else {
                val rise = bend * along * 0.40f * 2f
                val path = Path()
                if (eh >= w) {
                    path.moveTo(ax, y - along); path.quadTo(ax + side * rise, y, ax, y + along)
                } else {
                    path.moveTo(ax - along, y); path.quadTo(ax, y - rise, ax + along, y)
                }
                inkPaint.style = Paint.Style.STROKE; inkPaint.strokeWidth = thick
                canvas.drawPath(path, inkPaint)
            }
            canvas.restore()
        }
        canvas.restore()
        // The stars.
        val t = f.spark
        if (t > 0f && t < 1f) {
            val fx = cx + f.shiftX * d * OrbFace.RollTravel
            val fy = cy - f.bob * d * OrbFace.BobTravel
            val flight = 1f - (1f - t) * (1f - t)
            val show = sin(Math.PI.toFloat() * t)
            for (i in sparkAngles.indices) {
                val a = Math.toRadians(sparkAngles[i].toDouble()).toFloat()
                val reach = d * (0.46f + 0.40f * sparkReach[i]) * flight
                drawSpark(canvas, fx + cos(a) * reach, fy + sin(a) * reach, d * sparkSize[i] * (0.45f + 0.55f * show), (255 * show).toInt(), t * 60f + i * 27f)
            }
        }
    }

    private fun drawSpark(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Int, spin: Float) {
        if (radius <= 0.2f) return
        val inner = radius * 0.24f
        val path = Path()
        for (k in 0 until 4) {
            val tip = Math.toRadians((spin + k * 90f).toDouble()).toFloat()
            val waist = Math.toRadians((spin + k * 90f + 45f).toDouble()).toFloat()
            val next = Math.toRadians((spin + k * 90f + 90f).toDouble()).toFloat()
            if (k == 0) path.moveTo(x + cos(tip) * radius, y + sin(tip) * radius)
            path.quadTo(x + cos(waist) * inner, y + sin(waist) * inner, x + cos(next) * radius, y + sin(next) * radius)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawPath(path, paint)
    }

    private var bodyCorner = -1f
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x1B, 0x19, 0x16) }
    private val sparkAngles = floatArrayOf(-142f, -112f, -86f, -58f, -32f)
    private val sparkReach = floatArrayOf(0.55f, 0.82f, 1f, 0.72f, 0.48f)
    private val sparkSize = floatArrayOf(0.070f, 0.052f, 0.086f, 0.048f, 0.066f)

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

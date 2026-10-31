package hu.videovago

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class EditorActivity : Activity() {

    companion object {
        const val EXTRA_MULTI = "multi"
        private const val MIN_LEN_US = 50_000L

        /** 0:05.52 – short time format of the editor. */
        fun t(us: Long): String {
            val cs = max(us, 0) / 10_000
            val h = cs / 360_000
            val m = (cs / 6000) % 60
            val s = (cs / 100) % 60
            val f = cs % 100
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, f)
            else String.format(Locale.US, "%d:%02d.%02d", m, s, f)
        }
    }

    // views
    private lateinit var texture: TextureView
    private lateinit var videoBox: FrameLayout
    private lateinit var txtLoading: TextView
    private lateinit var rotBadge: TextView
    private lateinit var txtPos: TextView
    private lateinit var txtDur: TextView
    private lateinit var seek: SeekStripView
    private lateinit var strip: FilmstripView
    private lateinit var txtHeader: TextView
    private lateinit var txtStart: TextView
    private lateinit var txtEnd: TextView
    private lateinit var txtTotal: TextView
    private lateinit var editName: EditText
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVolume: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnAdd: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var btnAudio: ImageButton
    private lateinit var btnWave: ImageButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var edgeButtons: List<ImageButton>

    // state
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var uri: Uri
    private var multi = true
    private var info: VideoInfo? = null
    private var preview: MediaPreview? = null
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var surface: Surface? = null
    private var posUs = 0L
    private var rotation = 0
    private var previewMuted = false
    private var keepAudio = true
    private var showWave = true
    private var separateFiles = false
    private var previewResult = true
    private var edited = false

    private var keep = Segment(0, 0)                 // trim mode
    private val removes = mutableListOf<Segment>()   // multi-cut mode, kept sorted
    private var selected = -1

    private var cutter: LosslessCutter? = null

    private val dur get() = info?.durationUs ?: 0L
    private val frameUs: Long
        get() = info?.fps?.takeIf { it > 1 }?.let { (1_000_000.0 / it).toLong() } ?: 33_333L
    private val keys get() = info?.keyframes ?: LongArray(0)

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!playerReady || !p.isPlaying) return
            posUs = p.currentPosition * 1000L
            if (previewResult) skipRemoved(p)
            updatePosition()
            ui.postDelayed(this, 30)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        uri = intent.data ?: run { finish(); return }
        multi = intent.getBooleanExtra(EXTRA_MULTI, true)

        texture = findViewById(R.id.texture)
        videoBox = findViewById(R.id.videoBox)
        txtLoading = findViewById(R.id.txtLoading)
        rotBadge = findViewById(R.id.rotBadge)
        txtPos = findViewById(R.id.txtPos)
        txtDur = findViewById(R.id.txtDur)
        seek = findViewById(R.id.seek)
        strip = findViewById(R.id.strip)
        txtHeader = findViewById(R.id.txtHeader)
        txtStart = findViewById(R.id.txtStart)
        txtEnd = findViewById(R.id.txtEnd)
        txtTotal = findViewById(R.id.txtTotal)
        editName = findViewById(R.id.editName)
        btnPlay = findViewById(R.id.btnPlay)
        btnVolume = findViewById(R.id.btnVolume)
        btnRotate = findViewById(R.id.btnRotate)
        btnAdd = findViewById(R.id.btnAdd)
        btnDelete = findViewById(R.id.btnDelete)
        btnAudio = findViewById(R.id.btnAudio)
        btnWave = findViewById(R.id.btnWave)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        edgeButtons = listOf(
            findViewById(R.id.btnStartPrev), findViewById(R.id.btnStartNext),
            findViewById(R.id.btnEndPrev), findViewById(R.id.btnEndNext),
        )

        txtHeader.text = if (multi) "Eltávolítandó részek" else "Megtartott rész"
        btnAdd.visibility = if (multi) View.VISIBLE else View.GONE
        btnDelete.visibility = if (multi) View.VISIBLE else View.GONE
        strip.trimMode = !multi

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                player?.setSurface(surface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        videoBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }

        findViewById<View>(R.id.btnSetStart).setOnClickListener { braceAt(true) }
        findViewById<View>(R.id.btnSetEnd).setOnClickListener { braceAt(false) }
        findViewById<View>(R.id.btnToStart).setOnClickListener { pause(); seekTo(currentSegment()?.startUs ?: 0, true) }
        btnPlay.setOnClickListener { togglePlay() }
        btnVolume.setOnClickListener {
            previewMuted = !previewMuted
            val v = if (previewMuted) 0f else 1f
            player?.setVolume(v, v)
            btnVolume.setImageResource(if (previewMuted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        }
        btnRotate.setOnClickListener { setRotation(rotation + 90) }
        btnRotate.setOnLongClickListener { setRotation(0); toast("Forgatás visszaállítva"); true }

        findViewById<View>(R.id.btnInfo).setOnClickListener { showHelp() }
        btnAdd.setOnClickListener { addSegment() }
        btnDelete.setOnClickListener { deleteSelected() }
        btnAudio.setOnClickListener {
            keepAudio = !keepAudio
            toast(if (keepAudio) "A kimenetben megmarad a hang" else "A kimenet hang nélkül készül")
            refreshButtons()
        }
        btnWave.setOnClickListener { showWave = !showWave; strip.showWave = showWave; refreshButtons() }
        btnZoomIn.setOnClickListener { strip.zoomBy(2f) }
        btnZoomOut.setOnClickListener { strip.zoomBy(0.5f) }
        strip.onZoomChanged = { refreshButtons() }

        edgeButtons[0].setOnClickListener { nudge(start = true, forward = false) }
        edgeButtons[1].setOnClickListener { nudge(start = true, forward = true) }
        edgeButtons[2].setOnClickListener { nudge(start = false, forward = false) }
        edgeButtons[3].setOnClickListener { nudge(start = false, forward = true) }

        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<View>(R.id.btnClose).setOnClickListener { confirmClose() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btnExport).setOnClickListener { startExport() }

        seek.onSeek = { t, done -> pause(); seekTo(t, done) }
        strip.onSeek = { t -> pause(); seekTo(t, true) }
        strip.onSelect = { i -> selected = i; refreshSegments() }
        strip.onEdgeDrag = { isStart, t, done -> pause(); setEdge(isStart, t, done) }

        setEnabledAll(false)
        load()
    }

    override fun onPause() {
        super.onPause()
        pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        cutter?.cancelled = true
        preview?.close()
        player?.release()
        player = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = confirmClose()

    // ------------------------------------------------------------------ loading

    private fun load() {
        Thread {
            try {
                val vi = LosslessCutter.probe(this, uri)
                ui.post { onLoaded(vi) }
            } catch (e: Exception) {
                ui.post {
                    AlertDialog.Builder(this)
                        .setTitle("Nem sikerült megnyitni")
                        .setMessage(e.message ?: e.javaClass.simpleName)
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
        }.start()
    }

    private fun onLoaded(vi: VideoInfo) {
        if (isFinishing || isDestroyed) return
        info = vi
        keep = Segment(0, vi.durationUs)
        txtDur.text = t(vi.durationUs)
        editName.setText(vi.name.substringBeforeLast('.') + "_vagott")

        val sideways = vi.rotation == 90 || vi.rotation == 270
        val dw = if (sideways) vi.height else vi.width
        val dh = if (sideways) vi.width else vi.height
        strip.aspect = dw.toFloat() / max(1, dh)
        strip.durationUs = vi.durationUs
        seek.durationUs = vi.durationUs

        val thumbH = (64 * resources.displayMetrics.density).toInt()
        val pv = MediaPreview(this, uri, vi, thumbH) { ui.post { strip.invalidate() } }
        preview = pv
        strip.thumbs = pv
        Thread {
            val wf = pv.computeWaveform()
            ui.post { if (!isDestroyed) strip.waveform = wf }
        }.start()

        txtLoading.visibility = View.GONE
        setEnabledAll(true)
        preparePlayer()
        layoutVideo()
        refreshSegments()
        updatePosition()
        if (vi.audioTrack < 0) {
            keepAudio = false
            btnAudio.isEnabled = false
        }
        refreshButtons()
    }

    private fun preparePlayer() {
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(this, uri)
            surface?.let { p.setSurface(it) }
            p.setOnPreparedListener {
                if (player !== p) return@setOnPreparedListener
                playerReady = true
                if (previewMuted) p.setVolume(0f, 0f)
                p.seekTo(posUs / 1000, MediaPlayer.SEEK_CLOSEST)
            }
            p.setOnCompletionListener {
                btnPlay.setImageResource(R.drawable.ic_play)
                posUs = dur
                updatePosition()
            }
            p.setOnErrorListener { _, what, extra ->
                txtLoading.visibility = View.VISIBLE
                txtLoading.text = "Az előnézet nem játszható le ezen a telefonon ($what/$extra).\nA vágás ettől még működhet."
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            txtLoading.visibility = View.VISIBLE
            txtLoading.text = "Az előnézet nem indítható: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ playback

    private fun togglePlay() {
        val p = player ?: return
        if (!playerReady) return
        if (p.isPlaying) {
            pause()
            return
        }
        // start from the beginning of the result when at the end
        val kept = keeps()
        val lastEnd = kept.lastOrNull()?.endUs ?: dur
        if (posUs >= lastEnd - frameUs) seekTo(kept.firstOrNull()?.startUs ?: 0, true)
        else if (previewResult && !multi && posUs < keep.startUs) seekTo(keep.startUs, true)
        p.start()
        btnPlay.setImageResource(R.drawable.ic_pause)
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun pause() {
        val p = player ?: return
        if (playerReady && p.isPlaying) {
            p.pause()
            posUs = p.currentPosition * 1000L
            updatePosition()
        }
        btnPlay.setImageResource(R.drawable.ic_play)
    }

    /** "Preview result": jump over removed ranges while playing. */
    private fun skipRemoved(p: MediaPlayer) {
        for (r in removedRanges()) {
            if (posUs >= r.startUs && posUs < r.endUs - frameUs) {
                if (r.endUs >= dur - frameUs) {
                    pause()
                } else {
                    posUs = r.endUs
                    p.seekTo(r.endUs / 1000, MediaPlayer.SEEK_CLOSEST)
                }
                return
            }
        }
    }

    private fun seekTo(tUs: Long, precise: Boolean) {
        if (info == null) return
        posUs = tUs.coerceIn(0L, dur)
        if (playerReady) {
            player?.seekTo(posUs / 1000, if (precise) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_PREVIOUS_SYNC)
        }
        updatePosition()
    }

    private fun updatePosition() {
        txtPos.text = t(posUs)
        seek.posUs = posUs
        strip.posUs = posUs
    }

    // ------------------------------------------------------------------ segments

    private fun currentSegment(): Segment? = if (multi) removes.getOrNull(selected) else keep

    private fun removedRanges(): List<Segment> =
        if (multi) removes
        else listOfNotNull(
            Segment(0, keep.startUs).takeIf { keep.startUs > 0 },
            Segment(keep.endUs, dur).takeIf { keep.endUs < dur },
        )

    private fun keeps(): List<Segment> {
        if (!multi) return listOf(keep.copy())
        val out = mutableListOf<Segment>()
        var cur = 0L
        for (r in removes.sortedBy { it.startUs }) {
            if (r.startUs - cur > MIN_LEN_US / 5) out += Segment(cur, r.startUs)
            cur = max(cur, r.endUs)
        }
        if (dur - cur > MIN_LEN_US / 5) out += Segment(cur, dur)
        return out
    }

    /**
     * Lossless cuts can only *start* on a keyframe. The edges where kept video starts
     * (trim start, and the end of a removed range) snap to the previous keyframe,
     * so nothing wanted is lost; if there is none inside the range, to the next one.
     */
    private fun snapKeepStart(t: Long, lowerBound: Long): Long {
        if (keys.isEmpty() || t >= dur) return t
        var k = LosslessCutter.prevKey(keys, t)
        if (k <= lowerBound + frameUs) k = LosslessCutter.nextKey(keys, t, dur)
        return k
    }

    private fun neighbours(i: Int): Pair<Long, Long> {
        val lo = removes.getOrNull(i - 1)?.endUs ?: 0L
        val hi = removes.getOrNull(i + 1)?.startUs ?: dur
        return lo to hi
    }

    private fun setEdge(isStart: Boolean, tUs: Long, done: Boolean) {
        if (info == null) return
        edited = true
        val shown: Long
        if (!multi) {
            if (isStart) {
                var a = tUs.coerceIn(0L, keep.endUs - MIN_LEN_US)
                if (done) a = snapKeepStart(a, -1).coerceAtMost(keep.endUs - MIN_LEN_US)
                keep.startUs = max(0L, a)
                shown = keep.startUs
            } else {
                keep.endUs = tUs.coerceIn(keep.startUs + MIN_LEN_US, dur)
                shown = keep.endUs
            }
        } else {
            val s = removes.getOrNull(selected) ?: return
            val (lo, hi) = neighbours(selected)
            if (isStart) {
                s.startUs = tUs.coerceIn(lo, s.endUs - MIN_LEN_US)
                shown = s.startUs
            } else {
                var b = tUs.coerceIn(s.startUs + MIN_LEN_US, hi)
                if (done && b < dur) b = snapKeepStart(b, s.startUs).coerceIn(s.startUs + MIN_LEN_US, hi)
                s.endUs = b
                shown = s.endUs
            }
        }
        seekTo(shown, done)
        refreshSegments()
    }

    /** { and } buttons: move the selected edge to the playhead (or create a range in multi-cut). */
    private fun braceAt(isStart: Boolean) {
        if (info == null) return
        if (multi && selected < 0) {
            val inside = removes.indexOfFirst { posUs >= it.startUs && posUs <= it.endUs }
            if (inside >= 0) {
                selected = inside
            } else {
                val lo = removes.lastOrNull { it.endUs <= posUs }?.endUs ?: 0L
                val hi = removes.firstOrNull { it.startUs >= posUs }?.startUs ?: dur
                val len = defaultLen()
                val s = if (isStart) Segment(posUs, min(hi, posUs + len)) else Segment(max(lo, posUs - len), posUs)
                if (s.endUs - s.startUs < MIN_LEN_US) return toast("Itt nincs hely új résznek.")
                insertSegment(s)
                return
            }
        }
        setEdge(isStart, posUs, true)
    }

    private fun defaultLen() = max(1_000_000L, dur / 10)

    private fun addSegment() {
        if (info == null) return
        val inside = removes.indexOfFirst { posUs >= it.startUs && posUs < it.endUs }
        if (inside >= 0) {
            selected = inside
            refreshSegments()
            return toast("Itt már van kijelölt rész.")
        }
        val hi = removes.firstOrNull { it.startUs > posUs }?.startUs ?: dur
        var s = Segment(posUs, min(hi, posUs + defaultLen()))
        if (s.endUs - s.startUs < MIN_LEN_US) {
            val lo = removes.lastOrNull { it.endUs <= posUs }?.endUs ?: 0L
            s = Segment(max(lo, posUs - defaultLen()), posUs)
        }
        if (s.endUs - s.startUs < MIN_LEN_US) return toast("Itt nincs hely új résznek.")
        insertSegment(s)
    }

    private fun insertSegment(s: Segment) {
        if (s.endUs < dur) {
            val hi = removes.firstOrNull { it.startUs >= s.endUs }?.startUs ?: dur
            s.endUs = snapKeepStart(s.endUs, s.startUs).coerceIn(s.startUs + MIN_LEN_US, hi)
        }
        removes.add(s)
        removes.sortBy { it.startUs }
        selected = removes.indexOf(s)
        edited = true
        refreshSegments()
    }

    private fun deleteSelected() {
        if (selected !in removes.indices) return
        removes.removeAt(selected)
        selected = -1
        edited = true
        refreshSegments()
    }

    /**
     * < > buttons. Edges where kept video starts jump keyframe to keyframe,
     * the others move one frame.
     */
    private fun nudge(start: Boolean, forward: Boolean) {
        val s = currentSegment() ?: return
        val edge = if (start) s.startUs else s.endUs
        val keyEdge = (start && !multi) || (!start && multi)
        val target = if (keyEdge) {
            if (forward) LosslessCutter.nextKey(keys, edge, dur)
            else LosslessCutter.prevKey(keys, edge, strict = true)
        } else {
            edge + if (forward) frameUs else -frameUs
        }
        // apply without re-snapping (already on a keyframe or deliberately frame-exact)
        edited = true
        if (!multi) {
            if (start) keep.startUs = target.coerceIn(0L, keep.endUs - MIN_LEN_US)
            else keep.endUs = target.coerceIn(keep.startUs + MIN_LEN_US, dur)
        } else {
            val (lo, hi) = neighbours(selected)
            if (start) s.startUs = target.coerceIn(lo, s.endUs - MIN_LEN_US)
            else s.endUs = target.coerceIn(s.startUs + MIN_LEN_US, hi)
        }
        val shown = if (start) s.startUs else s.endUs
        strip.reveal(shown)
        seekTo(shown, true)
        refreshSegments()
    }

    private fun refreshSegments() {
        val segs = if (multi) removes.toList() else listOf(keep)
        strip.segments = segs
        strip.selected = if (multi) selected else 0
        seek.removed = removedRanges()
        seek.marks = segs.flatMap { listOf(it.startUs, it.endUs) }
            .filter { it in 1 until dur }.toLongArray()
        val cur = currentSegment()
        txtStart.text = cur?.let { t(it.startUs) } ?: "--:--"
        txtEnd.text = cur?.let { t(it.endUs) } ?: "--:--"
        txtTotal.text = "Összesen: " + t(keeps().sumOf { it.endUs - it.startUs })
        refreshButtons()
    }

    private fun refreshButtons() {
        val hasSel = currentSegment() != null && info != null
        for (b in edgeButtons) setIconEnabled(b, hasSel)
        setIconEnabled(btnDelete, multi && selected >= 0)
        setIconEnabled(btnZoomOut, strip.zoom > 1.01f)
        setIconEnabled(btnZoomIn, strip.zoom < 399f)
        tint(btnAudio, if (!keepAudio) R.color.teal else R.color.muted)
        tint(btnWave, if (showWave) R.color.teal else R.color.muted)
        tint(btnRotate, if (rotation != 0) R.color.teal else R.color.text)
    }

    private fun setIconEnabled(b: ImageButton, on: Boolean) {
        b.isEnabled = on
        tint(b, if (on) (if (b === btnAdd) R.color.teal else R.color.text) else R.color.disabled)
    }

    private fun tint(b: ImageButton, colorRes: Int) {
        b.imageTintList = ColorStateList.valueOf(getColor(colorRes))
    }

    // ------------------------------------------------------------------ rotation

    private fun setRotation(r: Int) {
        rotation = ((r % 360) + 360) % 360
        edited = true
        rotBadge.visibility = if (rotation != 0) View.VISIBLE else View.GONE
        rotBadge.text = "↻ $rotation°"
        layoutVideo()
        refreshButtons()
    }

    private fun layoutVideo() {
        val vi = info ?: return
        val cw = videoBox.width.toFloat()
        val ch = videoBox.height.toFloat()
        if (cw <= 0 || ch <= 0) return
        val srcSideways = vi.rotation == 90 || vi.rotation == 270
        val vw = (if (srcSideways) vi.height else vi.width).toFloat()
        val vh = (if (srcSideways) vi.width else vi.height).toFloat()
        val s = min(cw / vw, ch / vh)
        val w = (vw * s).toInt()
        val h = (vh * s).toInt()
        val lp = texture.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            texture.layoutParams = lp
        }
        texture.rotation = rotation.toFloat()
        val sideways = rotation == 90 || rotation == 270
        val scale = if (sideways) min(cw / h, ch / w) else 1f
        texture.scaleX = scale
        texture.scaleY = scale
    }

    // ------------------------------------------------------------------ dialogs & menu

    private fun showHelp() {
        val msg = if (multi) {
            "Jelöld ki pirossal azokat a részeket, amelyeket ki akarsz vágni: + gomb (vagy { és } a lejátszófej helyén). " +
                "A kijelölt rész széleit húzással vagy a < > gombokkal állíthatod, a többi rész összefűzve marad meg."
        } else {
            "Állítsd be a megtartandó részt: húzd a keret széleit, vagy használd a { és } gombot a lejátszófej helyén, " +
                "és a < > gombokat a finomhangoláshoz."
        }
        AlertDialog.Builder(this)
            .setTitle(if (multi) "Több vágás" else "Vágás")
            .setMessage(
                msg + "\n\nVeszteségmentes vágásnál a megtartott rész csak kulcskockánál kezdődhet, ezért az app " +
                    "ezeket a széleket a legközelebbi kulcskockára igazítja (a < > itt kulcskockánként lép). " +
                    "A rész vége bármelyik képkocka lehet (a < > itt képkockánként lép).\n\n" +
                    "Két ujjal vagy a nagyító gombokkal nagyíthatod a képsávot. A forgatás a fájl adatai közé kerül, " +
                    "a képpontokat nem változtatja.",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showSettings() {
        val vi = info ?: return
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        val chkAudio = CheckBox(this).apply {
            text = "Hang megtartása"
            isChecked = keepAudio
            isEnabled = vi.audioTrack >= 0
        }
        val chkSeparate = CheckBox(this).apply {
            text = "A megmaradt részek mentése külön fájlokba"
            isChecked = separateFiles
            visibility = if (multi) View.VISIBLE else View.GONE
        }
        box.addView(chkAudio)
        box.addView(chkSeparate)
        val rot = rotation
        box.addView(TextView(this).apply {
            setTextColor(getColor(R.color.muted))
            textSize = 13f
            setPadding(0, (10 * d).toInt(), 0, 0)
            text = "Formátum: MP4, az eredeti kódolt képkockák másolata (újrakódolás nélkül)\n" +
                "Forgatás: " + (if (rot == 0) "nincs" else "$rot° jobbra") + "\n" +
                "Mentés helye: Filmek/Videovago"
        })
        AlertDialog.Builder(this)
            .setTitle("Kimeneti beállítások")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                keepAudio = chkAudio.isChecked
                separateFiles = chkSeparate.isChecked
                refreshButtons()
            }
            .setNegativeButton("Mégse", null)
            .show()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Eredmény előnézete (törölt részek átugrása)")
            .setCheckable(true)
            .setChecked(previewResult)
        pm.menu.add(0, 2, 1, "Videó adatai")
        pm.menu.add(0, 3, 2, "Súgó")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> previewResult = !previewResult
                2 -> showVideoInfo()
                3 -> showHelp()
            }
            true
        }
        pm.show()
    }

    private fun showVideoInfo() {
        val vi = info ?: return
        val kf = vi.keyframes
        val avg = if (kf.size > 1) (kf.last() - kf.first()) / 1e6 / (kf.size - 1) else 0.0
        AlertDialog.Builder(this)
            .setTitle(vi.name)
            .setMessage(
                "Felbontás: ${vi.width}×${vi.height}\n" +
                    "Képkocka/mp: " + String.format(Locale.US, "%.2f", vi.fps) + "\n" +
                    "Videó: ${vi.videoMime}\n" +
                    "Hang: ${vi.audioMime ?: "nincs"}\n" +
                    "Hossz: ${t(vi.durationUs)}\n" +
                    (if (vi.sizeBytes > 0) "Méret: " + String.format(Locale.US, "%.1f MB", vi.sizeBytes / 1e6) + "\n" else "") +
                    (if (vi.rotation != 0) "Tárolt forgatás: ${vi.rotation}°\n" else "") +
                    "Kulcskockák: ${kf.size}" + (if (avg > 0) String.format(Locale.US, " (átlag %.2f mp-enként)", avg) else ""),
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmClose() {
        if (cutter != null) return toast("Mentés folyamatban…")
        if (!edited) return finish()
        AlertDialog.Builder(this)
            .setMessage("Kilépsz a szerkesztőből? A módosítások elvesznek.")
            .setPositiveButton("Kilépés") { _, _ -> finish() }
            .setNegativeButton("Mégse", null)
            .show()
    }

    // ------------------------------------------------------------------ export

    private fun startExport() {
        val vi = info ?: return
        if (cutter != null) return
        val list = keeps()
        if (list.isEmpty()) return toast("A teljes videót eltávolítanád – nincs mit menteni.")
        pause()
        val c = LosslessCutter(this, uri, vi)
        cutter = c
        val d = resources.displayMetrics.density
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(getColor(R.color.teal))
        }
        val label = TextView(this).apply { setTextColor(getColor(R.color.muted)); text = "Indul…" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            addView(bar)
            addView(label)
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Mentés újrakódolás nélkül")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Megszakítás") { _, _ -> c.cancelled = true }
            .show()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val name = editName.text.toString()
        val keepA = keepAudio
        val sep = multi && separateFiles
        val rot = rotation
        val t0 = System.currentTimeMillis()
        Thread {
            var result: List<Uri> = emptyList()
            var error: String? = null
            var cancelled = false
            try {
                result = c.export(list, rot, keepA, sep, name) { f, msg ->
                    ui.post {
                        bar.progress = (f * 1000).toInt()
                        label.text = "$msg – ${(f * 100).toInt()}%"
                    }
                }
            } catch (e: LosslessCutter.Cancelled) {
                cancelled = true
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
            }
            ui.post {
                cutter = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                runCatching { dlg.dismiss() }
                if (isDestroyed) return@post
                when {
                    cancelled -> toast("Megszakítva")
                    error != null -> AlertDialog.Builder(this)
                        .setTitle("Hiba a mentéskor")
                        .setMessage(error)
                        .setPositiveButton("OK", null)
                        .show()
                    else -> showDone(result, c.skipped, (System.currentTimeMillis() - t0) / 1000.0)
                }
            }
        }.start()
    }

    private fun showDone(outs: List<Uri>, skipped: List<String>, secs: Double) {
        edited = false
        val msg = buildString {
            append(if (outs.size == 1) "A videó elkészült" else "${outs.size} videó elkészült")
            append(String.format(Locale.US, " (%.1f mp), újrakódolás nélkül.\n\nHelye: Filmek/Videovago", secs))
            if (skipped.isNotEmpty()) append("\n\nKimaradt: ${skipped.joinToString()} (az MP4 nem támogatja).")
        }
        AlertDialog.Builder(this)
            .setTitle("Kész")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .setNeutralButton("Lejátszás") { _, _ ->
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(outs.first(), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { startActivity(i) }.onFailure { toast("Nincs videólejátszó alkalmazás.") }
            }
            .setNegativeButton("Megosztás") { _, _ ->
                val i = if (outs.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, outs[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outs))
                }
                i.type = "video/mp4"
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(i, "Megosztás"))
            }
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun setEnabledAll(on: Boolean) {
        val ids = intArrayOf(
            R.id.btnSetStart, R.id.btnSetEnd, R.id.btnToStart, R.id.btnPlay, R.id.btnVolume, R.id.btnRotate,
            R.id.btnAdd, R.id.btnDelete, R.id.btnAudio, R.id.btnWave, R.id.btnZoomIn, R.id.btnZoomOut,
            R.id.btnSettings, R.id.btnExport, R.id.btnMore, R.id.btnInfo,
        )
        for (id in ids) {
            val v = findViewById<View>(id)
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.4f
        }
        seek.isEnabled = on
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

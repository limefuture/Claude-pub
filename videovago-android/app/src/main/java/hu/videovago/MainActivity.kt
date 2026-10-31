package hu.videovago

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private lateinit var texture: TextureView
    private lateinit var videoBox: FrameLayout
    private lateinit var placeholder: TextView
    private lateinit var rotBadge: TextView
    private lateinit var txtName: TextView
    private lateinit var txtInfo: TextView
    private lateinit var txtTime: TextView
    private lateinit var txtTotal: TextView
    private lateinit var txtSel: TextView
    private lateinit var txtKeys: TextView
    private lateinit var txtStatus: TextView
    private lateinit var timeline: TimelineView
    private lateinit var segList: LinearLayout
    private lateinit var chkSnap: CheckBox
    private lateinit var chkAudio: CheckBox
    private lateinit var radioSeparate: RadioButton
    private lateinit var progress: ProgressBar
    private lateinit var btnPlay: Button
    private lateinit var btnExport: Button
    private lateinit var btnCancel: Button
    private lateinit var btnPlayOut: Button
    private lateinit var btnShare: Button

    private val ui = Handler(Looper.getMainLooper())
    private var uri: Uri? = null
    private var info: VideoInfo? = null
    private var player: MediaPlayer? = null
    private var playerReady = false
    private var surface: Surface? = null

    private var posUs = 0L
    private var inUs = 0L
    private var outUs = 0L
    private val segs = mutableListOf<Segment>()
    private var activeSeg = -1
    private var rotation = 0           // extra clockwise rotation chosen by the user
    private var cutter: LosslessCutter? = null
    private var outputs: List<Uri> = emptyList()

    private val frameUs: Long
        get() = info?.fps?.takeIf { it > 1 }?.let { (1_000_000.0 / it).toLong() } ?: 33_333L

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && playerReady && p.isPlaying) {
                posUs = p.currentPosition * 1000L
                updateTime()
                ui.postDelayed(this, 33)
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        texture = findViewById(R.id.texture)
        videoBox = findViewById(R.id.videoBox)
        placeholder = findViewById(R.id.placeholder)
        rotBadge = findViewById(R.id.rotBadge)
        txtName = findViewById(R.id.txtName)
        txtInfo = findViewById(R.id.txtInfo)
        txtTime = findViewById(R.id.txtTime)
        txtTotal = findViewById(R.id.txtTotal)
        txtSel = findViewById(R.id.txtSel)
        txtKeys = findViewById(R.id.txtKeys)
        txtStatus = findViewById(R.id.txtStatus)
        timeline = findViewById(R.id.timeline)
        segList = findViewById(R.id.segList)
        chkSnap = findViewById(R.id.chkSnap)
        chkAudio = findViewById(R.id.chkAudio)
        radioSeparate = findViewById(R.id.radioSeparate)
        progress = findViewById(R.id.progress)
        btnPlay = findViewById(R.id.btnPlay)
        btnExport = findViewById(R.id.btnExport)
        btnCancel = findViewById(R.id.btnCancel)
        btnPlayOut = findViewById(R.id.btnPlayOut)
        btnShare = findViewById(R.id.btnShare)

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

        findViewById<Button>(R.id.btnOpen).setOnClickListener { pickVideo() }
        btnPlay.setOnClickListener { togglePlay() }
        findViewById<Button>(R.id.btnPrevFrame).setOnClickListener { step(-1) }
        findViewById<Button>(R.id.btnNextFrame).setOnClickListener { step(1) }
        findViewById<Button>(R.id.btnPrevKey).setOnClickListener {
            info?.let { seek(LosslessCutter.prevKey(it.keyframes, posUs, strict = true)) }
        }
        findViewById<Button>(R.id.btnNextKey).setOnClickListener {
            info?.let { seek(LosslessCutter.nextKey(it.keyframes, posUs, it.durationUs)) }
        }
        findViewById<Button>(R.id.btnIn).setOnClickListener { setIn() }
        findViewById<Button>(R.id.btnOut).setOnClickListener { setOut() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { addSegment() }
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { timeline.zoomBy(2f) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { timeline.zoomBy(0.5f) }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            if (segs.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this).setMessage("Törlöd az összes szakaszt?")
                .setPositiveButton("Törlés") { _, _ -> segs.clear(); activeSeg = -1; renderSegments() }
                .setNegativeButton("Mégse", null).show()
        }
        findViewById<Button>(R.id.btnInvert).setOnClickListener { invertSegments() }
        findViewById<Button>(R.id.btnRotL).setOnClickListener { setRotation(rotation - 90) }
        findViewById<Button>(R.id.btnRotR).setOnClickListener { setRotation(rotation + 90) }
        findViewById<Button>(R.id.btnRot180).setOnClickListener { setRotation(rotation + 180) }
        findViewById<Button>(R.id.btnRot0).setOnClickListener { setRotation(0) }
        btnExport.setOnClickListener { startExport() }
        btnCancel.setOnClickListener { cutter?.cancelled = true }
        btnPlayOut.setOnClickListener { outputs.firstOrNull()?.let { viewVideo(it) } }
        btnShare.setOnClickListener { shareOutputs() }
        timeline.onSeek = { t ->
            if (player?.isPlaying == true) {
                player?.pause()
                btnPlay.text = "▶"
            }
            seek(t)
        }

        setControlsEnabled(false)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        if (player?.isPlaying == true) {
            player?.pause()
            btnPlay.text = "▶"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        cutter?.cancelled = true
        player?.release()
        player = null
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            load(u)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val u: Uri? = when (intent?.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (u != null) load(u)
    }

    // ------------------------------------------------------------------ loading

    private fun pickVideo() {
        if (cutter != null) return toast("Előbb várd meg az exportálás végét.")
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    private fun load(u: Uri) {
        if (cutter != null) return toast("Előbb várd meg az exportálás végét.")
        player?.release()
        player = null
        playerReady = false
        info = null
        setControlsEnabled(false)
        placeholder.visibility = View.VISIBLE
        placeholder.text = "Betöltés…"
        txtName.text = "Betöltés…"
        txtInfo.text = ""
        Thread {
            try {
                val vi = LosslessCutter.probe(this, u)
                ui.post { onLoaded(u, vi) }
            } catch (e: Exception) {
                ui.post {
                    placeholder.text = "Nem sikerült megnyitni:\n${e.message ?: e.javaClass.simpleName}"
                    txtName.text = "Nincs megnyitott videó"
                }
            }
        }.start()
    }

    private fun onLoaded(u: Uri, vi: VideoInfo) {
        uri = u
        info = vi
        posUs = 0
        inUs = 0
        outUs = vi.durationUs
        segs.clear()
        activeSeg = -1
        outputs = emptyList()
        rotation = 0

        txtName.text = vi.name
        val codec = vi.videoMime.substringAfter('/').uppercase(Locale.ROOT).replace("AVC", "H.264")
        val audio = vi.audioMime?.substringAfter('/')?.uppercase(Locale.ROOT) ?: "nincs hang"
        val fps = if (vi.fps % 1.0 < 0.01 || vi.fps % 1.0 > 0.99) "%.0f".format(Locale.US, vi.fps) else "%.2f".format(Locale.US, vi.fps)
        txtInfo.text = buildString {
            append("${vi.width}×${vi.height} · $fps fps · $codec · $audio")
            if (vi.sizeBytes > 0) append(" · ${fmtSize(vi.sizeBytes)}")
            if (vi.rotation != 0) append(" · forgatva ${vi.rotation}°")
        }
        txtTotal.text = "/ " + LosslessCutter.fmt(vi.durationUs)
        val avg = if (vi.keyframes.size > 1) (vi.keyframes.last() - vi.keyframes.first()) / 1e6 / (vi.keyframes.size - 1) else 0.0
        txtKeys.text = "${vi.keyframes.size} kulcskocka" + if (avg > 0) ", átlag %.2f mp-enként".format(Locale.US, avg) else ""

        timeline.durationUs = vi.durationUs
        timeline.keyframes = vi.keyframes
        placeholder.visibility = View.GONE
        txtStatus.text = ""
        progress.progress = 0
        btnPlayOut.visibility = View.GONE
        btnShare.visibility = View.GONE
        setControlsEnabled(true)
        setRotation(0)
        renderSegments()
        renderSelection()
        preparePlayer(u)
        layoutVideo()
    }

    private fun preparePlayer(u: Uri) {
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(this, u)
            surface?.let { p.setSurface(it) }
            p.setOnPreparedListener {
                if (player !== p) return@setOnPreparedListener
                playerReady = true
                p.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
            }
            p.setOnCompletionListener {
                btnPlay.text = "▶"
                posUs = info?.durationUs ?: 0
                updateTime()
            }
            p.setOnErrorListener { _, what, extra ->
                placeholder.visibility = View.VISIBLE
                placeholder.text = "Az előnézet nem játszható le ezen a telefonon ($what/$extra).\nA vágás ettől még működhet."
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            placeholder.visibility = View.VISIBLE
            placeholder.text = "Az előnézet nem indítható: ${e.message}"
        }
    }

    // ------------------------------------------------------------------ playback

    private fun togglePlay() {
        val p = player ?: return
        if (!playerReady) return
        if (p.isPlaying) {
            p.pause()
            btnPlay.text = "▶"
            posUs = p.currentPosition * 1000L
            updateTime()
        } else {
            if (posUs >= (info?.durationUs ?: 0) - frameUs) seek(0)
            p.start()
            btnPlay.text = "⏸"
            ui.removeCallbacks(ticker)
            ui.post(ticker)
        }
    }

    private fun seek(t: Long) {
        val vi = info ?: return
        posUs = t.coerceIn(0L, vi.durationUs)
        if (playerReady) player?.seekTo(posUs / 1000, MediaPlayer.SEEK_CLOSEST)
        updateTime()
    }

    private fun step(n: Int) {
        if (player?.isPlaying == true) {
            player?.pause()
            btnPlay.text = "▶"
            posUs = (player?.currentPosition ?: 0) * 1000L
        }
        seek(posUs + n * frameUs + if (n > 0) 500 else 0)
    }

    private fun updateTime() {
        txtTime.text = LosslessCutter.fmt(posUs)
        timeline.posUs = posUs
    }

    // ------------------------------------------------------------------ selection & segments

    private fun snap(t: Long): Long {
        val vi = info ?: return t
        return if (chkSnap.isChecked) LosslessCutter.prevKey(vi.keyframes, t) else t
    }

    private fun setIn() {
        val vi = info ?: return
        inUs = snap(posUs)
        if (outUs <= inUs) outUs = vi.durationUs
        renderSelection()
    }

    private fun setOut() {
        val vi = info ?: return
        outUs = posUs.coerceIn(0, vi.durationUs)
        if (inUs >= outUs) inUs = 0
        renderSelection()
    }

    private fun renderSelection() {
        txtSel.text = if (info == null) "" else
            "${LosslessCutter.fmt(inUs)} → ${LosslessCutter.fmt(outUs)}\n(${LosslessCutter.fmt(outUs - inUs)})"
        timeline.inUs = inUs
        timeline.outUs = outUs
    }

    private fun addSegment() {
        if (outUs - inUs < 10_000) return toast("Előbb jelöld ki a kezdetet és a véget.")
        val s = Segment(inUs, outUs)
        segs.add(s)
        segs.sortBy { it.startUs }
        activeSeg = segs.indexOf(s)
        renderSegments()
    }

    private fun invertSegments() {
        val vi = info ?: return
        if (segs.isEmpty()) return
        val merged = mutableListOf<Segment>()
        for (s in segs.sortedBy { it.startUs }) {
            val last = merged.lastOrNull()
            if (last != null && s.startUs <= last.endUs) last.endUs = max(last.endUs, s.endUs)
            else merged.add(s.copy())
        }
        val inv = mutableListOf<Segment>()
        var cur = 0L
        for (s in merged) {
            if (s.startUs - cur > 10_000) inv.add(Segment(snap(cur), s.startUs))
            cur = s.endUs
        }
        if (vi.durationUs - cur > 10_000) inv.add(Segment(snap(cur), vi.durationUs))
        segs.clear()
        segs.addAll(inv)
        activeSeg = -1
        renderSegments()
    }

    private fun renderSegments() {
        segList.removeAllViews()
        val d = resources.displayMetrics.density
        if (segs.isEmpty()) {
            segList.addView(TextView(this).apply {
                text = "Még nincs szakasz. Állítsd be a kezdetet és a véget, majd „+ Szakasz”. " +
                    "Szakasz nélkül a kijelölés (alapból a teljes videó) kerül exportálásra."
                setTextColor(getColor(R.color.muted))
                textSize = 13f
            })
        }
        segs.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                setBackgroundColor(getColor(if (i == activeSeg) R.color.line else R.color.panel2))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = (6 * d).toInt() }
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}."
                setTextColor(getColor(R.color.muted))
                minWidth = (22 * d).toInt()
            })
            row.addView(TextView(this).apply {
                text = "${LosslessCutter.fmt(s.startUs)} → ${LosslessCutter.fmt(s.endUs)}\n" +
                    "hossz: ${LosslessCutter.fmt(s.endUs - s.startUs)}"
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            fun small(label: String, action: () -> Unit) = Button(this, null, 0, R.style.Btn).apply {
                text = label
                minWidth = (40 * d).toInt()
                setOnClickListener { action() }
            }
            row.addView(small("▶") { activeSeg = i; seek(s.startUs); renderSegments() })
            row.addView(small("✎") {
                // load into the selection for adjusting, then "+ Szakasz" adds it back
                inUs = s.startUs; outUs = s.endUs
                segs.removeAt(i); activeSeg = -1
                seek(s.startUs); renderSelection(); renderSegments()
                toast("Állítsd be, majd „+ Szakasz”.")
            })
            row.addView(small("✕") { segs.removeAt(i); activeSeg = -1; renderSegments() })
            segList.addView(row)
        }
        timeline.segments = segs.toList()
        timeline.activeSegment = activeSeg
    }

    // ------------------------------------------------------------------ rotation

    private fun setRotation(r: Int) {
        rotation = ((r % 360) + 360) % 360
        rotBadge.visibility = if (rotation != 0) View.VISIBLE else View.GONE
        rotBadge.text = "Forgatás: $rotation° jobbra"
        layoutVideo()
    }

    /** Fit the TextureView to the (source-rotated) aspect, then preview the extra rotation. */
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

    // ------------------------------------------------------------------ export

    private fun startExport() {
        val vi = info ?: return
        val u = uri ?: return
        if (cutter != null) return
        val list = if (segs.isNotEmpty()) segs.map { it.copy() } else listOf(Segment(inUs, outUs))
        if (player?.isPlaying == true) togglePlay()
        val c = LosslessCutter(this, u, vi)
        cutter = c
        val keepAudio = chkAudio.isChecked
        val separate = radioSeparate.isChecked
        val rot = rotation
        setExporting(true)
        txtStatus.setTextColor(getColor(R.color.muted))
        txtStatus.text = "Indul…"
        progress.progress = 0
        val t0 = System.currentTimeMillis()
        Thread {
            try {
                val outs = c.export(list, rot, keepAudio, separate) { f, msg ->
                    ui.post {
                        progress.progress = (f * 1000).toInt()
                        txtStatus.text = "$msg – ${(f * 100).toInt()}%"
                    }
                }
                ui.post {
                    outputs = outs
                    val secs = (System.currentTimeMillis() - t0) / 1000.0
                    progress.progress = 1000
                    txtStatus.setTextColor(getColor(R.color.ok))
                    txtStatus.text = buildString {
                        append("Kész, újrakódolás nélkül (%.1f mp).\n".format(Locale.US, secs))
                        append(if (outs.size == 1) "Mentve: Filmek/Videovago" else "${outs.size} fájl mentve: Filmek/Videovago")
                        if (c.skipped.isNotEmpty()) append("\nKimaradt: ${c.skipped.joinToString()} (az MP4 nem támogatja).")
                    }
                    btnPlayOut.visibility = View.VISIBLE
                    btnShare.visibility = View.VISIBLE
                }
            } catch (e: LosslessCutter.Cancelled) {
                ui.post {
                    txtStatus.setTextColor(getColor(R.color.muted))
                    txtStatus.text = "Megszakítva."
                    progress.progress = 0
                }
            } catch (e: Throwable) {
                ui.post {
                    txtStatus.setTextColor(getColor(R.color.danger))
                    txtStatus.text = "Hiba: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                ui.post {
                    cutter = null
                    setExporting(false)
                }
            }
        }.start()
    }

    private fun setExporting(on: Boolean) {
        btnExport.isEnabled = !on
        btnCancel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            btnPlayOut.visibility = View.GONE
            btnShare.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun viewVideo(u: Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(i) }.onFailure { toast("Nincs videólejátszó alkalmazás.") }
    }

    private fun shareOutputs() {
        if (outputs.isEmpty()) return
        val i = if (outputs.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, outputs[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(outputs))
        }
        i.type = "video/mp4"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Megosztás"))
    }

    // ------------------------------------------------------------------ helpers

    private fun setControlsEnabled(on: Boolean) {
        val ids = intArrayOf(
            R.id.btnPlay, R.id.btnPrevKey, R.id.btnPrevFrame, R.id.btnNextFrame, R.id.btnNextKey,
            R.id.btnIn, R.id.btnOut, R.id.btnAdd, R.id.btnZoomIn, R.id.btnZoomOut, R.id.btnClear,
            R.id.btnInvert, R.id.btnRotL, R.id.btnRotR, R.id.btnRot180, R.id.btnRot0, R.id.btnExport,
        )
        for (id in ids) findViewById<View>(id).isEnabled = on
        val alpha = if (on) 1f else 0.4f
        for (id in ids) findViewById<View>(id).alpha = alpha
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun fmtSize(b: Long) =
        if (b > 1_000_000_000) "%.2f GB".format(Locale.US, b / 1e9) else "%.1f MB".format(Locale.US, b / 1e6)

    companion object {
        private const val REQ_OPEN = 1
    }
}

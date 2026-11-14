package com.duplikatumkereso.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duplikatumkereso.core.FolderRef
import com.duplikatumkereso.data.AppSettings
import com.duplikatumkereso.data.CrashGuard
import com.duplikatumkereso.data.FingerprintCache
import com.duplikatumkereso.data.GroupKind
import com.duplikatumkereso.data.MediaRepository
import com.duplikatumkereso.data.ScanOptions
import com.duplikatumkereso.data.ScanProgress
import com.duplikatumkereso.data.ScanResult
import com.duplikatumkereso.scan.ScanService
import com.duplikatumkereso.scan.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Results, Trash, Settings }
enum class Tab(val label: String) { EXACT("Pontos"), SAME("Felbontás"), SUBCLIP("Részlet"), IMAGES("Képek") }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings(app)
    private val repo = MediaRepository(app)

    var screen by mutableStateOf(Screen.Home)
    var tab by mutableStateOf(Tab.EXACT)
    var folder by mutableStateOf(settings.lastFolder())
        private set
    var includeSubfolders by mutableStateOf(settings.includeSubfolders)
    var scanVideos by mutableStateOf(settings.scanVideos)
    var scanImages by mutableStateOf(settings.scanImages)
    var selection by mutableStateOf<Set<Long>>(emptySet())
        private set
    var trashItems by mutableStateOf<List<MediaRepository.TrashedItem>>(emptyList())
        private set
    var trashSelection by mutableStateOf<Set<Long>>(emptySet())
    var cacheEntries by mutableStateOf(0L)
        private set
    var message by mutableStateOf<String?>(null)

    /** Az éppen jóváhagyásra váró rendszer-kérés által érintett elemek. */
    private var pendingTrash: Set<Long> = emptySet()

    val progress = ScanState.progress
    val result = ScanState.result

    var crashedOn by mutableStateOf<List<String>>(emptyList())
    var skippedCount by mutableStateOf(0)
        private set

    init {
        // Ha az előző vizsgálat közben az app leállt: a gyanús fájlokat feljegyezzük, legközelebb kihagyjuk.
        if (ScanState.job?.isActive != true) {
            crashedOn = CrashGuard(app).recoverFromPreviousRun()
        }
        viewModelScope.launch {
            var lastCreated = ScanState.result.value?.createdAt
            ScanState.result.collect { r ->
                if (r != null && r.createdAt != lastCreated) {
                    lastCreated = r.createdAt
                    selection = defaultSelection(r)
                    tab = when {
                        r.exact.isNotEmpty() -> Tab.EXACT
                        r.sameContent.isNotEmpty() -> Tab.SAME
                        r.subclips.isNotEmpty() -> Tab.SUBCLIP
                        r.images.isNotEmpty() -> Tab.IMAGES
                        else -> Tab.EXACT
                    }
                    screen = Screen.Results
                }
            }
        }
    }

    private fun defaultSelection(r: ScanResult): Set<Long> =
        (r.exact + r.images.filter { it.kind == GroupKind.IMAGE_EXACT })
            .flatMap { g -> g.items.filter { it.id != g.keepId }.map { it.id } }
            .toSet()

    fun setFolderFromDocumentId(docId: String) {
        val f = FolderRef.fromDocumentId(docId)
        if (f == null) {
            message = "Ezt a mappát nem lehet vizsgálni. Válassz egy mappát a belső tárhelyen vagy az SD-kártyán."
            return
        }
        folder = f
        settings.lastFolderDocId = docId
    }

    fun startScan() {
        val f = folder ?: return
        settings.includeSubfolders = includeSubfolders
        settings.scanVideos = scanVideos
        settings.scanImages = scanImages
        ScanState.pendingOptions = ScanOptions(
            documentId = f.documentId,
            volume = f.volume,
            relativePath = f.relativePath,
            label = f.label,
            includeSubfolders = includeSubfolders,
            videos = scanVideos,
            images = scanImages,
            intervalSec = settings.intervalSec,
            minMatches = settings.minMatches,
            frameThreshold = settings.frameThreshold,
            imageThreshold = settings.imageThreshold,
            durationToleranceSec = settings.durationToleranceSec,
            accurateFrames = settings.accurateFrames,
        )
        ScanState.progress.value = ScanProgress.Running("Indítás", 0, 0)
        ScanService.start(getApplication())
    }

    fun cancelScan() = ScanState.cancel()

    fun acknowledgeProgress() {
        ScanState.progress.value = ScanProgress.Idle
    }

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun selectAllButKeep(ids: List<Long>, keepId: Long) {
        selection = selection + ids.filter { it != keepId }
    }

    fun selectedBytes(r: ScanResult): Long {
        val all = (r.exact + r.sameContent + r.images).flatMap { it.items } +
            r.subclips.flatMap { listOf(it.long, it.short) }
        return all.distinctBy { it.id }.filter { it.id in selection }.sumOf { it.size }
    }

    fun trashRequestForSelection(): IntentSender? {
        val r = result.value ?: return null
        val all = ((r.exact + r.sameContent + r.images).flatMap { it.items } +
            r.subclips.flatMap { listOf(it.long, it.short) }).distinctBy { it.id }
        val chosen = all.filter { it.id in selection }
        if (chosen.isEmpty()) return null
        pendingTrash = chosen.map { it.id }.toSet()
        return repo.trashRequest(chosen.map { it.uri }).intentSender
    }

    fun onTrashResult(ok: Boolean) {
        if (ok && pendingTrash.isNotEmpty()) {
            val removed = pendingTrash
            ScanState.result.value = ScanState.result.value?.without(removed)
            selection = selection - removed
            message = "${removed.size} fájl a kukába került (30 napig visszaállítható)."
        }
        pendingTrash = emptySet()
    }

    // ---- Kuka ----

    fun openTrash() {
        screen = Screen.Trash
        loadTrash()
    }

    fun loadTrash() {
        val f = folder ?: return
        viewModelScope.launch {
            trashItems = withContext(Dispatchers.IO) {
                runCatching { repo.queryTrashed(f, includeSubfolders) }.getOrDefault(emptyList())
            }
            trashSelection = trashSelection.intersect(trashItems.map { it.id }.toSet())
        }
    }

    private fun selectedTrashUris(): List<Uri> = trashItems.filter { it.id in trashSelection }.map { it.uri }

    fun restoreRequest(): IntentSender? =
        selectedTrashUris().takeIf { it.isNotEmpty() }?.let { repo.restoreRequest(it).intentSender }

    fun deleteForeverRequest(): IntentSender? =
        selectedTrashUris().takeIf { it.isNotEmpty() }?.let { repo.deleteRequest(it).intentSender }

    fun onTrashActionDone() {
        trashSelection = emptySet()
        loadTrash()
    }

    // ---- Beállítások ----

    fun clearSkipped() {
        CrashGuard(getApplication()).clearSkipped()
        skippedCount = 0
        message = "A kihagyott fájlok listája törölve, a következő vizsgálat újra megpróbálja őket."
    }

    fun loadCacheInfo() {
        skippedCount = CrashGuard(getApplication()).skipped().size
        viewModelScope.launch {
            cacheEntries = withContext(Dispatchers.IO) {
                FingerprintCache(getApplication()).use { it.entryCount() }
            }
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FingerprintCache(getApplication()).use { it.clear() } }
            loadCacheInfo()
            message = "A tárolt ujjlenyomatok törölve."
        }
    }
}

package com.duplikatumkereso.scan

import com.duplikatumkereso.core.Format
import com.duplikatumkereso.core.MatchParams
import com.duplikatumkereso.core.Matcher
import com.duplikatumkereso.core.UnionFind
import com.duplikatumkereso.core.VideoFingerprint
import com.duplikatumkereso.core.VideoMatch
import com.duplikatumkereso.data.CrashGuard
import com.duplikatumkereso.data.DupGroup
import com.duplikatumkereso.data.FingerprintCache
import com.duplikatumkereso.data.GroupKind
import com.duplikatumkereso.data.MediaItem
import com.duplikatumkereso.data.MediaRepository
import com.duplikatumkereso.data.ScanOptions
import com.duplikatumkereso.data.ScanProgress
import com.duplikatumkereso.data.ScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class Scanner(
    private val repo: MediaRepository,
    private val cache: FingerprintCache,
    private val guard: CrashGuard,
) {
    suspend fun run(o: ScanOptions, report: (ScanProgress.Running) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val ctx = coroutineContext
        val cancelled = { !ctx.isActive }

        // 1. Fájlok listázása a MediaStore-ból (méret, hossz, felbontás egyből megvan)
        report(ScanProgress.Running("Fájlok listázása", 0, 0))
        val skipped = guard.skipped()
        val listing = repo.listFolder(o)
        val allItems = listing.items
        val items = allItems.filter { it.cacheKey !in skipped }
        val skippedHere = allItems.filter { it.cacheKey in skipped }.map { "${it.relativePath}${it.name}" }
        val byId = HashMap(items.associateBy { it.id })

        // 2. Pontos egyezés: azonos méret -> részleges hash -> teljes hash
        val sizeGroups = items.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        val toHash = sizeGroups.sumOf { it.size }
        var hashed = 0
        val exactSets = ArrayList<List<MediaItem>>()
        for (group in sizeGroups) {
            ensureActive()
            val partials = group.mapNotNull { item ->
                hashed++
                report(ScanProgress.Running("Azonos méretű fájlok ellenőrzése", hashed, toHash, item.name))
                val cached = cache.getHashes(item)
                val p = cached.first ?: runCatching { guard.guard(item) { repo.partialHash(item) } }.getOrNull()?.also {
                    cache.putHashes(item, it, cached.second)
                }
                p?.let { item to it }
            }.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }

            for (candidates in partials) {
                val fulls = candidates.mapNotNull { item ->
                    ensureActive()
                    report(ScanProgress.Running("Teljes tartalom összevetése", hashed, toHash, item.name))
                    val cached = cache.getHashes(item)
                    val f = cached.second ?: runCatching { guard.guard(item) { repo.fullHash(item, cancelled) } }.getOrNull()?.also {
                        cache.putHashes(item, cached.first, it)
                    }
                    f?.let { item to it }
                }.groupBy({ it.second }, { it.first }).values.filter { it.size > 1 }
                exactSets.addAll(fulls)
            }
        }
        ensureActive()

        // Pontos másolatokból csak egyet (a megtartandót) vizsgálunk tovább
        val duplicatesOfOthers = HashSet<Long>()
        val exactVideo = ArrayList<DupGroup>()
        val imageGroups = ArrayList<DupGroup>()
        for (set in exactSets) {
            val keep = ScanResult.bestOf(set)
            set.filter { it.id != keep.id }.forEach { duplicatesOfOthers.add(it.id) }
            val kind = if (keep.isVideo) GroupKind.EXACT_VIDEO else GroupKind.IMAGE_EXACT
            val g = DupGroup(kind, set, keep.id)
            if (keep.isVideo) exactVideo.add(g) else imageGroups.add(g)
        }
        val reps = items.filter { it.id !in duplicatesOfOthers }

        // 3. Videók képkockái
        val diagnostics = ArrayList<String>()
        val sameContent = ArrayList<DupGroup>()
        val subclips = ArrayList<com.duplikatumkereso.data.SubclipMatch>()
        if (o.videos) {
            val videos = reps.filter { it.isVideo }
            val intervalMs = o.intervalSec * 1000L
            val fps = ConcurrentHashMap<Long, VideoFingerprint>()
            val done = AtomicInteger(0)
            val sem = Semaphore(3) // a hardveres dekóderek száma korlátos
            val framesStarted = System.currentTimeMillis()
            val computed = AtomicInteger(0)
            coroutineScope {
                videos.map { item ->
                    async {
                        sem.withPermit {
                            ensureActive()
                            val fp = cache.getVideo(item, intervalMs, o.accurateFrames)
                                ?: guard.guard(item) {
                                    computed.incrementAndGet()
                                    repo.videoFingerprint(item, intervalMs, o.accurateFrames, cancelled)
                                }?.also {
                                    if (!cancelled()) cache.putVideo(item, o.accurateFrames, it)
                                }
                            if (fp != null) fps[item.id] = fp
                            report(ScanProgress.Running("Videók képkockáinak elemzése", done.incrementAndGet(), videos.size, item.name))
                        }
                    }
                }.awaitAll()
            }
            ensureActive()

            // A médiatárban nem szereplő videóknál a hossz és a felbontás az elemzésből jön.
            for (item in videos) {
                val fp = fps[item.id] ?: continue
                if (item.durationMs <= 0 || item.width <= 0) {
                    byId[item.id] = item.copy(
                        durationMs = if (item.durationMs > 0) item.durationMs else fp.durationMs,
                        width = if (item.width > 0) item.width else fp.width,
                        height = if (item.height > 0) item.height else fp.height,
                    )
                }
            }

            report(ScanProgress.Running("Videók összevetése", 0, 0))
            val params = MatchParams(
                frameThreshold = o.frameThreshold,
                minMatches = o.minMatches,
                durationToleranceMs = o.durationToleranceSec * 1000L,
            )
            val fpList = videos.mapNotNull { fps[it.id] }
            val analysis = Matcher.analyze(fpList, params, cancelled)
            val matches = analysis.mapNotNull { it.second }

            // Diagnosztika (csak számok): videónként a kockák, páronként az egyezések.
            val framesSec = (System.currentTimeMillis() - framesStarted) / 1000.0
            diagnostics.add(
                String.format(
                    java.util.Locale.forLanguageTag("hu-HU"),
                    "Képkockák kiolvasása: %.1f mp, %d videó újonnan elemezve (gyors módszer: %d, régi módszer: %d), %d a tárolt adatokból.",
                    framesSec, computed.get(), repo.fastCount.get(), repo.slowCount.get(), videos.size - computed.get(),
                ),
            )
            diagnostics.add("Beállítás: ${o.intervalSec} mp-enként 1 kocka, részlethez >${o.minMatches} egyező kocka, kockánként ≤${o.frameThreshold} bit eltérés.")
            for (item in videos.sortedBy { it.name }.take(100)) {
                val fp = fps[item.id]
                diagnostics.add(
                    if (fp == null) {
                        "${item.name}: NEM sikerült elemezni"
                    } else {
                        "${item.name}: ${Format.duration(fp.durationMs)}, ${fp.hashes.size} kocka, ebből ${fp.validCount} használható"
                    },
                )
            }
            val names = videos.associate { it.id to it.name }
            analysis.sortedByDescending { max(it.first.consistent, it.first.bestWindow) }.take(15).forEach { (sc, m) ->
                val verdict = when (m) {
                    is VideoMatch.Subclip -> "→ RÉSZLET"
                    is VideoMatch.SameContent -> "→ AZONOS"
                    null -> "→ nem jelezve"
                }
                diagnostics.add(
                    "${names[sc.shortId]} ↔ ${names[sc.longId]}: részletekben ${sc.consistent} egyező kocka " +
                        "(${sc.segments.size} részlet), egy eltolásnál ${sc.bestWindow}, rövidebb használható kockái ${sc.shortValid} $verdict",
                )
            }
            if (analysis.isEmpty() && fpList.size > 1) diagnostics.add("Egyik videópárban sincs egyetlen egyező kocka sem.")
            ensureActive()

            // Azonos tartalom: csoportokká fűzzük
            val same = matches.filterIsInstance<VideoMatch.SameContent>()
            if (same.isNotEmpty()) {
                val ids = same.flatMap { listOf(it.a, it.b) }.distinct()
                val index = ids.withIndex().associate { it.value to it.index }
                val uf = UnionFind(ids.size)
                same.forEach { uf.union(index.getValue(it.a), index.getValue(it.b)) }
                for (g in uf.groups()) {
                    if (g.size < 2) continue
                    val groupItems = g.mapNotNull { byId[ids[it]] }
                    val groupIds = groupItems.map { it.id }.toSet()
                    val ratio = same.filter { it.a in groupIds && it.b in groupIds }.minOf { it.ratio }
                    sameContent.add(
                        DupGroup(
                            GroupKind.SAME_CONTENT,
                            groupItems,
                            ScanResult.bestOf(groupItems).id,
                            (ratio * 100).toInt().coerceIn(0, 100),
                        ),
                    )
                }
            }
            matches.filterIsInstance<VideoMatch.Subclip>().forEach { m ->
                val l = byId[m.longId]
                val s = byId[m.shortId]
                if (l != null && s != null) {
                    subclips.add(com.duplikatumkereso.data.SubclipMatch(l, s, m.startMs, m.endMs, m.matchedFrames, m.ratio, m.segments))
                }
            }
        }

        // 4. Képek
        if (o.images) {
            val images = reps.filter { !it.isVideo }
            val ids = ArrayList<Long>(images.size)
            val hashes = ArrayList<Long>(images.size)
            for ((i, item) in images.withIndex()) {
                ensureActive()
                if (i % 20 == 0) report(ScanProgress.Running("Képek elemzése", i, images.size, item.name))
                val h = cache.getImage(item)
                    ?: (guard.guard(item) { repo.imageHash(item) } ?: Long.MIN_VALUE).also { cache.putImage(item, it) }
                if (h != Long.MIN_VALUE) {
                    ids.add(item.id)
                    hashes.add(h)
                }
            }
            report(ScanProgress.Running("Képek összevetése", 0, 0))
            Matcher.groupSimilarImages(ids.toLongArray(), hashes.toLongArray(), o.imageThreshold).forEach { g ->
                val groupItems = g.mapNotNull { byId[it] }
                imageGroups.add(DupGroup(GroupKind.IMAGE_SIMILAR, groupItems, ScanResult.bestOf(groupItems).id))
            }
        }

        // A pontos másolatok bájtra azonosak: a hosszt/felbontást a megtartott (elemzett) példánytól kapják.
        val exactEnriched = exactVideo.map { g ->
            val rep = byId[g.keepId]
            if (rep == null) g else g.copy(items = g.items.map { it.copy(durationMs = rep.durationMs, width = rep.width, height = rep.height) })
        }

        ScanResult(
            createdAt = System.currentTimeMillis(),
            folderLabel = o.label,
            fileCount = items.size,
            exact = exactEnriched.sortedByDescending { it.reclaimable },
            sameContent = sameContent.sortedByDescending { it.reclaimable },
            subclips = subclips.sortedByDescending { it.short.size },
            images = imageGroups.sortedByDescending { it.reclaimable },
            skippedFiles = skippedHere,
            notIndexed = listing.notIndexed,
            diagnostics = diagnostics,
        )
    }
}

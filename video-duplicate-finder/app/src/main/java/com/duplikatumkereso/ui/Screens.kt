package com.duplikatumkereso.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duplikatumkereso.core.Format
import com.duplikatumkereso.data.DupGroup
import com.duplikatumkereso.data.GroupKind
import com.duplikatumkereso.data.MediaItem
import com.duplikatumkereso.data.ScanProgress
import com.duplikatumkereso.data.ScanResult
import com.duplikatumkereso.data.SubclipMatch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ Kezdőképernyő

@Composable
fun HomeScreen(
    vm: MainViewModel,
    progress: ScanProgress,
    hasResult: Boolean,
    onPickFolder: () -> Unit,
    onScan: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Duplikátum­kereső",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = C.text,
                modifier = Modifier.weight(1f),
            )
            RoundIcon(Icons.Filled.Delete, "Kuka", onClick = vm::openTrash)
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Filled.Settings, "Beállítások") {
                vm.loadCacheInfo()
                vm.screen = Screen.Settings
            }
        }
        Text(
            "Videók és képek ismétlődéseinek keresése egy mappán belül.",
            color = C.text2,
            fontSize = 15.sp,
        )

        SectionCard {
            CardHeader("Mappa", "")
            val f = vm.folder
            Text(
                f?.label ?: "Még nincs kiválasztva",
                color = if (f == null) C.text2 else C.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedButton(
                onClick = onPickFolder,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, C.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (f == null) "Mappa kiválasztása" else "Másik mappa")
            }
        }

        SectionCard {
            CardHeader("Mit vizsgáljon?", "")
            ToggleRow("Almappák is", vm.includeSubfolders) { vm.includeSubfolders = it }
            ToggleRow("Videók", vm.scanVideos) { vm.scanVideos = it }
            ToggleRow("Képek", vm.scanImages) { vm.scanImages = it }
        }

        if (vm.crashedOn.isNotEmpty()) {
            InfoText(
                "Az előző vizsgálat közben az app leállt ennél a fájlnál: " +
                    vm.crashedOn.joinToString(", ") +
                    ". A következő vizsgálat ezt kihagyja (Beállításokban visszaállítható).",
            ) { vm.crashedOn = emptyList() }
        }

        when (progress) {
            is ScanProgress.Cancelled -> InfoText("A vizsgálat megszakadt. Az eddig elemzett fájlokat megjegyeztem, újraindításkor onnan folytatja.") {
                vm.acknowledgeProgress()
            }
            is ScanProgress.Failed -> InfoText("Hiba történt: ${progress.message}") { vm.acknowledgeProgress() }
            else -> {}
        }

        Button(
            onClick = onScan,
            enabled = vm.folder != null && (vm.scanVideos || vm.scanImages),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C.accent,
                contentColor = C.onAccent,
                disabledContainerColor = C.surface2,
                disabledContentColor = C.text3,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) { Text("Vizsgálat indítása", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

        if (hasResult) {
            OutlinedButton(
                onClick = { vm.screen = Screen.Results },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, C.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) { Text("Legutóbbi eredmény") }
        }

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = C.text3, modifier = Modifier.size(16.dp))
            Text(
                "Minden a telefonon történik. Az app nem kapott internet-hozzáférést, semmilyen adatot nem küld el.",
                color = C.text3,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun InfoText(text: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.surface2)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = C.text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("OK", color = C.accentText) }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = C.accent,
                checkedThumbColor = C.onAccent,
                uncheckedTrackColor = C.track,
                uncheckedThumbColor = C.text2,
                uncheckedBorderColor = C.outline,
            ),
        )
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(containerColor = C.surface, contentColor = C.text),
        modifier = Modifier.size(44.dp),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp)) }
}

// ------------------------------------------------------------------ Vizsgálat folyamatban

@Composable
fun ScanningScreen(p: ScanProgress.Running, runsInBackground: Boolean, onCancel: () -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(runsInBackground) {
        view.keepScreenOn = !runsInBackground
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Text("Keresés folyamatban", color = C.text2, fontSize = 15.sp)
        Text(p.phase, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp)
        if (p.total > 0) {
            LinearProgressIndicator(
                progress = { p.done.toFloat() / p.total },
                color = C.accent,
                trackColor = C.track,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Text(
                "${Format.count(p.done)} / ${Format.count(p.total)}",
                color = C.accentText,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
        } else {
            LinearProgressIndicator(
                color = C.accent,
                trackColor = C.track,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        if (p.detail.isNotEmpty()) {
            Text(p.detail, color = C.text3, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (runsInBackground) {
            Text(
                "A vizsgálat a háttérben is fut, a telefon nyugodtan lezárható. " +
                    "Az elemzett videókat megjegyzi, így a következő keresés sokkal gyorsabb.",
                color = C.text2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        } else {
            Text(
                "A telefon nem engedi, hogy a vizsgálat a háttérben fusson. Tartsd nyitva az appot, " +
                    "és ne zárd le a képernyőt. Ha mégis megszakad, az eddig elemzett fájlokat megjegyzi, " +
                    "újraindításkor onnan folytatja.",
                color = C.accentText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, C.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) { Text("Megszakítás") }
    }
}

// ------------------------------------------------------------------ Eredmények

@Composable
fun ResultsScreen(vm: MainViewModel, result: ScanResult, onRescan: () -> Unit, onTrash: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.screen = Screen.Home }, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza", tint = C.text)
                }
                Text(
                    result.folderLabel,
                    color = C.text2,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RoundIcon(Icons.Filled.Refresh, "Újravizsgálás", onClick = onRescan)
            }
            Column {
                Text(Format.bytes(result.totalReclaimable), color = C.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (result.groupCount == 0) "nem találtam ismétlődést" else "ismétlődés ${result.groupCount} csoportban",
                    color = C.text2,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (result.fileCount == 0) {
                        "A mappában nem találtam videót vagy képet."
                    } else {
                        "${Format.count(result.fileCount)} fájl átnézve" +
                            if (result.notIndexed > 0) ", ebből ${Format.count(result.notIndexed)} a médiatáron kívül" else ""
                    },
                    color = if (result.fileCount == 0) C.accentText else C.text3,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (t in Tab.entries) {
                    val n = when (t) {
                        Tab.EXACT -> result.exact.size
                        Tab.SAME -> result.sameContent.size
                        Tab.SUBCLIP -> result.subclips.size
                        Tab.IMAGES -> result.images.size
                    }
                    Pill("${t.label} $n", vm.tab == t) { vm.tab = t }
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (result.skippedFiles.isNotEmpty()) {
                item {
                    Text(
                        "Kihagyva (korábban leállást okozott): " + result.skippedFiles.joinToString(", "),
                        color = C.text3,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            when (vm.tab) {
                Tab.EXACT -> {
                    if (result.exact.isEmpty()) item { EmptyTab("Nincs bájtra pontosan egyező videó.") }
                    items(result.exact, key = { "e" + it.keepId }) { GroupCard(vm, it) }
                }
                Tab.SAME -> {
                    if (result.sameContent.isEmpty()) item { EmptyTab("Nincs azonos hosszúságú, egyező tartalmú videó.") }
                    items(result.sameContent, key = { "s" + it.keepId }) { GroupCard(vm, it) }
                }
                Tab.SUBCLIP -> {
                    if (result.subclips.isEmpty()) item { EmptyTab("Nincs olyan videó, amely egy hosszabb videó részlete lenne.") }
                    items(result.subclips, key = { "c" + it.long.id + "_" + it.short.id }) { SubclipCard(vm, it) }
                }
                Tab.IMAGES -> {
                    if (result.images.isEmpty()) item { EmptyTab("Nincs ismétlődő kép.") }
                    items(result.images, key = { "i" + it.kind.name + it.keepId }) { GroupCard(vm, it) }
                }
            }
            if (result.diagnostics.isNotEmpty()) {
                item(key = "diag") { DiagnosticsCard(result.diagnostics) }
            }
        }

        val selectedCount = vm.selection.size
        Row(
            Modifier
                .fillMaxWidth()
                .background(C.bg)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = {
                    val docId = vm.folder?.documentId
                    if (docId != null) openFolder(ctx, docId)
                },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, C.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) { Text("Mappa megnyitása", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = onTrash,
                enabled = selectedCount > 0,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.accent,
                    contentColor = C.onAccent,
                    disabledContainerColor = C.surface2,
                    disabledContentColor = C.text3,
                ),
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Kukába ($selectedCount)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (selectedCount > 0) Text(Format.bytes(vm.selectedBytes(result)), fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(text: String) {
    Text(
        text,
        color = C.text2,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
    )
}

@Composable
private fun GroupCard(vm: MainViewModel, g: DupGroup) {
    val title = when (g.kind) {
        GroupKind.EXACT_VIDEO -> "Pontos másolat"
        GroupKind.SAME_CONTENT -> "Eltérő felbontás / tömörítés"
        GroupKind.IMAGE_EXACT -> "Pontos másolat (kép)"
        GroupKind.IMAGE_SIMILAR -> "Hasonló kép"
    }
    val right = buildString {
        append("${g.items.size} fájl · ")
        if (g.similarity != null) append("egyezés ${g.similarity}% · ")
        append(Format.bytes(g.reclaimable))
    }
    SectionCard {
        CardHeader(title, right)
        for (item in g.items) {
            FileRow(vm, item, keep = item.id == g.keepId)
        }
        if (g.kind != GroupKind.EXACT_VIDEO && g.kind != GroupKind.IMAGE_EXACT) {
            TextButton(
                onClick = { vm.selectAllButKeep(g.items.map { it.id }, g.keepId) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(32.dp),
            ) { Text("A megtartandón kívül mind kijelölése", color = C.accentText, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun FileRow(vm: MainViewModel, item: MediaItem, keep: Boolean) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(C.surface2)
            .clickable { openMedia(ctx, item.uri, item.mime) }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumb(item.uri, item.isVideo, Modifier.size(width = 64.dp, height = 44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.name,
                color = C.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(metaLine(item), color = C.text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.relativePath.trimEnd('/'), color = C.text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (keep) KeepBadge()
        DarkCheckbox(item.id in vm.selection) { vm.toggle(item.id) }
    }
}

private fun metaLine(item: MediaItem): String = buildList {
    if (item.width > 0 && item.height > 0) add("${item.width}×${item.height}")
    if (item.isVideo && item.durationMs > 0) add(Format.duration(item.durationMs))
    add(Format.bytes(item.size))
}.joinToString(" · ")

@Composable
private fun SubclipCard(vm: MainViewModel, m: SubclipMatch) {
    val ctx = LocalContext.current
    SectionCard {
        CardHeader("Kivágott részlet", "${m.matchedFrames} egyező kocka")
        Text("Eredeti (hosszabb)", color = C.text3, fontSize = 12.sp)
        FileRow(vm, m.long, keep = false)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { openMedia(ctx, m.long.uri, m.long.mime) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    m.long.name,
                    color = C.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Format.duration(m.long.durationMs),
                    color = C.text2,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Timeline(m.segments, m.long.durationMs)
            Row(Modifier.fillMaxWidth()) {
                Text("0:00", color = C.text3, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(
                    if (m.segments.size == 1) "${Format.duration(m.startMs)} – ${Format.duration(m.endMs)}" else "${m.segments.size} részlet",
                    color = C.accentText,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                Text(Format.duration(m.long.durationMs), color = C.text3, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text("Összevágott / kivágott (rövidebb)", color = C.text3, fontSize = 12.sp)
        FileRow(vm, m.short, keep = false)
        Text(
            if (m.segments.size == 1) {
                "A rövidebb videó (${Format.duration(m.short.durationMs)}) a hosszabb " +
                    "${Format.duration(m.startMs)} és ${Format.duration(m.endMs)} közötti szakaszából készült. " +
                    "Jelöld ki, melyiket szeretnéd törölni."
            } else {
                "A rövidebb videó (${Format.duration(m.short.durationMs)}) a hosszabb ${m.segments.size} részletéből " +
                    "van összevágva: " +
                    m.segments.joinToString(", ") { "${Format.duration(it.first)}–${Format.duration(it.second)}" } +
                    ". Jelöld ki, melyiket szeretnéd törölni."
            },
            color = C.text2,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun Timeline(segments: List<Pair<Long, Long>>, totalMs: Long) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(C.track),
    ) {
        val total = totalMs.coerceAtLeast(1).toFloat()
        for ((startMs, endMs) in segments) {
            val startFrac = (startMs / total).coerceIn(0f, 0.985f)
            val widthFrac = ((endMs - startMs) / total).coerceIn(0.015f, 1f - startFrac)
            Box(
                Modifier
                    .offset(x = maxWidth * startFrac)
                    .width(maxWidth * widthFrac)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(C.accent),
            )
        }
    }
}

// ------------------------------------------------------------------ Kuka

@Composable
fun TrashScreen(vm: MainViewModel, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val ctx = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy. MM. dd.", Locale.forLanguageTag("hu-HU")) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.screen = Screen.Home }, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza", tint = C.text)
                }
                Text("Kuka", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                RoundIcon(Icons.Filled.Refresh, "Frissítés", onClick = vm::loadTrash)
            }
            Text(
                "A kiválasztott mappából (${vm.folder?.label ?: "–"}) törölt fájlok. " +
                    "A rendszer a lejárat napján véglegesen törli őket.",
                color = C.text2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.trashItems.isEmpty()) item { EmptyTab("A kuka üres.") }
            items(vm.trashItems, key = { it.id }) { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(C.surface)
                        .clickable {
                            vm.trashSelection = if (t.id in vm.trashSelection) vm.trashSelection - t.id else vm.trashSelection + t.id
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            t.name.removePrefix(".trashed-").replace(Regex("^\\d+-"), ""),
                            color = C.text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val expires = if (t.expires > 0) " · lejár: ${dateFormat.format(Date(t.expires * 1000))}" else ""
                        Text(
                            "${if (t.isVideo) "Videó" else "Kép"} · ${Format.bytes(t.size)}$expires",
                            color = C.text2,
                            fontSize = 12.sp,
                        )
                    }
                    DarkCheckbox(t.id in vm.trashSelection) {
                        vm.trashSelection = if (t.id in vm.trashSelection) vm.trashSelection - t.id else vm.trashSelection + t.id
                    }
                }
            }
        }
        val n = vm.trashSelection.size
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onDeleteForever,
                enabled = n > 0,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, C.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.danger, disabledContentColor = C.text3),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) { Text("Végleges törlés", fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = onRestore,
                enabled = n > 0,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = C.accent,
                    contentColor = C.onAccent,
                    disabledContainerColor = C.surface2,
                    disabledContentColor = C.text3,
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) { Text("Visszaállítás ($n)", fontWeight = FontWeight.SemiBold) }
        }
    }
    LaunchedEffect(Unit) { if (vm.folder == null) vm.message = "Előbb válassz mappát a kezdőképernyőn." }
}

// ------------------------------------------------------------------ Beállítások

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.settings
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.screen = Screen.Home }, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza", tint = C.text)
            }
            Text("Beállítások", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }

        SectionCard {
            CardHeader("Videó-összevetés", "")
            Stepper(
                "Képkocka-mintavétel",
                "Ennyi másodpercenként vesz egy kockát. Ritkább = gyorsabb, de a rövid részleteket nehezebb felismerni.",
                s.intervalSec, 2, 60, 1, "mp",
            ) { s.intervalSec = it }
            Stepper(
                "Részlet: egyező kockák",
                "Ennél TÖBB egyező kocka esetén jelzi, hogy az egyik videó a másik részlete. " +
                    "Jelenleg legalább ~${(s.minMatches + 1) * s.intervalSec / 60} perc közös rész kell.",
                s.minMatches, 2, 50, 1, "db",
            ) { s.minMatches = it }
            Stepper(
                "Kockák hasonlósága",
                "Hány bitben térhet el két kocka 64-ből, hogy egyezőnek számítson. Nagyobb = engedékenyebb.",
                s.frameThreshold, 2, 20, 1, "bit",
            ) { s.frameThreshold = it }
            Stepper(
                "Azonos hossz tűrése",
                "Ekkora hosszeltérésnél még azonos videónak tekinti (eltérő felbontás fül).",
                s.durationToleranceSec, 0, 10, 1, "mp",
            ) { s.durationToleranceSec = it }
            var accurate by remember { mutableStateOf(s.accurateFrames) }
            ToggleRow("Pontos képkocka (lassabb)", accurate) {
                accurate = it
                s.accurateFrames = it
            }
            Text(
                "Kikapcsolva a legközelebbi kulcskockát használja, ami többszörösen gyorsabb.",
                color = C.text3,
                fontSize = 12.sp,
            )
        }

        SectionCard {
            CardHeader("Részlet-találatok", "")
            Text("Alapból kijelölve törlésre:", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            var choice by remember { mutableIntStateOf(s.subclipDefault) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Semmi", "A hosszabb (eredeti)", "A rövidebb").forEachIndexed { i, label ->
                    Pill(label, choice == i) {
                        choice = i
                        s.subclipDefault = i
                    }
                }
            }
            Text(
                "A következő vizsgálattól érvényes. Kijelölés után is minden kártyán átállítható.",
                color = C.text3,
                fontSize = 12.sp,
            )
        }

        SectionCard {
            CardHeader("Képek", "")
            Stepper(
                "Hasonló kép tűrése",
                "Hány bitben térhet el két kép ujjlenyomata. 0 = csak szinte azonos képek.",
                s.imageThreshold, 0, 12, 1, "bit",
            ) { s.imageThreshold = it }
        }

        SectionCard {
            CardHeader("Tárolt ujjlenyomatok", "${Format.count(vm.cacheEntries.toInt())} fájl")
            Text(
                "Az elemzett fájlok ujjlenyomatait az app megjegyzi (csak ezen a telefonon), így az újabb keresés gyors. " +
                    "A mintavétel módosítása után a videókat újra elemzi.",
                color = C.text2,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            OutlinedButton(
                onClick = vm::clearCache,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, C.outline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tárolt ujjlenyomatok törlése") }
        }

        if (vm.skippedCount > 0) {
            SectionCard {
                CardHeader("Kihagyott fájlok", "${vm.skippedCount} db")
                Text(
                    "Ezek a fájlok korábban az app leállását okozták, ezért a vizsgálat kihagyja őket.",
                    color = C.text2,
                    fontSize = 13.sp,
                )
                OutlinedButton(
                    onClick = vm::clearSkipped,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Újrapróbálás legközelebb") }
            }
        }
    }
}

@Composable
private fun Stepper(
    title: String,
    help: String,
    initial: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String,
    onChange: (Int) -> Unit,
) {
    var value by remember { mutableIntStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StepButton("−", "$title csökkentése", value > min) {
                value = (value - step).coerceAtLeast(min)
                onChange(value)
            }
            Text(
                "$value $unit",
                color = C.accentText,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                modifier = Modifier.width(72.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            StepButton("+", "$title növelése", value < max) {
                value = (value + step).coerceAtMost(max)
                onChange(value)
            }
        }
        Text(help, color = C.text3, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(C.surface2)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = if (enabled) C.text else C.text3, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DiagnosticsCard(lines: List<String>) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text(if (open) "Vizsgálat részletei ▲" else "Vizsgálat részletei ▼", color = C.text2, fontSize = 13.sp)
        }
        if (open) {
            SectionCard {
                Text(
                    "Csak számok és fájlnevek, a videók tartalmából semmi. Ha valami nem stimmel, ezt másold ki.",
                    color = C.text3,
                    fontSize = 12.sp,
                )
                for (line in lines) {
                    Text(line, color = C.text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                }
                OutlinedButton(
                    onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Diagnosztika", lines.joinToString("\n")))
                        android.widget.Toast.makeText(ctx, "Vágólapra másolva", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, C.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.text),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Másolás") }
            }
        }
    }
}

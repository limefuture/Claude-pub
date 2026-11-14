package com.duplikatumkereso.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object ThumbCache {
    val cache = LruCache<String, ImageBitmap>(150)
}

/** Bélyegkép a rendszer saját (helyi) bélyegkép-tárából. */
@Composable
fun Thumb(uri: Uri, isVideo: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = uri.toString()
    var bmp by remember(key) { mutableStateOf<ImageBitmap?>(ThumbCache.cache.get(key)) }
    LaunchedEffect(key) {
        if (bmp == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null).asImageBitmap()
                }.getOrNull()
            }
            if (loaded != null) {
                ThumbCache.cache.put(key, loaded)
                bmp = loaded
            }
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(C.thumb),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (isVideo) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = C.accentText, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.accent, contentColor = C.onAccent),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(40.dp),
        ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color2.pillText),
            border = androidx.compose.foundation.BorderStroke(1.dp, C.outline),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
            modifier = Modifier.height(40.dp),
        ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

private object Color2 {
    val pillText = androidx.compose.ui.graphics.Color(0xFFC9CDD6)
}

@Composable
fun DarkCheckbox(checked: Boolean, onChange: () -> Unit) {
    Checkbox(
        checked = checked,
        onCheckedChange = { onChange() },
        colors = CheckboxDefaults.colors(
            checkedColor = C.accent,
            uncheckedColor = C.text2,
            checkmarkColor = C.onAccent,
        ),
    )
}

@Composable
fun KeepBadge() {
    Text(
        "Megtart",
        color = C.keepText,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(C.keepBg)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(C.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content() }
}

@Composable
fun CardHeader(left: String, right: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            left.uppercase(),
            color = C.accentText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.weight(1f),
        )
        Text(right, color = C.text2, fontSize = 12.sp)
    }
}

@Composable
fun OutlineBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, C.outline, RoundedCornerShape(14.dp)),
    ) { content() }
}

/** Fájl megnyitása a telefon lejátszójával / képnézegetőjével. */
fun openMedia(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Nincs alkalmazás a megnyitáshoz", Toast.LENGTH_SHORT).show()
    }
}

/** A vizsgált mappa megnyitása fájlkezelőben (ha a telefon fájlkezelője támogatja). */
fun openFolder(ctx: Context, documentId: String) {
    val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(ctx, "A fájlkezelő nem nyitható meg innen. Nyisd meg a Saját fájlok appot kézzel.", Toast.LENGTH_LONG).show()
    }
}

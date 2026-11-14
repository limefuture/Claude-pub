package com.duplikatumkereso.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.duplikatumkereso.DupApp
import com.duplikatumkereso.scan.ScanState
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.duplikatumkereso.data.ScanProgress

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DupTheme {
                App()
            }
        }
    }
}

private fun hasFullMediaAccess(ctx: android.content.Context): Boolean {
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return if (Build.VERSION.SDK_INT >= 33) {
        granted(Manifest.permission.READ_MEDIA_VIDEO) && granted(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val progress by vm.progress.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val runsInBackground by ScanState.runsInBackground.collectAsStateWithLifecycle()
    var showPermissionHelp by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf(DupApp.takeLastCrash(ctx)) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) vm.setFolder(uri)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!hasFullMediaAccess(ctx)) {
            showPermissionHelp = true
        } else if (!vm.startScan()) {
            folderPicker.launch(null)
        }
    }

    fun requestScan() {
        if (hasFullMediaAccess(ctx)) {
            if (!vm.startScan()) folderPicker.launch(null)
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms)
    }

    var trashAction by remember { mutableStateOf<String?>(null) }
    val intentSenderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val ok = res.resultCode == android.app.Activity.RESULT_OK
        when (trashAction) {
            "trash" -> vm.onTrashResult(ok)
            else -> if (ok) vm.onTrashActionDone()
        }
        trashAction = null
    }

    fun launchSender(action: String, sender: IntentSender?) {
        if (sender == null) return
        trashAction = action
        intentSenderLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            vm.message = null
        }
    }

    BackHandler(enabled = vm.screen != Screen.Home && progress !is ScanProgress.Running) {
        vm.screen = Screen.Home
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(C.bg)
            .safeDrawingPadding(),
    ) {
        val p = progress
        when {
            p is ScanProgress.Running -> ScanningScreen(p, runsInBackground, onCancel = vm::cancelScan)
            vm.screen == Screen.Results && result != null -> ResultsScreen(
                vm = vm,
                result = result!!,
                onRescan = ::requestScan,
                onTrash = { launchSender("trash", vm.trashRequestForSelection()) },
            )
            vm.screen == Screen.Trash -> TrashScreen(
                vm = vm,
                onRestore = { launchSender("restore", vm.restoreRequest()) },
                onDeleteForever = { launchSender("delete", vm.deleteForeverRequest()) },
            )
            vm.screen == Screen.Settings -> SettingsScreen(vm)
            else -> HomeScreen(
                vm = vm,
                progress = p,
                hasResult = result != null,
                onPickFolder = { folderPicker.launch(null) },
                onScan = ::requestScan,
            )
        }
    }

    lastCrash?.let { crash ->
        AlertDialog(
            onDismissRequest = { lastCrash = null },
            title = { Text("Az app legutóbb hibával leállt") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Másold ki a hiba leírását, és küldd el a fejlesztőnek. (Semmi nem lett elküldve automatikusan.)")
                    Text(crash, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("Hiba", crash))
                    Toast.makeText(ctx, "Vágólapra másolva", Toast.LENGTH_SHORT).show()
                    lastCrash = null
                }) { Text("Másolás") }
            },
            dismissButton = {
                TextButton(onClick = { lastCrash = null }) { Text("Bezárás") }
            },
        )
    }

    if (vm.pendingPermanentDelete.isNotEmpty()) {
        val items = vm.pendingPermanentDelete
        AlertDialog(
            onDismissRequest = vm::cancelPermanentDelete,
            title = { Text("Végleges törlés?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "${items.size} kijelölt fájl nem szerepel a rendszer médiatárában, ezért nem tehető kukába, " +
                            "csak véglegesen törölhető (nem visszaállítható):",
                    )
                    items.take(20).forEach { Text("• ${it.name}", fontSize = 13.sp) }
                    if (items.size > 20) Text("… és még ${items.size - 20}", fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = vm::confirmPermanentDelete) { Text("Végleges törlés", color = C.danger) }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelPermanentDelete) { Text("Mégse") }
            },
        )
    }

    if (showPermissionHelp) {
        AlertDialog(
            onDismissRequest = { showPermissionHelp = false },
            title = { Text("Teljes hozzáférés szükséges") },
            text = {
                Text(
                    "A kereséshez az app-nak látnia kell az összes fotót és videót. " +
                        "Ha csak „Kiválasztott fotók” hozzáférést adtál, a Beállításokban állítsd át " +
                        "„Mindig engedélyezett”-re (Fotók és videók). Az app semmit nem küld el, minden a telefonon marad.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionHelp = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
                    )
                }) { Text("Beállítások") }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionHelp = false }) { Text("Mégse") }
            },
        )
    }
}

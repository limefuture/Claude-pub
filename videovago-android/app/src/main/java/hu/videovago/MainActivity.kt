package hu.videovago

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View

/** Start screen: pick a video (or receive one via Share), then choose the editing mode. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.tileOpen).setOnClickListener { pickVideo() }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
        if (u != null) chooseMode(u)
    }

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("video/*")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OPEN)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK) {
            val u = data?.data ?: return
            runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            chooseMode(u)
        }
    }

    private fun chooseMode(u: Uri) {
        val view = layoutInflater.inflate(R.layout.dialog_mode, null)
        view.findViewById<ModeDiagramView>(R.id.diagMulti).multi = true
        val dlg = AlertDialog.Builder(this).setView(view).create()
        view.findViewById<View>(R.id.cardTrim).setOnClickListener { dlg.dismiss(); openEditor(u, multi = false) }
        view.findViewById<View>(R.id.cardMulti).setOnClickListener { dlg.dismiss(); openEditor(u, multi = true) }
        view.findViewById<View>(R.id.btnModeCancel).setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun openEditor(u: Uri, multi: Boolean) {
        val i = Intent(this, EditorActivity::class.java)
            .setDataAndType(u, "video/*")
            .putExtra(EditorActivity.EXTRA_MULTI, multi)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(i)
    }

    companion object {
        private const val REQ_OPEN = 1
    }
}

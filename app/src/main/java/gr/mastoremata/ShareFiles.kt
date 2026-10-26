package gr.mastoremata

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDate

/**
 * Αρχεία προς άλλες εφαρμογές, χωρίς AndroidX (αντί για FileProvider ένας μικρός δικός μας):
 *   content://gr.mastoremata.files/share/<όνομα>  cache/share, μόνο ανάγνωση (αντίγραφο προς το Drive κ.λπ.)
 *   content://gr.mastoremata.files/cam/<όνομα>    cache/cam, και εγγραφή (εκεί γράφει η κάμερα τη φωτογραφία)
 */
class ShareProvider : ContentProvider() {
    companion object {
        const val AUTH = "gr.mastoremata.files"
        fun dir(ctx: Context): File = File(ctx.cacheDir, "share").apply { mkdirs() }
        fun camDir(ctx: Context): File = File(ctx.cacheDir, "cam").apply { mkdirs() }
        fun uri(name: String): Uri = Uri.parse("content://$AUTH/share/" + Uri.encode(name))
        fun camUri(name: String): Uri = Uri.parse("content://$AUTH/cam/" + Uri.encode(name))
    }

    private fun file(uri: Uri): File? {
        val seg = uri.pathSegments
        if (seg.size != 2) return null
        val n = seg[1]
        if (n.contains('/') || n.startsWith(".")) return null
        val ctx = context ?: return null
        val d = when (seg[0]) { "share" -> dir(ctx); "cam" -> camDir(ctx); else -> return null }
        return File(d, n)
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment ?: ""
        return when {
            n.endsWith(".jpg") -> "image/jpeg"
            n.endsWith(".zip") -> "application/zip"
            n.endsWith(".txt") -> "text/plain"
            else -> "application/json"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(uri) ?: throw FileNotFoundException(uri.toString())
        val write = mode.contains('w')
        if (write) {
            // Μόνο η κάμερα γράφει, και μόνο στο cache/cam.
            if (uri.pathSegments[0] != "cam") throw SecurityException("read only")
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode) or ParcelFileDescriptor.MODE_CREATE)
        }
        if (!f.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Όνομα και μέγεθος, για να κρατήσει ο παραλήπτης (Drive) το σωστό όνομα αρχείου. */
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

/** Ποια εφαρμογή διαλέχτηκε στο μενού κοινοποίησης του αντιγράφου· η JS το διαβάζει με consumeShared. */
class ChooserReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val cn: ComponentName? = if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        } else {
            @Suppress("DEPRECATION")
            i.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        }
        Db.get(ctx).setMeta("shared", (cn?.packageName ?: "?") + "|" + LocalDate.now())
    }
}

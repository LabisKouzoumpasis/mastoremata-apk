package gr.mastoremata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.InputStream

/**
 * Φωτογραφίες στον ιδιωτικό χώρο της εφαρμογής (files/photos). Οι εγγραφές κρατούν μόνο το όνομα του αρχείου
 * (π.χ. "p-lx2k9a-4f3c.jpg"). Κάθε εικόνα μικραίνει στα MAX pixel (μεγάλη πλευρά) και γυρίζει σωστά (EXIF).
 */
object Photos {
    const val MAX = 1600
    const val QUALITY = 82
    /** Επιτρεπτά ονόματα: και για ασφάλεια στην επαναφορά από zip (όχι "../"). */
    val NAME = Regex("[A-Za-z0-9_-]{1,64}\\.jpg")

    fun dir(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }

    fun newName(): String =
        "p-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + java.lang.Long.toString((Math.random() * 1679616).toLong(), 36) + ".jpg"

    fun file(ctx: Context, name: String): File? = if (NAME.matches(name)) File(dir(ctx), name) else null

    fun all(dir: File): List<File> = (dir.listFiles() ?: arrayOf()).filter { it.isFile && NAME.matches(it.name) }.sortedBy { it.name }

    /** Πόσα pixel παραλείπονται στην αποκωδικοποίηση (δύναμη του 2), ώστε να μείνει ≥ MAX. */
    fun sampleSize(w: Int, h: Int, max: Int = MAX): Int {
        var s = 1
        while (Math.max(w, h) / (s * 2) >= max) s *= 2
        return s
    }

    /** Διαβάζει εικόνα (open() δίνει κάθε φορά νέο stream), τη μικραίνει, τη γυρίζει και τη γράφει ως JPEG. Επιστρέφει το όνομα ή null. */
    fun importImage(ctx: Context, open: () -> InputStream?): String? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        var bmp: Bitmap = open()?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rot = try {
            open()?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        val scale = Math.min(1f, MAX.toFloat() / Math.max(bmp.width, bmp.height))
        if (scale < 1f || rot != 0f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rot != 0f) m.postRotate(rot)
            val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (t !== bmp) bmp.recycle()
            bmp = t
        }
        val name = newName()
        val tmp = File(dir(ctx), "$name.tmp")
        try {
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (!tmp.renameTo(File(dir(ctx), name))) return null
        } finally {
            bmp.recycle()
            tmp.delete()
        }
        return name
    }

    /** Σβήνει όσες φωτογραφίες δεν αναφέρει καμία εγγραφή. Επιστρέφει πόσες έσβησε. */
    fun prune(dir: File, keep: Set<String>): Int {
        var n = 0
        for (f in all(dir)) if (f.name !in keep && f.delete()) n++
        (dir.listFiles() ?: arrayOf()).filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        return n
    }
}

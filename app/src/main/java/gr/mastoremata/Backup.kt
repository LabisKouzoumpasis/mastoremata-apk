package gr.mastoremata

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Αντίγραφα ασφαλείας. Κάθε αντίγραφο είναι ένα .zip με το data.json (όλες οι εγγραφές, από τη JS) και
 * τον φάκελο photos/ (όλες οι φωτογραφίες). Αυτόματα αντίγραφα σε φάκελο που διάλεξε ο χρήστης:
 * κρατά τις 7 πιο πρόσφατες μέρες και, πιο πίσω, ένα ανά εβδομάδα για 8 εβδομάδες.
 */
object Backup {
    const val PREFIX = "mastoremata-auto-"
    const val DATA = "data.json"
    private const val DAYS = 7
    private const val WEEKS = 8

    /** Ποια από τα αρχεία μας σβήνονται. Όσα δεν έχουν ημερομηνία στο όνομα μένουν. */
    fun toDelete(names: List<String>): List<String> {
        val days = HashSet<String>()
        val weeks = HashSet<Long>()
        val out = ArrayList<String>()
        for (n in names.sortedDescending()) {
            val day = try { LocalDate.parse(n.substring(PREFIX.length, PREFIX.length + 10)) } catch (e: Exception) { continue }
            val ds = day.toString()
            if (days.contains(ds) || days.size < DAYS) { days.add(ds); continue }
            val w = Math.floorDiv(day.toEpochDay(), 7L)
            if (!weeks.contains(w) && weeks.size < WEEKS) { weeks.add(w); continue }
            out.add(n)
        }
        return out
    }

    /** Γράφει το zip (data.json + photos/…). Οι φωτογραφίες είναι ήδη JPEG, οπότε γρήγορη συμπίεση. */
    fun zip(json: String, photos: List<File>, out: OutputStream) {
        val z = ZipOutputStream(out)
        z.setLevel(Deflater.BEST_SPEED)
        z.putNextEntry(ZipEntry(DATA))
        z.write(json.toByteArray(Charsets.UTF_8))
        z.closeEntry()
        for (f in photos) {
            z.putNextEntry(ZipEntry("photos/" + f.name))
            f.inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
        z.finish()
        z.flush()
    }

    /**
     * Διαβάζει zip αντιγράφου: οι φωτογραφίες που λείπουν μπαίνουν στο photoDir (οι υπάρχουσες μένουν ως έχουν),
     * επιστρέφει το data.json ή null αν δεν υπάρχει. Αγνοεί ό,τι άλλο (και ονόματα σαν "../").
     */
    fun unzip(inp: InputStream, photoDir: File): String? {
        var json: String? = null
        photoDir.mkdirs()
        val z = ZipInputStream(inp)
        while (true) {
            val e = z.nextEntry ?: break
            val n = e.name
            if (n == DATA) {
                json = z.readBytes().toString(Charsets.UTF_8)
            } else if (n.startsWith("photos/") && Photos.NAME.matches(n.substring(7))) {
                val f = File(photoDir, n.substring(7))
                if (!f.exists()) {
                    val tmp = File(photoDir, f.name + ".tmp")
                    tmp.outputStream().use { z.copyTo(it) }
                    if (!tmp.renameTo(f)) tmp.delete()
                }
            }
            z.closeEntry()
        }
        return json
    }

    /** true αν τα πρώτα bytes είναι zip ("PK\u0003\u0004"). */
    fun isZip(head: ByteArray, n: Int): Boolean =
        n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    fun folderName(ctx: Context): String {
        val db = Db.get(ctx)
        val u = db.getMeta("backupTree") ?: return ""
        var ok = false
        for (p in ctx.contentResolver.persistedUriPermissions) if (p.uri.toString() == u && p.isWritePermission) ok = true
        return if (ok) (db.getMeta("backupTreeName") ?: "φάκελος") else ""
    }

    fun displayName(ctx: Context, tree: Uri): String {
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "φάκελος"
        } catch (e: Exception) {
            "φάκελος"
        }
    }

    /** Γράφει το αντίγραφο (zip) στον φάκελο αντιγράφων και σβήνει τα παλιά κατά το `toDelete`. */
    @Synchronized
    fun write(ctx: Context, name: String, json: String): Boolean {
        if (folderName(ctx).isEmpty()) return false
        val tree = Uri.parse(Db.get(ctx).getMeta("backupTree"))
        val cr = ctx.contentResolver
        return try {
            val parentId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val ours = ArrayList<String>()
            val ids = HashMap<String, String>()
            cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    if (n.startsWith(PREFIX)) {
                        ours.add(n)
                        ids[n] = c.getString(0)
                    }
                }
            }
            val existing = ids[name]
            val target = if (existing != null) DocumentsContract.buildDocumentUriUsingTree(tree, existing)
            else DocumentsContract.createDocument(cr, parent, "application/zip", name)
            if (target == null) return false
            cr.openOutputStream(target, "wt").use { out -> if (out == null) return false; zip(json, Photos.all(Photos.dir(ctx)), out) }
            if (existing == null) ours.add(name)
            for (old in toDelete(ours)) {
                val id = ids[old] ?: continue
                try {
                    DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id))
                } catch (e: Exception) {
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}

package gr.mastoremata

import android.graphics.Color
import android.net.Uri
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

/** Η γέφυρα ανάμεσα στις οθόνες (HTML/JS) και στο Android. Ονομάζεται AndroidBridge στο JS. */
class Bridge(private val act: MainActivity) {
    private val db = Db.get(act)

    @JavascriptInterface fun loadAll(): String = db.loadAll()

    @JavascriptInterface fun applyOps(ops: String): Boolean = db.applyOps(ops)

    @JavascriptInterface fun saveFile(name: String, mime: String, content: String) = act.saveFile(name, mime, content)

    /** Αντίγραφο (zip: δεδομένα + φωτογραφίες) σε αρχείο που διαλέγει ο χρήστης· απάντηση στο __onFileSaved. */
    @JavascriptInterface fun saveBackup(name: String, json: String) = act.saveBackup(name, json)

    /** Άνοιγμα αντιγράφου (.zip ή παλιό .json)· οι φωτογραφίες του zip μπαίνουν στην εφαρμογή, το JSON στο __onFileOpened. */
    @JavascriptInterface fun openFile() = act.openFile()

    @JavascriptInterface fun share(text: String) = act.share(text)

    @JavascriptInterface fun shareBackup(name: String, json: String) = act.shareBackup(name, json)

    /** «πακέτο|ημερομηνία» της εφαρμογής που διαλέχτηκε για το αντίγραφο (μία φορά), αλλιώς "". */
    @JavascriptInterface fun consumeShared(): String {
        val s = db.getMeta("shared") ?: return ""
        if (s.isNotEmpty()) db.setMeta("shared", "")
        return s
    }

    @JavascriptInterface fun keepScreenOn(on: Boolean) = act.keepScreenOn(on)

    @JavascriptInterface fun setTheme(dark: Boolean) {
        act.runOnUiThread(Runnable { act.setBars(dark) })
    }

    /** Χρώματα του θέματος για τη μπάρα κατάστασης (φόντο) και τη μπάρα πλοήγησης (κάτω μπάρα), σε #RRGGBB. */
    @JavascriptInterface fun setBars(status: String, nav: String, dark: Boolean) {
        act.runOnUiThread(Runnable {
            try {
                act.setBars(dark, Color.parseColor(status), Color.parseColor(nav))
            } catch (e: IllegalArgumentException) {
                act.setBars(dark)
            }
        })
    }

    @JavascriptInterface fun pickBackupFolder() = act.pickBackupFolder()

    @JavascriptInterface fun backupFolderName(): String = Backup.folderName(act)

    @JavascriptInterface fun writeBackup(name: String, json: String): Boolean = Backup.write(act, name, json)

    /** Κάμερα· η απάντηση στο window.__onPhotos([όνομα]) ή __onPhotos([]) αν ακυρώθηκε. */
    @JavascriptInterface fun takePhoto() = act.takePhoto()

    /** Συλλογή (έως 10)· η απάντηση στο window.__onPhotos([ονόματα]). */
    @JavascriptInterface fun pickPhotos() = act.pickPhotos()

    /** Διεύθυνση για <img src>, ή "" αν η φωτογραφία δεν υπάρχει. */
    @JavascriptInterface fun photoUrl(name: String): String {
        val f = Photos.file(act, name) ?: return ""
        return if (f.isFile) Uri.fromFile(f).toString() else ""
    }

    /** Σβήνει τις φωτογραφίες που δεν αναφέρονται στη λίστα (JSON πίνακας ονομάτων). Επιστρέφει πόσες έσβησε. */
    @JavascriptInterface fun prunePhotos(keepJson: String): Int {
        return try {
            val a = JSONArray(keepJson)
            val keep = HashSet<String>()
            for (i in 0 until a.length()) keep.add(a.getString(i))
            Photos.prune(Photos.dir(act), keep)
        } catch (e: Exception) {
            0
        }
    }

    @JavascriptInterface fun photoCount(): Int = Photos.all(Photos.dir(act)).size

    /** Λήψη σελίδας οδηγού (HTML ή JSON)· απάντηση στο window.__onFetched(url, κείμενο ή null). */
    @JavascriptInterface fun fetchUrl(url: String) {
        Thread(Runnable {
            val body = try { Web.text(url) } catch (e: Exception) { null }
            act.js("window.__onFetched&&window.__onFetched(" + JSONObject.quote(url) + "," + (if (body == null) "null" else JSONObject.quote(body)) + ")")
        }).start()
    }

    /**
     * Λήψη φωτογραφιών οδηγού (JSON πίνακας διευθύνσεων, έως 20): μικραίνουν και μπαίνουν στις φωτογραφίες της εφαρμογής.
     * Απάντηση στο window.__onFetchedPhotos([όνομα ή "" για όσες απέτυχαν], στην ίδια σειρά).
     */
    @JavascriptInterface fun fetchPhotos(urlsJson: String) {
        Thread(Runnable {
            val out = JSONArray()
            try {
                val a = JSONArray(urlsJson)
                for (i in 0 until Math.min(a.length(), 20)) {
                    val name = try {
                        val bytes = Web.image(a.getString(i))
                        if (bytes == null) null else Photos.importImage(act) { java.io.ByteArrayInputStream(bytes) }
                    } catch (e: Throwable) { null }
                    out.put(name ?: "")
                }
            } catch (e: Exception) {
            }
            act.js("window.__onFetchedPhotos&&window.__onFetchedPhotos(" + out.toString() + ")")
        }).start()
    }

    /** Ελέγχει για νεότερη έκδοση· η απάντηση έρχεται στο window.__onUpdate({version,url,size} ή null). */
    @JavascriptInterface fun checkUpdate() {
        Thread(Runnable {
            val r = try { Updater.latest() } catch (e: Exception) { null }
            act.js("window.__onUpdate&&window.__onUpdate(" + (r?.toString() ?: "null") + ")")
        }).start()
    }

    /** Κατεβάζει και εγκαθιστά· η απάντηση στο window.__onUpdateInstall('started'|'perm'|μήνυμα λάθους). */
    @JavascriptInterface fun installUpdate(url: String, version: String) {
        if (!act.packageManager.canRequestPackageInstalls()) {
            act.runOnUiThread(Runnable { act.openInstallSettings() })
            act.js("window.__onUpdateInstall&&window.__onUpdateInstall('perm')")
            return
        }
        Thread(Runnable {
            val err = try { Updater.install(act, url) } catch (e: Exception) { e.javaClass.simpleName }
            act.js("window.__onUpdateInstall&&window.__onUpdateInstall(" + JSONObject.quote(err ?: "started") + ")")
        }).start()
    }

    @JavascriptInterface fun appVersion(): String {
        return try {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
}

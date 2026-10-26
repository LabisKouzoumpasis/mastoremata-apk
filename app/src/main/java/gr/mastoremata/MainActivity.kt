package gr.mastoremata

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File

class MainActivity : Activity() {

    companion object {
        /** Για το UpdateReceiver (μηνύματα προς τη σελίδα όσο είναι ανοιχτή). */
        @JvmStatic var instance: MainActivity? = null
        private const val REQ_SAVE = 11
        private const val REQ_OPEN = 12
        private const val REQ_TREE = 15
        private const val REQ_CAM = 16
        private const val REQ_PICK = 17
        private const val MAX_PICK = 10
    }

    lateinit var web: WebView
    private var pendingText: String? = null
    private var pendingZip: String? = null
    private var pendingName: String = ""
    private var pageReady = false
    /** Φωτογραφίες που ήρθαν πριν φορτώσει η σελίδα (π.χ. το Android έκλεισε την εφαρμογή όσο ήταν ανοιχτή η κάμερα). */
    private var pendingPhotos: List<String>? = null
    /** Κείμενο που κοινοποιήθηκε από άλλη εφαρμογή, μέχρι να φορτώσει η σελίδα: [κείμενο, θέμα]. */
    private var pendingShare: Array<String>? = null
    /** Το αρχείο όπου γράφει η κάμερα (κρατιέται και αν ξαναδημιουργηθεί η οθόνη). */
    private var camName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        camName = savedInstanceState?.getString("camName")
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // Για τις φωτογραφίες: <img src="file:///…/files/photos/…">.
        s.allowFileAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: ActivityNotFoundException) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                deliverPhotos()
                deliverShare()
            }
        }
        web.addJavascriptInterface(Bridge(this), "AndroidBridge")
        web.loadUrl("file:///android_asset/index.html")
        setBars(isSystemDark())
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Κείμενο που μοιράστηκε από άλλη εφαρμογή (π.χ. οδηγίες επιλεγμένες στον browser). */
    private fun handleShare(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        pendingShare = arrayOf(text, i.getStringExtra(Intent.EXTRA_SUBJECT) ?: "")
        i.action = Intent.ACTION_MAIN
        deliverShare()
    }

    private fun deliverShare() {
        if (!pageReady) return
        val p = pendingShare ?: return
        pendingShare = null
        web.evaluateJavascript("window.__onShared&&window.__onShared(" + JSONObject.quote(p[0]) + "," + JSONObject.quote(p[1]) + ")", null)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Η ρύθμιση «Εγκατάσταση άγνωστων εφαρμογών» για τα Μαστορέματα (μία φορά, για τις ενημερώσεις). */
    fun openInstallSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
        } catch (e: ActivityNotFoundException) {
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        camName?.let { outState.putString("camName", it) }
    }

    override fun onResume() {
        super.onResume()
        if (this::web.isInitialized) web.evaluateJavascript("window.__resume&&window.__resume()", null)
    }

    @Deprecated("Χρησιμοποιείται για συμβατότητα με όλες τις εκδόσεις")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.__back&&window.__back())?'y':'n'", ValueCallback<String> { r ->
            if (r == null || !r.contains("y")) moveTaskToBack(true)
        })
    }

    fun isSystemDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Χρώματα μπαρών συστήματος ανάλογα με το θέμα της εφαρμογής. */
    fun setBars(dark: Boolean) {
        setBars(dark, if (dark) Color.parseColor("#161514") else Color.parseColor("#F3F1EC"), if (dark) Color.parseColor("#201E1C") else Color.WHITE)
    }

    fun setBars(dark: Boolean, status: Int, nav: Int) {
        val w = window
        w.statusBarColor = status
        w.navigationBarColor = nav
        var flags = w.decorView.systemUiVisibility
        flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 27) {
            flags = if (dark) flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        w.decorView.systemUiVisibility = flags
    }

    fun js(code: String) {
        runOnUiThread(Runnable { web.evaluateJavascript(code, null) })
    }

    fun toast(msg: String) {
        js("window.__toast&&window.__toast(" + JSONObject.quote(msg) + ")")
    }

    // ---------- Φωτογραφίες ----------

    fun takePhoto() {
        runOnUiThread(Runnable {
            val d = ShareProvider.camDir(this)
            d.listFiles()?.forEach { it.delete() }
            val name = "shot-" + System.currentTimeMillis() + ".jpg"
            camName = name
            val u = ShareProvider.camUri(name)
            val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(MediaStore.EXTRA_OUTPUT, u)
            i.clipData = ClipData.newRawUri(name, u)
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivityForResult(i, REQ_CAM)
            } catch (e: Exception) {
                toast("Δεν βρέθηκε εφαρμογή κάμερας.")
                photosReady(listOf())
            }
        })
    }

    fun pickPhotos() {
        runOnUiThread(Runnable {
            val i: Intent
            if (Build.VERSION.SDK_INT >= 33) {
                i = Intent(MediaStore.ACTION_PICK_IMAGES)
                i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX_PICK)
            } else {
                i = Intent(Intent.ACTION_GET_CONTENT)
                i.type = "image/*"
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try {
                startActivityForResult(i, REQ_PICK)
            } catch (e: ActivityNotFoundException) {
                toast("Δεν βρέθηκε συλλογή φωτογραφιών.")
                photosReady(listOf())
            }
        })
    }

    private fun photosReady(names: List<String>) {
        runOnUiThread(Runnable {
            pendingPhotos = names
            deliverPhotos()
        })
    }

    private fun deliverPhotos() {
        if (!pageReady) return
        val p = pendingPhotos ?: return
        pendingPhotos = null
        web.evaluateJavascript("window.__onPhotos&&window.__onPhotos(" + JSONArray(p).toString() + ")", null)
    }

    private fun importSafely(open: () -> java.io.InputStream?): String? =
        try { Photos.importImage(this, open) } catch (e: Throwable) { null }

    // ---------- Αρχεία ----------

    fun saveFile(name: String, mime: String, content: String) {
        runOnUiThread(Runnable {
            pendingText = content
            pendingZip = null
            createDocument(name, mime)
        })
    }

    fun saveBackup(name: String, json: String) {
        runOnUiThread(Runnable {
            pendingZip = json
            pendingText = null
            createDocument(name, "application/zip")
        })
    }

    private fun createDocument(name: String, mime: String) {
        pendingName = name
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.putExtra(Intent.EXTRA_TITLE, name)
        try {
            startActivityForResult(i, REQ_SAVE)
        } catch (e: ActivityNotFoundException) {
            toast("Δεν βρέθηκε εφαρμογή αρχείων στο κινητό.")
        }
    }

    fun openFile() {
        runOnUiThread(Runnable {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/json", "text/plain", "application/octet-stream"))
            try {
                startActivityForResult(i, REQ_OPEN)
            } catch (e: ActivityNotFoundException) {
                toast("Δεν βρέθηκε εφαρμογή αρχείων στο κινητό.")
            }
        })
    }

    fun pickBackupFolder() {
        runOnUiThread(Runnable {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try {
                startActivityForResult(i, REQ_TREE)
            } catch (e: ActivityNotFoundException) {
                toast("Δεν βρέθηκε εφαρμογή αρχείων στο κινητό.")
            }
        })
    }

    @Deprecated("Χρησιμοποιείται για συμβατότητα")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        when (requestCode) {
            REQ_CAM -> {
                val n = camName
                camName = null
                val f = if (n != null) File(ShareProvider.camDir(this), n) else null
                if (resultCode != RESULT_OK || f == null || f.length() == 0L) {
                    f?.delete()
                    if (resultCode == RESULT_OK) toast("Η κάμερα δεν έδωσε φωτογραφία.")
                    photosReady(listOf())
                    return
                }
                Thread(Runnable {
                    val name = importSafely { f.inputStream() }
                    f.delete()
                    if (name == null) toast("Δεν αποθηκεύτηκε η φωτογραφία.")
                    photosReady(listOfNotNull(name))
                }).start()
            }
            REQ_PICK -> {
                val uris = ArrayList<Uri>()
                val clip = data?.clipData
                if (clip != null) for (k in 0 until Math.min(clip.itemCount, MAX_PICK)) clip.getItemAt(k).uri?.let { uris.add(it) }
                else if (uri != null) uris.add(uri)
                if (resultCode != RESULT_OK || uris.isEmpty()) {
                    photosReady(listOf())
                    return
                }
                Thread(Runnable {
                    val names = uris.mapNotNull { u -> importSafely { contentResolver.openInputStream(u) } }
                    if (names.size < uris.size) toast("Κάποιες φωτογραφίες δεν διαβάστηκαν.")
                    photosReady(names)
                }).start()
            }
            REQ_SAVE -> {
                val text = pendingText
                val zipJson = pendingZip
                pendingText = null
                pendingZip = null
                if (resultCode != RESULT_OK || uri == null || (text == null && zipJson == null)) return
                val name = pendingName
                Thread(Runnable {
                    try {
                        contentResolver.openOutputStream(uri, "wt").use { out ->
                            if (out == null) throw IllegalStateException()
                            if (zipJson != null) Backup.zip(zipJson, Photos.all(Photos.dir(this)), out)
                            else out.write(text!!.toByteArray(Charsets.UTF_8))
                        }
                        js("window.__onFileSaved&&window.__onFileSaved(true," + JSONObject.quote(name) + "," + JSONObject.quote(uri.authority ?: "") + ")")
                    } catch (e: Exception) {
                        js("window.__onFileSaved&&window.__onFileSaved(false,'')")
                    }
                }).start()
            }
            REQ_TREE -> {
                if (resultCode != RESULT_OK || uri == null) return
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val name = Backup.displayName(this, uri)
                    val db = Db.get(this)
                    db.setMeta("backupTree", uri.toString())
                    db.setMeta("backupTreeName", name)
                    js("window.__onBackupFolder&&window.__onBackupFolder(" + JSONObject.quote(name) + ")")
                } catch (e: Exception) {
                    toast("Δεν δόθηκε πρόσβαση στον φάκελο.")
                }
            }
            REQ_OPEN -> {
                if (resultCode != RESULT_OK || uri == null) return
                Thread(Runnable {
                    try {
                        val text = contentResolver.openInputStream(uri).use { raw ->
                            if (raw == null) throw IllegalStateException()
                            val inp = BufferedInputStream(raw)
                            inp.mark(8)
                            val head = ByteArray(4)
                            val n = inp.read(head)
                            inp.reset()
                            if (Backup.isZip(head, n)) Backup.unzip(inp, Photos.dir(this))
                            else inp.readBytes().toString(Charsets.UTF_8)
                        }
                        if (text == null) toast("Το αρχείο δεν είναι αντίγραφο των Μαστορεμάτων.")
                        else js("window.__onFileOpened&&window.__onFileOpened(" + JSONObject.quote(text) + ")")
                    } catch (e: Exception) {
                        toast("Δεν διαβάστηκε το αρχείο.")
                    }
                }).start()
            }
        }
    }

    /** Αντίγραφο (zip) μέσα από το μενού κοινοποίησης (εκεί φαίνεται πάντα το Drive)· η επιλογή πάει στο ChooserReceiver. */
    fun shareBackup(name: String, json: String) {
        Thread(Runnable {
            try {
                val d = ShareProvider.dir(this)
                d.listFiles()?.forEach { it.delete() }
                File(d, name).outputStream().use { Backup.zip(json, Photos.all(Photos.dir(this)), it) }
                runOnUiThread(Runnable {
                    try {
                        val u = ShareProvider.uri(name)
                        val send = Intent(Intent.ACTION_SEND)
                        send.type = "application/zip"
                        send.putExtra(Intent.EXTRA_STREAM, u)
                        send.putExtra(Intent.EXTRA_SUBJECT, name)
                        send.clipData = ClipData.newRawUri(name, u)
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                        val pi = android.app.PendingIntent.getBroadcast(this, 21, Intent(this, ChooserReceiver::class.java), flags)
                        startActivity(Intent.createChooser(send, "Αντίγραφο εκτός κινητού", pi.intentSender))
                    } catch (e: Exception) {
                        toast("Δεν έγινε η αποστολή του αντιγράφου.")
                    }
                })
            } catch (e: Exception) {
                toast("Δεν έγινε η αποστολή του αντιγράφου.")
            }
        }).start()
    }

    fun share(text: String) {
        runOnUiThread(Runnable {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "text/plain"
            i.putExtra(Intent.EXTRA_TEXT, text)
            try {
                startActivity(Intent.createChooser(i, "Κοινοποίηση"))
            } catch (e: ActivityNotFoundException) {
            }
        })
    }

    fun keepScreenOn(on: Boolean) {
        runOnUiThread(Runnable {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        })
    }
}

package gr.mastoremata

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ενημέρωση μέσα από την εφαρμογή (όπως στην Καθημερινότητα). Ελέγχει τα Releases στο GitHub χωρίς token,
 * άρα βλέπει μόνο ΔΗΜΟΣΙΑ αποθετήρια: είτε το mastoremata-apk (αν γίνει δημόσιο) είτε ένα δημόσιο mastoremata-releases
 * (όπου δημοσιεύει το build.yml με το μυστικό APK_REPO_TOKEN). Κρατά τη νεότερη έκδοση που βρέθηκε.
 * Το Android δέχεται την ενημέρωση μόνο αν είναι υπογεγραμμένη με το ίδιο κλειδί με την εγκατεστημένη εφαρμογή.
 */
object Updater {
    val REPOS = listOf("LabisKouzoumpasis/mastoremata-apk", "LabisKouzoumpasis/mastoremata-releases")
    const val ACTION_STATUS = "gr.mastoremata.UPDATE_STATUS"

    /** true αν η έκδοση a είναι νεότερη από τη b ("1.0.10" > "1.0.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until Math.max(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** {version, url, size} από την απάντηση releases/latest του GitHub, αν έχει APK. */
    fun parse(json: String): JSONObject? {
        val j = JSONObject(json)
        val assets = j.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return JSONObject()
                    .put("version", j.optString("tag_name").removePrefix("v"))
                    .put("url", a.getString("browser_download_url"))
                    .put("size", a.optLong("size"))
            }
        }
        return null
    }

    /** Μόνο APK από τα Releases των δικών μας αποθετηρίων. */
    fun allowedUrl(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && u.host == "github.com" && REPOS.any { u.path.startsWith("/$it/releases/download/") } && u.path.endsWith(".apk")
    } catch (e: Exception) {
        false
    }

    private fun latestOf(repo: String): JSONObject? {
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            return parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Η νεότερη έκδοση από όσα αποθετήρια απαντούν, ή null (χωρίς σύνδεση ή αν δεν είναι δημόσια). */
    fun latest(): JSONObject? {
        var best: JSONObject? = null
        for (r in REPOS) {
            val j = try { latestOf(r) } catch (e: Exception) { null } ?: continue
            if (best == null || newer(j.optString("version"), best.optString("version"))) best = j
        }
        return best
    }

    /** Κατεβάζει το APK και το δίνει στο Android για εγκατάσταση. null αν ξεκίνησε, αλλιώς τι πήγε στραβά. */
    fun install(ctx: Context, url: String): String? {
        if (!allowedUrl(url)) return "μη αποδεκτός σύνδεσμος"
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        // Σε Android 12+ μια εφαρμογή που ενημερώνει τον εαυτό της μπορεί να μη χρειάζεται επιβεβαίωση.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val session = pi.openSession(pi.createSession(params))
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (conn.responseCode != 200) {
                session.abandon()
                return "λήψη: " + conn.responseCode
            }
            val len = conn.contentLengthLong
            conn.inputStream.use { inp ->
                session.openWrite("app.apk", 0, if (len > 0) len else -1).use { out ->
                    inp.copyTo(out)
                    session.fsync(out)
                }
            }
            conn.disconnect()
            val i = Intent(ctx, UpdateReceiver::class.java)
            i.action = ACTION_STATUS
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(ctx, 4, i, flags).intentSender)
            session.close()
            return null
        } catch (e: Exception) {
            session.abandon()
            return e.javaClass.simpleName
        }
    }
}

/** Αποτέλεσμα της εγκατάστασης: ζητά επιβεβαίωση αν χρειάζεται, αλλιώς λέει τι πήγε στραβά. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirm)
                    } catch (e: Exception) {
                    }
                }
            }
            // Με επιτυχία η εφαρμογή κλείνει και αντικαθίσταται· στο επόμενο άνοιγμα η JS λέει «Ενημερώθηκε».
            PackageInstaller.STATUS_SUCCESS -> {}
            // Συχνή αιτία στα Samsung: ο «Αυτόματος αποκλεισμός» (η JS εξηγεί τι να κάνεις).
            PackageInstaller.STATUS_FAILURE_ABORTED -> MainActivity.instance?.js("window.__onUpdateInstall&&window.__onUpdateInstall('aborted')")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                MainActivity.instance?.toast("Η ενημέρωση δεν ολοκληρώθηκε. $msg".trim())
            }
        }
    }
}

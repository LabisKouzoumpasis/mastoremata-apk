package gr.mastoremata

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Λήψη σελίδας οδηγού ή φωτογραφίας, μόνο όταν ο χρήστης μοιράζεται/δίνει σύνδεσμο.
 * Μόνο http/https, έως 6 ανακατευθύνσεις, όριο μεγέθους.
 */
object Web {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun allowed(u: String): Boolean = u.startsWith("https://") || u.startsWith("http://")

    /** Ανοίγει τη σύνδεση ακολουθώντας ανακατευθύνσεις (και από http σε https). null αν δεν απαντήσει 2xx. */
    private fun open(url: String, accept: String): HttpURLConnection? {
        var u = URL(url)
        var hops = 0
        while (true) {
            val conn = u.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", accept)
            conn.setRequestProperty("Accept-Language", "el-GR,el;q=0.9,en;q=0.6")
            val code = conn.responseCode
            if (code in 300..399 && hops < 6) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc == null) return null
                u = URL(u, loc)
                if (u.protocol != "http" && u.protocol != "https") return null
                hops++
                continue
            }
            if (code in 200..299) return conn
            conn.disconnect()
            return null
        }
    }

    /** Διαβάζει έως max bytes· null αν είναι μεγαλύτερο. */
    fun readLimited(inp: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    /** Κωδικοποίηση από το Content-Type (π.χ. "text/html; charset=windows-1253"), αλλιώς UTF-8. */
    fun charset(contentType: String?): String {
        val cs = (contentType ?: "").substringAfter("charset=", "").substringBefore(';').trim().trim('"')
        return if (cs.isEmpty()) "UTF-8" else cs
    }

    /** Κείμενο σελίδας (HTML ή JSON) ή null. */
    fun text(url: String): String? {
        if (!allowed(url)) return null
        val conn = open(url, "text/html,application/json;q=0.9,*/*;q=0.8") ?: return null
        try {
            val bytes = conn.inputStream.use { readLimited(it, 4_000_000) } ?: return null
            return try { String(bytes, java.nio.charset.Charset.forName(charset(conn.contentType))) } catch (e: Exception) { String(bytes, Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    /** Bytes εικόνας (μόνο image/…, έως 15 MB) ή null. */
    fun image(url: String): ByteArray? {
        if (!allowed(url)) return null
        val conn = open(url, "image/*") ?: return null
        try {
            val ct = conn.contentType ?: ""
            if (ct.isNotEmpty() && !ct.startsWith("image/")) return null
            return conn.inputStream.use { readLimited(it, 15_000_000) }
        } finally {
            conn.disconnect()
        }
    }
}

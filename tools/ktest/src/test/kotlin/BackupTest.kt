import gr.mastoremata.Backup
import gr.mastoremata.Photos
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private fun name(d: LocalDate, extra: String = "") = Backup.PREFIX + d + extra + ".zip"

    @Test fun keepsSevenDaysThenWeekly() {
        val today = LocalDate.parse("2026-10-23")
        val all = (0L until 100L).map { name(today.minusDays(it)) }
        val del = Backup.toDelete(all).toSet()
        val kept = all.filter { it !in del }
        assertEquals(15, kept.size)
        for (i in 0L until 7L) assertTrue(name(today.minusDays(i)) in kept)
        assertFalse(name(today.minusDays(99)) in kept)
    }

    @Test fun safetyCopyOfSameDayStays() {
        val d = LocalDate.parse("2026-10-23")
        val all = listOf(name(d), name(d, "-prin"), name(d.minusDays(1)))
        assertTrue(Backup.toDelete(all).isEmpty())
        assertTrue(Backup.toDelete(listOf(Backup.PREFIX + "κάτι.zip")).isEmpty())
    }

    @Test fun zipRoundTripWithPhotos() {
        val src = Files.createTempDirectory("src").toFile()
        val a = File(src, "p-a1-b2.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val b = File(src, "p-c3-d4.jpg").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        val out = ByteArrayOutputStream()
        val json = "{\"repair\":{\"r1\":{\"t\":\"Βρύση\",\"photos\":[\"p-a1-b2.jpg\"]}}}"
        Backup.zip(json, Photos.all(src), out)
        val bytes = out.toByteArray()
        assertTrue(Backup.isZip(bytes, bytes.size))
        assertFalse(Backup.isZip("{\"a\":1}".toByteArray(), 7))

        val dst = Files.createTempDirectory("dst").toFile()
        // Υπάρχουσα φωτογραφία με το ίδιο όνομα δεν αλλάζει.
        File(dst, "p-c3-d4.jpg").writeBytes(byteArrayOf(9))
        assertEquals(json, Backup.unzip(ByteArrayInputStream(bytes), dst))
        assertArrayEquals(a.readBytes(), File(dst, "p-a1-b2.jpg").readBytes())
        assertArrayEquals(byteArrayOf(9), File(dst, "p-c3-d4.jpg").readBytes())
        assertEquals(2, dst.listFiles()!!.size)
    }

    @Test fun unzipIgnoresBadNames() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (n in listOf("photos/../../evil.jpg", "photos/x.png", "other.txt", "photos/ok_1.jpg")) {
                z.putNextEntry(ZipEntry(n)); z.write(byteArrayOf(7)); z.closeEntry()
            }
        }
        val dst = Files.createTempDirectory("bad").toFile()
        assertNull(Backup.unzip(ByteArrayInputStream(out.toByteArray()), dst))
        assertEquals(listOf("ok_1.jpg"), dst.listFiles()!!.map { it.name })
        assertFalse(File(dst.parentFile.parentFile, "evil.jpg").exists())
    }

    @Test fun pruneAndSample() {
        val d = Files.createTempDirectory("pr").toFile()
        for (n in listOf("p-1.jpg", "p-2.jpg", "p-3.jpg.tmp", "notes.txt")) File(d, n).writeText("x")
        assertEquals(1, Photos.prune(d, setOf("p-1.jpg")))
        assertEquals(setOf("p-1.jpg", "notes.txt"), d.listFiles()!!.map { it.name }.toSet())
        assertEquals(1, Photos.sampleSize(1600, 1200))
        assertEquals(2, Photos.sampleSize(4000, 3000))
        assertEquals(4, Photos.sampleSize(8000, 6000))
        assertTrue(Photos.NAME.matches(Photos.newName()))
    }
}

package app.buxes.feature.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Photos of the artist of the playing song, for the Lyrics screen (Buxes): found on Wikipedia the
 * way a person would — the artist's name with the song's title, then the name with "singer" — and
 * checked before they're believed: the page's opening words must say it is a musician, or must
 * name the song. No API key; the public Wikipedia API only, with a proper user agent. Up to four
 * photos a page, each saved small on disk once; a miss is remembered too, so a song isn't searched
 * again and again.
 */
object ArtistPictures {
    private const val TAG = "BuxesArtist"
    private const val MAX = 4
    private const val THUMB_PX = 640
    private const val UA = "Buxes/1.3 (Android music player; https://github.com/veldafelix7-oss/buxes)"

    private val lock = Mutex()
    private val memory = HashMap<String, List<File>>()

    /** Starts fetching in the background so the Lyrics screen finds the photos ready. */
    suspend fun prefetch(context: Context, artist: String?, title: String?) {
        if (artist.isNullOrBlank()) return
        runCatching { pictures(context, artist, title) }
    }

    /** The photo files for [artist] (empty if none could be found or the phone is offline). */
    suspend fun pictures(context: Context, artist: String, title: String?): List<File> = withContext(Dispatchers.IO) {
        val name = cleanArtist(artist)
        if (name.isBlank()) return@withContext emptyList()
        val key = sha1(name.lowercase())
        memory[key]?.let { return@withContext it }
        lock.withLock {
            memory[key]?.let { return@withLock it }
            val dir = File(File(context.cacheDir, "artist-pictures"), key)
            val cached = dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
            val result = when {
                cached.isNotEmpty() -> cached
                File(dir, "none").exists() && File(dir, "none").lastModified() > System.currentTimeMillis() - MISS_TTL_MS -> emptyList()
                else -> {
                    val found = runCatching { find(name, title) }.onFailure { Log.i(TAG, "lookup failed: ${it.javaClass.simpleName}") }.getOrDefault(emptyList())
                    dir.mkdirs()
                    val saved = found.take(MAX).mapIndexedNotNull { i, url -> download(url, File(dir, "$i.jpg")) }
                    if (saved.isEmpty()) File(dir, "none").writeText("") else File(dir, "none").delete()
                    saved
                }
            }
            memory[key] = result
            result
        }
    }

    /** Decodes a saved photo at about [sizePx] a side. */
    fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- the search ----

    private class Site(val host: String, val musician: List<String>, val singer: String)

    private val sites = listOf(
        Site(
            "en.wikipedia.org",
            listOf("singer", "rapper", "musician", "band", "songwriter", "music group", "musical group", "duo", "vocalist", "record producer", "composer", "idol group", "boy band", "girl group", "dj", "music producer", "recording artist"),
            "singer",
        ),
        Site(
            "id.wikipedia.org",
            listOf("penyanyi", "musisi", "grup musik", "band", "rapper", "pencipta lagu", "penulis lagu", "grup vokal", "komposer", "produser rekaman", "disjoki"),
            "penyanyi",
        ),
    )

    private fun find(artist: String, title: String?): List<String> {
        val song = title?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
        for (site in sites) {
            val queries = buildList {
                if (song != null) add("$artist $song")
                add("$artist ${site.singer}")
                add(artist)
            }
            for (q in queries) {
                val page = search(site, q, artist, song) ?: continue
                val urls = imagesOf(site, page)
                if (urls.isNotEmpty()) {
                    Log.i(TAG, "found ${urls.size} pictures on ${site.host} via \"$q\"")
                    return urls
                }
            }
        }
        return emptyList()
    }

    private class Page(val title: String, val lead: String?)

    /** Searches [site] for [q]; the first result whose opening words make it the artist. */
    private fun search(site: Site, q: String, artist: String, song: String?): Page? {
        val url = "https://${site.host}/w/api.php?action=query&format=json&generator=search&gsrlimit=6&gsrsearch=${enc(q)}" +
            "&prop=extracts|pageimages&exintro=1&explaintext=1&exsentences=3&exlimit=6&piprop=original"
        val json = get(url) ?: return null
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val candidates = pages.keys().asSequence().map { pages.getJSONObject(it) }
            .sortedBy { it.optInt("index", 99) }.toList()
        val artistWords = artist.lowercase()
        for (p in candidates) {
            val pageTitle = p.optString("title")
            val extract = p.optString("extract").lowercase()
            if (extract.isBlank()) continue
            val opening = extract.take(400)
            // The page must be about this name: the title carries it, or the opening does.
            val named = pageTitle.lowercase().contains(artistWords) || opening.contains(artistWords)
            if (!named) continue
            // Not a song's or an album's own page — the person's or the band's.
            if (Regex("""\b(is|was) (a|an|the) (\d{4} )?(song|single|album|ep|mixtape|soundtrack|film|movie|video game)\b""").containsMatchIn(opening)) continue
            if (Regex("""\badalah (sebuah )?(lagu|singel|album|film)\b""").containsMatchIn(opening)) continue
            val musician = site.musician.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(opening) }
            val namesSong = song != null && song.length >= 4 && extract.contains(song.lowercase())
            if (musician || namesSong) {
                return Page(pageTitle, p.optJSONObject("original")?.optString("source"))
            }
        }
        return null
    }

    /** The photographs on [page]: the lead picture first, then the page's other real photos. */
    private fun imagesOf(site: Site, page: Page): List<String> {
        val out = ArrayList<String>()
        page.lead?.takeIf { isPhotoName(it) }?.let { out += thumbOf(it) }
        val url = "https://${site.host}/w/api.php?action=query&format=json&generator=images&gimlimit=40&titles=${enc(page.title)}" +
            "&prop=imageinfo&iiprop=url|size|mime&iiurlwidth=$THUMB_PX"
        val json = get(url)
        val pages = json?.optJSONObject("query")?.optJSONObject("pages")
        if (pages != null) {
            val files = pages.keys().asSequence().map { pages.getJSONObject(it) }.toList()
            for (f in files) {
                val name = f.optString("title")
                val info = f.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val mime = info.optString("mime")
                val w = info.optInt("width")
                val h = info.optInt("height")
                if (mime != "image/jpeg") continue // logos, flags, icons and maps are png/svg
                if (w < 300 || h < 300) continue
                if (!isPhotoName(name)) continue
                // Covers and posters are square-ish or portrait; leave in what looks like a photo.
                val thumb = info.optString("thumburl").ifBlank { info.optString("url") }
                if (thumb.isBlank()) continue
                if (out.none { sameFile(it, thumb) }) out += thumb
                if (out.size >= MAX) break
            }
        }
        return out.take(MAX)
    }

    private val notPhoto = listOf("logo", "icon", "flag", "map", "signature", "cover", "poster", "wordmark", "symbol", "seal", "emblem", "chart", "diagram", "commons-", "wiki", "question", "ambox", "star", "award", "certif", "disambig", "edit-", "nuvola", "crystal", "gnome", "ok_", "red_x", "crystal_clear", "svg")

    private fun isPhotoName(nameOrUrl: String): Boolean {
        val n = nameOrUrl.lowercase()
        if (!n.endsWith(".jpg") && !n.endsWith(".jpeg") && !n.contains(".jpg/") && !n.contains(".jpeg/")) return false
        return notPhoto.none { n.contains(it) }
    }

    private fun sameFile(a: String, b: String) = a.substringAfterLast('/').substringBefore('?') == b.substringAfterLast('/').substringBefore('?')

    /** Builds a sized thumbnail URL for an upload.wikimedia.org original. */
    private fun thumbOf(original: String): String {
        // https://upload.wikimedia.org/wikipedia/commons/a/ab/Name.jpg → .../commons/thumb/a/ab/Name.jpg/640px-Name.jpg
        val m = Regex("""^(https://upload\.wikimedia\.org/wikipedia/[^/]+)/([0-9a-f]/[0-9a-f]{2})/([^/]+)$""").find(original) ?: return original
        val (root, hash, name) = m.destructured
        return "$root/thumb/$hash/$name/${THUMB_PX}px-$name"
    }

    // ---- names ----

    private fun cleanArtist(artist: String): String {
        var s = artist
        s = s.replace(Regex("""(?i)\s*-\s*topic$"""), "")
        s = s.replace(Regex("""(?i)\s*(vevo|official)$"""), "")
        s = s.split(Regex("""\s*[,&/]\s*"""), limit = 2).first()
        s = s.split(Regex("""(?i)\s+(feat\.?|ft\.?|featuring|with|x|×)\s+"""), limit = 2).first()
        return s.trim()
    }

    private fun cleanTitle(title: String): String {
        var s = title
        s = s.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "") // (Official Video), [Lyrics]
        s = s.split(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+""")).first()
        s = s.split(" - ").last()
        return s.trim()
    }

    // ---- http ----

    private fun get(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, to: File): File? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // Kept small: enough for a softened full-screen picture, light on the phone's storage.
            val scale = THUMB_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            to.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            to
        } catch (e: Exception) {
            to.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
}

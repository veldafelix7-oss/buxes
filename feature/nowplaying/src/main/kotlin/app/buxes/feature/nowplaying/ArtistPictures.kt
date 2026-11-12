package app.buxes.feature.nowplaying

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Pictures of the artist of the playing song, for the Lyrics screen (Buxes). Found without any
 * key, from several places at once — Deezer (the artist's photo and album covers, checked against
 * the song), Wikimedia Commons, Wikipedia (English, then Indonesian) and the iTunes catalogue —
 * each saved small on disk the first time, and handed over as they arrive, so the first picture is
 * there in a moment and the rest join the turn. Up to ten a song.
 *
 * The cache keeps itself: pictures of artists no longer in the queue are dropped when the music
 * moves on, nothing older than a week stays, and the whole store never passes a small budget.
 * Nothing of this is shown.
 */
object ArtistPictures {
    private const val TAG = "BuxesArtist"
    const val MAX = 10
    private const val THUMB_PX = 640
    private const val UA = "Buxes/1.4 (Android music player; https://github.com/veldafelix7-oss/buxes)"
    private const val SOURCE_TIMEOUT_MS = 4500L
    private const val MISS_TTL_MS = 3L * 24 * 60 * 60 * 1000
    private const val KEEP_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val BUDGET_BYTES = 30L * 1024 * 1024
    private const val BUDGET_ARTISTS = 40

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flows = HashMap<String, MutableStateFlow<List<File>>>()
    private val started = HashSet<String>()

    /** The pictures for [artist], filling in as they're found; empty while none (or when none). */
    fun pictures(context: Context, artist: String?, title: String?): StateFlow<List<File>> {
        val name = artist?.let(::cleanArtist).orEmpty()
        val key = keyOf(name)
        val flow = synchronized(flows) { flows.getOrPut(key) { MutableStateFlow(emptyList()) } }
        if (name.isBlank()) return flow
        val fresh = synchronized(started) { started.add(key) }
        if (fresh) scope.launch { fill(context.applicationContext, key, name, title, flow) }
        return flow
    }

    /** Looks the artist up now so the Lyrics screen finds the pictures ready. */
    fun prefetch(context: Context, artist: String?, title: String?) {
        pictures(context, artist, title)
    }

    /** Decodes a saved picture at about [sizePx] a side. */
    fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /**
     * Tidies the store: keeps the artists in [keep] (playing and queued), drops the rest that are
     * older than a week or beyond the budget, oldest first. Runs off the main thread, a little after
     * the music moved on so the player isn't competing with it.
     */
    fun sweep(context: Context, keep: Collection<String?>, delayMs: Long = 5000L) {
        val keepKeys = keep.mapNotNull { it?.let(::cleanArtist)?.takeIf(String::isNotBlank)?.let(::keyOf) }.toSet()
        val app = context.applicationContext
        scope.launch {
            delay(delayMs)
            runCatching { sweepNow(root(app), keepKeys) }
        }
    }

    private fun sweepNow(root: File, keepKeys: Set<String>) {
        val dirs = root.listFiles { f -> f.isDirectory }?.toList().orEmpty()
        val now = System.currentTimeMillis()
        val kept = ArrayList<File>()
        for (d in dirs) {
            val stamp = File(d, "used").takeIf { it.exists() }?.lastModified() ?: d.lastModified()
            val miss = File(d, "none").exists()
            val stale = now - stamp > (if (miss) MISS_TTL_MS else KEEP_TTL_MS)
            val keep = d.name in keepKeys
            if (!keep && (stale || miss)) {
                drop(d)
            } else {
                kept += d
            }
        }
        // Over budget: the least recently used go first, never the ones in the queue.
        var bytes = kept.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
        val byAge = kept.filter { it.name !in keepKeys }.sortedBy { File(it, "used").takeIf { f -> f.exists() }?.lastModified() ?: it.lastModified() }
        var count = kept.size
        for (d in byAge) {
            if (bytes <= BUDGET_BYTES && count <= BUDGET_ARTISTS) break
            bytes -= d.listFiles()?.sumOf { it.length() } ?: 0L
            count--
            drop(d)
        }
    }

    private fun drop(dir: File) {
        synchronized(flows) {
            flows.remove(dir.name)
            synchronized(started) { started.remove(dir.name) }
        }
        dir.deleteRecursively()
    }

    // ---- filling ----

    private suspend fun fill(app: Context, key: String, name: String, title: String?, flow: MutableStateFlow<List<File>>) {
        val dir = File(root(app), key)
        dir.mkdirs()
        File(dir, "used").apply { if (!exists()) writeText("") else setLastModified(System.currentTimeMillis()) }
        val cached = dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
        if (cached.isNotEmpty()) {
            flow.value = cached
            if (File(dir, "done").exists()) return
        } else if (File(dir, "none").exists() && File(dir, "none").lastModified() > System.currentTimeMillis() - MISS_TTL_MS) {
            return
        }
        val song = title?.let(::cleanTitle)?.takeIf { it.isNotBlank() }
        val seen = HashSet<String>()
        cached.forEach { seen += it.name }
        val sources: List<Pair<String, suspend () -> List<String>>> = listOf(
            "deezer" to { deezer(name, song) },
            "commons" to { commons(name) },
            "wiki" to { wikipedia(name, song) },
            "itunes" to { itunes(name) },
        )
        withContext(Dispatchers.IO) {
            sources.map { (label, find) ->
                async {
                    val urls = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { runCatching { find() }.onFailure { Log.i(TAG, "$label failed: ${it.javaClass.simpleName}") }.getOrDefault(emptyList()) }.orEmpty()
                    var n = 0
                    for (url in urls) {
                        if (flow.value.size >= MAX) break
                        val fileName = "$label-${sha1(url.substringAfterLast('/').substringBefore('?')).take(10)}.jpg"
                        val dup = synchronized(seen) { !seen.add(fileName) }
                        if (dup) continue
                        val file = download(url, File(dir, fileName)) ?: continue
                        n++
                        flow.update { (it + file).take(MAX) }
                    }
                    if (n > 0) Log.i(TAG, "$label: $n pictures")
                }
            }.awaitAll()
        }
        if (flow.value.isEmpty()) File(dir, "none").writeText("") else File(dir, "none").delete()
        File(dir, "done").writeText("")
    }

    // ---- Deezer: the artist confirmed by the song; the photo, then the covers of their albums ----

    private fun deezer(name: String, song: String?): List<String> {
        var artist: JSONObject? = null
        if (song != null) {
            val hits = get("https://api.deezer.com/search?limit=8&q=${enc("artist:\"$name\" track:\"$song\"")}")?.optJSONArray("data")
            artist = hits?.objects()?.map { it.optJSONObject("artist") }?.firstOrNull { it != null && sameName(it.optString("name"), name) }
        }
        if (artist == null) {
            val hits = get("https://api.deezer.com/search/artist?limit=5&q=${enc(name)}")?.optJSONArray("data")
            artist = hits?.objects()?.firstOrNull { sameName(it.optString("name"), name) }
                ?: hits?.objects()?.firstOrNull()?.takeIf { song == null && it.optInt("nb_fan") > 2000 }
        }
        val a = artist ?: return emptyList()
        val out = ArrayList<String>()
        a.optString("picture_xl").ifBlank { a.optString("picture_big") }.takeIf { it.isNotBlank() && !it.contains("/artist//") }?.let { out += it }
        val id = a.optLong("id")
        if (id > 0) {
            val albums = get("https://api.deezer.com/artist/$id/albums?limit=12")?.optJSONArray("data")?.objects().orEmpty()
            for (al in albums) {
                val cover = al.optString("cover_big")
                if (cover.isNotBlank() && out.none { sameFile(it, cover) }) out += cover
                if (out.size >= 6) break
            }
        }
        return out
    }

    private fun sameName(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && (x == y || x.contains(y) && y.length >= 4 || y.contains(x) && x.length >= 4)
    }

    // ---- Wikimedia Commons: photographs filed under the artist's name ----

    private fun commons(name: String): List<String> {
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=12" +
            "&gsrsearch=${enc("\"$name\" filetype:bitmap")}&prop=imageinfo&iiprop=url|size|mime&iiurlwidth=$THUMB_PX"
        val pages = get(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        val out = ArrayList<String>()
        for (f in pages.values()) {
            val title = f.optString("title")
            // The name must be in the file's own name: a search hit on the description only is often someone else.
            if (!title.lowercase().contains(name.lowercase().split(" ").first())) continue
            val info = f.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            if (info.optString("mime") != "image/jpeg") continue
            if (info.optInt("width") < 300 || info.optInt("height") < 300) continue
            if (!isPhotoName(title)) continue
            val thumb = info.optString("thumburl").ifBlank { info.optString("url") }
            if (thumb.isNotBlank()) out += thumb
            if (out.size >= 5) break
        }
        return out
    }

    // ---- Wikipedia: the artist's page, checked; its photographs ----

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

    private fun wikipedia(artist: String, song: String?): List<String> {
        for (site in sites) {
            val queries = buildList {
                if (song != null) add("$artist $song")
                add("$artist ${site.singer}")
            }
            for (q in queries) {
                val page = search(site, q, artist, song) ?: continue
                val urls = imagesOf(site, page)
                if (urls.isNotEmpty()) return urls
            }
        }
        return emptyList()
    }

    private class Page(val title: String, val lead: String?)

    private fun search(site: Site, q: String, artist: String, song: String?): Page? {
        val url = "https://${site.host}/w/api.php?action=query&format=json&generator=search&gsrlimit=6&gsrsearch=${enc(q)}" +
            "&prop=extracts|pageimages&exintro=1&explaintext=1&exsentences=3&exlimit=6&piprop=original"
        val pages = get(url)?.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val candidates = pages.values().sortedBy { it.optInt("index", 99) }
        val artistWords = artist.lowercase()
        for (p in candidates) {
            val pageTitle = p.optString("title")
            val extract = p.optString("extract").lowercase()
            if (extract.isBlank()) continue
            val opening = extract.take(400)
            val named = pageTitle.lowercase().contains(artistWords) || opening.contains(artistWords)
            if (!named) continue
            if (Regex("""\b(is|was) (a|an|the) (\d{4} )?(song|single|album|ep|mixtape|soundtrack|film|movie|video game)\b""").containsMatchIn(opening)) continue
            if (Regex("""\badalah (sebuah )?(lagu|singel|album|film)\b""").containsMatchIn(opening)) continue
            val musician = site.musician.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(opening) }
            val namesSong = song != null && song.length >= 4 && extract.contains(song.lowercase())
            if (musician || namesSong) return Page(pageTitle, p.optJSONObject("original")?.optString("source"))
        }
        return null
    }

    private fun imagesOf(site: Site, page: Page): List<String> {
        val out = ArrayList<String>()
        page.lead?.takeIf { isPhotoName(it) }?.let { out += thumbOf(it) }
        val url = "https://${site.host}/w/api.php?action=query&format=json&generator=images&gimlimit=40&titles=${enc(page.title)}" +
            "&prop=imageinfo&iiprop=url|size|mime&iiurlwidth=$THUMB_PX"
        val pages = get(url)?.optJSONObject("query")?.optJSONObject("pages")
        if (pages != null) {
            for (f in pages.values()) {
                val name = f.optString("title")
                val info = f.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                if (info.optString("mime") != "image/jpeg") continue
                if (info.optInt("width") < 300 || info.optInt("height") < 300) continue
                if (!isPhotoName(name)) continue
                val thumb = info.optString("thumburl").ifBlank { info.optString("url") }
                if (thumb.isBlank()) continue
                if (out.none { sameFile(it, thumb) }) out += thumb
                if (out.size >= 5) break
            }
        }
        return out
    }

    // ---- iTunes: the covers of the artist's other releases ----

    private fun itunes(name: String): List<String> {
        val hits = get("https://itunes.apple.com/search?media=music&entity=album&limit=10&term=${enc(name)}")?.optJSONArray("results")?.objects().orEmpty()
        val out = ArrayList<String>()
        for (h in hits) {
            if (!sameName(h.optString("artistName"), name)) continue
            val art = h.optString("artworkUrl100").replace("100x100bb", "600x600bb")
            if (art.isNotBlank() && out.none { sameFile(it, art) }) out += art
            if (out.size >= 4) break
        }
        return out
    }

    // ---- names & filters ----

    private val notPhoto = listOf("logo", "icon", "flag", "map", "signature", "poster", "wordmark", "symbol", "seal", "emblem", "chart", "diagram", "commons-", "wiki", "question", "ambox", "star", "award", "certif", "disambig", "edit-", "nuvola", "crystal", "gnome", "ok_", "red_x", "svg")

    private fun isPhotoName(nameOrUrl: String): Boolean {
        val n = nameOrUrl.lowercase()
        if (!n.endsWith(".jpg") && !n.endsWith(".jpeg") && !n.contains(".jpg/") && !n.contains(".jpeg/")) return false
        return notPhoto.none { n.contains(it) }
    }

    private fun sameFile(a: String, b: String) = a.substringAfterLast('/').substringBefore('?') == b.substringAfterLast('/').substringBefore('?')

    private fun thumbOf(original: String): String {
        val m = Regex("""^(https://upload\.wikimedia\.org/wikipedia/[^/]+)/([0-9a-f]/[0-9a-f]{2})/([^/]+)$""").find(original) ?: return original
        val (root, hash, name) = m.destructured
        return "$root/thumb/$hash/$name/${THUMB_PX}px-$name"
    }

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
        s = s.replace(Regex("""\s*[\(\[].*?[\)\]]"""), "")
        s = s.split(Regex("""(?i)\s+(feat\.?|ft\.?|featuring)\s+""")).first()
        s = s.split(" - ").last()
        return s.trim()
    }

    private fun keyOf(name: String) = sha1(name.lowercase())

    private fun root(app: Context) = File(app.cacheDir, "artist-pictures")

    // ---- http ----

    private fun get(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (conn.responseCode != 200) null else JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, to: File): File? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 8000
            setRequestProperty("User-Agent", UA)
        }
        return try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (bitmap.width < 120 || bitmap.height < 120) return null
            val scale = THUMB_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
            to.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            to
        } catch (e: Exception) {
            to.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONObject.values(): List<JSONObject> = keys().asSequence().mapNotNull { optJSONObject(it) }.toList()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

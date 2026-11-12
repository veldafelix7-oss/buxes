package app.buxes.feature.nowplaying

import androidx.compose.ui.graphics.Color

/**
 * What a word is, read from the words themselves and their pace (Buxes): no model, no network —
 * word lists in English and Indonesian, how the line is written, and how fast it is sung. Decided
 * once per line when it is laid out, so colouring costs nothing while the words come in.
 *
 * - [HOT]: cursing, anger, a shouted word (ALL CAPS in the lyric, or ending in "!").
 * - [ENERGY]: cheers and chants, a word repeated in the line, the chorus, a fast (rap) line.
 * - [CALM]: love and comfort, and a slow line.
 * - [PLAIN]: everything else.
 */
internal enum class WordMood(val color: Color, val scale: Float) {
    PLAIN(Color.White, 1f),
    HOT(Color(0xFFFF5252), 1.18f),
    ENERGY(Color(0xFFFFD54F), 1.08f),
    CALM(Color(0xFF80F0A0), 1f),
}

/** How fast a line is sung, from its words' times. */
internal enum class LinePace { FAST, EVEN, SLOW }

internal object LyricMood {
    private const val FAST_WORD_MS = 260L
    private const val SLOW_WORD_MS = 720L

    fun pace(starts: List<Long>?, nextLineMs: Long?): LinePace {
        if (starts == null || starts.size < 2) return LinePace.EVEN
        val end = nextLineMs ?: return LinePace.EVEN
        val perWord = (end - starts.first()).toFloat() / starts.size
        return when {
            perWord < FAST_WORD_MS -> LinePace.FAST
            perWord > SLOW_WORD_MS -> LinePace.SLOW
            else -> LinePace.EVEN
        }
    }

    /** The mood of each word of [words] (already split), in order. */
    fun moods(words: List<String>, pace: LinePace, chorus: Boolean): List<WordMood> {
        val keys = words.map(::key)
        val counts = keys.groupingBy { it }.eachCount()
        return words.mapIndexed { i, raw ->
            val k = keys[i]
            when {
                k.isEmpty() -> WordMood.PLAIN
                k in hot || hotStems.any { k.startsWith(it) } -> WordMood.HOT
                isShout(raw) -> WordMood.HOT
                k in cheer -> WordMood.ENERGY
                counts.getValue(k) >= 2 && k.length >= 2 -> WordMood.ENERGY
                k in calm || calmStems.any { k.startsWith(it) } -> WordMood.CALM
                pace == LinePace.FAST && raw.length >= 5 -> WordMood.ENERGY
                pace == LinePace.SLOW && raw.length >= 5 -> WordMood.CALM
                chorus && raw.length >= 6 -> WordMood.ENERGY
                else -> WordMood.PLAIN
            }
        }
    }

    /** A line sung more than once in the song is its chorus (or a hook) — the line's energy. */
    fun chorusLines(lines: List<String>): Set<Int> {
        val norm = lines.map { it.lowercase().replace(Regex("""[^\p{L}\p{N} ]"""), "").trim() }
        val counts = norm.filter { it.length >= 12 }.groupingBy { it }.eachCount()
        return norm.indices.filter { counts.getOrDefault(norm[it], 0) >= 2 }.toSet()
    }

    private fun key(word: String): String = word.lowercase().replace(Regex("""[^\p{L}\p{N}*]"""), "")

    private fun isShout(raw: String): Boolean {
        val letters = raw.filter { it.isLetter() }
        if (letters.length >= 3 && letters.all { it.isUpperCase() }) return true
        return raw.endsWith("!") && letters.length >= 2
    }

    private val hot = setOf(
        // English
        "fuck", "fucking", "fucked", "fucker", "motherfucker", "shit", "bullshit", "bitch", "bitches", "ass", "asshole", "damn", "goddamn",
        "hell", "dick", "pussy", "cunt", "bastard", "whore", "slut", "hoe", "hoes", "nigga", "niggas", "crap", "piss", "pissed",
        "hate", "hatred", "kill", "killed", "killing", "die", "dead", "death", "blood", "bleed", "burn", "burning", "fire", "rage", "angry", "anger",
        "fight", "fighting", "war", "scream", "screaming", "shut", "stupid", "idiot", "liar", "lies", "enemy", "revenge", "destroy", "pain", "hurt", "broken",
        "f**k", "f*ck", "s**t", "sh*t", "b*tch", "a**",
        // Indonesian
        "anjing", "anjir", "anjrit", "bangsat", "bajingan", "kontol", "memek", "ngentot", "goblok", "tolol", "bodoh", "brengsek", "keparat",
        "setan", "sialan", "sial", "babi", "monyet", "tai", "taik", "bego", "kampret", "jancok", "jancuk", "cok", "asu", "pantek", "pepek", "perek", "lonte",
        "benci", "marah", "murka", "dendam", "bunuh", "mati", "darah", "hancur", "hancurkan", "bakar", "api", "perang", "lawan", "musuh", "pengkhianat",
        "bohong", "pembohong", "dusta", "sakit", "luka", "muak", "jijik", "kejam", "kasar", "neraka", "iblis",
    )
    private val hotStems = setOf("fuck", "shit", "bitch", "anjing", "bangsat", "kontol", "ngentot", "goblok", "jancok", "bajing")

    private val cheer = setOf(
        "oh", "ooh", "oooh", "ohh", "yeah", "yeh", "yea", "yay", "hey", "heyy", "woah", "whoa", "wow", "uh", "uhh", "huh", "hah", "ha", "haha", "ay", "aye", "ayy", "ey",
        "jump", "dance", "party", "tonight", "run", "fly", "loud", "wild", "crazy", "fire", "lit", "hype", "rock", "bounce", "higher", "louder", "faster", "let's", "lets",
        "ayo", "ayok", "yuk", "hore", "horee", "yeay", "yeyy", "wooo", "woo", "wuhu", "hei", "heh", "hai", "goyang", "joget", "lompat", "lari", "terbang", "pesta", "gila", "liar", "semangat", "bangkit", "maju", "teriak", "nyanyi", "menyala",
    )

    private val calm = setOf(
        "love", "loved", "loving", "lover", "heart", "hearts", "home", "peace", "calm", "quiet", "gentle", "soft", "softly", "slow", "slowly", "sleep", "dream", "dreams", "dreaming",
        "hold", "stay", "close", "warm", "smile", "kiss", "forever", "always", "beautiful", "pretty", "angel", "baby", "darling", "honey", "sweet", "sweetheart", "dear",
        "breathe", "rest", "safe", "light", "sun", "sunshine", "moon", "stars", "sky", "ocean", "rain", "heaven", "pray", "hope", "faith", "grace", "thank", "thanks",
        "cinta", "cintaku", "mencintai", "mencintaimu", "kucinta", "sayang", "sayangku", "kasih", "kasihku", "rindu", "merindu", "merindukanmu", "rinduku", "hati", "hatiku", "hatimu",
        "tenang", "damai", "pulang", "rumah", "tidur", "mimpi", "bermimpi", "peluk", "memeluk", "pelukan", "genggam", "senyum", "senyummu", "tersenyum", "manis", "cantik", "indah", "lembut", "hangat",
        "selamanya", "abadi", "setia", "bahagia", "syukur", "terima", "doa", "berdoa", "tuhan", "surga", "bintang", "bulan", "langit", "hujan", "senja", "pagi", "malam", "bersamamu", "denganmu", "untukmu",
    )
    private val calmStems = setOf("cint", "sayang", "rindu", "lovel")
}

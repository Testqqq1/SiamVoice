package com.example.engine

/**
 * Common Thai navigation phrases that are pre-synthesized once ("Download navigation voices") and
 * stored persistently, plus a segmenter that recognises sentences composed only of those phrases
 * (e.g. "อีก 200 เมตร เลี้ยวซ้าย") so they can be played from cached clips without the network.
 */
object NavigationPhrases {
    val ACTIONS: List<String> = listOf(
        "เลี้ยวซ้าย", "เลี้ยวขวา", "เลี้ยวซ้ายเล็กน้อย", "เลี้ยวขวาเล็กน้อย",
        "ชิดซ้าย", "ชิดขวา", "เบี่ยงซ้าย", "เบี่ยงขวา",
        "ตรงไป", "ขับตรงไป", "กลับรถ", "เข้าวงเวียน",
        "ขึ้นทางด่วน", "ลงทางด่วน", "ถึงจุดหมายแล้ว", "คำนวณเส้นทางใหม่"
    )

    val EXIT_NUMBERS: List<Int> = (1..8).toList()
    val DISTANCES_METERS: List<Int> = listOf(50, 100, 150, 200, 250, 300, 400, 500, 600, 700, 800, 900)
    val DISTANCES_KM: List<String> = listOf("1", "1.5", "2", "3", "5", "10")

    private val allPhrases: List<String> by lazy {
        buildList {
            addAll(ACTIONS)
            EXIT_NUMBERS.forEach { add("ทางออกที่ $it") }
            DISTANCES_METERS.forEach { add("อีก $it เมตร") }
            DISTANCES_KM.forEach { add("อีก $it กิโลเมตร") }
        }
    }

    private val allPhraseSet: Set<String> by lazy { allPhrases.toSet() }

    /** Longest first so "เลี้ยวซ้ายเล็กน้อย" wins over "เลี้ยวซ้าย". */
    private val byLength: List<String> by lazy { allPhrases.sortedByDescending { it.length } }

    fun all(): List<String> = allPhrases

    fun isKnownPhrase(normalizedText: String): Boolean = normalizedText in allPhraseSet

    private val THAI_DIGITS = "๐๑๒๓๔๕๖๗๘๙"

    /** Trims, converts Thai digits, drops punctuation and collapses whitespace. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val idx = THAI_DIGITS.indexOf(ch)
            sb.append(
                when {
                    idx >= 0 -> ('0' + idx)
                    ch == ',' || ch == ';' || ch == '!' || ch == '?' || ch == '、' || ch == '。' -> ' '
                    ch == '.' -> '.'
                    else -> ch
                }
            )
        }
        return sb.toString()
            .replace(Regex("(?<!\\d)\\.|\\.(?!\\d)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Splits [text] into known phrases when the whole sentence consists of them (in any order),
     * otherwise returns null. Example: "อีก 200 เมตร เลี้ยวซ้าย" -> ["อีก 200 เมตร", "เลี้ยวซ้าย"].
     */
    fun segment(text: String, known: List<String> = byLength): List<String>? {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return null
        val result = ArrayList<String>()
        var i = 0
        while (i < normalized.length) {
            if (normalized[i] == ' ') {
                i++
                continue
            }
            val match = known.firstOrNull { normalized.startsWith(it, i) } ?: return null
            result.add(match)
            i += match.length
        }
        return result.takeIf { it.isNotEmpty() }
    }
}

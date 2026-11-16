package com.example.processor

import com.example.model.PoliteEndingStyle

object ThaiPoliteEndingProcessor {

    // Common Thai polite ending particles to detect and prevent duplicates
    private val EXISTING_ENDINGS = listOf(
        "ครับผม",
        "นะครับ",
        "ครับ",
        "นะคะ",
        "ค่ะ",
        "คะ",
        "นะฮะ",
        "ฮะ",
        "นะจ๊ะ",
        "นะจ้ะ",
        "จ๊ะ",
        "จ้ะ",
        "นะจ้า",
        "จ้า",
        "ก๊าบ"
    )

    // Directional and turn-by-turn navigation keywords that use friendly "นะครับ" in Auto Natural mode
    private val NAVIGATION_ACTIONS = listOf(
        "เลี้ยวซ้าย",
        "เลี้ยวขวา",
        "ตรงไป",
        "ชิดซ้าย",
        "ชิดขวา",
        "กลับรถ",
        "กลับรถด้านหน้า",
        "เข้าสู่วงเวียน",
        "ออกทางออก",
        "เบี่ยงซ้าย",
        "เบี่ยงขวา",
        "ขับตรงไป",
        "ข้ามสะพาน",
        "ขึ้นทางด่วน",
        "ลงทางด่วน"
    )

    // Arrival and task completion keywords that use "ครับผม" in Auto Natural mode
    private val ARRIVAL_ACTIONS = listOf(
        "ถึงจุดหมายแล้ว",
        "ถึงที่หมายแล้ว",
        "ถึงจุดหมาย",
        "ถึงที่หมาย",
        "เสร็จสิ้นการนำทาง",
        "สิ้นสุดการนำทาง",
        "เสร็จสิ้นแล้ว",
        "เรียบร้อยแล้ว"
    )

    /**
     * Processes input text to append intelligent Thai polite endings:
     * - ครับ (KRUB)
     * - นะครับ (NA_KRUB)
     * - ครับผม (KRUB_PHOM)
     * - Auto Natural (AUTO_NATURAL)
     * - Off (OFF)
     *
     * Never modifies road names, distances, numbers, or navigation-critical content,
     * and prevents duplicate polite particles.
     */
    fun process(text: String, style: PoliteEndingStyle = PoliteEndingStyle.AUTO_NATURAL): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || style == PoliteEndingStyle.OFF) {
            return trimmed
        }

        // Clean trailing punctuation only while preserving all road names, distances, and numbers intact
        val cleanText = trimmed.trimEnd('.', '!', '?', ' ', '\t', '\n')
        if (cleanText.isEmpty()) {
            return trimmed
        }

        // Prevent duplicate polite endings
        if (hasExistingPoliteEnding(cleanText)) {
            return cleanText
        }

        return when (style) {
            PoliteEndingStyle.AUTO_NATURAL -> applyAutoNaturalEnding(cleanText)
            PoliteEndingStyle.KRUB -> "${cleanText}ครับ"
            PoliteEndingStyle.NA_KRUB -> "${cleanText}นะครับ"
            PoliteEndingStyle.KRUB_PHOM -> "${cleanText}ครับผม"
            PoliteEndingStyle.OFF -> cleanText
        }
    }

    private fun hasExistingPoliteEnding(text: String): Boolean {
        for (ending in EXISTING_ENDINGS) {
            if (text.endsWith(ending)) {
                return true
            }
        }
        return false
    }

    private fun applyAutoNaturalEnding(text: String): String {
        // 1. Arrival / completion -> "ครับผม"
        if (ARRIVAL_ACTIONS.any { text.endsWith(it) || text == it }) {
            return "${text}ครับผม"
        }

        // 2. Navigation turn instructions (including instructions with road names) -> "นะครับ"
        if (NAVIGATION_ACTIONS.any { text.endsWith(it) || text.contains(it) }) {
            return "${text}นะครับ"
        }

        // 3. Questions -> "นะครับ", general statements -> "ครับ"
        return if (text.endsWith("ไหม") || text.endsWith("หรือยัง") || text.endsWith("หรือไม่")) {
            "${text}นะครับ"
        } else {
            "${text}ครับ"
        }
    }
}

package com.studydock.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HabitPatternsAndCatalogTest {

    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    private fun epochAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun dayKey(date: LocalDate): Long =
        date.year * 10000L + date.monthValue * 100L + date.dayOfMonth

    @Test
    fun habitIconCatalogCoversIconsColorsFallbackAndAccentInsensitiveSearch() {
        val icons = HabitIconCatalog.icons
        assertTrue("Expected at least 24 icons, got ${icons.size}", icons.size >= 24)

        val keys = icons.map { it.key }
        assertEquals("Habit icon keys must be unique", keys.size, keys.distinct().size)
        assertTrue(icons.all { it.key.isNotBlank() && it.label.isNotBlank() && it.category.isNotBlank() && it.fallbackEmoji.isNotBlank() })

        // Default fallback behavior
        assertEquals("default", HabitIconCatalog.DEFAULT_KEY)
        assertEquals(HabitIconCatalog.DEFAULT_KEY, HabitIconCatalog.findIcon(null).key)
        assertEquals(HabitIconCatalog.DEFAULT_KEY, HabitIconCatalog.findIcon("").key)
        assertEquals(HabitIconCatalog.DEFAULT_KEY, HabitIconCatalog.findIcon("   ").key)
        assertEquals(HabitIconCatalog.DEFAULT_KEY, HabitIconCatalog.findIcon("inexistente").key)
        assertEquals("book", HabitIconCatalog.findIcon("book").key)
        assertEquals("book", HabitIconCatalog.findIcon(" book ").key)

        // Search behavior (Portuguese keywords, accent-insensitive, blank query)
        val bookSearch = HabitIconCatalog.search("livro")
        assertTrue("Search for 'livro' must find 'book'", bookSearch.any { it.key == "book" })

        val unaccentedHeart = HabitIconCatalog.search("coracao")
        assertTrue("Search for 'coracao' must find 'heart_break'", unaccentedHeart.any { it.key == "heart_break" })

        val accentedHeart = HabitIconCatalog.search("coração")
        assertTrue("Search for 'coração' must find 'heart_break'", accentedHeart.any { it.key == "heart_break" })

        val unaccentedCategory = HabitIconCatalog.search("saude")
        assertTrue("Accent-insensitive category search 'saude' must match Saúde icons", unaccentedCategory.any { it.key == "water" })

        val multiToken = HabitIconCatalog.search("estudo livro")
        assertTrue(multiToken.any { it.key == "book" })

        assertEquals(icons, HabitIconCatalog.search("  "))
        assertEquals(icons, HabitIconCatalog.search(""))

        // Colors catalog without duplicates
        assertTrue(HabitIconCatalog.colors.isNotEmpty())
        assertEquals("Colors catalog must not contain duplicates", HabitIconCatalog.colors.size, HabitIconCatalog.colors.distinct().size)
    }

    @Test
    fun classifyHourToZoneMapsBoundaryHoursAccurately() {
        assertEquals(FocusTimeZone.MORNING, HabitPatternsEngine.classifyHourToZone(7))
        assertEquals(FocusTimeZone.MORNING, HabitPatternsEngine.classifyHourToZone(11))

        assertEquals(FocusTimeZone.AFTERNOON, HabitPatternsEngine.classifyHourToZone(12))
        assertEquals(FocusTimeZone.AFTERNOON, HabitPatternsEngine.classifyHourToZone(16))

        assertEquals(FocusTimeZone.EVENING, HabitPatternsEngine.classifyHourToZone(17))
        assertEquals(FocusTimeZone.EVENING, HabitPatternsEngine.classifyHourToZone(23))
        assertEquals(FocusTimeZone.EVENING, HabitPatternsEngine.classifyHourToZone(0))
        assertEquals(FocusTimeZone.EVENING, HabitPatternsEngine.classifyHourToZone(6))
    }

    @Test
    fun computePatternsEnforcesUnlockThresholdAndMissedHabitBreakdown() {
        val today = LocalDate.of(2026, 11, 13)
        val todayKey = dayKey(today)
        val startDate = today.minusDays(5) // 2026-11-08
        val habit = Habit(
            id = 1L,
            title = "Leitura Diária",
            area = "Estudo",
            kind = "GOOD",
            repeatDaysMask = 127,
            createdAt = epochAt(startDate, 6, 0),
            startedAt = epochAt(startDate, 6, 0)
        )

        // Fewer than 3 tracked days keeps missed summary locked and bestZone null
        val twoDaySnapshot = HabitPatternsEngine.computePatterns(
            habits = listOf(habit),
            completions = listOf(
                HabitCompletion(habitId = 1L, dayKey = dayKey(today.minusDays(2)), status = "DONE", loggedAt = epochAt(today.minusDays(2), 9, 0)),
                HabitCompletion(habitId = 1L, dayKey = dayKey(today.minusDays(1)), status = "DONE", loggedAt = epochAt(today.minusDays(1), 14, 0))
            ),
            todayDayKey = todayKey,
            rangeDays = 6,
            kindFilter = "GOOD",
            zoneId = zone
        )
        assertFalse(twoDaySnapshot.missed.unlocked)
        assertEquals(2, twoDaySnapshot.missed.trackedDaysCount)
        assertEquals(1, twoDaySnapshot.missed.remainingDaysToUnlock)
        assertTrue(twoDaySnapshot.missed.remainingDaysToUnlock > 0)
        assertNull(twoDaySnapshot.bestZone)
        assertTrue(twoDaySnapshot.peakZones.all { it.successRatePercent == null })

        // >= 3 tracked days unlocks patterns and accurately separates SKIPPED vs FAILED (explicit + implicit past days)
        val unlockedSnapshot = HabitPatternsEngine.computePatterns(
            habits = listOf(habit),
            completions = listOf(
                HabitCompletion(habitId = 1L, dayKey = dayKey(today.minusDays(5)), status = "DONE", loggedAt = epochAt(today.minusDays(5), 8, 30)),
                HabitCompletion(habitId = 1L, dayKey = dayKey(today.minusDays(4)), status = "PARTIAL", loggedAt = epochAt(today.minusDays(4), 10, 15)),
                HabitCompletion(habitId = 1L, dayKey = dayKey(today.minusDays(3)), status = "SKIPPED", loggedAt = epochAt(today.minusDays(3), 9, 0)),
                HabitCompletion(habitId = 1L, dayKey = dayKey(today.minusDays(2)), status = "FAILED", loggedAt = epochAt(today.minusDays(2), 20, 0))
                // today.minusDays(1) has NO completion -> counts as implicit FAILED
                // today has NO completion -> still open today, must NOT count as FAILED
            ),
            todayDayKey = todayKey,
            rangeDays = 6,
            kindFilter = "GOOD",
            zoneId = zone
        )

        assertTrue(unlockedSnapshot.missed.unlocked)
        assertEquals(0, unlockedSnapshot.missed.remainingDaysToUnlock)
        assertEquals(4, unlockedSnapshot.trackedDaysCount)
        assertEquals(1, unlockedSnapshot.missed.skippedCount)
        assertEquals(2, unlockedSnapshot.missed.failedCount) // 1 explicit FAILED + 1 past unlogged scheduled day
        assertEquals(3, unlockedSnapshot.missed.totalMissed)
        assertEquals(5, unlockedSnapshot.missed.evaluatedScheduledCount)

        assertEquals(FocusTimeZone.MORNING, unlockedSnapshot.bestZone)
        val morning = unlockedSnapshot.peakZones.first { it.zone == FocusTimeZone.MORNING }
        val afternoon = unlockedSnapshot.peakZones.first { it.zone == FocusTimeZone.AFTERNOON }
        val evening = unlockedSnapshot.peakZones.first { it.zone == FocusTimeZone.EVENING }
        assertEquals(2, morning.completionCount)
        assertEquals(6, morning.scheduledDenominator)
        assertEquals(33, morning.successRatePercent)
        assertEquals(1f, morning.shareOfCompletions, 1e-5f)
        assertEquals(0, afternoon.completionCount)
        assertNull(afternoon.successRatePercent)
        assertEquals(0, evening.completionCount)
        assertNull(evening.successRatePercent)
    }

    @Test
    fun computePatternsRespectsKindHabitAreaAndRepeatDaysMaskFilters() {
        // 2026-11-13 is Friday; startDate = 2026-11-09 (Monday)
        val today = LocalDate.of(2026, 11, 13)
        val monday = today.minusDays(4)
        val tuesday = today.minusDays(3)
        val wednesday = today.minusDays(2)
        val thursday = today.minusDays(1)
        val startEpoch = epochAt(monday, 6, 0)

        // Scheduled ONLY on Monday (bit 0), Wednesday (bit 2), Friday (bit 4)
        val monWedFriMask = (1 shl 0) or (1 shl 2) or (1 shl 4)
        val goodStudyHabit = Habit(
            id = 10L,
            title = "Revisar Questões",
            area = "Estudo",
            kind = "GOOD",
            repeatDaysMask = monWedFriMask,
            createdAt = startEpoch,
            startedAt = startEpoch
        )
        val goodHealthHabit = Habit(
            id = 11L,
            title = "Alongar",
            area = "Saúde",
            kind = "GOOD",
            repeatDaysMask = 127,
            createdAt = startEpoch,
            startedAt = startEpoch
        )
        val badPhoneHabit = Habit(
            id = 12L,
            title = "Redes Sociais",
            area = "Foco",
            kind = "BAD",
            repeatDaysMask = 127,
            createdAt = startEpoch,
            startedAt = startEpoch
        )

        val habits = listOf(goodStudyHabit, goodHealthHabit, badPhoneHabit)
        val completions = listOf(
            // Study GOOD: Mon, Wed, Fri at afternoon (Tue/Thu unscheduled -> must NOT fail)
            HabitCompletion(habitId = 10L, dayKey = dayKey(monday), status = "DONE", loggedAt = epochAt(monday, 14, 0)),
            HabitCompletion(habitId = 10L, dayKey = dayKey(wednesday), status = "DONE", loggedAt = epochAt(wednesday, 15, 0)),
            HabitCompletion(habitId = 10L, dayKey = dayKey(today), status = "DONE", loggedAt = epochAt(today, 13, 30)),
            // Health GOOD: Mon-Wed morning DONE, Thu SKIPPED
            HabitCompletion(habitId = 11L, dayKey = dayKey(monday), status = "DONE", loggedAt = epochAt(monday, 8, 0)),
            HabitCompletion(habitId = 11L, dayKey = dayKey(tuesday), status = "DONE", loggedAt = epochAt(tuesday, 8, 15)),
            HabitCompletion(habitId = 11L, dayKey = dayKey(wednesday), status = "DONE", loggedAt = epochAt(wednesday, 8, 30)),
            HabitCompletion(habitId = 11L, dayKey = dayKey(thursday), status = "SKIPPED", loggedAt = epochAt(thursday, 8, 30)),
            // Phone BAD: 3 evening successes + 1 explicit MISSED
            HabitCompletion(habitId = 12L, dayKey = dayKey(monday), status = "DONE", loggedAt = epochAt(monday, 21, 0)),
            HabitCompletion(habitId = 12L, dayKey = dayKey(tuesday), status = "MISSED", loggedAt = epochAt(tuesday, 22, 0)),
            HabitCompletion(habitId = 12L, dayKey = dayKey(wednesday), status = "DONE", loggedAt = epochAt(wednesday, 21, 30)),
            HabitCompletion(habitId = 12L, dayKey = dayKey(thursday), status = "DONE", loggedAt = epochAt(thursday, 20, 0))
        )

        // 1. Filter by habitIdFilter = 10L & kindFilter = "GOOD" -> respects Mon/Wed/Fri repeatDaysMask
        val studyById = HabitPatternsEngine.computePatterns(
            habits = habits,
            completions = completions,
            todayDayKey = dayKey(today),
            rangeDays = 5,
            kindFilter = "GOOD",
            habitIdFilter = 10L,
            zoneId = zone
        )
        assertTrue(studyById.missed.unlocked)
        assertEquals(3, studyById.trackedDaysCount)
        assertEquals(0, studyById.missed.skippedCount)
        assertEquals(0, studyById.missed.failedCount) // Tue/Thu unscheduled -> zero missed
        assertEquals(FocusTimeZone.AFTERNOON, studyById.bestZone)
        assertEquals(3, studyById.peakZones.first { it.zone == FocusTimeZone.AFTERNOON }.completionCount)
        assertEquals(0, studyById.peakZones.first { it.zone == FocusTimeZone.EVENING }.completionCount)

        // 2. Filter by areaFilter = "Saúde"
        val healthByArea = HabitPatternsEngine.computePatterns(
            habits = habits,
            completions = completions,
            todayDayKey = dayKey(today),
            rangeDays = 5,
            kindFilter = "GOOD",
            areaFilter = "Saúde",
            zoneId = zone
        )
        assertTrue(healthByArea.missed.unlocked)
        assertEquals(4, healthByArea.trackedDaysCount)
        assertEquals(1, healthByArea.missed.skippedCount)
        assertEquals(0, healthByArea.missed.failedCount)
        assertEquals(FocusTimeZone.MORNING, healthByArea.bestZone)
        assertEquals(3, healthByArea.peakZones.first { it.zone == FocusTimeZone.MORNING }.completionCount)
        assertEquals(0, healthByArea.peakZones.first { it.zone == FocusTimeZone.AFTERNOON }.completionCount)

        // 3. Filter by kindFilter = "BAD" strictly excludes GOOD habits
        val badOnly = HabitPatternsEngine.computePatterns(
            habits = habits,
            completions = completions,
            todayDayKey = dayKey(today),
            rangeDays = 5,
            kindFilter = "BAD",
            zoneId = zone
        )
        assertEquals("BAD", badOnly.kind)
        assertTrue(badOnly.missed.unlocked)
        assertEquals(4, badOnly.trackedDaysCount)
        assertEquals(0, badOnly.missed.skippedCount)
        assertEquals(1, badOnly.missed.failedCount)
        assertEquals(FocusTimeZone.EVENING, badOnly.bestZone)
        assertEquals(3, badOnly.peakZones.first { it.zone == FocusTimeZone.EVENING }.completionCount)
        assertEquals(0, badOnly.peakZones.first { it.zone == FocusTimeZone.MORNING }.completionCount)
        assertEquals(0, badOnly.peakZones.first { it.zone == FocusTimeZone.AFTERNOON }.completionCount)

        // 4. Mismatched kindFilter + habitIdFilter yields empty filtered set
        val mismatched = HabitPatternsEngine.computePatterns(
            habits = habits,
            completions = completions,
            todayDayKey = dayKey(today),
            rangeDays = 5,
            kindFilter = "GOOD",
            habitIdFilter = 12L,
            zoneId = zone
        )
        assertFalse(mismatched.missed.unlocked)
        assertEquals(0, mismatched.trackedDaysCount)
        assertNull(mismatched.bestZone)
    }

    @Test
    fun readerPaginateTextAndFocusAudioStoreKeysAreConsistent() {
        // Empty and whitespace-only texts
        assertTrue(ReaderPreferencesStore.paginateText("").isEmpty())
        assertTrue(ReaderPreferencesStore.paginateText("   \n\n  \t ").isEmpty())

        // Short text stays on 1 page
        val shortText = "Um parágrafo curto de estudo."
        val shortPages = ReaderPreferencesStore.paginateText(shortText)
        assertEquals(listOf(shortText), shortPages)

        // Multi-paragraph text preserves all words and splits into >= 2 pages
        val longParagraphs = buildString {
            appendLine("# Capítulo 1 — Fundamentos")
            appendLine()
            for (p in 1..8) {
                append("Parágrafo $p: ")
                repeat(45) { idx ->
                    append("palavra_${p}_$idx ")
                }
                append("fim do bloco $p.")
                if (p == 4) {
                    appendLine()
                    appendLine()
                    appendLine("## Seção 2 — Aplicações")
                }
                appendLine()
                appendLine()
            }
        }.trim()

        val pages = ReaderPreferencesStore.paginateText(
            rawText = longParagraphs,
            fontSizeSp = 22f,
            lineHeightMultiplier = 1.65f,
            marginPreset = ReadEraMarginPreset.WIDE
        )
        assertTrue("Expected at least 2 pages, got ${pages.size}", pages.size >= 2)
        assertTrue(pages.all { it.isNotBlank() })

        val originalWords = longParagraphs.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val paginatedWords = pages.flatMap { it.split(Regex("\\s+")).filter { token -> token.isNotEmpty() } }
        assertEquals("Pagination must preserve every word in order", originalWords, paginatedWords)

        // ReadEra enum fallbacks
        assertEquals(ReadEraTheme.SEPIA, ReadEraTheme.fromKey("unknown"))
        assertEquals(ReadEraTheme.AMOLED, ReadEraTheme.fromKey(" amoLed "))
        assertEquals(ReadEraFontFamily.SERIF, ReadEraFontFamily.fromKey(null))
        assertEquals(ReadEraMarginPreset.MEDIUM, ReadEraMarginPreset.fromKey("unknown"))
        assertEquals(ReadEraScrollMode.PAGINATED, ReadEraScrollMode.fromKey("unknown"))

        // FocusAudioStore defaultSoundVolumes contains all 32 expected sound keys
        val expectedSoundKeys = setOf(
            "lofi",
            "soft_piano",
            "wind_chimes",
            "ambient_pad",
            "singing_bowl",
            "binaural_alpha",
            "binaural_theta",
            "binaural_beta",
            "binaural_gamma",
            "binaural_delta",
            "birds",
            "mourning_dove",
            "forest",
            "forest_stream",
            "forest_rain",
            "forest_night",
            "mountain_wind",
            "rain",
            "rain_window",
            "thunder",
            "waves",
            "waterfall",
            "coffee",
            "library",
            "keyboard",
            "fire",
            "fan",
            "train",
            "brown",
            "pink",
            "green_noise",
            "white"
        )
        assertEquals(expectedSoundKeys, FocusAudioStore.defaultSoundVolumes.keys)
        assertEquals(expectedSoundKeys, FocusAudioStore.allSoundKeys.toSet())
        assertTrue(FocusAudioStore.defaultSoundVolumes.values.all { it in 0.01f..1.0f })
        assertEquals(expectedSoundKeys, com.studydock.app.audio.FocusAudioService.defaultSoundVolumes.keys)
        assertEquals(expectedSoundKeys, com.studydock.app.audio.FocusAudioService.soundLabels.keys)
        assertTrue(com.studydock.app.audio.FocusAudioService.presetMixes.values.all { mix ->
            mix.isNotEmpty() && mix.keys.all { it in expectedSoundKeys }
        })
    }
}

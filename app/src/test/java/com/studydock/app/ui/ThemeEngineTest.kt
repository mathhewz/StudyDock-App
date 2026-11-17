package com.studydock.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEngineTest {

    @Test
    fun encodeAndDecodeThemeCodeRoundTripsAllCustomizations() {
        val customSlots = listOf(
            StudyNavSlots.SLOT_HOJE,
            StudyNavSlots.SLOT_NOTAS,
            StudyNavSlots.SLOT_CARTOES,
            StudyNavSlots.SLOT_TAREFAS,
            StudyNavSlots.SLOT_MAIS
        )
        val hiddenWidgets = setOf(
            StudyDashboardWidgets.DIARY_CHECKIN,
            StudyDashboardWidgets.RECENT_SESSIONS
        )
        val customOrder = listOf(
            StudyDashboardWidgets.TODAY_AGENDA,
            StudyDashboardWidgets.HERO_STUDY,
            StudyDashboardWidgets.DAILY_GOAL,
            StudyDashboardWidgets.PINNED_GOALS,
            StudyDashboardWidgets.QUICK_ACTIONS,
            StudyDashboardWidgets.HABITS_TODAY,
            StudyDashboardWidgets.STATS_GRID,
            StudyDashboardWidgets.CHARTS_HEATMAP,
            StudyDashboardWidgets.DIARY_CHECKIN,
            StudyDashboardWidgets.RECENT_SESSIONS
        )
        val original = StudyThemeConfig(
            baseSurface = StudyBaseSurface.NORD_SLATE,
            accentMode = StudyAccentMode.CUSTOM,
            accentPresetId = "emerald",
            customAccentArgb = 0x123456, // alpha intentionally omitted; normalized() must make opaque
            cardStyle = StudyCardStyle.OUTLINED,
            cornerStyle = StudyCornerStyle.ROUNDED,
            density = StudyUiDensity.COMPACT,
            immersiveFocusTheme = true,
            bottomNavSlots = customSlots,
            hiddenDashboardWidgets = hiddenWidgets,
            dashboardWidgetOrder = customOrder
        )

        val code = encodeThemeCode(original)
        assertTrue("Theme code must start with SDTHEME:v1:", code.startsWith("SDTHEME:v1:"))

        val decoded = decodeThemeCode(code)
        assertNotNull(decoded)
        assertEquals(original.normalized(), decoded)
        assertEquals(0xFF123456.toInt(), decoded!!.customAccentArgb)
    }

    @Test
    fun encodeAndDecodeThemeCodeCoversAllSurfacesPresetsAndStyles() {
        for (surface in StudyBaseSurface.entries) {
            for (cardStyle in StudyCardStyle.entries) {
                for (cornerStyle in StudyCornerStyle.entries) {
                    for (density in StudyUiDensity.entries) {
                        val cfg = StudyThemeConfig(
                            baseSurface = surface,
                            accentMode = StudyAccentMode.PRESET,
                            accentPresetId = StudyAccentPresets.all[surface.ordinal % StudyAccentPresets.all.size].id,
                            cardStyle = cardStyle,
                            cornerStyle = cornerStyle,
                            density = density
                        )
                        val decoded = decodeThemeCode("  ${encodeThemeCode(cfg)}  ")
                        assertEquals(cfg.normalized(), decoded)
                    }
                }
            }
        }
    }

    @Test
    fun decodeThemeCodeHandlesPrefixVariantsAndInvalidInputs() {
        val base = StudyThemeConfig(
            baseSurface = StudyBaseSurface.SEPIA_READING,
            accentMode = StudyAccentMode.PRESET,
            accentPresetId = "amber"
        )
        val encoded = encodeThemeCode(base)
        val lowercasePrefix = "sdtheme:v1:" + encoded.removePrefix("SDTHEME:v1:")
        val legacyPrefix = "SDTHEME:" + encoded.removePrefix("SDTHEME:v1:")
        val standardBase64 = encoded.replace('-', '+').replace('_', '/') + "=="

        assertEquals(base.normalized(), decodeThemeCode(lowercasePrefix))
        assertEquals(base.normalized(), decodeThemeCode(legacyPrefix))
        assertEquals(base.normalized(), decodeThemeCode(standardBase64))

        assertNull(decodeThemeCode(""))
        assertNull(decodeThemeCode("   "))
        assertNull(decodeThemeCode("SDTHEME:v1:"))
        assertNull(decodeThemeCode("SDTHEME:"))
        assertNull(decodeThemeCode("OTHER:v1:cz13YXJtX3BhcGVy"))
        assertNull(decodeThemeCode("SDTHEME:v1:!!!not-base64!!!"))
        // Missing surface (s=)
        assertNull(decodeThemeCode("SDTHEME:v1:bT1wcmVzZXQmcD1pbmRpZ28"))
        // Unknown surface id
        assertNull(decodeThemeCode("SDTHEME:v1:cz11bmtub3duX3N1cmZhY2U"))
    }

    @Test
    fun hexAndColorMathHelpersWorkAccurately() {
        assertEquals("#6156D9", formatHexColor(0xFF6156D9.toInt(), includeHash = true))
        assertEquals("00FFAA", formatHexColor(0x4000FFAA, includeHash = false))

        assertEquals(0xFFFF00AA.toInt(), parseHexColorOrNull("#F0A"))
        assertEquals(0xFF6156D9.toInt(), parseHexColorOrNull("  #6156d9 "))
        assertEquals(0xFF112233.toInt(), parseHexColorOrNull("80112233"))
        assertNull(parseHexColorOrNull(""))
        assertNull(parseHexColorOrNull("#12"))
        assertNull(parseHexColorOrNull("#12345"))
        assertNull(parseHexColorOrNull("#GGGGGG"))

        assertEquals(0.0, relativeLuminance(Color.Black), 1e-6)
        assertEquals(1.0, relativeLuminance(Color.White), 1e-6)
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 1e-4)
        assertEquals(21.0, contrastRatio(Color.White, Color.Black), 1e-4)
        assertEquals(1.0, contrastRatio(Color.Red, Color.Red), 1e-6)

        val sampleColors = listOf(
            Color(0xFF6156D9),
            Color(0xFFE11D48),
            Color(0xFF059669),
            Color(0xFFD97706),
            Color(0xFF000000),
            Color(0xFFFFFFFF),
            Color(0xFFFFFF00)
        )
        for (c in sampleColors) {
            val hsv = colorToHsv(c)
            val reconstructed = hsvToColor(hsv.h, hsv.s, hsv.v)
            assertEquals(c.red, reconstructed.red, 1e-3f)
            assertEquals(c.green, reconstructed.green, 1e-3f)
            assertEquals(c.blue, reconstructed.blue, 1e-3f)
        }
    }

    @Test
    fun colorSchemeMaintainsWcagContrastAcrossAllSurfacesAndPresets() {
        assertEquals(6, StudyBaseSurface.entries.size)
        assertEquals(10, StudyAccentPresets.all.size)

        for (surface in StudyBaseSurface.entries) {
            for (preset in StudyAccentPresets.all) {
                val config = StudyThemeConfig(
                    baseSurface = surface,
                    accentMode = StudyAccentMode.PRESET,
                    accentPresetId = preset.id
                )
                val scheme = buildStudyColorScheme(config, context = null, isTimerRunning = false)
                assertSchemeWcagContrast("surface=${surface.id}, preset=${preset.id}", scheme)
            }
        }
    }

    @Test
    fun colorSchemeMaintainsWcagContrastForExtremeCustomColorsAcrossAllSurfaces() {
        val extremeColors = listOf(
            0xFF000000.toInt(), // pure black
            0xFFFFFFFF.toInt(), // pure white
            0xFFFFFF00.toInt(), // pure yellow (notorious low-contrast on light surfaces)
            0xFF00FF00.toInt(), // pure green
            0xFF00FFFF.toInt(), // pure cyan
            0xFFFF0000.toInt(), // pure red
            0xFF0000FF.toInt(), // pure blue
            0xFFFF00FF.toInt(), // pure magenta
            0xFF808080.toInt()  // mid-gray
        )

        for (surface in StudyBaseSurface.entries) {
            for (argb in extremeColors) {
                val config = StudyThemeConfig(
                    baseSurface = surface,
                    accentMode = StudyAccentMode.CUSTOM,
                    customAccentArgb = argb
                )
                val scheme = buildStudyColorScheme(config, context = null, isTimerRunning = false)
                assertSchemeWcagContrast(
                    "surface=${surface.id}, custom=${formatHexColor(argb)}",
                    scheme
                )
            }
        }
    }

    @Test
    fun semanticColorsMaintainAccessibleContrastAcrossAllSurfaces() {
        for (surface in StudyBaseSurface.entries) {
            val scheme = buildStudyColorScheme(StudyThemeConfig(baseSurface = surface))
            val semantic = buildStudySemanticColors(
                baseSurface = surface,
                surfaceContainer = scheme.surfaceContainer,
                surface = scheme.surface
            )

            assertTrue(
                "success vs surface on ${surface.id}",
                contrastRatio(semantic.success, scheme.surface) >= 4.5
            )
            assertTrue(
                "success vs onSuccess on ${surface.id}",
                contrastRatio(semantic.success, semantic.onSuccess) >= 4.5
            )
            assertTrue(
                "warning vs surface on ${surface.id}",
                contrastRatio(semantic.warning, scheme.surface) >= 4.5
            )
            assertTrue(
                "warning vs onWarning on ${surface.id}",
                contrastRatio(semantic.warning, semantic.onWarning) >= 4.5
            )
            assertTrue(
                "info vs surface on ${surface.id}",
                contrastRatio(semantic.info, scheme.surface) >= 4.5
            )
            assertTrue(
                "info vs onInfo on ${surface.id}",
                contrastRatio(semantic.info, semantic.onInfo) >= 4.5
            )
            assertTrue(
                "successContainer vs onSuccessContainer on ${surface.id}",
                contrastRatio(semantic.successContainer, semantic.onSuccessContainer) >= 4.5
            )
            assertTrue(
                "warningContainer vs onWarningContainer on ${surface.id}",
                contrastRatio(semantic.warningContainer, semantic.onWarningContainer) >= 4.5
            )
            assertTrue(
                "infoContainer vs onInfoContainer on ${surface.id}",
                contrastRatio(semantic.infoContainer, semantic.onInfoContainer) >= 4.5
            )
        }
    }

    @Test
    fun studyNavSlotsAlwaysPreservesHomeMoreAndFiveDistinctSlots() {
        // Valid custom configuration preserves exact order
        val validCustom = listOf(
            StudyNavSlots.SLOT_HOJE,
            StudyNavSlots.SLOT_CARTOES,
            StudyNavSlots.SLOT_NOTAS,
            StudyNavSlots.SLOT_TAREFAS,
            StudyNavSlots.SLOT_MAIS
        )
        assertEquals(validCustom, StudyNavSlots.sanitize(validCustom))

        // Empty list falls back to 5 valid slots including Hoje and Mais
        val fromEmpty = StudyNavSlots.sanitize(emptyList())
        assertEquals(5, fromEmpty.size)
        assertEquals(5, fromEmpty.distinct().size)
        assertTrue(StudyNavSlots.SLOT_HOJE in fromEmpty)
        assertTrue(StudyNavSlots.SLOT_MAIS in fromEmpty)
        assertTrue(fromEmpty.all { StudyNavSlots.isValid(it) })

        // Missing Mais, duplicates, unknown slots, extra whitespace, >5 items
        val broken = listOf(
            "  Timer ",
            "Timer",
            "invalido",
            StudyNavSlots.SLOT_AGENDA,
            StudyNavSlots.SLOT_METAS,
            StudyNavSlots.SLOT_LIVROS,
            StudyNavSlots.SLOT_DIARIO
        )
        val sanitized = StudyNavSlots.sanitize(broken)
        assertEquals(5, sanitized.size)
        assertEquals(5, sanitized.distinct().size)
        assertEquals(StudyNavSlots.SLOT_HOJE, sanitized.first())
        assertEquals(StudyNavSlots.SLOT_MAIS, sanitized.last())
        assertTrue(StudyNavSlots.SLOT_TIMER in sanitized)
        assertTrue(StudyNavSlots.SLOT_AGENDA in sanitized)
        assertTrue(StudyNavSlots.SLOT_METAS in sanitized)

        // Swappable options exclude locked slots (Hoje & Mais)
        assertFalse(StudyNavSlots.swappableMiddleOptions.any { it.id == StudyNavSlots.SLOT_HOJE })
        assertFalse(StudyNavSlots.swappableMiddleOptions.any { it.id == StudyNavSlots.SLOT_MAIS })
        assertEquals(StudyNavSlots.SLOT_HOJE, StudyNavSlots.byId("does_not_exist").id)
    }

    @Test
    fun studyDashboardWidgetsSanitizeOrderAndConfigNormalizationWork() {
        val emptySanitized = StudyDashboardWidgets.sanitizeOrder(emptyList())
        assertEquals(StudyDashboardWidgets.defaultOrder, emptySanitized)

        val partial = listOf(
            "  ${StudyDashboardWidgets.CHARTS_HEATMAP} ",
            "unknown_widget",
            StudyDashboardWidgets.CHARTS_HEATMAP,
            StudyDashboardWidgets.TODAY_AGENDA
        )
        val reordered = StudyDashboardWidgets.sanitizeOrder(partial)
        assertEquals(StudyDashboardWidgets.all.size, reordered.size)
        assertEquals(StudyDashboardWidgets.allIds, reordered.toSet())
        assertEquals(StudyDashboardWidgets.CHARTS_HEATMAP, reordered[0])
        assertEquals(StudyDashboardWidgets.TODAY_AGENDA, reordered[1])
        assertFalse("unknown_widget" in reordered)

        val config = StudyThemeConfig(
            baseSurface = StudyBaseSurface.WARM_PAPER,
            accentPresetId = "non_existent",
            customAccentArgb = 0x00ABCDEF,
            immersiveFocusTheme = true,
            bottomNavSlots = listOf("Notas"),
            hiddenDashboardWidgets = setOf(StudyDashboardWidgets.STATS_GRID, "bogus"),
            dashboardWidgetOrder = listOf(StudyDashboardWidgets.RECENT_SESSIONS)
        ).normalized()

        assertEquals(StudyAccentPresets.all.first().id, config.accentPresetId)
        assertEquals(0xFFABCDEF.toInt(), config.customAccentArgb)
        assertEquals(5, config.bottomNavSlots.size)
        assertTrue(StudyNavSlots.SLOT_MAIS in config.bottomNavSlots)
        assertEquals(setOf(StudyDashboardWidgets.STATS_GRID), config.hiddenDashboardWidgets)
        assertFalse(config.isWidgetVisible(StudyDashboardWidgets.STATS_GRID))
        assertTrue(config.isWidgetVisible(StudyDashboardWidgets.HERO_STUDY))
        assertEquals(StudyDashboardWidgets.RECENT_SESSIONS, config.dashboardWidgetOrder.first())
        assertEquals(StudyDashboardWidgets.all.size, config.dashboardWidgetOrder.size)

        // Immersive focus switches to AMOLED_BLACK only while timer is running
        assertEquals(StudyBaseSurface.WARM_PAPER, config.effectiveBaseSurface(isTimerRunning = false))
        assertEquals(StudyBaseSurface.AMOLED_BLACK, config.effectiveBaseSurface(isTimerRunning = true))

        // Immersive focus disabled keeps baseSurface even when timer is running
        val nonImmersive = config.copy(immersiveFocusTheme = false)
        assertEquals(StudyBaseSurface.WARM_PAPER, nonImmersive.effectiveBaseSurface(isTimerRunning = true))

        // Dynamic You gracefully falls back to preset scheme when context is null
        val dynamicWithoutContext = buildStudyColorScheme(
            StudyThemeConfig(baseSurface = StudyBaseSurface.OBSIDIAN_DARK, accentMode = StudyAccentMode.DYNAMIC_YOU),
            context = null
        )
        assertSchemeWcagContrast("dynamic_you_no_context", dynamicWithoutContext)
    }

    @Test
    fun densityAndScreenPaddingTokensRespectBreakpoints() {
        assertEquals(16.dp, StudyTokens.cardPadding(StudyUiDensity.COMFORTABLE))
        assertEquals(12.dp, StudyTokens.cardPadding(StudyUiDensity.COMPACT))
        assertEquals(12.dp, studyHorizontalScreenPadding(320.dp, StudyUiDensity.COMPACT))
        assertEquals(14.dp, studyHorizontalScreenPadding(320.dp, StudyUiDensity.COMFORTABLE))
        assertEquals(16.dp, studyHorizontalScreenPadding(412.dp, StudyUiDensity.COMFORTABLE))
        assertEquals(24.dp, studyHorizontalScreenPadding(840.dp, StudyUiDensity.COMFORTABLE))

        val shapes = buildStudyShapes(StudyCornerStyle.SHARP)
        assertNotNull(shapes.medium)
        assertTrue(Color(0xFF6156D9).toArgb() != 0)
    }

    private fun assertSchemeWcagContrast(label: String, scheme: androidx.compose.material3.ColorScheme) {
        assertTrue(
            "$label: primary vs surface < 4.5 (${contrastRatio(scheme.primary, scheme.surface)})",
            contrastRatio(scheme.primary, scheme.surface) >= 4.5
        )
        assertTrue(
            "$label: primary vs onPrimary < 4.5 (${contrastRatio(scheme.primary, scheme.onPrimary)})",
            contrastRatio(scheme.primary, scheme.onPrimary) >= 4.5
        )
        assertTrue(
            "$label: secondary vs surface < 4.5 (${contrastRatio(scheme.secondary, scheme.surface)})",
            contrastRatio(scheme.secondary, scheme.surface) >= 4.5
        )
        assertTrue(
            "$label: secondary vs onSecondary < 4.5 (${contrastRatio(scheme.secondary, scheme.onSecondary)})",
            contrastRatio(scheme.secondary, scheme.onSecondary) >= 4.5
        )
        assertTrue(
            "$label: tertiary vs surface < 4.5 (${contrastRatio(scheme.tertiary, scheme.surface)})",
            contrastRatio(scheme.tertiary, scheme.surface) >= 4.5
        )
        assertTrue(
            "$label: tertiary vs onTertiary < 4.5 (${contrastRatio(scheme.tertiary, scheme.onTertiary)})",
            contrastRatio(scheme.tertiary, scheme.onTertiary) >= 4.5
        )
        assertTrue(
            "$label: primaryContainer vs onPrimaryContainer < 4.5 (${contrastRatio(scheme.primaryContainer, scheme.onPrimaryContainer)})",
            contrastRatio(scheme.primaryContainer, scheme.onPrimaryContainer) >= 4.5
        )
        assertTrue(
            "$label: secondaryContainer vs onSecondaryContainer < 4.5 (${contrastRatio(scheme.secondaryContainer, scheme.onSecondaryContainer)})",
            contrastRatio(scheme.secondaryContainer, scheme.onSecondaryContainer) >= 4.5
        )
        assertTrue(
            "$label: tertiaryContainer vs onTertiaryContainer < 4.5 (${contrastRatio(scheme.tertiaryContainer, scheme.onTertiaryContainer)})",
            contrastRatio(scheme.tertiaryContainer, scheme.onTertiaryContainer) >= 4.5
        )
        assertTrue(
            "$label: surface vs onSurface < 7.0 (${contrastRatio(scheme.surface, scheme.onSurface)})",
            contrastRatio(scheme.surface, scheme.onSurface) >= 7.0
        )
        assertTrue(
            "$label: background vs onBackground < 7.0 (${contrastRatio(scheme.background, scheme.onBackground)})",
            contrastRatio(scheme.background, scheme.onBackground) >= 7.0
        )
        assertTrue(
            "$label: surface vs onSurfaceVariant < 4.5 (${contrastRatio(scheme.surface, scheme.onSurfaceVariant)})",
            contrastRatio(scheme.surface, scheme.onSurfaceVariant) >= 4.5
        )
        assertTrue(
            "$label: error vs surface < 4.5 (${contrastRatio(scheme.error, scheme.surface)})",
            contrastRatio(scheme.error, scheme.surface) >= 4.5
        )
        assertTrue(
            "$label: error vs onError < 4.5 (${contrastRatio(scheme.error, scheme.onError)})",
            contrastRatio(scheme.error, scheme.onError) >= 4.5
        )
        assertTrue(
            "$label: errorContainer vs onErrorContainer < 4.5 (${contrastRatio(scheme.errorContainer, scheme.onErrorContainer)})",
            contrastRatio(scheme.errorContainer, scheme.onErrorContainer) >= 4.5
        )
        assertTrue(
            "$label: inverseSurface vs inverseOnSurface < 7.0 (${contrastRatio(scheme.inverseSurface, scheme.inverseOnSurface)})",
            contrastRatio(scheme.inverseSurface, scheme.inverseOnSurface) >= 7.0
        )
        assertTrue(
            "$label: inversePrimary vs inverseSurface < 3.0 (${contrastRatio(scheme.inversePrimary, scheme.inverseSurface)})",
            contrastRatio(scheme.inversePrimary, scheme.inverseSurface) >= 3.0
        )
    }
}

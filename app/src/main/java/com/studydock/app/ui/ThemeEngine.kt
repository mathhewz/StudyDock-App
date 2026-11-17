@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

const val STUDYDOCK_SETTINGS_FILE = "studydock_settings"

private const val KEY_LEGACY_DARK_MODE = "dark_mode"
private const val KEY_BASE_SURFACE = "theme_base_surface"
private const val KEY_LAST_LIGHT_SURFACE = "theme_last_light_surface"
private const val KEY_LAST_DARK_SURFACE = "theme_last_dark_surface"
private const val KEY_ACCENT_MODE = "theme_accent_mode"
private const val KEY_ACCENT_PRESET = "theme_accent_preset"
private const val KEY_CUSTOM_ACCENT_ARGB = "theme_custom_accent_argb"
private const val KEY_CARD_STYLE = "theme_card_style"
private const val KEY_CORNER_STYLE = "theme_corner_style"
private const val KEY_UI_DENSITY = "theme_ui_density"
private const val KEY_IMMERSIVE_FOCUS = "theme_immersive_focus"
private const val KEY_BOTTOM_NAV_SLOTS = "theme_bottom_nav_slots"
private const val KEY_HIDDEN_DASHBOARD_WIDGETS = "theme_hidden_dashboard_widgets"
private const val KEY_DASHBOARD_WIDGET_ORDER = "theme_dashboard_widget_order"

private const val THEME_CODE_PREFIX = "SDTHEME:v1:"

// ============================================================================
// Design System Models & Registries
// ============================================================================

enum class StudyBaseSurface(
    val id: String,
    val label: String,
    val description: String,
    val isDark: Boolean,
    val previewBackground: Color,
    val previewSurface: Color,
    val previewOnSurface: Color
) {
    WARM_PAPER(
        id = "warm_paper",
        label = "Papel Quente",
        description = "Claro acolhedor",
        isDark = false,
        previewBackground = Color(0xFFF7F6F2),
        previewSurface = Color(0xFFFFFFFF),
        previewOnSurface = Color(0xFF1E1D22)
    ),
    MINIMAL_LIGHT(
        id = "minimal_light",
        label = "Minimal Claro",
        description = "Neutro limpo",
        isDark = false,
        previewBackground = Color(0xFFF9FAFB),
        previewSurface = Color(0xFFFFFFFF),
        previewOnSurface = Color(0xFF111827)
    ),
    SEPIA_READING(
        id = "sepia_reading",
        label = "Sépia Leitura",
        description = "Conforto ocular",
        isDark = false,
        previewBackground = Color(0xFFF4ECD8),
        previewSurface = Color(0xFFFBF6EA),
        previewOnSurface = Color(0xFF2E2417)
    ),
    OBSIDIAN_DARK(
        id = "obsidian_dark",
        label = "Obsidian Escuro",
        description = "Grafite equilibrado",
        isDark = true,
        previewBackground = Color(0xFF121116),
        previewSurface = Color(0xFF1C1B22),
        previewOnSurface = Color(0xFFECEAF2)
    ),
    NORD_SLATE(
        id = "nord_slate",
        label = "Nord Slate",
        description = "Azul ardósia",
        isDark = true,
        previewBackground = Color(0xFF0F172A),
        previewSurface = Color(0xFF1E293B),
        previewOnSurface = Color(0xFFE2E8F0)
    ),
    AMOLED_BLACK(
        id = "amoled_black",
        label = "AMOLED Preto",
        description = "Preto puro OLED",
        isDark = true,
        previewBackground = Color(0xFF000000),
        previewSurface = Color(0xFF101014),
        previewOnSurface = Color(0xFFF2F2F7)
    );

    companion object {
        fun fromId(id: String?): StudyBaseSurface? =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) }
    }
}

enum class StudyAccentMode(val id: String, val label: String) {
    PRESET("preset", "Paleta"),
    DYNAMIC_YOU("dynamic_you", "Material You"),
    CUSTOM("custom", "Personalizada");

    companion object {
        fun fromId(id: String?): StudyAccentMode =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: PRESET
    }
}

@Immutable
data class StudyAccentPreset(
    val id: String,
    val label: String,
    val seedColor: Color,
    val lightColor: Color,
    val darkColor: Color
)

object StudyAccentPresets {
    val all: List<StudyAccentPreset> = listOf(
        StudyAccentPreset("indigo", "StudyDock Indigo", Color(0xFF6156D9), Color(0xFF584CD0), Color(0xFFABA3FF)),
        StudyAccentPreset("ocean", "Ocean Blue", Color(0xFF2563EB), Color(0xFF2563EB), Color(0xFF7DB4FF)),
        StudyAccentPreset("emerald", "Emerald Forest", Color(0xFF059669), Color(0xFF059669), Color(0xFF5CE0B2)),
        StudyAccentPreset("amber", "Solar Amber", Color(0xFFD97706), Color(0xFFB45309), Color(0xFFFBBF24)),
        StudyAccentPreset("coral", "Coral Rose", Color(0xFFE11D48), Color(0xFFD91B45), Color(0xFFFF8FA3)),
        StudyAccentPreset("amethyst", "Amethyst Purple", Color(0xFF9333EA), Color(0xFF7E22CE), Color(0xFFD2A8FF)),
        StudyAccentPreset("teal", "Deep Teal", Color(0xFF0D9488), Color(0xFF0F766E), Color(0xFF5EEAD4)),
        StudyAccentPreset("crimson", "Editorial Crimson", Color(0xFFB91C1C), Color(0xFFB91C1C), Color(0xFFFCA5A5)),
        StudyAccentPreset("mint", "Mint Fresh", Color(0xFF10B981), Color(0xFF047857), Color(0xFF6EE7B7)),
        StudyAccentPreset("graphite", "Graphite Mono", Color(0xFF4B5563), Color(0xFF374151), Color(0xFFCBD5E1))
    )

    fun byId(id: String?): StudyAccentPreset =
        all.firstOrNull { it.id == id } ?: all.first()
}

enum class StudyCardStyle(val id: String, val label: String, val description: String) {
    TONAL("tonal", "Tonal", "Superfície suave com hierarquia Material 3"),
    OUTLINED("outlined", "Borda", "Fundo limpo com contorno fino estilo Linear"),
    ELEVATED("elevated", "Elevado", "Cartões táteis com sombra e contraste suave");

    companion object {
        fun fromId(id: String?): StudyCardStyle =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: TONAL
    }
}

enum class StudyCornerStyle(
    val id: String,
    val label: String,
    val cardDp: Dp,
    val controlDp: Dp,
    val heroDp: Dp
) {
    SHARP("sharp", "Reto", 10.dp, 8.dp, 14.dp),
    DEFAULT("default", "Padrão", 16.dp, 12.dp, 22.dp),
    ROUNDED("rounded", "Suave", 22.dp, 16.dp, 28.dp);

    companion object {
        fun fromId(id: String?): StudyCornerStyle =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

enum class StudyUiDensity(val id: String, val label: String, val description: String) {
    COMFORTABLE("comfortable", "Confortável", "Respiro generoso para leitura e toque"),
    COMPACT("compact", "Compacta", "Mais conteúdo visível por tela");

    companion object {
        fun fromId(id: String?): StudyUiDensity =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: COMFORTABLE
    }
}

@Immutable
data class StudyNavSlot(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val locked: Boolean = false
)

object StudyNavSlots {
    const val SLOT_HOJE = "Hoje"
    const val SLOT_TIMER = "Timer"
    const val SLOT_AGENDA = "Agenda"
    const val SLOT_ORGANIZAR = "Organizar"
    const val SLOT_TAREFAS = "Tarefas"
    const val SLOT_NOTAS = "Notas"
    const val SLOT_CARTOES = "Cartoes"
    const val SLOT_LIVROS = "Livros"
    const val SLOT_DIARIO = "Diario"
    const val SLOT_MATERIAS = "Materias"
    const val SLOT_METAS = "Metas"
    const val SLOT_ASSISTENTE = "Assistente"
    const val SLOT_MAIS = "Mais"

    val defaultIds: List<String> = listOf(
        SLOT_HOJE,
        SLOT_TIMER,
        SLOT_AGENDA,
        SLOT_ORGANIZAR,
        SLOT_MAIS
    )

    val all: List<StudyNavSlot> = listOf(
        StudyNavSlot(SLOT_HOJE, "Hoje", Icons.Default.Home, locked = true),
        StudyNavSlot(SLOT_TIMER, "Timer", Icons.Default.Timer),
        StudyNavSlot(SLOT_AGENDA, "Agenda", Icons.Default.CalendarMonth),
        StudyNavSlot(SLOT_ORGANIZAR, "Organizar", Icons.Default.CheckCircle),
        StudyNavSlot(SLOT_TAREFAS, "Tarefas", Icons.Default.Checklist),
        StudyNavSlot(SLOT_NOTAS, "Notas", Icons.Default.Description),
        StudyNavSlot(SLOT_CARTOES, "Cartões", Icons.Default.Style),
        StudyNavSlot(SLOT_LIVROS, "Livros", Icons.Default.AutoStories),
        StudyNavSlot(SLOT_DIARIO, "Diário", Icons.Default.EditNote),
        StudyNavSlot(SLOT_MATERIAS, "Matérias", Icons.AutoMirrored.Filled.MenuBook),
        StudyNavSlot(SLOT_METAS, "Metas", Icons.Default.Flag),
        StudyNavSlot(SLOT_ASSISTENTE, "Tutor IA", Icons.Default.AutoAwesome),
        StudyNavSlot(SLOT_MAIS, "Mais", Icons.Default.MoreHoriz, locked = true)
    )

    private val byIdMap: Map<String, StudyNavSlot> = all.associateBy { it.id }

    val swappableMiddleOptions: List<StudyNavSlot> = all.filter { !it.locked }

    fun byId(id: String): StudyNavSlot = byIdMap[id] ?: all.first()

    fun isValid(id: String): Boolean = id in byIdMap

    /**
     * Ensures exactly 5 distinct valid slot IDs while guaranteeing both [SLOT_HOJE] and [SLOT_MAIS]
     * remain reachable so the user can never lock themselves out of Home or More/Theme Studio.
     */
    fun sanitize(rawIds: List<String>): List<String> {
        val validUnique = rawIds.asSequence()
            .map { it.trim() }
            .filter { it in byIdMap }
            .distinct()
            .toList()

        if (validUnique.size == 5 && SLOT_HOJE in validUnique && SLOT_MAIS in validUnique) {
            return validUnique
        }

        val middlePool = (validUnique + defaultIds + swappableMiddleOptions.map { it.id })
            .asSequence()
            .filter { it != SLOT_HOJE && it != SLOT_MAIS && it in byIdMap }
            .distinct()
            .take(3)
            .toList()

        return listOf(SLOT_HOJE) + middlePool + listOf(SLOT_MAIS)
    }
}

@Immutable
data class StudyDashboardWidgetInfo(
    val id: String,
    val label: String,
    val description: String
)

object StudyDashboardWidgets {
    const val HERO_STUDY = "hero_study"
    const val DAILY_GOAL = "daily_goal"
    const val PINNED_GOALS = "pinned_goals"
    const val DIARY_CHECKIN = "diary_checkin"
    const val TODAY_AGENDA = "today_agenda"
    const val QUICK_ACTIONS = "quick_actions"
    const val HABITS_TODAY = "habits_today"
    const val STATS_GRID = "stats_grid"
    const val CHARTS_HEATMAP = "charts_heatmap"
    const val RECENT_SESSIONS = "recent_sessions"

    val all: List<StudyDashboardWidgetInfo> = listOf(
        StudyDashboardWidgetInfo(HERO_STUDY, "Foco & Próximo Passo", "Tempo estudado hoje e botão de início rápido"),
        StudyDashboardWidgetInfo(DAILY_GOAL, "Meta Diária", "Barra de progresso do objetivo diário"),
        StudyDashboardWidgetInfo(PINNED_GOALS, "Metas Fixadas", "Progresso das metas marcadas com alfinete"),
        StudyDashboardWidgetInfo(DIARY_CHECKIN, "Check-in do Diário", "Humor, energia e acesso rápido ao diário"),
        StudyDashboardWidgetInfo(TODAY_AGENDA, "Agenda de Hoje", "Tarefas e compromissos programados para hoje"),
        StudyDashboardWidgetInfo(QUICK_ACTIONS, "Acesso Rápido", "Atalhos de 1 toque para Tarefa, Nota e Revisão"),
        StudyDashboardWidgetInfo(HABITS_TODAY, "Hábitos de Hoje", "Checklist rápido dos hábitos do dia"),
        StudyDashboardWidgetInfo(STATS_GRID, "Resumo Numérico", "Contadores de matérias, sessões, hábitos e cartões"),
        StudyDashboardWidgetInfo(CHARTS_HEATMAP, "Gráficos & Insights", "Barras de 30 dias, heatmap mensal e insights"),
        StudyDashboardWidgetInfo(RECENT_SESSIONS, "Últimas Sessões", "Histórico recente de blocos estudados")
    )

    val defaultOrder: List<String> = all.map { it.id }
    val allIds: Set<String> = defaultOrder.toSet()
    private val infoById: Map<String, StudyDashboardWidgetInfo> = all.associateBy { it.id }

    fun info(id: String): StudyDashboardWidgetInfo? = infoById[id]

    fun sanitizeOrder(order: List<String>): List<String> {
        val cleaned = order.asSequence()
            .map { it.trim() }
            .filter { it in allIds }
            .distinct()
            .toMutableList()
        defaultOrder.forEach { id ->
            if (id !in cleaned) cleaned.add(id)
        }
        return cleaned
    }
}

@Immutable
data class StudyThemeConfig(
    val baseSurface: StudyBaseSurface = StudyBaseSurface.WARM_PAPER,
    val accentMode: StudyAccentMode = StudyAccentMode.PRESET,
    val accentPresetId: String = "indigo",
    val customAccentArgb: Int = 0xFF6156D9.toInt(),
    val cardStyle: StudyCardStyle = StudyCardStyle.TONAL,
    val cornerStyle: StudyCornerStyle = StudyCornerStyle.DEFAULT,
    val density: StudyUiDensity = StudyUiDensity.COMFORTABLE,
    val immersiveFocusTheme: Boolean = false,
    val bottomNavSlots: List<String> = StudyNavSlots.defaultIds,
    val hiddenDashboardWidgets: Set<String> = emptySet(),
    val dashboardWidgetOrder: List<String> = StudyDashboardWidgets.defaultOrder
) {
    fun normalized(): StudyThemeConfig = copy(
        accentPresetId = StudyAccentPresets.byId(accentPresetId).id,
        customAccentArgb = customAccentArgb or 0xFF000000.toInt(),
        bottomNavSlots = StudyNavSlots.sanitize(bottomNavSlots),
        hiddenDashboardWidgets = hiddenDashboardWidgets.filterTo(LinkedHashSet()) { it in StudyDashboardWidgets.allIds },
        dashboardWidgetOrder = StudyDashboardWidgets.sanitizeOrder(dashboardWidgetOrder)
    )

    fun effectiveBaseSurface(isTimerRunning: Boolean = false): StudyBaseSurface =
        if (immersiveFocusTheme && isTimerRunning) StudyBaseSurface.AMOLED_BLACK else baseSurface

    fun isWidgetVisible(widgetId: String): Boolean = widgetId !in hiddenDashboardWidgets
}

// ============================================================================
// Persistence & Migration (studydock_settings)
// ============================================================================

object StudyThemeStore {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadLock = Any()

    @Volatile
    private var cachedConfig: StudyThemeConfig? = null

    private val _configFlow = MutableStateFlow<StudyThemeConfig?>(null)
    val configFlow: StateFlow<StudyThemeConfig?> = _configFlow.asStateFlow()

    @Volatile
    private var listenerRegisteredPrefs: SharedPreferences? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
        if (key == null || key == KEY_LEGACY_DARK_MODE || key.startsWith("theme_")) {
            ioScope.launch {
                val refreshed = readFromPrefs(shared)
                cachedConfig = refreshed
                _configFlow.value = refreshed
            }
        }
    }

    private fun appPrefs(context: Context): SharedPreferences =
        (context.applicationContext ?: context).getSharedPreferences(STUDYDOCK_SETTINGS_FILE, Context.MODE_PRIVATE)

    private fun ensureListenerRegistered(prefs: SharedPreferences) {
        if (listenerRegisteredPrefs === prefs) return
        synchronized(loadLock) {
            if (listenerRegisteredPrefs !== prefs) {
                prefs.registerOnSharedPreferenceChangeListener(prefListener)
                listenerRegisteredPrefs = prefs
            }
        }
    }

    private fun readFromPrefs(prefs: SharedPreferences): StudyThemeConfig {
        val legacyDark = prefs.getBoolean(KEY_LEGACY_DARK_MODE, false)
        val defaultSurface = if (legacyDark) StudyBaseSurface.OBSIDIAN_DARK else StudyBaseSurface.WARM_PAPER
        val baseSurface = StudyBaseSurface.fromId(prefs.getString(KEY_BASE_SURFACE, null)) ?: defaultSurface

        val accentMode = StudyAccentMode.fromId(prefs.getString(KEY_ACCENT_MODE, null))
        val presetId = StudyAccentPresets.byId(prefs.getString(KEY_ACCENT_PRESET, null)).id
        val customArgb = prefs.getInt(KEY_CUSTOM_ACCENT_ARGB, 0xFF6156D9.toInt())
        val cardStyle = StudyCardStyle.fromId(prefs.getString(KEY_CARD_STYLE, null))
        val cornerStyle = StudyCornerStyle.fromId(prefs.getString(KEY_CORNER_STYLE, null))
        val density = StudyUiDensity.fromId(prefs.getString(KEY_UI_DENSITY, null))
        val immersive = prefs.getBoolean(KEY_IMMERSIVE_FOCUS, false)

        val navSlotsRaw = prefs.getString(KEY_BOTTOM_NAV_SLOTS, null)
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?: StudyNavSlots.defaultIds

        val hiddenRaw = prefs.getString(KEY_HIDDEN_DASHBOARD_WIDGETS, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        val orderRaw = prefs.getString(KEY_DASHBOARD_WIDGET_ORDER, null)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: StudyDashboardWidgets.defaultOrder

        return StudyThemeConfig(
            baseSurface = baseSurface,
            accentMode = accentMode,
            accentPresetId = presetId,
            customAccentArgb = customArgb,
            cardStyle = cardStyle,
            cornerStyle = cornerStyle,
            density = density,
            immersiveFocusTheme = immersive,
            bottomNavSlots = navSlotsRaw,
            hiddenDashboardWidgets = hiddenRaw,
            dashboardWidgetOrder = orderRaw
        ).normalized()
    }

    /** Returns the already-cached config without touching disk or SharedPreferences. */
    fun peekCached(): StudyThemeConfig? = cachedConfig

    /** Triggers asynchronous background loading so the Main thread never stalls on SharedPrefs XML parsing. */
    fun preloadAsync(context: Context) {
        val appCtx = context.applicationContext ?: context
        if (cachedConfig != null && listenerRegisteredPrefs != null) return
        ioScope.launch {
            load(appCtx)
        }
    }

    fun load(context: Context): StudyThemeConfig {
        cachedConfig?.let { existing ->
            ensureListenerRegistered(appPrefs(context))
            return existing
        }
        val prefs = appPrefs(context)
        return synchronized(loadLock) {
            cachedConfig?.also { ensureListenerRegistered(prefs) } ?: run {
                ensureListenerRegistered(prefs)
                readFromPrefs(prefs).also { loaded ->
                    cachedConfig = loaded
                    _configFlow.value = loaded
                }
            }
        }
    }

    fun save(context: Context, config: StudyThemeConfig): StudyThemeConfig {
        val normalized = config.normalized()
        val prefs = appPrefs(context)
        ensureListenerRegistered(prefs)
        cachedConfig = normalized
        _configFlow.value = normalized
        prefs.edit().apply {
            putString(KEY_BASE_SURFACE, normalized.baseSurface.id)
            putBoolean(KEY_LEGACY_DARK_MODE, normalized.baseSurface.isDark)
            if (normalized.baseSurface.isDark) {
                putString(KEY_LAST_DARK_SURFACE, normalized.baseSurface.id)
            } else {
                putString(KEY_LAST_LIGHT_SURFACE, normalized.baseSurface.id)
            }
            putString(KEY_ACCENT_MODE, normalized.accentMode.id)
            putString(KEY_ACCENT_PRESET, normalized.accentPresetId)
            putInt(KEY_CUSTOM_ACCENT_ARGB, normalized.customAccentArgb)
            putString(KEY_CARD_STYLE, normalized.cardStyle.id)
            putString(KEY_CORNER_STYLE, normalized.cornerStyle.id)
            putString(KEY_UI_DENSITY, normalized.density.id)
            putBoolean(KEY_IMMERSIVE_FOCUS, normalized.immersiveFocusTheme)
            putString(KEY_BOTTOM_NAV_SLOTS, normalized.bottomNavSlots.joinToString(","))
            putString(KEY_HIDDEN_DASHBOARD_WIDGETS, normalized.hiddenDashboardWidgets.joinToString(","))
            putString(KEY_DASHBOARD_WIDGET_ORDER, normalized.dashboardWidgetOrder.joinToString(","))
        }.apply()
        return normalized
    }

    fun update(context: Context, transform: (StudyThemeConfig) -> StudyThemeConfig): StudyThemeConfig =
        save(context, transform(load(context)))

    fun setDarkMode(context: Context, dark: Boolean): StudyThemeConfig {
        val prefs = appPrefs(context)
        val current = load(context)
        if (current.baseSurface.isDark == dark) {
            return save(context, current)
        }
        val targetSurface = if (dark) {
            StudyBaseSurface.fromId(prefs.getString(KEY_LAST_DARK_SURFACE, null))
                ?.takeIf { it.isDark }
                ?: StudyBaseSurface.OBSIDIAN_DARK
        } else {
            StudyBaseSurface.fromId(prefs.getString(KEY_LAST_LIGHT_SURFACE, null))
                ?.takeIf { !it.isDark }
                ?: StudyBaseSurface.WARM_PAPER
        }
        return save(context, current.copy(baseSurface = targetSurface))
    }

    fun reset(context: Context): StudyThemeConfig = save(context, StudyThemeConfig())
}

@Composable
fun rememberStudyThemeConfig(): StudyThemeConfig {
    val context = LocalContext.current
    LaunchedEffect(context) {
        StudyThemeStore.preloadAsync(context)
    }
    val collected by StudyThemeStore.configFlow.collectAsStateWithLifecycle()
    return collected ?: StudyThemeStore.peekCached() ?: remember { StudyThemeConfig() }
}

// ============================================================================
// Shareable Theme Code (SDTHEME:v1:...) & Hex Helpers
// ============================================================================

fun formatHexColor(argb: Int, includeHash: Boolean = true): String {
    val rgb = argb and 0x00FFFFFF
    val hex = String.format(Locale.ROOT, "%06X", rgb)
    return if (includeHash) "#$hex" else hex
}

fun parseHexColorOrNull(input: String): Int? {
    val cleaned = input.trim().removePrefix("#").uppercase(Locale.ROOT)
    val hex6 = when (cleaned.length) {
        3 -> buildString(6) { cleaned.forEach { ch -> append(ch); append(ch) } }
        6 -> cleaned
        8 -> cleaned.substring(2)
        else -> return null
    }
    val parsed = hex6.toIntOrNull(16) ?: return null
    return parsed or 0xFF000000.toInt()
}

fun encodeThemeCode(config: StudyThemeConfig): String {
    val c = config.normalized()
    val pairs = buildList {
        add("s=${c.baseSurface.id}")
        add("m=${c.accentMode.id}")
        add("p=${c.accentPresetId}")
        add("c=${formatHexColor(c.customAccentArgb, includeHash = false)}")
        add("k=${c.cardStyle.id}")
        add("r=${c.cornerStyle.id}")
        add("d=${c.density.id}")
        add("i=${if (c.immersiveFocusTheme) 1 else 0}")
        if (c.bottomNavSlots != StudyNavSlots.defaultIds) {
            add("n=${c.bottomNavSlots.joinToString(",")}")
        }
        if (c.hiddenDashboardWidgets.isNotEmpty()) {
            add("h=${c.hiddenDashboardWidgets.joinToString(",")}")
        }
        if (c.dashboardWidgetOrder != StudyDashboardWidgets.defaultOrder) {
            add("o=${c.dashboardWidgetOrder.joinToString(",")}")
        }
    }
    val payload = pairs.joinToString("&").toByteArray(Charsets.UTF_8)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    return "$THEME_CODE_PREFIX$encoded"
}

fun decodeThemeCode(code: String): StudyThemeConfig? {
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return null
    val rawBody = when {
        trimmed.startsWith(THEME_CODE_PREFIX, ignoreCase = true) ->
            trimmed.substring(THEME_CODE_PREFIX.length)
        trimmed.startsWith("SDTHEME:", ignoreCase = true) ->
            trimmed.substring("SDTHEME:".length)
        else -> return null
    }.trim()
    if (rawBody.isEmpty()) return null

    val decoded = runCatching {
        val normalizedB64 = rawBody.replace('+', '-').replace('/', '_').trimEnd('=')
        String(Base64.getUrlDecoder().decode(normalizedB64), Charsets.UTF_8)
    }.getOrNull() ?: return null

    val map = decoded.split('&')
        .mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
        }
        .toMap()

    val surface = StudyBaseSurface.fromId(map["s"]) ?: return null
    val accentMode = StudyAccentMode.fromId(map["m"])
    val presetId = StudyAccentPresets.byId(map["p"]).id
    val customArgb = map["c"]?.let(::parseHexColorOrNull) ?: 0xFF6156D9.toInt()
    val cardStyle = StudyCardStyle.fromId(map["k"])
    val cornerStyle = StudyCornerStyle.fromId(map["r"])
    val density = StudyUiDensity.fromId(map["d"])
    val immersive = map["i"] == "1" || map["i"].equals("true", ignoreCase = true)
    val navSlots = map["n"]?.split(',')?.filter { it.isNotBlank() } ?: StudyNavSlots.defaultIds
    val hiddenWidgets = map["h"]?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
    val widgetOrder = map["o"]?.split(',')?.filter { it.isNotBlank() } ?: StudyDashboardWidgets.defaultOrder

    return StudyThemeConfig(
        baseSurface = surface,
        accentMode = accentMode,
        accentPresetId = presetId,
        customAccentArgb = customArgb,
        cardStyle = cardStyle,
        cornerStyle = cornerStyle,
        density = density,
        immersiveFocusTheme = immersive,
        bottomNavSlots = navSlots,
        hiddenDashboardWidgets = hiddenWidgets,
        dashboardWidgetOrder = widgetOrder
    ).normalized()
}

// ============================================================================
// Pure Kotlin Color Math, HSV, & WCAG 2.1 Contrast Solver
// ============================================================================

@Immutable
data class StudyHsv(val h: Float, val s: Float, val v: Float)

fun colorToHsv(color: Color): StudyHsv {
    val r = color.red.coerceIn(0f, 1f)
    val g = color.green.coerceIn(0f, 1f)
    val b = color.blue.coerceIn(0f, 1f)
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC

    val h = when {
        delta < 1e-5f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }

    val s = if (maxC < 1e-5f) 0f else (delta / maxC).coerceIn(0f, 1f)
    val v = maxC.coerceIn(0f, 1f)
    return StudyHsv(h = h, s = s, v = v)
}

fun hsvToColor(h: Float, s: Float, v: Float, alpha: Float = 1f): Color {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val valComp = v.coerceIn(0f, 1f)

    val c = valComp * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = valComp - c

    val (r1, g1, b1) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

fun relativeLuminance(color: Color): Double {
    fun linearize(channel: Float): Double {
        val c = channel.toDouble().coerceIn(0.0, 1.0)
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linearize(color.red) +
        0.7152 * linearize(color.green) +
        0.0722 * linearize(color.blue)
}

fun contrastRatio(a: Color, b: Color): Double {
    val l1 = relativeLuminance(a)
    val l2 = relativeLuminance(b)
    val lighter = max(l1, l2)
    val darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

private val LightOnAccentInk = Color(0xFF141318)

private fun bestOnColor(background: Color): Color {
    val whiteContrast = contrastRatio(background, Color.White)
    val darkContrast = contrastRatio(background, LightOnAccentInk)
    return if (whiteContrast >= darkContrast) Color.White else LightOnAccentInk
}

/**
 * Solves an accessible foreground/accent pair `(accent, onAccent)` so that:
 * 1) `accent` has strong contrast (`>= minSurfaceContrast`, default 4.5:1) against [surface]
 * 2) `onAccent` has strong contrast (`>= minOnContrast`, default 4.5:1) against `accent`.
 */
private fun solveAccentPair(
    seed: Color,
    surface: Color,
    isDark: Boolean,
    minSurfaceContrast: Double = 4.5,
    minOnContrast: Double = 4.5
): Pair<Color, Color> {
    var (h, s, v) = colorToHsv(seed)
    val isNeutral = s < 0.06f
    if (isNeutral) s = 0f

    if (isDark) {
        v = v.coerceIn(0.74f, 0.94f)
        if (!isNeutral) s = s.coerceAtMost(0.68f)
        var candidate = hsvToColor(h, s, v)
        var guard = 0
        while (contrastRatio(candidate, surface) < minSurfaceContrast && guard < 40) {
            if (v < 0.96f) {
                v = (v + 0.04f).coerceAtMost(0.96f)
            } else if (s > 0.04f) {
                s = (s - 0.04f).coerceAtLeast(0f)
            } else {
                break
            }
            candidate = hsvToColor(h, s, v)
            guard++
        }
        var onAccent = bestOnColor(candidate)
        guard = 0
        while (contrastRatio(candidate, onAccent) < minOnContrast && guard < 30) {
            if (onAccent == LightOnAccentInk) {
                if (v < 0.98f) v = (v + 0.03f).coerceAtMost(0.98f)
                else s = (s - 0.04f).coerceAtLeast(0f)
            } else {
                v = (v - 0.03f).coerceAtLeast(0.45f)
            }
            candidate = hsvToColor(h, s, v)
            onAccent = bestOnColor(candidate)
            guard++
        }
        return candidate to onAccent
    } else {
        v = v.coerceIn(0.18f, 0.86f)
        var candidate = hsvToColor(h, s, v)
        var guard = 0
        while (contrastRatio(candidate, surface) < minSurfaceContrast && v > 0.10f && guard < 45) {
            v = (v - 0.03f).coerceAtLeast(0.10f)
            candidate = hsvToColor(h, s, v)
            guard++
        }
        var onAccent = bestOnColor(candidate)
        guard = 0
        while (contrastRatio(candidate, onAccent) < minOnContrast && guard < 30) {
            if (onAccent == Color.White) {
                v = (v - 0.03f).coerceAtLeast(0.08f)
            } else {
                v = (v + 0.03f).coerceAtMost(0.96f)
            }
            candidate = hsvToColor(h, s, v)
            onAccent = bestOnColor(candidate)
            guard++
        }
        return candidate to onAccent
    }
}

private fun solveContainerPair(
    accent: Color,
    surface: Color,
    surfaceContainerHigh: Color,
    onSurfaceFallback: Color,
    isDark: Boolean,
    isSepia: Boolean
): Pair<Color, Color> {
    val container = if (isDark) {
        lerp(surfaceContainerHigh, accent, 0.24f)
    } else {
        lerp(surface, accent, if (isSepia) 0.17f else 0.14f)
    }
    val (h, s, _) = colorToHsv(accent)
    val isNeutral = s < 0.06f

    var onContainer = if (isDark) {
        hsvToColor(h, if (isNeutral) 0f else (s * 0.45f).coerceAtMost(0.55f), 0.96f)
    } else {
        hsvToColor(h, if (isNeutral) 0f else (s * 0.95f).coerceAtMost(0.92f), 0.28f)
    }

    var guard = 0
    while (contrastRatio(onContainer, container) < 7.0 && guard < 35) {
        val hsv = colorToHsv(onContainer)
        onContainer = if (isDark) {
            hsvToColor(hsv.h, (hsv.s - 0.05f).coerceAtLeast(0f), (hsv.v + 0.03f).coerceAtMost(1f))
        } else {
            hsvToColor(hsv.h, hsv.s, (hsv.v - 0.03f).coerceAtLeast(0.05f))
        }
        guard++
    }
    if (contrastRatio(onContainer, container) < 5.5) {
        onContainer = onSurfaceFallback
    }
    return container to onContainer
}

private data class SurfacePaletteTokens(
    val background: Color,
    val surface: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color
)

private fun surfaceTokensFor(surface: StudyBaseSurface): SurfacePaletteTokens = when (surface) {
    StudyBaseSurface.WARM_PAPER -> SurfacePaletteTokens(
        background = Color(0xFFF7F6F2),
        surface = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFECEAE3),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF9F8F4),
        surfaceContainer = Color(0xFFF2F0EA),
        surfaceContainerHigh = Color(0xFFEAE7DF),
        surfaceContainerHighest = Color(0xFFE2DFD5),
        onBackground = Color(0xFF1E1D22),
        onSurface = Color(0xFF1E1D22),
        onSurfaceVariant = Color(0xFF5E5C64),
        outline = Color(0xFF8C8992),
        outlineVariant = Color(0xFFDBD8CF),
        inverseSurface = Color(0xFF2E2D33),
        inverseOnSurface = Color(0xFFF4F3EE)
    )
    StudyBaseSurface.MINIMAL_LIGHT -> SurfacePaletteTokens(
        background = Color(0xFFF9FAFB),
        surface = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFEBEEF2),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F6F8),
        surfaceContainer = Color(0xFFEEF1F5),
        surfaceContainerHigh = Color(0xFFE5E9EF),
        surfaceContainerHighest = Color(0xFFDCE1E8),
        onBackground = Color(0xFF111827),
        onSurface = Color(0xFF111827),
        onSurfaceVariant = Color(0xFF525C6A),
        outline = Color(0xFF8B95A5),
        outlineVariant = Color(0xFFDFE3EA),
        inverseSurface = Color(0xFF1F2937),
        inverseOnSurface = Color(0xFFF9FAFB)
    )
    StudyBaseSurface.SEPIA_READING -> SurfacePaletteTokens(
        background = Color(0xFFF4ECD8),
        surface = Color(0xFFFBF6EA),
        surfaceDim = Color(0xFFE6DAC0),
        surfaceBright = Color(0xFFFDF9EF),
        surfaceContainerLowest = Color(0xFFFDF9EF),
        surfaceContainerLow = Color(0xFFF8F1E1),
        surfaceContainer = Color(0xFFEFE5CE),
        surfaceContainerHigh = Color(0xFFE7DBC0),
        surfaceContainerHighest = Color(0xFFDFD0B2),
        onBackground = Color(0xFF2E2417),
        onSurface = Color(0xFF2E2417),
        onSurfaceVariant = Color(0xFF685947),
        outline = Color(0xFF968570),
        outlineVariant = Color(0xFFD9CCB2),
        inverseSurface = Color(0xFF3A2E20),
        inverseOnSurface = Color(0xFFFBF6EA)
    )
    StudyBaseSurface.OBSIDIAN_DARK -> SurfacePaletteTokens(
        background = Color(0xFF121116),
        surface = Color(0xFF1C1B22),
        surfaceDim = Color(0xFF0D0C10),
        surfaceBright = Color(0xFF2A2932),
        surfaceContainerLowest = Color(0xFF0D0C10),
        surfaceContainerLow = Color(0xFF16151C),
        surfaceContainer = Color(0xFF1E1D25),
        surfaceContainerHigh = Color(0xFF272630),
        surfaceContainerHighest = Color(0xFF31303C),
        onBackground = Color(0xFFECEAF2),
        onSurface = Color(0xFFECEAF2),
        onSurfaceVariant = Color(0xFFA8A4B3),
        outline = Color(0xFF6E6A7A),
        outlineVariant = Color(0xFF2E2C38),
        inverseSurface = Color(0xFFECEAF2),
        inverseOnSurface = Color(0xFF18171E)
    )
    StudyBaseSurface.NORD_SLATE -> SurfacePaletteTokens(
        background = Color(0xFF0F172A),
        surface = Color(0xFF1E293B),
        surfaceDim = Color(0xFF0A101F),
        surfaceBright = Color(0xFF283549),
        surfaceContainerLowest = Color(0xFF0B1120),
        surfaceContainerLow = Color(0xFF162033),
        surfaceContainer = Color(0xFF1E293B),
        surfaceContainerHigh = Color(0xFF263348),
        surfaceContainerHighest = Color(0xFF314058),
        onBackground = Color(0xFFE2E8F0),
        onSurface = Color(0xFFE2E8F0),
        onSurfaceVariant = Color(0xFF94A3B8),
        outline = Color(0xFF64748B),
        outlineVariant = Color(0xFF334155),
        inverseSurface = Color(0xFFE2E8F0),
        inverseOnSurface = Color(0xFF0F172A)
    )
    StudyBaseSurface.AMOLED_BLACK -> SurfacePaletteTokens(
        background = Color(0xFF000000),
        surface = Color(0xFF101014),
        surfaceDim = Color(0xFF000000),
        surfaceBright = Color(0xFF1D1D24),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0A0D),
        surfaceContainer = Color(0xFF121217),
        surfaceContainerHigh = Color(0xFF1A1A22),
        surfaceContainerHighest = Color(0xFF24242E),
        onBackground = Color(0xFFF2F2F7),
        onSurface = Color(0xFFF2F2F7),
        onSurfaceVariant = Color(0xFF9898A6),
        outline = Color(0xFF5E5E6E),
        outlineVariant = Color(0xFF24242C),
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Color(0xFF0E0E12)
    )
}

/**
 * Generates a complete 36-token Material 3 [ColorScheme] with guaranteed WCAG contrast across all
 * 6 base surfaces, 10 curated presets, Material You (API 31+), and arbitrary custom hex/HSV colors.
 */
fun buildStudyColorScheme(
    config: StudyThemeConfig,
    context: Context? = null,
    isTimerRunning: Boolean = false
): ColorScheme {
    val effectiveSurface = config.effectiveBaseSurface(isTimerRunning)
    val isDark = effectiveSurface.isDark
    val isSepia = effectiveSurface == StudyBaseSurface.SEPIA_READING
    val neutrals = surfaceTokensFor(effectiveSurface)

    val errorColor: Color
    val onErrorColor: Color
    val errorContainerColor: Color
    val onErrorContainerColor: Color
    if (isDark) {
        errorColor = Color(0xFFFF8A80)
        onErrorColor = Color(0xFF3B0909)
        errorContainerColor = lerp(neutrals.surfaceContainerHigh, Color(0xFFEF4444), 0.24f)
        onErrorContainerColor = Color(0xFFFFDAD6)
    } else {
        errorColor = Color(0xFFC62828)
        onErrorColor = Color.White
        errorContainerColor = lerp(neutrals.surface, Color(0xFFC62828), 0.14f)
        onErrorContainerColor = Color(0xFF5F1010)
    }
    val scrimColor = Color.Black.copy(alpha = if (isDark) 0.64f else 0.36f)

    // Material You (Android 12+ / API 31+): blend system dynamic accent roles cleanly onto user's base surface.
    if (config.accentMode == StudyAccentMode.DYNAMIC_YOU &&
        context != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ) {
        val sys = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        val (primary, onPrimary) = if (
            contrastRatio(sys.primary, neutrals.surface) >= 4.0 &&
            contrastRatio(sys.primary, sys.onPrimary) >= 4.5
        ) {
            sys.primary to sys.onPrimary
        } else {
            solveAccentPair(sys.primary, neutrals.surface, isDark)
        }
        val (primaryContainer, onPrimaryContainer) = solveContainerPair(
            accent = primary,
            surface = neutrals.surface,
            surfaceContainerHigh = neutrals.surfaceContainerHigh,
            onSurfaceFallback = neutrals.onSurface,
            isDark = isDark,
            isSepia = isSepia
        )
        val (secondary, onSecondary) = solveAccentPair(sys.secondary, neutrals.surface, isDark)
        val (secondaryContainer, onSecondaryContainer) = solveContainerPair(
            accent = secondary,
            surface = neutrals.surface,
            surfaceContainerHigh = neutrals.surfaceContainerHigh,
            onSurfaceFallback = neutrals.onSurface,
            isDark = isDark,
            isSepia = isSepia
        )
        val (tertiary, onTertiary) = solveAccentPair(sys.tertiary, neutrals.surface, isDark)
        val (tertiaryContainer, onTertiaryContainer) = solveContainerPair(
            accent = tertiary,
            surface = neutrals.surface,
            surfaceContainerHigh = neutrals.surfaceContainerHigh,
            onSurfaceFallback = neutrals.onSurface,
            isDark = isDark,
            isSepia = isSepia
        )
        val (inversePrimary, _) = solveAccentPair(
            seed = primary,
            surface = neutrals.inverseSurface,
            isDark = !isDark,
            minSurfaceContrast = 3.5,
            minOnContrast = 4.5
        )
        return ColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = neutrals.background,
            onBackground = neutrals.onBackground,
            surface = neutrals.surface,
            onSurface = neutrals.onSurface,
            surfaceVariant = neutrals.surfaceContainerHigh,
            onSurfaceVariant = neutrals.onSurfaceVariant,
            surfaceTint = primary,
            inverseSurface = neutrals.inverseSurface,
            inverseOnSurface = neutrals.inverseOnSurface,
            error = errorColor,
            onError = onErrorColor,
            errorContainer = errorContainerColor,
            onErrorContainer = onErrorContainerColor,
            outline = neutrals.outline,
            outlineVariant = neutrals.outlineVariant,
            scrim = scrimColor,
            surfaceBright = neutrals.surfaceBright,
            surfaceDim = neutrals.surfaceDim,
            surfaceContainer = neutrals.surfaceContainer,
            surfaceContainerHigh = neutrals.surfaceContainerHigh,
            surfaceContainerHighest = neutrals.surfaceContainerHighest,
            surfaceContainerLow = neutrals.surfaceContainerLow,
            surfaceContainerLowest = neutrals.surfaceContainerLowest
        )
    }

    val preset = StudyAccentPresets.byId(config.accentPresetId)
    val rawPrimarySeed = when (config.accentMode) {
        StudyAccentMode.CUSTOM -> Color(config.customAccentArgb or 0xFF000000.toInt())
        StudyAccentMode.PRESET, StudyAccentMode.DYNAMIC_YOU ->
            if (isDark) preset.darkColor else preset.lightColor
    }

    val (primary, onPrimary) = if (
        config.accentMode != StudyAccentMode.CUSTOM &&
        contrastRatio(rawPrimarySeed, neutrals.surface) >= 4.5 &&
        contrastRatio(rawPrimarySeed, bestOnColor(rawPrimarySeed)) >= 4.5
    ) {
        rawPrimarySeed to bestOnColor(rawPrimarySeed)
    } else {
        solveAccentPair(rawPrimarySeed, neutrals.surface, isDark)
    }

    val primaryHsv = colorToHsv(
        if (config.accentMode == StudyAccentMode.CUSTOM) rawPrimarySeed else preset.seedColor
    )
    val isNeutralAccent = primaryHsv.s < 0.08f

    val secondarySeed = if (isNeutralAccent) {
        hsvToColor(215f, 0.14f, if (isDark) 0.78f else 0.36f)
    } else {
        hsvToColor(
            h = (primaryHsv.h + 24f) % 360f,
            s = (primaryHsv.s * 0.78f).coerceIn(0.18f, 0.85f),
            v = if (isDark) 0.84f else 0.56f
        )
    }
    val tertiarySeed = if (isNeutralAccent) {
        hsvToColor(32f, 0.24f, if (isDark) 0.80f else 0.42f)
    } else {
        hsvToColor(
            h = (primaryHsv.h + 58f) % 360f,
            s = (primaryHsv.s * 0.82f).coerceIn(0.20f, 0.85f),
            v = if (isDark) 0.84f else 0.54f
        )
    }

    val (secondary, onSecondary) = solveAccentPair(secondarySeed, neutrals.surface, isDark)
    val (tertiary, onTertiary) = solveAccentPair(tertiarySeed, neutrals.surface, isDark)

    val (primaryContainer, onPrimaryContainer) = solveContainerPair(
        accent = primary,
        surface = neutrals.surface,
        surfaceContainerHigh = neutrals.surfaceContainerHigh,
        onSurfaceFallback = neutrals.onSurface,
        isDark = isDark,
        isSepia = isSepia
    )
    val (secondaryContainer, onSecondaryContainer) = solveContainerPair(
        accent = secondary,
        surface = neutrals.surface,
        surfaceContainerHigh = neutrals.surfaceContainerHigh,
        onSurfaceFallback = neutrals.onSurface,
        isDark = isDark,
        isSepia = isSepia
    )
    val (tertiaryContainer, onTertiaryContainer) = solveContainerPair(
        accent = tertiary,
        surface = neutrals.surface,
        surfaceContainerHigh = neutrals.surfaceContainerHigh,
        onSurfaceFallback = neutrals.onSurface,
        isDark = isDark,
        isSepia = isSepia
    )

    val (inversePrimary, _) = solveAccentPair(
        seed = rawPrimarySeed,
        surface = neutrals.inverseSurface,
        isDark = !isDark,
        minSurfaceContrast = 3.5,
        minOnContrast = 4.5
    )

    return ColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = neutrals.background,
        onBackground = neutrals.onBackground,
        surface = neutrals.surface,
        onSurface = neutrals.onSurface,
        surfaceVariant = neutrals.surfaceContainerHigh,
        onSurfaceVariant = neutrals.onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = neutrals.inverseSurface,
        inverseOnSurface = neutrals.inverseOnSurface,
        error = errorColor,
        onError = onErrorColor,
        errorContainer = errorContainerColor,
        onErrorContainer = onErrorContainerColor,
        outline = neutrals.outline,
        outlineVariant = neutrals.outlineVariant,
        scrim = scrimColor,
        surfaceBright = neutrals.surfaceBright,
        surfaceDim = neutrals.surfaceDim,
        surfaceContainer = neutrals.surfaceContainer,
        surfaceContainerHigh = neutrals.surfaceContainerHigh,
        surfaceContainerHighest = neutrals.surfaceContainerHighest,
        surfaceContainerLow = neutrals.surfaceContainerLow,
        surfaceContainerLowest = neutrals.surfaceContainerLowest
    )
}

fun buildStudyShapes(cornerStyle: StudyCornerStyle): Shapes = Shapes(
    extraSmall = RoundedCornerShape((cornerStyle.controlDp.value * 0.65f).roundToInt().dp),
    small = RoundedCornerShape(cornerStyle.controlDp),
    medium = RoundedCornerShape(cornerStyle.cardDp),
    large = RoundedCornerShape(cornerStyle.heroDp),
    extraLarge = RoundedCornerShape(cornerStyle.heroDp + 4.dp)
)

// ============================================================================
// Semantic Colors & CompositionLocals
// ============================================================================

@Immutable
data class StudySemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val streak: Color,
    val goldStar: Color
)

fun buildStudySemanticColors(
    baseSurface: StudyBaseSurface,
    surfaceContainer: Color = baseSurface.previewSurface,
    surface: Color = baseSurface.previewSurface
): StudySemanticColors {
    return if (baseSurface.isDark) {
        StudySemanticColors(
            success = Color(0xFF4ADE80),
            onSuccess = Color(0xFF052E16),
            successContainer = lerp(surfaceContainer, Color(0xFF22C55E), 0.22f),
            onSuccessContainer = Color(0xFFBBF7D0),
            warning = Color(0xFFFBBF24),
            onWarning = Color(0xFF422006),
            warningContainer = lerp(surfaceContainer, Color(0xFFF59E0B), 0.22f),
            onWarningContainer = Color(0xFFFEF08A),
            info = Color(0xFF60A5FA),
            onInfo = Color(0xFF0F2347),
            infoContainer = lerp(surfaceContainer, Color(0xFF3B82F6), 0.22f),
            onInfoContainer = Color(0xFFDBEAFE),
            streak = Color(0xFFFB923C),
            goldStar = Color(0xFFFACC15)
        )
    } else {
        StudySemanticColors(
            success = Color(0xFF15803D),
            onSuccess = Color.White,
            successContainer = lerp(surface, Color(0xFF15803D), 0.13f),
            onSuccessContainer = Color(0xFF0A401E),
            warning = Color(0xFFB45309),
            onWarning = Color.White,
            warningContainer = lerp(surface, Color(0xFFD97706), 0.14f),
            onWarningContainer = Color(0xFF5A2904),
            info = Color(0xFF1D4ED8),
            onInfo = Color.White,
            infoContainer = lerp(surface, Color(0xFF2563EB), 0.13f),
            onInfoContainer = Color(0xFF0F2973),
            streak = Color(0xFFEA580C),
            goldStar = Color(0xFFCA8A04)
        )
    }
}

val LocalStudyThemeConfig = staticCompositionLocalOf { StudyThemeConfig() }
val LocalStudySemanticColors = staticCompositionLocalOf { buildStudySemanticColors(StudyBaseSurface.WARM_PAPER) }
val LocalGlobalSearchOpener = staticCompositionLocalOf<() -> Unit> { {} }
val LocalGlobalSearchWithQueryOpener = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalThemeStudioOpener = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun animateStudyColorScheme(target: ColorScheme, durationMs: Int = 320): ColorScheme {
    val spec = tween<Color>(durationMillis = durationMs)
    return ColorScheme(
        primary = animateColorAsState(target.primary, spec, label = "primary").value,
        onPrimary = animateColorAsState(target.onPrimary, spec, label = "onPrimary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "primaryContainer").value,
        onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer").value,
        inversePrimary = animateColorAsState(target.inversePrimary, spec, label = "inversePrimary").value,
        secondary = animateColorAsState(target.secondary, spec, label = "secondary").value,
        onSecondary = animateColorAsState(target.onSecondary, spec, label = "onSecondary").value,
        secondaryContainer = animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer").value,
        onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer").value,
        tertiary = animateColorAsState(target.tertiary, spec, label = "tertiary").value,
        onTertiary = animateColorAsState(target.onTertiary, spec, label = "onTertiary").value,
        tertiaryContainer = animateColorAsState(target.tertiaryContainer, spec, label = "tertiaryContainer").value,
        onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, spec, label = "onTertiaryContainer").value,
        background = animateColorAsState(target.background, spec, label = "background").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "onBackground").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "onSurface").value,
        surfaceVariant = animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant").value,
        onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant").value,
        surfaceTint = animateColorAsState(target.surfaceTint, spec, label = "surfaceTint").value,
        inverseSurface = animateColorAsState(target.inverseSurface, spec, label = "inverseSurface").value,
        inverseOnSurface = animateColorAsState(target.inverseOnSurface, spec, label = "inverseOnSurface").value,
        error = animateColorAsState(target.error, spec, label = "error").value,
        onError = animateColorAsState(target.onError, spec, label = "onError").value,
        errorContainer = animateColorAsState(target.errorContainer, spec, label = "errorContainer").value,
        onErrorContainer = animateColorAsState(target.onErrorContainer, spec, label = "onErrorContainer").value,
        outline = animateColorAsState(target.outline, spec, label = "outline").value,
        outlineVariant = animateColorAsState(target.outlineVariant, spec, label = "outlineVariant").value,
        scrim = animateColorAsState(target.scrim, spec, label = "scrim").value,
        surfaceBright = animateColorAsState(target.surfaceBright, spec, label = "surfaceBright").value,
        surfaceDim = animateColorAsState(target.surfaceDim, spec, label = "surfaceDim").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh").value,
        surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest").value,
        surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow").value,
        surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, spec, label = "surfaceContainerLowest").value
    )
}

/**
 * Root theme provider wiring [MaterialTheme], [LocalStudyThemeConfig], [LocalStudySemanticColors],
 * [LocalGlobalSearchOpener], [LocalGlobalSearchWithQueryOpener], and [LocalThemeStudioOpener].
 */
@Composable
fun StudyDockTheme(
    config: StudyThemeConfig = rememberStudyThemeConfig(),
    isTimerRunning: Boolean = false,
    onOpenGlobalSearch: (String) -> Unit = {},
    onOpenThemeStudio: () -> Unit = {},
    animateColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val effectiveSurface = config.effectiveBaseSurface(isTimerRunning)
    val rawScheme = remember(config, context, isTimerRunning) {
        buildStudyColorScheme(config = config, context = context, isTimerRunning = isTimerRunning)
    }
    val scheme = if (animateColors) animateStudyColorScheme(rawScheme) else rawScheme
    val semanticColors = remember(effectiveSurface, scheme.surfaceContainer, scheme.surface) {
        buildStudySemanticColors(
            baseSurface = effectiveSurface,
            surfaceContainer = scheme.surfaceContainer,
            surface = scheme.surface
        )
    }
    val shapes = remember(config.cornerStyle) { buildStudyShapes(config.cornerStyle) }
    val zeroArgSearch = remember(onOpenGlobalSearch) { { onOpenGlobalSearch("") } }

    CompositionLocalProvider(
        LocalStudyThemeConfig provides config,
        LocalStudySemanticColors provides semanticColors,
        LocalGlobalSearchOpener provides zeroArgSearch,
        LocalGlobalSearchWithQueryOpener provides onOpenGlobalSearch,
        LocalThemeStudioOpener provides onOpenThemeStudio
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = shapes,
            content = content
        )
    }
}

// ============================================================================
// Density Tokens & Card Styling Helpers
// ============================================================================

object StudyTokens {
    fun cardPadding(density: StudyUiDensity): Dp =
        if (density == StudyUiDensity.COMPACT) 12.dp else 16.dp

    fun sectionSpacing(density: StudyUiDensity): Dp =
        if (density == StudyUiDensity.COMPACT) 8.dp else 12.dp

    fun itemSpacing(density: StudyUiDensity): Dp =
        if (density == StudyUiDensity.COMPACT) 6.dp else 8.dp

    fun headerHeight(density: StudyUiDensity): Dp =
        if (density == StudyUiDensity.COMPACT) 46.dp else 54.dp

    fun searchBarHeight(density: StudyUiDensity): Dp =
        if (density == StudyUiDensity.COMPACT) 42.dp else 46.dp

    @Composable
    fun cardPadding(): Dp = cardPadding(LocalStudyThemeConfig.current.density)

    @Composable
    fun sectionSpacing(): Dp = sectionSpacing(LocalStudyThemeConfig.current.density)

    @Composable
    fun itemSpacing(): Dp = itemSpacing(LocalStudyThemeConfig.current.density)

    @Composable
    fun headerHeight(): Dp = headerHeight(LocalStudyThemeConfig.current.density)

    @Composable
    fun searchBarHeight(): Dp = searchBarHeight(LocalStudyThemeConfig.current.density)
}

fun studyHorizontalScreenPadding(
    maxWidthDp: Dp,
    density: StudyUiDensity
): Dp = when {
    maxWidthDp < 361.dp -> if (density == StudyUiDensity.COMPACT) 12.dp else 14.dp
    maxWidthDp < 600.dp -> if (density == StudyUiDensity.COMPACT) 12.dp else 16.dp
    else -> if (density == StudyUiDensity.COMPACT) 20.dp else 24.dp
}

@Composable
fun studyHorizontalScreenPadding(maxWidthDp: Dp): Dp =
    studyHorizontalScreenPadding(maxWidthDp, LocalStudyThemeConfig.current.density)

@Composable
fun studyCardShape(
    cornerStyle: StudyCornerStyle = LocalStudyThemeConfig.current.cornerStyle,
    isHero: Boolean = false
): Shape = RoundedCornerShape(if (isHero) cornerStyle.heroDp else cornerStyle.cardDp)

@Composable
fun studyControlShape(
    cornerStyle: StudyCornerStyle = LocalStudyThemeConfig.current.cornerStyle
): Shape = RoundedCornerShape(cornerStyle.controlDp)

@Composable
fun studyCardColors(
    cardStyle: StudyCardStyle = LocalStudyThemeConfig.current.cardStyle,
    emphasized: Boolean = false,
    containerColorOverride: Color? = null
): CardColors {
    if (containerColorOverride != null) {
        return CardDefaults.cardColors(containerColor = containerColorOverride)
    }
    if (emphasized) {
        return CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
    val config = LocalStudyThemeConfig.current
    val isDark = config.baseSurface.isDark
    val container = when (cardStyle) {
        StudyCardStyle.TONAL -> MaterialTheme.colorScheme.surfaceContainer
        StudyCardStyle.OUTLINED -> MaterialTheme.colorScheme.surface
        StudyCardStyle.ELEVATED ->
            if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainerLowest
    }
    return CardDefaults.cardColors(
        containerColor = container,
        contentColor = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
fun studyCardBorder(
    config: StudyThemeConfig = LocalStudyThemeConfig.current,
    highlighted: Boolean = false,
    isTimerRunning: Boolean = false
): BorderStroke? = when {
    highlighted -> BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
    config.cardStyle == StudyCardStyle.OUTLINED ->
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    config.effectiveBaseSurface(isTimerRunning) == StudyBaseSurface.AMOLED_BLACK ->
        BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
    config.cardStyle == StudyCardStyle.ELEVATED ->
        BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    else -> null
}

@Composable
fun studyCardElevation(
    cardStyle: StudyCardStyle = LocalStudyThemeConfig.current.cardStyle,
    isDark: Boolean = LocalStudyThemeConfig.current.baseSurface.isDark
): CardElevation = when (cardStyle) {
    StudyCardStyle.ELEVATED -> CardDefaults.cardElevation(
        defaultElevation = if (isDark) 1.dp else 3.dp,
        pressedElevation = if (isDark) 2.dp else 4.dp
    )
    StudyCardStyle.TONAL, StudyCardStyle.OUTLINED ->
        CardDefaults.cardElevation(defaultElevation = 0.dp)
}

@Composable
fun StudyCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
    highlighted: Boolean = false,
    isHero: Boolean = false,
    containerColorOverride: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val config = LocalStudyThemeConfig.current
    val shape = studyCardShape(config.cornerStyle, isHero = isHero)
    val colors = studyCardColors(
        cardStyle = config.cardStyle,
        emphasized = emphasized,
        containerColorOverride = containerColorOverride
    )
    val border = studyCardBorder(config = config, highlighted = highlighted)
    val elevation = studyCardElevation(config.cardStyle, config.baseSurface.isDark)

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
            elevation = elevation,
            content = content
        )
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            border = border,
            elevation = elevation,
            content = content
        )
    }
}

// ============================================================================
// Shared Chrome Primitives: StudyPageHeader, SlimGlobalSearchPill, Local Bridge
// ============================================================================

/**
 * Unified compact screen header with optional back navigation, ellipsis-safe title/subtitle,
 * automatic compact global search button via [LocalGlobalSearchOpener], and custom trailing actions.
 */
@Composable
fun StudyPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    showGlobalSearch: Boolean = true,
    searchContentDescription: String = "Busca global",
    actions: @Composable RowScope.() -> Unit = {}
) {
    val config = LocalStudyThemeConfig.current
    val density = config.density
    val openGlobalSearch = LocalGlobalSearchOpener.current
    val minHeight = StudyTokens.headerHeight(density)
    val topPad = if (density == StudyUiDensity.COMPACT) 6.dp else 10.dp
    val bottomPad = if (density == StudyUiDensity.COMPACT) 4.dp else 6.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .padding(top = topPad, bottom = bottomPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Voltar"
                )
            }
            Spacer(Modifier.width(2.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (density == StudyUiDensity.COMPACT) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (showGlobalSearch) {
                IconButton(
                    onClick = openGlobalSearch,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = searchContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            actions()
        }
    }
}

/**
 * Slim 44dp Spotlight search trigger designed exclusively for top-level entry hubs
 * (`Hoje`, `Organizar`, `Mais`) to replace oversized persistent bars.
 */
@Composable
fun SlimGlobalSearchPill(
    modifier: Modifier = Modifier,
    dueCardsCount: Int = 0,
    showKeyboardHint: Boolean = false,
    onOpenPalette: () -> Unit = LocalGlobalSearchOpener.current,
    onQuickCards: (() -> Unit)? = null
) {
    val config = LocalStudyThemeConfig.current
    val height = StudyTokens.searchBarHeight(config.density)
    val shape = RoundedCornerShape(50)

    Surface(
        onClick = onOpenPalette,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp)
            .defaultMinSize(minHeight = height),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (config.baseSurface == StudyBaseSurface.AMOLED_BLACK ||
            config.cardStyle == StudyCardStyle.OUTLINED
        ) {
            BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Busca global",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Buscar ou > comandos...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            if (dueCardsCount > 0 && onQuickCards != null) {
                Surface(
                    onClick = onQuickCards,
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "🃏 $dueCardsCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            } else if (showKeyboardHint) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "⌘K",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact inline search field with a built-in `"Tudo ↗"` bridge pill that opens Global Search
 * pre-filled with the current local query via [LocalGlobalSearchWithQueryOpener].
 */
@Composable
fun StudyLocalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearchGlobal: ((String) -> Unit)? = null
) {
    val config = LocalStudyThemeConfig.current
    val globalWithQuery = onSearchGlobal ?: LocalGlobalSearchWithQueryOpener.current
    val shape = studyControlShape(config.cornerStyle)

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = shape,
        placeholder = {
            Text(
                text = placeholder,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Surface(
                        onClick = { globalWithQuery(query.trim()) },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "Tudo ↗",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Limpar busca",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (query.isNotBlank()) globalWithQuery(query.trim())
        })
    )
}

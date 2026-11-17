@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.audio.FocusAudioService
import com.studydock.app.data.AudioPlaybackSnapshot
import com.studydock.app.data.AudioTrackItem
import com.studydock.app.data.CustomSoundMix
import com.studydock.app.data.FocusAudioStore
import com.studydock.app.data.MusicPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private object FocusAudioUiHub {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _snapshot = MutableStateFlow(AudioPlaybackSnapshot())
    val snapshot: StateFlow<AudioPlaybackSnapshot> = _snapshot.asStateFlow()

    private val _playlists = MutableStateFlow<List<MusicPlaylist>>(emptyList())
    val playlists: StateFlow<List<MusicPlaylist>> = _playlists.asStateFlow()

    private val _customMixes = MutableStateFlow<List<CustomSoundMix>>(emptyList())
    val customMixes: StateFlow<List<CustomSoundMix>> = _customMixes.asStateFlow()

    @Volatile
    private var registeredPrefs: SharedPreferences? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
        ioScope.launch {
            when (key) {
                null -> reloadAll(shared)
                "music_playlists_v2", "music_playlist" -> {
                    _playlists.value = FocusAudioStore.readPlaylists(shared)
                    _snapshot.value = FocusAudioStore.readSnapshot(shared)
                }
                "custom_sound_mixes_v1" -> {
                    _customMixes.value = FocusAudioStore.readCustomMixes(shared)
                }
                else -> {
                    _snapshot.value = FocusAudioStore.readSnapshot(shared)
                }
            }
        }
    }

    private fun reloadAll(prefs: SharedPreferences) {
        _snapshot.value = FocusAudioStore.readSnapshot(prefs)
        _playlists.value = FocusAudioStore.readPlaylists(prefs)
        _customMixes.value = FocusAudioStore.readCustomMixes(prefs)
    }

    fun ensureStarted(context: Context) {
        val prefs = FocusAudioStore.prefs(context)
        if (registeredPrefs === prefs) return
        synchronized(lock) {
            if (registeredPrefs !== prefs) {
                prefs.registerOnSharedPreferenceChangeListener(prefListener)
                registeredPrefs = prefs
                ioScope.launch { reloadAll(prefs) }
            }
        }
    }

    fun refreshSnapshotAsync(prefs: SharedPreferences) {
        ioScope.launch { _snapshot.value = FocusAudioStore.readSnapshot(prefs) }
    }

    fun refreshPlaylistsAndSnapshotAsync(prefs: SharedPreferences) {
        ioScope.launch {
            _playlists.value = FocusAudioStore.readPlaylists(prefs)
            _snapshot.value = FocusAudioStore.readSnapshot(prefs)
        }
    }

    fun updatePlaylists(updated: List<MusicPlaylist>) {
        _playlists.value = updated
    }

    fun updateCustomMixes(updated: List<CustomSoundMix>) {
        _customMixes.value = updated
    }
}

private enum class SoundCategoryFilter(val label: String) {
    ALL("Todos"),
    FOCUS_LOFI("Foco & Lo-fi"),
    NATURE_FOREST("Natureza & Floresta"),
    BIRDS("Pássaros"),
    RAIN_WATER("Chuva & Água"),
    AMBIENT("Ambientes"),
    NOISE("Ruídos"),
    BINAURAL("Binaural")
}

private enum class PlayerTab(val label: String, val icon: ImageVector) {
    SOUNDS("Sons", Icons.Default.GraphicEq),
    MIXES("Mixes & Binaural", Icons.Default.Tune),
    MUSIC("Músicas", Icons.Default.LibraryMusic)
}

private data class SoundCatalogOption(
    val key: String,
    val label: String,
    val description: String,
    val category: SoundCategoryFilter,
    val icon: ImageVector,
    val defaultVolume: Float,
    val isBinaural: Boolean = false
)

private data class BuiltInSoundPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val volumes: Map<String, Float>,
    val isBinaural: Boolean = false,
    val badge: String? = null
)

private val soundCatalog: List<SoundCatalogOption> = listOf(
    SoundCatalogOption(
        key = "lofi",
        label = "Lo-fi Quente",
        description = "Acordes Rhodes suaves, baixo quente e vinil macio",
        category = SoundCategoryFilter.FOCUS_LOFI,
        icon = Icons.Default.MusicNote,
        defaultVolume = 0.24f
    ),
    SoundCatalogOption(
        key = "soft_piano",
        label = "Piano Ambiente",
        description = "Notas lentas de piano felt com ressonância calma",
        category = SoundCategoryFilter.FOCUS_LOFI,
        icon = Icons.Default.Piano,
        defaultVolume = 0.26f
    ),
    SoundCatalogOption(
        key = "wind_chimes",
        label = "Sinos ao Vento",
        description = "Sinos zen pentatônicos tocados por brisa leve",
        category = SoundCategoryFilter.FOCUS_LOFI,
        icon = Icons.Default.Air,
        defaultVolume = 0.24f
    ),
    SoundCatalogOption(
        key = "ambient_pad",
        label = "Pad Cósmico",
        description = "Sintetizador espacial analógico com evolução lenta",
        category = SoundCategoryFilter.FOCUS_LOFI,
        icon = Icons.Default.AutoAwesome,
        defaultVolume = 0.26f
    ),
    SoundCatalogOption(
        key = "singing_bowl",
        label = "Tigela Tibetana",
        description = "Ressônancia harmônica quente para atenção plena",
        category = SoundCategoryFilter.FOCUS_LOFI,
        icon = Icons.Default.Spa,
        defaultVolume = 0.28f
    ),
    SoundCatalogOption(
        key = "binaural_alpha",
        label = "Binaural Alpha 10 Hz",
        description = "Foco alerta e resolução de problemas (200/210 Hz)",
        category = SoundCategoryFilter.BINAURAL,
        icon = Icons.Default.Headphones,
        defaultVolume = 0.30f,
        isBinaural = true
    ),
    SoundCatalogOption(
        key = "binaural_theta",
        label = "Binaural Theta 6 Hz",
        description = "Leitura profunda, revisão e retenção (180/186 Hz)",
        category = SoundCategoryFilter.BINAURAL,
        icon = Icons.Default.Headphones,
        defaultVolume = 0.30f,
        isBinaural = true
    ),
    SoundCatalogOption(
        key = "binaural_beta",
        label = "Binaural Beta 16 Hz",
        description = "Raciocínio analítico, lógica e exatas (240/256 Hz)",
        category = SoundCategoryFilter.BINAURAL,
        icon = Icons.Default.Psychology,
        defaultVolume = 0.28f,
        isBinaural = true
    ),
    SoundCatalogOption(
        key = "binaural_gamma",
        label = "Binaural Gamma 40 Hz",
        description = "Alta concentração e processamento rápido (220/260 Hz)",
        category = SoundCategoryFilter.BINAURAL,
        icon = Icons.Default.Bolt,
        defaultVolume = 0.25f,
        isBinaural = true
    ),
    SoundCatalogOption(
        key = "binaural_delta",
        label = "Binaural Delta 3 Hz",
        description = "Relaxamento profundo e descanso pós-estudo (140/143 Hz)",
        category = SoundCategoryFilter.BINAURAL,
        icon = Icons.Default.Bedtime,
        defaultVolume = 0.30f,
        isBinaural = true
    ),
    SoundCatalogOption(
        key = "birds",
        label = "Pássaros da Manhã",
        description = "Cantos e trinados orgânicos suaves com brisa matinal",
        category = SoundCategoryFilter.BIRDS,
        icon = Icons.Default.FlutterDash,
        defaultVolume = 0.30f
    ),
    SoundCatalogOption(
        key = "mourning_dove",
        label = "Pomba-Rolinha",
        description = "Canto calmo de 4 notas (cuu-UUU-uu-uu) ao amanhecer",
        category = SoundCategoryFilter.BIRDS,
        icon = Icons.Default.WbTwilight,
        defaultVolume = 0.32f
    ),
    SoundCatalogOption(
        key = "forest",
        label = "Floresta & Copa",
        description = "Vento orgânico na folhagem com pássaro distante",
        category = SoundCategoryFilter.NATURE_FOREST,
        icon = Icons.Default.Forest,
        defaultVolume = 0.28f
    ),
    SoundCatalogOption(
        key = "forest_stream",
        label = "Riacho na Floresta",
        description = "Água corrente borbulhando em pedras na mata",
        category = SoundCategoryFilter.NATURE_FOREST,
        icon = Icons.Default.Water,
        defaultVolume = 0.34f
    ),
    SoundCatalogOption(
        key = "forest_rain",
        label = "Chuva na Mata",
        description = "Chuva constante em folhas largas e terra úmida",
        category = SoundCategoryFilter.NATURE_FOREST,
        icon = Icons.Default.Park,
        defaultVolume = 0.36f
    ),
    SoundCatalogOption(
        key = "forest_night",
        label = "Floresta Noturna",
        description = "Grilos suaves em coro, coruja distante e brisa calma",
        category = SoundCategoryFilter.NATURE_FOREST,
        icon = Icons.Default.NightsStay,
        defaultVolume = 0.30f
    ),
    SoundCatalogOption(
        key = "mountain_wind",
        label = "Vento na Montanha",
        description = "Brisa alpina modulada em picos silenciosos",
        category = SoundCategoryFilter.NATURE_FOREST,
        icon = Icons.Default.Landscape,
        defaultVolume = 0.30f
    ),
    SoundCatalogOption(
        key = "rain",
        label = "Chuva",
        description = "Chuva leve constante com gotas orgânicas suaves",
        category = SoundCategoryFilter.RAIN_WATER,
        icon = Icons.Default.Cloud,
        defaultVolume = 0.38f
    ),
    SoundCatalogOption(
        key = "rain_window",
        label = "Chuva na Janela",
        description = "Chuva abafada no vidro com gotas delicadas em sala",
        category = SoundCategoryFilter.RAIN_WATER,
        icon = Icons.Default.Window,
        defaultVolume = 0.35f
    ),
    SoundCatalogOption(
        key = "thunder",
        label = "Tempestade",
        description = "Chuva densa com trovões graves distantes",
        category = SoundCategoryFilter.RAIN_WATER,
        icon = Icons.Default.Thunderstorm,
        defaultVolume = 0.34f
    ),
    SoundCatalogOption(
        key = "waves",
        label = "Ondas do Mar",
        description = "Mar calmo quebrando em ritmo lento e contínuo",
        category = SoundCategoryFilter.RAIN_WATER,
        icon = Icons.Default.Waves,
        defaultVolume = 0.35f
    ),
    SoundCatalogOption(
        key = "waterfall",
        label = "Cachoeira",
        description = "Cascata rica e contínua para isolamento total",
        category = SoundCategoryFilter.RAIN_WATER,
        icon = Icons.Default.WaterDrop,
        defaultVolume = 0.34f
    ),
    SoundCatalogOption(
        key = "coffee",
        label = "Cafeteria",
        description = "Murmúrio acolhedor, xícaras suaves e vapor de café",
        category = SoundCategoryFilter.AMBIENT,
        icon = Icons.Default.LocalCafe,
        defaultVolume = 0.26f
    ),
    SoundCatalogOption(
        key = "library",
        label = "Biblioteca",
        description = "Sala silenciosa com folhear suave de páginas",
        category = SoundCategoryFilter.AMBIENT,
        icon = Icons.AutoMirrored.Filled.MenuBook,
        defaultVolume = 0.32f
    ),
    SoundCatalogOption(
        key = "keyboard",
        label = "Teclado Abafado",
        description = "Toque mecânico suave de ritmo produtivo",
        category = SoundCategoryFilter.AMBIENT,
        icon = Icons.Default.Keyboard,
        defaultVolume = 0.28f
    ),
    SoundCatalogOption(
        key = "fire",
        label = "Lareira",
        description = "Brasas quentes com crepitar macio de lenha",
        category = SoundCategoryFilter.AMBIENT,
        icon = Icons.Default.LocalFireDepartment,
        defaultVolume = 0.26f
    ),
    SoundCatalogOption(
        key = "fan",
        label = "Ventilador Suave",
        description = "Zumbido estável e acolhedor de quarto silencioso",
        category = SoundCategoryFilter.AMBIENT,
        icon = Icons.Default.Cyclone,
        defaultVolume = 0.34f
    ),
    SoundCatalogOption(
        key = "train",
        label = "Trem nos Trilhos",
        description = "Cadência hipnótica sobre trilhos durante viagem noturna",
        category = SoundCategoryFilter.AMBIENT,
        icon = Icons.Default.Train,
        defaultVolume = 0.32f
    ),
    SoundCatalogOption(
        key = "brown",
        label = "Ruído Marrom",
        description = "Grave profundo e contínuo para isolar distrações",
        category = SoundCategoryFilter.NOISE,
        icon = Icons.Default.GraphicEq,
        defaultVolume = 0.38f
    ),
    SoundCatalogOption(
        key = "green_noise",
        label = "Ruído Verde",
        description = "Frequências médias naturais semelhantes a mata distante",
        category = SoundCategoryFilter.NOISE,
        icon = Icons.Default.GraphicEq,
        defaultVolume = 0.34f
    ),
    SoundCatalogOption(
        key = "pink",
        label = "Ruído Rosa",
        description = "Frequências equilibradas e macias",
        category = SoundCategoryFilter.NOISE,
        icon = Icons.Default.GraphicEq,
        defaultVolume = 0.30f
    ),
    SoundCatalogOption(
        key = "white",
        label = "Ruído Branco",
        description = "Mascaramento uniforme de sons externos",
        category = SoundCategoryFilter.NOISE,
        icon = Icons.Default.GraphicEq,
        defaultVolume = 0.25f
    )
)

private val soundCatalogDefaultVolumes: Map<String, Float> = soundCatalog.associate { it.key to it.defaultVolume }

private val builtInPresets: List<BuiltInSoundPreset> = listOf(
    BuiltInSoundPreset(
        id = "binaural_alpha_focus",
        title = "Foco Profundo (Alpha 10 Hz)",
        subtitle = "Ondas Alpha para concentração ativa + base de Ruído Marrom",
        volumes = mapOf("binaural_alpha" to 0.32f, "brown" to 0.24f),
        isBinaural = true,
        badge = "Binaural · Foco"
    ),
    BuiltInSoundPreset(
        id = "binaural_theta_read",
        title = "Leitura & Memória (Theta 6 Hz)",
        subtitle = "Ondas Theta para absorção de leitura + Chuva suave",
        volumes = mapOf("binaural_theta" to 0.30f, "rain" to 0.25f),
        isBinaural = true,
        badge = "Binaural · Leitura"
    ),
    BuiltInSoundPreset(
        id = "binaural_beta_math",
        title = "Cálculo & Lógica (Beta 16 Hz)",
        subtitle = "Ondas Beta analíticas com Ruído Verde suave",
        volumes = mapOf("binaural_beta" to 0.30f, "green_noise" to 0.26f),
        isBinaural = true,
        badge = "Binaural · Exatas"
    ),
    BuiltInSoundPreset(
        id = "binaural_gamma_sprint",
        title = "Foco Máximo (Gamma 40 Hz)",
        subtitle = "Alta clareza cognitiva + Chuva na Janela",
        volumes = mapOf("binaural_gamma" to 0.26f, "rain_window" to 0.28f),
        isBinaural = true,
        badge = "Binaural · Pico"
    ),
    BuiltInSoundPreset(
        id = "binaural_delta_rest",
        title = "Relaxamento Profundo (Delta 3 Hz)",
        subtitle = "Descanso reparador pós-sessão com Ondas do Mar",
        volumes = mapOf("binaural_delta" to 0.32f, "waves" to 0.24f),
        isBinaural = true,
        badge = "Binaural · Pausa"
    ),
    BuiltInSoundPreset(
        id = "cosmic_focus",
        title = "Imersão Cósmica",
        subtitle = "Pad Espacial contínuo + Ruído Verde envolvente",
        volumes = mapOf("ambient_pad" to 0.28f, "green_noise" to 0.24f),
        badge = "Espacial"
    ),
    BuiltInSoundPreset(
        id = "waterfall_zen",
        title = "Cachoeira & Tigela Tibetana",
        subtitle = "Fluxo contínuo d'água com harmônicos contemplativos",
        volumes = mapOf("waterfall" to 0.34f, "singing_bowl" to 0.26f),
        badge = "Natureza"
    ),
    BuiltInSoundPreset(
        id = "mountain_cabin",
        title = "Cabana na Montanha",
        subtitle = "Vento alpino, lareira aconchegante e chuva no vidro",
        volumes = mapOf("mountain_wind" to 0.30f, "fire" to 0.28f, "rain_window" to 0.20f),
        badge = "Imersão"
    ),
    BuiltInSoundPreset(
        id = "night_train_rain",
        title = "Trem Noturno na Chuva",
        subtitle = "Cadência suave nos trilhos com gotas na janela",
        volumes = mapOf("train" to 0.34f, "rain_window" to 0.26f),
        badge = "Noturno"
    ),
    BuiltInSoundPreset(
        id = "lofi_rain",
        title = "Lo-fi para Estudar",
        subtitle = "Acordes Rhodes quentes com chuva suave de estudo",
        volumes = mapOf("lofi" to 0.30f, "rain" to 0.36f),
        badge = "Clássico"
    ),
    BuiltInSoundPreset(
        id = "lofi_coffee_real",
        title = "Lo-fi & Cafeteria Real",
        subtitle = "Acordes Rhodes quentes em cafeteria acolhedora com chuva na janela",
        volumes = mapOf("lofi" to 0.30f, "coffee" to 0.26f, "rain_window" to 0.20f),
        badge = "Estudo"
    ),
    BuiltInSoundPreset(
        id = "dawn_dove",
        title = "Amanhecer com Pomba-Rolinha",
        subtitle = "Canto calmo de pomba-rolinha com copa suave e sinos leves",
        volumes = mapOf("mourning_dove" to 0.34f, "forest" to 0.22f, "wind_chimes" to 0.14f),
        badge = "Natureza"
    ),
    BuiltInSoundPreset(
        id = "stream_birds",
        title = "Riacho & Pássaros",
        subtitle = "Água corrente em pedras com passarinhos da manhã na mata",
        volumes = mapOf("forest_stream" to 0.36f, "birds" to 0.28f, "forest" to 0.18f),
        badge = "Natureza"
    ),
    BuiltInSoundPreset(
        id = "forest_rain_deep",
        title = "Chuva na Mata",
        subtitle = "Chuva caindo em folhas largas sobre terra úmida e grave envolvente",
        volumes = mapOf("forest_rain" to 0.40f, "brown" to 0.20f),
        badge = "Imersão"
    ),
    BuiltInSoundPreset(
        id = "forest_night_zen",
        title = "Floresta Noturna",
        subtitle = "Grilos suaves, coruja distante e notas calmas de piano felt",
        volumes = mapOf("forest_night" to 0.34f, "soft_piano" to 0.20f),
        badge = "Noturno"
    ),
    BuiltInSoundPreset(
        id = "piano_rain_window",
        title = "Piano & Chuva na Janela",
        subtitle = "Piano minimalista aconchegante com chuva no vidro e lareira baixa",
        volumes = mapOf("soft_piano" to 0.28f, "rain_window" to 0.34f, "fire" to 0.16f),
        badge = "Leitura"
    ),
    BuiltInSoundPreset(
        id = "library_focus",
        title = "Biblioteca de Estudos",
        subtitle = "Sala silenciosa, folhear de páginas e teclado suave",
        volumes = mapOf("library" to 0.38f, "keyboard" to 0.20f),
        badge = "Imersão"
    ),
    BuiltInSoundPreset(
        id = "rain_brown",
        title = "Chuva Tranquila",
        subtitle = "Chuva contínua + Ruído Marrom para isolamento acústico",
        volumes = mapOf("rain" to 0.38f, "brown" to 0.35f)
    ),
    BuiltInSoundPreset(
        id = "storm_fire",
        title = "Tempestade & Lareira",
        subtitle = "Trovões graves distantes com lareira aconchegante",
        volumes = mapOf("thunder" to 0.36f, "fire" to 0.28f)
    ),
    BuiltInSoundPreset(
        id = "coffee_pink",
        title = "Cafeteria Produtiva",
        subtitle = "Ambiente de café acolhedor + Ruído Rosa suave",
        volumes = mapOf("coffee" to 0.28f, "pink" to 0.25f)
    ),
    BuiltInSoundPreset(
        id = "waves_pink",
        title = "Mar & Ruído Rosa",
        subtitle = "Ondas lentas com fundo acústico equilibrado",
        volumes = mapOf("waves" to 0.35f, "pink" to 0.24f)
    ),
    BuiltInSoundPreset(
        id = "fire_rain",
        title = "Lareira & Chuva",
        subtitle = "Combinação clássica de foco noturno",
        volumes = mapOf("fire" to 0.28f, "rain" to 0.34f)
    )
)

/**
 * Full Sound & Music Focus Hub (`Page.Player`).
 * Synchronizes continuously with [FocusAudioStore] (`"studydock_audio"`).
 */
@Composable
fun PlayerPage(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember(context) { FocusAudioStore.prefs(context) }

    LaunchedEffect(context) { FocusAudioUiHub.ensureStarted(context) }

    val snapshot by FocusAudioUiHub.snapshot.collectAsStateWithLifecycle()
    val playlists by FocusAudioUiHub.playlists.collectAsStateWithLifecycle()
    val customMixes by FocusAudioUiHub.customMixes.collectAsStateWithLifecycle()
    val isAnythingPlaying = snapshot.musicPlaying || snapshot.ambientPlaying
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(isAnythingPlaying, snapshot.sleepTimerEndAt) {
        if (isAnythingPlaying && snapshot.sleepTimerEndAt != null) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(PlayerTab.SOUNDS.name) }
    var selectedCategory by rememberSaveable { mutableStateOf(SoundCategoryFilter.ALL.name) }
    var trackSearchQuery by rememberSaveable { mutableStateOf("") }

    var showSleepDialog by remember { mutableStateOf(false) }
    var showAutoAudioDialog by remember { mutableStateOf(false) }
    var showSaveMixDialog by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<MusicPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<MusicPlaylist?>(null) }

    val activePlaylist = remember(playlists, snapshot.activePlaylistId) {
        playlists.firstOrNull { it.id == snapshot.activePlaylistId } ?: playlists.firstOrNull()
    }
    val currentTracks = activePlaylist?.tracks.orEmpty()
    val safeTrackIndex = if (currentTracks.isEmpty()) 0 else snapshot.currentTrackIndex.coerceIn(0, currentTracks.lastIndex)

    fun sendAmbientCommand(
        action: String,
        activeSet: Set<String> = snapshot.activeSounds,
        levels: Map<String, Float> = snapshot.soundVolumes,
        masterVol: Float = snapshot.ambientMasterVolume
    ) {
        val orderedKeys = soundCatalog.map { it.key }.filter { it in activeSet }
        val intent = Intent(context, FocusAudioService::class.java)
            .setAction(action)
            .putStringArrayListExtra(FocusAudioService.EXTRA_SOUNDS, ArrayList(orderedKeys))
            .putExtra(
                FocusAudioService.EXTRA_VOLUMES,
                orderedKeys.map {
                    levels[it] ?: soundCatalogDefaultVolumes[it] ?: FocusAudioStore.defaultSoundVolumes[it] ?: 0.35f
                }.toFloatArray()
            )
            .putExtra(FocusAudioService.EXTRA_MASTER_VOLUME, masterVol)
        runCatching {
            if (action == FocusAudioService.ACTION_PLAY) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    var lastLiveDispatchMs by remember { mutableLongStateOf(0L) }
    fun applyMix(
        nextActive: Set<String>,
        nextVolumes: Map<String, Float> = snapshot.soundVolumes,
        masterVol: Float = snapshot.ambientMasterVolume,
        startImmediately: Boolean = false
    ) {
        val mergedVolumes = LinkedHashMap<String, Float>().apply {
            putAll(FocusAudioStore.defaultSoundVolumes)
            putAll(soundCatalogDefaultVolumes)
            putAll(snapshot.soundVolumes)
            putAll(nextVolumes)
        }
        val willPlay = startImmediately || (snapshot.ambientPlaying && nextActive.isNotEmpty())
        FocusAudioStore.updateAmbientState(
            prefs = prefs,
            ambientPlaying = willPlay,
            activeSounds = nextActive,
            soundVolumes = mergedVolumes,
            masterVolume = masterVol
        )
        FocusAudioUiHub.refreshSnapshotAsync(prefs)
        if (startImmediately && nextActive.isNotEmpty()) {
            sendAmbientCommand(FocusAudioService.ACTION_PLAY, nextActive, mergedVolumes, masterVol)
        } else if (willPlay || nextActive.isEmpty()) {
            sendAmbientCommand(FocusAudioService.ACTION_MIX, nextActive, mergedVolumes, masterVol)
        }
    }

    fun previewMixThrottled(
        nextActive: Set<String>,
        nextVolumes: Map<String, Float>,
        masterVol: Float = snapshot.ambientMasterVolume
    ) {
        if (!snapshot.ambientPlaying) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastLiveDispatchMs < 80L) return
        lastLiveDispatchMs = nowElapsed
        val mergedVolumes = LinkedHashMap<String, Float>().apply {
            putAll(FocusAudioStore.defaultSoundVolumes)
            putAll(soundCatalogDefaultVolumes)
            putAll(snapshot.soundVolumes)
            putAll(nextVolumes)
        }
        sendAmbientCommand(FocusAudioService.ACTION_MIX, nextActive, mergedVolumes, masterVol)
    }

    fun sendMusicCommand(
        action: String,
        playlist: MusicPlaylist? = activePlaylist,
        trackIndex: Int = safeTrackIndex,
        seekMs: Long? = null,
        shuffleOverride: Boolean? = null,
        repeatOverride: String? = null
    ) {
        val tracks = playlist?.tracks.orEmpty()
        val intent = Intent(context, FocusAudioService::class.java)
            .setAction(action)
            .putExtra(FocusAudioService.EXTRA_PLAYLIST_ID, playlist?.id ?: FocusAudioStore.DEFAULT_PLAYLIST_ID)
            .putStringArrayListExtra(FocusAudioService.EXTRA_TRACK_URIS, ArrayList(tracks.map { it.uri }))
            .putStringArrayListExtra(FocusAudioService.EXTRA_TRACK_TITLES, ArrayList(tracks.map { it.title }))
            .putStringArrayListExtra(FocusAudioService.EXTRA_TRACK_ARTISTS, ArrayList(tracks.map { it.artist }))
            .putExtra(FocusAudioService.EXTRA_TRACK_INDEX, trackIndex)
        seekMs?.let { intent.putExtra(FocusAudioService.EXTRA_SEEK_MS, it) }
        shuffleOverride?.let { intent.putExtra(FocusAudioService.EXTRA_SHUFFLE, it) }
        repeatOverride?.let { intent.putExtra(FocusAudioService.EXTRA_REPEAT_MODE, it) }
        runCatching {
            if (action == FocusAudioService.ACTION_PLAY_MUSIC ||
                action == FocusAudioService.ACTION_NEXT_MUSIC ||
                action == FocusAudioService.ACTION_PREV_MUSIC
            ) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { selectedUris ->
        if (selectedUris.isEmpty()) return@rememberLauncherForActivityResult
        val targetPl = activePlaylist ?: FocusAudioStore.createPlaylist(prefs, FocusAudioStore.DEFAULT_PLAYLIST_NAME)
        val existingUris = targetPl.tracks.map { it.uri }.toSet()
        val imported = selectedUris.mapNotNull { uri ->
            val uriStr = uri.toString()
            if (uriStr in existingUris) return@mapNotNull null
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            extractAudioTrackMetadata(context, uri)
        }
        if (imported.isNotEmpty()) {
            val updatedTracks = targetPl.tracks + imported
            val updatedPlaylists = FocusAudioStore.updatePlaylistTracks(prefs, targetPl.id, updatedTracks)
            FocusAudioUiHub.updatePlaylists(updatedPlaylists)
            val updatedPl = updatedPlaylists.firstOrNull { it.id == targetPl.id }
            sendMusicCommand(FocusAudioService.ACTION_UPDATE_PLAYLIST, updatedPl, safeTrackIndex)
        }
    }

    val currentTab = remember(selectedTab) {
        PlayerTab.entries.firstOrNull { it.name == selectedTab } ?: PlayerTab.SOUNDS
    }
    val currentCategory = remember(selectedCategory) {
        SoundCategoryFilter.entries.firstOrNull { it.name == selectedCategory } ?: SoundCategoryFilter.ALL
    }

    val sleepRemainingMinutes = remember(snapshot.sleepTimerEndAt, nowMs) {
        snapshot.sleepTimerEndAt?.let { endAt ->
            val diff = endAt - nowMs
            if (diff > 0L) ceil(diff / 60_000.0).toInt() else null
        }
    }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val soundColumns = when {
        screenWidthDp >= 720 -> 3
        screenWidthDp < 360 -> 1
        else -> 2
    }
    val filteredSounds = remember(currentCategory) {
        if (currentCategory == SoundCategoryFilter.ALL) {
            soundCatalog
        } else {
            soundCatalog.filter { it.category == currentCategory }
        }
    }
    val soundRows = remember(filteredSounds, soundColumns) {
        filteredSounds.chunked(soundColumns)
    }
    val queryTrim = trackSearchQuery.trim()
    val indexedTracks = remember(currentTracks, queryTrim) {
        currentTracks.mapIndexedNotNull { index, item ->
            val matches = queryTrim.isEmpty() ||
                item.title.contains(queryTrim, ignoreCase = true) ||
                item.artist.contains(queryTrim, ignoreCase = true)
            if (matches) index to item else null
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "audio_header_controls", contentType = "audioHeader") {
            Column(Modifier.fillMaxWidth()) {
                // Header
                StudyPageHeader(
            title = "Central Sonora & Foco",
            subtitle = "Misture sons ambientes, ondas binaurais e playlists locais.",
            onBack = onBack,
            showGlobalSearch = true,
            actions = {
                IconButton(
                    onClick = { showAutoAudioDialog = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Preferências de Auto-Áudio",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        )

        // Master Playback & Controls Card
        MasterPlaybackControlCard(
            snapshot = snapshot,
            activePlaylist = activePlaylist,
            sleepRemainingMinutes = sleepRemainingMinutes,
            onToggleAmbient = {
                if (snapshot.ambientPlaying) {
                    FocusAudioStore.updateAmbientState(prefs, ambientPlaying = false)
                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                    sendAmbientCommand(FocusAudioService.ACTION_PAUSE)
                } else {
                    val nextActive = snapshot.activeSounds.ifEmpty { setOf("brown") }
                    applyMix(nextActive, snapshot.soundVolumes, snapshot.ambientMasterVolume, startImmediately = true)
                }
            },
            onStopAmbient = {
                FocusAudioStore.updateAmbientState(prefs, ambientPlaying = false)
                FocusAudioUiHub.refreshSnapshotAsync(prefs)
                sendAmbientCommand(FocusAudioService.ACTION_STOP)
            },
            onToggleMusic = {
                if (snapshot.musicPlaying) {
                    FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = false)
                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                    sendMusicCommand(FocusAudioService.ACTION_PAUSE_MUSIC)
                } else if (currentTracks.isNotEmpty()) {
                    FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = true)
                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                    sendMusicCommand(FocusAudioService.ACTION_PLAY_MUSIC, activePlaylist, safeTrackIndex)
                } else {
                    selectedTab = PlayerTab.MUSIC.name
                }
            },
            onMasterVolumePreview = { newMaster ->
                val nowElapsed = SystemClock.elapsedRealtime()
                if (snapshot.ambientPlaying && nowElapsed - lastLiveDispatchMs >= 80L) {
                    lastLiveDispatchMs = nowElapsed
                    val intent = Intent(context, FocusAudioService::class.java)
                        .setAction(FocusAudioService.ACTION_SET_MASTER_VOLUME)
                        .putExtra(FocusAudioService.EXTRA_MASTER_VOLUME, newMaster.coerceIn(0f, 1f))
                    runCatching { context.startService(intent) }
                }
            },
            onMasterVolumeCommit = { newMaster ->
                val clamped = newMaster.coerceIn(0f, 1f)
                FocusAudioStore.updateAmbientState(prefs, masterVolume = clamped)
                FocusAudioUiHub.refreshSnapshotAsync(prefs)
                val intent = Intent(context, FocusAudioService::class.java)
                    .setAction(FocusAudioService.ACTION_SET_MASTER_VOLUME)
                    .putExtra(FocusAudioService.EXTRA_MASTER_VOLUME, clamped)
                runCatching { context.startService(intent) }
            },
            onOpenSleepTimer = { showSleepDialog = true },
            onOpenAutoAudio = { showAutoAudioDialog = true }
        )

        // 3 Clean Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PlayerTab.entries.forEach { tabItem ->
                FilterChip(
                    selected = currentTab == tabItem,
                    onClick = { selectedTab = tabItem.name },
                    label = { Text(tabItem.label) },
                    leadingIcon = { Icon(tabItem.icon, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }
        }
            }
        }

        when (currentTab) {
            PlayerTab.SOUNDS -> {
                item(key = "sounds_filters_and_active", contentType = "filterBar") {
                    Column(Modifier.fillMaxWidth()) {
                        // Category Filter Bar + Save Mix Action
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SoundCategoryFilter.entries.forEach { cat ->
                                FilterChip(
                                    selected = currentCategory == cat,
                                    onClick = { selectedCategory = cat.name },
                                    label = { Text(cat.label, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }

                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            val activeLabelText = if (snapshot.activeSounds.isEmpty()) {
                                "Nenhuma camada ativa"
                            } else {
                                "${snapshot.activeSounds.size} ${if (snapshot.activeSounds.size == 1) "camada ativa" else "camadas ativas"}"
                            }
                            if (maxWidth < 380.dp && snapshot.activeSounds.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            activeLabelText,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (snapshot.activeSounds.size > 1) {
                                            TextButton(
                                                onClick = { applyMix(emptySet(), snapshot.soundVolumes) }
                                            ) {
                                                Text("Limpar", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = { showSaveMixDialog = true },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Salvar como Mix", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        activeLabelText,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (snapshot.activeSounds.isNotEmpty()) {
                                        OutlinedButton(
                                            onClick = { showSaveMixDialog = true },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Salvar como Mix", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                        }
                                    }
                                    if (snapshot.activeSounds.size > 1) {
                                        TextButton(
                                            onClick = { applyMix(emptySet(), snapshot.soundVolumes) }
                                        ) {
                                            Text("Limpar", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                items(
                    items = soundRows,
                    key = { row -> "soundrow_" + row.joinToString("_") { it.key } },
                    contentType = { "soundRow" }
                ) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { sound ->
                            val isEnabled = sound.key in snapshot.activeSounds
                            val currentVol = snapshot.soundVolumes[sound.key] ?: sound.defaultVolume
                            SoundLayerCard(
                                sound = sound,
                                enabled = isEnabled,
                                volume = currentVol,
                                modifier = Modifier.weight(1f),
                                onToggle = {
                                    val nextSet = if (isEnabled) {
                                        snapshot.activeSounds - sound.key
                                    } else {
                                        snapshot.activeSounds + sound.key
                                    }
                                    applyMix(nextSet, snapshot.soundVolumes)
                                },
                                onVolumePreview = { newVol ->
                                    val updatedVols = snapshot.soundVolumes + (sound.key to newVol.coerceIn(0f, 1f))
                                    val nextSet = if (newVol > 0.02f) snapshot.activeSounds + sound.key else snapshot.activeSounds
                                    previewMixThrottled(nextSet, updatedVols)
                                },
                                onVolumeChange = { newVol ->
                                    val updatedVols = snapshot.soundVolumes + (sound.key to newVol.coerceIn(0f, 1f))
                                    val nextSet = if (newVol > 0.02f) snapshot.activeSounds + sound.key else snapshot.activeSounds
                                    applyMix(nextSet, updatedVols)
                                }
                            )
                        }
                        repeat(soundColumns - rowItems.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                item(key = "sounds_footer_actions", contentType = "footer") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (snapshot.ambientPlaying) {
                                    FocusAudioStore.updateAmbientState(prefs, ambientPlaying = false)
                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                    sendAmbientCommand(FocusAudioService.ACTION_PAUSE)
                                } else {
                                    val next = snapshot.activeSounds.ifEmpty { setOf("brown") }
                                    applyMix(next, snapshot.soundVolumes, snapshot.ambientMasterVolume, startImmediately = true)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (snapshot.ambientPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                            Text(if (snapshot.ambientPlaying) "  Pausar ambiente" else "  Tocar ambiente")
                        }
                        OutlinedButton(
                            onClick = {
                                FocusAudioStore.updateAmbientState(prefs, ambientPlaying = false)
                                FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                sendAmbientCommand(FocusAudioService.ACTION_STOP)
                            }
                        ) {
                            Text("Parar")
                        }
                    }
                }
            }

            PlayerTab.MIXES -> {
                // Custom Mixes Section
                item(key = "mixes_header", contentType = "sectionHeader") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Meus Mixes Salvos",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Suas combinações personalizadas com volumes exatos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { showSaveMixDialog = true },
                            enabled = snapshot.activeSounds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Salvar Atual")
                        }
                    }
                }

                if (customMixes.isEmpty()) {
                    item(key = "mixes_empty", contentType = "empty") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text("Nenhum mix personalizado salvo", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Monte sua combinação na aba 'Sons' e toque em 'Salvar como Mix'.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = customMixes,
                        key = { "mix_${it.id}" },
                        contentType = { "customMix" }
                    ) { mix ->
                        val isCurrentMix = snapshot.activeSounds == mix.volumes.keys && mix.volumes.isNotEmpty()
                        Card(
                            onClick = {
                                applyMix(
                                    nextActive = mix.volumes.keys,
                                    nextVolumes = mix.volumes,
                                    masterVol = mix.masterVolume,
                                    startImmediately = true
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrentMix) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            val mixTitleColor = if (isCurrentMix) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            val mixMutedColor = if (isCurrentMix) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isCurrentMix) {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(10.dp).size(20.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        mix.name,
                                        fontWeight = FontWeight.SemiBold,
                                        color = mixTitleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val layersDesc = mix.volumes.entries.joinToString(" + ") { (k, v) ->
                                        val label = FocusAudioService.soundLabels[k] ?: k
                                        "$label ${(v * 100).roundToInt()}%"
                                    }
                                    Text(
                                        layersDesc.ifBlank { "Sem camadas" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = mixMutedColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        applyMix(
                                            nextActive = mix.volumes.keys,
                                            nextVolumes = mix.volumes,
                                            masterVol = mix.masterVolume,
                                            startImmediately = true
                                        )
                                    }
                                ) {
                                    Icon(
                                        if (isCurrentMix && snapshot.ambientPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                        contentDescription = "Tocar ${mix.name}",
                                        tint = mixTitleColor
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        FocusAudioUiHub.updateCustomMixes(FocusAudioStore.deleteCustomMix(prefs, mix.id))
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Excluir mix",
                                        tint = mixMutedColor
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "presets_header", contentType = "sectionHeader") {
                    Column(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Presets Prontos & Ondas Binaurais",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                itemsIndexed(
                    items = builtInPresets,
                    key = { idx, preset -> "preset_${idx}_${preset.title}" },
                    contentType = { _, _ -> "preset" }
                ) { _, preset ->
                    val isCurrent = snapshot.activeSounds == preset.volumes.keys
                    Card(
                        onClick = {
                            applyMix(
                                nextActive = preset.volumes.keys,
                                nextVolumes = preset.volumes,
                                startImmediately = true
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            }
                        )
                    ) {
                        val presetTitleColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        val presetSubColor = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        val presetIconTint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (preset.isBinaural) Icons.Default.Headphones else Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = presetIconTint
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp, end = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        preset.title,
                                        fontWeight = FontWeight.SemiBold,
                                        color = presetTitleColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (preset.badge != null) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isCurrent) {
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                                            } else {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            }
                                        ) {
                                            Text(
                                                preset.badge,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    preset.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = presetSubColor
                                )
                            }
                            Icon(
                                if (isCurrent && snapshot.ambientPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                                contentDescription = "Ativar preset",
                                tint = presetIconTint
                            )
                        }
                    }
                }
            }

            PlayerTab.MUSIC -> {
                item(key = "music_header_and_deck", contentType = "musicHeader") {
                    Column(Modifier.fillMaxWidth()) {
                        // Multi-Playlist Selector Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Playlists Locais",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Organize trilhas por matéria, leitura ou momento.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showCreatePlaylistDialog = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Nova Playlist", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // Playlist Chips + Rename / Delete actions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            playlists.forEach { pl ->
                                val isSelected = pl.id == activePlaylist?.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        FocusAudioStore.setActivePlaylist(prefs, pl.id)
                                        FocusAudioStore.updateMusicPlayback(
                                            prefs = prefs,
                                            activePlaylistId = pl.id,
                                            currentTrackIndex = 0
                                        )
                                        FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                        sendMusicCommand(FocusAudioService.ACTION_UPDATE_PLAYLIST, pl, 0)
                                    },
                                    label = { Text("${pl.name} (${pl.tracks.size})") },
                                    leadingIcon = {
                                        Icon(
                                            if (isSelected) Icons.AutoMirrored.Filled.QueueMusic else Icons.Default.LibraryMusic,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                            if (activePlaylist != null) {
                                IconButton(
                                    onClick = { playlistToRename = activePlaylist },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Renomear playlist",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                if (playlists.size > 1) {
                                    IconButton(
                                        onClick = { playlistToDelete = activePlaylist },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Excluir playlist",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Full Music Player Deck (SeekBar mm:ss, Prev, Play/Pause, Next, Shuffle, Repeat)
                        if (currentTracks.isNotEmpty()) {
                            val currentTrack = currentTracks.getOrNull(safeTrackIndex)
                            MusicTransportCard(
                                track = currentTrack,
                                trackIndex = safeTrackIndex,
                                totalTracks = currentTracks.size,
                                isPlaying = snapshot.musicPlaying,
                                positionMs = snapshot.positionMs,
                                durationMs = snapshot.durationMs.takeIf { it > 0L } ?: (currentTrack?.durationMs ?: 0L),
                                shuffle = snapshot.shuffle,
                                repeatMode = snapshot.repeatMode,
                                onPlayPause = {
                                    if (snapshot.musicPlaying) {
                                        FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = false)
                                        FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                        sendMusicCommand(FocusAudioService.ACTION_PAUSE_MUSIC)
                                    } else {
                                        FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = true)
                                        FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                        sendMusicCommand(FocusAudioService.ACTION_PLAY_MUSIC, activePlaylist, safeTrackIndex)
                                    }
                                },
                                onPrev = {
                                    sendMusicCommand(FocusAudioService.ACTION_PREV_MUSIC, activePlaylist, safeTrackIndex)
                                },
                                onNext = {
                                    sendMusicCommand(FocusAudioService.ACTION_NEXT_MUSIC, activePlaylist, safeTrackIndex)
                                },
                                onStop = {
                                    FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = false, positionMs = 0L)
                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                    sendMusicCommand(FocusAudioService.ACTION_STOP_MUSIC)
                                },
                                onSeek = { targetMs ->
                                    FocusAudioStore.updateMusicPlayback(prefs, positionMs = targetMs)
                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                    sendMusicCommand(FocusAudioService.ACTION_SEEK_MUSIC, seekMs = targetMs)
                                },
                                onToggleShuffle = {
                                    val nextShuffle = !snapshot.shuffle
                                    FocusAudioStore.updateMusicPlayback(prefs, shuffle = nextShuffle)
                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                    sendMusicCommand(FocusAudioService.ACTION_SET_SHUFFLE, shuffleOverride = nextShuffle)
                                },
                                onCycleRepeat = {
                                    val nextRepeat = when (snapshot.repeatMode) {
                                        FocusAudioStore.REPEAT_ALL -> FocusAudioStore.REPEAT_ONE
                                        FocusAudioStore.REPEAT_ONE -> FocusAudioStore.REPEAT_OFF
                                        else -> FocusAudioStore.REPEAT_ALL
                                    }
                                    FocusAudioStore.updateMusicPlayback(prefs, repeatMode = nextRepeat)
                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                    sendMusicCommand(FocusAudioService.ACTION_SET_REPEAT, repeatOverride = nextRepeat)
                                }
                            )
                        }

                        // Search & Add Tracks Bar
                        val openGlobalSearch = LocalGlobalSearchWithQueryOpener.current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StudyLocalSearchField(
                                query = trackSearchQuery,
                                onQueryChange = { trackSearchQuery = it },
                                placeholder = "Buscar faixa ou artista...",
                                modifier = Modifier.weight(1f),
                                onSearchGlobal = openGlobalSearch
                            )
                            FilledTonalIconButton(
                                onClick = { musicPicker.launch(arrayOf("audio/*")) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Adicionar músicas")
                            }
                        }
                    }
                }

                if (currentTracks.isEmpty()) {
                    item(key = "music_empty", contentType = "empty") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.LibraryMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    "Playlist '${activePlaylist?.name ?: "Favoritas"}' vazia",
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                Text(
                                    "Importe arquivos de áudio (MP3, M4A, OGG, FLAC) do aparelho para ouvir offline.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                                )
                                Button(onClick = { musicPicker.launch(arrayOf("audio/*")) }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Importar músicas")
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = indexedTracks,
                        key = { (index, trackItem) -> "track_${index}_${trackItem.uri}" },
                        contentType = { "musicTrack" }
                    ) { (index, trackItem) ->
                        val isCurrentTrack = index == safeTrackIndex
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrentTrack) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            shape = RoundedCornerShape(15.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                        ) {
                            val trackTitleColor = if (isCurrentTrack) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                            val trackSubColor = if (isCurrentTrack) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (isCurrentTrack && snapshot.musicPlaying) {
                                            FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = false)
                                            FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                            sendMusicCommand(FocusAudioService.ACTION_PAUSE_MUSIC)
                                        } else {
                                            FocusAudioStore.updateMusicPlayback(
                                                prefs = prefs,
                                                musicPlaying = true,
                                                currentTrackIndex = index,
                                                currentTrackTitle = trackItem.title,
                                                currentTrackArtist = trackItem.artist
                                            )
                                            FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                            sendMusicCommand(FocusAudioService.ACTION_PLAY_MUSIC, activePlaylist, index)
                                        }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        if (snapshot.musicPlaying && isCurrentTrack) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Tocar ${trackItem.title}"
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 10.dp)
                                ) {
                                    Text(
                                        trackItem.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = trackTitleColor
                                    )
                                    val subtitleParts = buildList {
                                        if (trackItem.artist.isNotBlank()) add(trackItem.artist)
                                        add("Faixa ${index + 1}/${currentTracks.size}")
                                        if (trackItem.durationMs > 0L) add(formatAudioTimeMs(trackItem.durationMs))
                                    }
                                    Text(
                                        subtitleParts.joinToString(" · "),
                                        color = trackSubColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (activePlaylist != null) {
                                    var showTrackMenu by remember(trackItem.uri, index) { mutableStateOf(false) }
                                    Box {
                                        IconButton(
                                            onClick = { showTrackMenu = true },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Opções da faixa",
                                                tint = trackSubColor
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showTrackMenu,
                                            onDismissRequest = { showTrackMenu = false }
                                        ) {
                                            if (queryTrim.isEmpty() && index > 0) {
                                                DropdownMenuItem(
                                                    text = { Text("Mover para cima") },
                                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                                                    onClick = {
                                                        showTrackMenu = false
                                                        val list = currentTracks.toMutableList()
                                                        val moved = list.removeAt(index)
                                                        list.add(index - 1, moved)
                                                        val nextIdx = when (safeTrackIndex) {
                                                            index -> index - 1
                                                            index - 1 -> index
                                                            else -> safeTrackIndex
                                                        }
                                                        val updatedPlaylists = FocusAudioStore.updatePlaylistTracks(prefs, activePlaylist.id, list)
                                                        FocusAudioUiHub.updatePlaylists(updatedPlaylists)
                                                        FocusAudioStore.updateMusicPlayback(prefs, currentTrackIndex = nextIdx)
                                                        FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                                        sendMusicCommand(
                                                            FocusAudioService.ACTION_UPDATE_PLAYLIST,
                                                            updatedPlaylists.firstOrNull { it.id == activePlaylist.id },
                                                            nextIdx
                                                        )
                                                    }
                                                )
                                            }
                                            if (queryTrim.isEmpty() && index < currentTracks.lastIndex) {
                                                DropdownMenuItem(
                                                    text = { Text("Mover para baixo") },
                                                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                                    onClick = {
                                                        showTrackMenu = false
                                                        val list = currentTracks.toMutableList()
                                                        val moved = list.removeAt(index)
                                                        list.add(index + 1, moved)
                                                        val nextIdx = when (safeTrackIndex) {
                                                            index -> index + 1
                                                            index + 1 -> index
                                                            else -> safeTrackIndex
                                                        }
                                                        val updatedPlaylists = FocusAudioStore.updatePlaylistTracks(prefs, activePlaylist.id, list)
                                                        FocusAudioUiHub.updatePlaylists(updatedPlaylists)
                                                        FocusAudioStore.updateMusicPlayback(prefs, currentTrackIndex = nextIdx)
                                                        FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                                        sendMusicCommand(
                                                            FocusAudioService.ACTION_UPDATE_PLAYLIST,
                                                            updatedPlaylists.firstOrNull { it.id == activePlaylist.id },
                                                            nextIdx
                                                        )
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Remover faixa") },
                                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                                onClick = {
                                                    showTrackMenu = false
                                                    val selectedUri = currentTracks.getOrNull(safeTrackIndex)?.uri
                                                    val wasCurrent = index == safeTrackIndex
                                                    if (wasCurrent) {
                                                        sendMusicCommand(FocusAudioService.ACTION_STOP_MUSIC)
                                                    }
                                                    val remaining = currentTracks.filterIndexed { i, _ -> i != index }
                                                    val updatedPlaylists = FocusAudioStore.updatePlaylistTracks(prefs, activePlaylist.id, remaining)
                                                    FocusAudioUiHub.updatePlaylists(updatedPlaylists)
                                                    val nextIdx = remaining.indexOfFirst { it.uri == selectedUri }.coerceAtLeast(0)
                                                    FocusAudioStore.updateMusicPlayback(
                                                        prefs = prefs,
                                                        musicPlaying = if (wasCurrent) false else snapshot.musicPlaying,
                                                        currentTrackIndex = nextIdx
                                                    )
                                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                                    if (!wasCurrent && remaining.isNotEmpty()) {
                                                        sendMusicCommand(
                                                            FocusAudioService.ACTION_UPDATE_PLAYLIST,
                                                            updatedPlaylists.firstOrNull { it.id == activePlaylist.id },
                                                            nextIdx
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            currentRemainingMinutes = sleepRemainingMinutes,
            onSelectMinutes = { mins ->
                val intent = Intent(context, FocusAudioService::class.java)
                    .setAction(FocusAudioService.ACTION_SET_SLEEP_TIMER)
                    .putExtra(FocusAudioService.EXTRA_SLEEP_MINUTES, mins)
                if (mins <= 0) {
                    FocusAudioStore.setSleepTimerEndAt(prefs, null)
                } else {
                    FocusAudioStore.setSleepTimerEndAt(prefs, System.currentTimeMillis() + mins * 60_000L)
                }
                FocusAudioUiHub.refreshSnapshotAsync(prefs)
                runCatching { context.startService(intent) }
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false }
        )
    }

    if (showAutoAudioDialog) {
        AutoAudioPreferencesDialog(
            snapshot = snapshot,
            playlists = playlists,
            customMixes = customMixes,
            onSave = { studyOn, readingOn, pauseBreak, studyTarget, readingTarget ->
                FocusAudioStore.updateAutoAudioPreferences(
                    prefs = prefs,
                    autoPlayOnStudy = studyOn,
                    autoPlayOnReading = readingOn,
                    autoPauseOnBreak = pauseBreak,
                    selectedStudyTarget = studyTarget,
                    selectedReadingTarget = readingTarget
                )
                FocusAudioUiHub.refreshSnapshotAsync(prefs)
                showAutoAudioDialog = false
            },
            onDismiss = { showAutoAudioDialog = false }
        )
    }

    if (showSaveMixDialog) {
        SaveCustomMixDialog(
            activeSounds = snapshot.activeSounds,
            volumes = snapshot.soundVolumes,
            masterVolume = snapshot.ambientMasterVolume,
            onSave = { name ->
                val activeMap = snapshot.activeSounds.associateWith { key ->
                    (snapshot.soundVolumes[key]
                        ?: soundCatalogDefaultVolumes[key]
                        ?: FocusAudioStore.defaultSoundVolumes[key]
                        ?: 0.35f).coerceIn(0f, 1f)
                }
                FocusAudioUiHub.updateCustomMixes(
                    FocusAudioStore.saveCustomMix(
                        prefs = prefs,
                        mix = CustomSoundMix(
                            name = name,
                            volumes = activeMap,
                            masterVolume = snapshot.ambientMasterVolume
                        )
                    )
                )
                showSaveMixDialog = false
            },
            onDismiss = { showSaveMixDialog = false }
        )
    }

    if (showCreatePlaylistDialog) {
        PlaylistNameDialog(
            title = "Nova Playlist",
            initialName = "",
            confirmLabel = "Criar",
            onConfirm = { name ->
                val created = FocusAudioStore.createPlaylist(prefs, name)
                FocusAudioStore.setActivePlaylist(prefs, created.id)
                FocusAudioUiHub.refreshPlaylistsAndSnapshotAsync(prefs)
                showCreatePlaylistDialog = false
            },
            onDismiss = { showCreatePlaylistDialog = false }
        )
    }

    playlistToRename?.let { target ->
        PlaylistNameDialog(
            title = "Renomear Playlist",
            initialName = target.name,
            confirmLabel = "Salvar",
            onConfirm = { newName ->
                FocusAudioUiHub.updatePlaylists(FocusAudioStore.renamePlaylist(prefs, target.id, newName))
                playlistToRename = null
            },
            onDismiss = { playlistToRename = null }
        )
    }

    playlistToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Excluir playlist '${target.name}'?") },
            text = { Text("As faixas desta playlist serão removidas da lista (os arquivos no aparelho não serão apagados).") },
            confirmButton = {
                TextButton(
                    onClick = {
                        FocusAudioUiHub.updatePlaylists(FocusAudioStore.deletePlaylist(prefs, target.id))
                        FocusAudioUiHub.refreshSnapshotAsync(prefs)
                        playlistToDelete = null
                    }
                ) {
                    Text("Excluir")
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

/**
 * Compact persistent audio bar that appears only when ambient sounds or local music is playing.
 */
@Composable
fun MiniAudioPlayer(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    applyNavigationBarPadding: Boolean = false
) {
    val context = LocalContext.current
    LaunchedEffect(context) { FocusAudioUiHub.ensureStarted(context) }
    val prefs = remember(context) { FocusAudioStore.prefs(context) }
    val snapshot by FocusAudioUiHub.snapshot.collectAsStateWithLifecycle()
    val playlists by FocusAudioUiHub.playlists.collectAsStateWithLifecycle()
    val isAnythingPlaying = snapshot.musicPlaying || snapshot.ambientPlaying
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var collapsed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(isAnythingPlaying, snapshot.sleepTimerEndAt) {
        if (isAnythingPlaying && snapshot.sleepTimerEndAt != null) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1_000L)
            }
        }
    }

    AnimatedVisibility(visible = isAnythingPlaying, modifier = modifier) {
        val activePlaylist = remember(playlists, snapshot.activePlaylistId) {
            playlists.firstOrNull { it.id == snapshot.activePlaylistId } ?: playlists.firstOrNull()
        }
        val tracks = activePlaylist?.tracks.orEmpty()
        val trackTitle = snapshot.currentTrackTitle.ifBlank {
            tracks.getOrNull(snapshot.currentTrackIndex)?.title.orEmpty()
        }
        val trackArtist = snapshot.currentTrackArtist.ifBlank {
            tracks.getOrNull(snapshot.currentTrackIndex)?.artist.orEmpty()
        }
        val ambientSummary = remember(snapshot.activeSounds) {
            if (snapshot.activeSounds.isEmpty()) {
                "Ambiente ativo"
            } else {
                snapshot.activeSounds.take(3).joinToString(" + ") { FocusAudioService.soundLabels[it] ?: it }
            }
        }
        val sleepMin = snapshot.sleepTimerEndAt?.let { endAt ->
            val rem = endAt - nowMs
            if (rem > 0L) ceil(rem / 60_000.0).toInt() else null
        }

        val primaryTitle = when {
            snapshot.musicPlaying && trackTitle.isNotBlank() -> trackTitle
            snapshot.ambientPlaying -> ambientSummary
            else -> "Reproduzindo áudio"
        }
        val secondarySubtitle = buildList {
            if (snapshot.musicPlaying && trackArtist.isNotBlank()) add(trackArtist)
            if (snapshot.musicPlaying && snapshot.ambientPlaying) add("🎧 $ambientSummary")
            if (snapshot.musicPlaying && snapshot.durationMs > 0L) {
                add("${formatAudioTimeMs(snapshot.positionMs)} / ${formatAudioTimeMs(snapshot.durationMs)}")
            }
            if (sleepMin != null) add("⏱ ${sleepMin}m")
        }.joinToString(" · ").ifBlank {
            if (snapshot.ambientPlaying) "Foco sonoro ativo · toque para ajustar" else "Toque para abrir o player"
        }

        val barContainerModifier = if (applyNavigationBarPadding) {
            Modifier.fillMaxWidth().navigationBarsPadding()
        } else {
            Modifier.fillMaxWidth()
        }

        Box(modifier = barContainerModifier) {
            if (collapsed) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Surface(
                        onClick = { collapsed = false },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (snapshot.musicPlaying) Icons.Default.MusicNote else Icons.Default.Headphones,
                                contentDescription = "Expandir player",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                primaryTitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 140.dp)
                            )
                            IconButton(
                                onClick = {
                                    FocusAudioStore.updateAmbientState(prefs, ambientPlaying = false)
                                    FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = false)
                                    FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                    runCatching {
                                        context.startService(
                                            Intent(context, FocusAudioService::class.java)
                                                .setAction(FocusAudioService.ACTION_PAUSE_ALL)
                                        )
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Pause,
                                    contentDescription = "Pausar reprodução",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Surface(
                    onClick = onOpenPlayer,
                    shape = studyControlShape(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                        ) {
                            Icon(
                                if (snapshot.musicPlaying) Icons.Default.MusicNote else Icons.Default.Headphones,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(7.dp).size(16.dp)
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(
                                primaryTitle,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                secondarySubtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = {
                                FocusAudioStore.updateAmbientState(prefs, ambientPlaying = false)
                                FocusAudioStore.updateMusicPlayback(prefs, musicPlaying = false)
                                FocusAudioUiHub.refreshSnapshotAsync(prefs)
                                runCatching {
                                    context.startService(
                                        Intent(context, FocusAudioService::class.java)
                                            .setAction(FocusAudioService.ACTION_PAUSE_ALL)
                                    )
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = "Pausar reprodução",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        if (snapshot.musicPlaying && tracks.size > 1) {
                            IconButton(
                                onClick = {
                                    runCatching {
                                        context.startForegroundService(
                                            Intent(context, FocusAudioService::class.java)
                                                .setAction(FocusAudioService.ACTION_NEXT_MUSIC)
                                        )
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.SkipNext,
                                    contentDescription = "Próxima faixa",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        IconButton(
                            onClick = { collapsed = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Minimizar player",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterPlaybackControlCard(
    snapshot: AudioPlaybackSnapshot,
    activePlaylist: MusicPlaylist?,
    sleepRemainingMinutes: Int?,
    onToggleAmbient: () -> Unit,
    onStopAmbient: () -> Unit,
    onToggleMusic: () -> Unit,
    onMasterVolumePreview: (Float) -> Unit,
    onMasterVolumeCommit: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenAutoAudio: () -> Unit
) {
    val activeLabels = remember(snapshot.activeSounds) {
        if (snapshot.activeSounds.isEmpty()) {
            "Nenhum som selecionado"
        } else {
            snapshot.activeSounds.map { FocusAudioService.soundLabels[it] ?: it }.joinToString(" + ")
        }
    }
    val hasBinauralActive = snapshot.activeSounds.any { it.startsWith("binaural_") }
    val tracks = activePlaylist?.tracks.orEmpty()
    val trackTitle = snapshot.currentTrackTitle.ifBlank {
        tracks.getOrNull(snapshot.currentTrackIndex)?.title ?: "Nenhuma faixa selecionada"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Default.Headphones,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Column {
                        Text(
                            "CONTROLE MASTER & REPRODUÇÃO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            when {
                                snapshot.ambientPlaying && snapshot.musicPlaying -> "Ambiente + Música ativos"
                                snapshot.ambientPlaying -> "Ambiente em reprodução"
                                snapshot.musicPlaying -> "Música local em reprodução"
                                else -> "Pronto para focar"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                if (hasBinauralActive) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                    ) {
                        Text(
                            "🎧 Binaural",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Dual Status Pills (Ambient & Music)
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val stackPills = maxWidth < 400.dp || fontScale >= 1.2f
                val ambientPill: @Composable (Modifier) -> Unit = { pillMod ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        modifier = pillMod
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Sons Ambientes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    activeLabels,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = onToggleAmbient, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (snapshot.ambientPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (snapshot.ambientPlaying) "Pausar sons" else "Tocar sons",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (snapshot.ambientPlaying) {
                                IconButton(onClick = onStopAmbient, modifier = Modifier.size(48.dp)) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = "Parar sons",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                val musicPill: @Composable (Modifier) -> Unit = { pillMod ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        modifier = pillMod
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    activePlaylist?.name ?: "Música Local",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (tracks.isEmpty()) "Sem faixas" else trackTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = onToggleMusic, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (snapshot.musicPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (snapshot.musicPlaying) "Pausar música" else "Tocar música",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (stackPills) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ambientPill(Modifier.fillMaxWidth())
                        musicPill(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ambientPill(Modifier.weight(1f))
                        musicPill(Modifier.weight(1f))
                    }
                }
            }

            // Master Volume Slider
            var draggingMaster by remember { mutableStateOf<Float?>(null) }
            val displayMaster = draggingMaster ?: snapshot.ambientMasterVolume
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    "Master ${(displayMaster * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp).widthIn(min = 76.dp)
                )
                Slider(
                    value = displayMaster,
                    onValueChange = { valNext ->
                        val clamped = valNext.coerceIn(0f, 1f)
                        draggingMaster = clamped
                        onMasterVolumePreview(clamped)
                    },
                    onValueChangeFinished = {
                        val committed = draggingMaster ?: snapshot.ambientMasterVolume
                        draggingMaster = null
                        onMasterVolumeCommit(committed)
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Sleep Timer & Auto-Audio Chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = onOpenSleepTimer,
                    label = {
                        Text(
                            if (sleepRemainingMinutes != null) {
                                "Sleep Timer · ${sleepRemainingMinutes}m"
                            } else {
                                "Sleep Timer"
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                val autoActiveCount = listOf(
                    snapshot.autoPlayOnStudy,
                    snapshot.autoPlayOnReading
                ).count { it }
                AssistChip(
                    onClick = onOpenAutoAudio,
                    label = {
                        Text(
                            if (autoActiveCount > 0) {
                                "Auto-Áudio ($autoActiveCount ativo${if (autoActiveCount > 1) "s" else ""})"
                            } else {
                                "Auto-Áudio (Estudo/Leitura)"
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun SoundLayerCard(
    sound: SoundCatalogOption,
    enabled: Boolean,
    volume: Float,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onVolumePreview: (Float) -> Unit = {},
    onVolumeChange: (Float) -> Unit
) {
    val contentColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    sound.icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    sound.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { onToggle() }
                )
            }
            if (sound.isBinaural) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    },
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                ) {
                    Text(
                        "🎧 Use fones",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                sound.description,
                color = mutedColor,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            var draggingVolume by remember(sound.key) { mutableStateOf<Float?>(null) }
            val displayVolume = draggingVolume ?: volume
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${(displayVolume * 100).roundToInt()}%",
                    color = mutedColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    softWrap = false
                )
                Slider(
                    value = displayVolume,
                    onValueChange = { next ->
                        val clamped = next.coerceIn(0f, 1f)
                        draggingVolume = clamped
                        onVolumePreview(clamped)
                    },
                    onValueChangeFinished = {
                        val committed = draggingVolume ?: volume
                        draggingVolume = null
                        onVolumeChange(committed)
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp),
                    valueRange = 0f..1f
                )
            }
        }
    }
}

@Composable
private fun MusicTransportCard(
    track: AudioTrackItem?,
    trackIndex: Int,
    totalTracks: Int,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffle: Boolean,
    repeatMode: String,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    val safeDur = durationMs.coerceAtLeast(0L)
    val safePos = if (safeDur > 0L) positionMs.coerceIn(0L, safeDur) else positionMs.coerceAtLeast(0L)
    var draggingPos by remember(track?.uri) { mutableStateOf<Float?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp).size(22.dp)
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        track?.title ?: "Sua música",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = buildList {
                        if (!track?.artist.isNullOrBlank()) add(track!!.artist)
                        add("Faixa ${trackIndex + 1} de $totalTracks")
                    }.joinToString(" · ")
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Parar música")
                }
            }

            // SeekBar with mm:ss labels
            val sliderMax = safeDur.coerceAtLeast(1L).toFloat()
            val sliderVal = draggingPos ?: safePos.toFloat().coerceIn(0f, sliderMax)
            Slider(
                value = sliderVal,
                onValueChange = { draggingPos = it },
                onValueChangeFinished = {
                    val target = draggingPos?.toLong() ?: safePos
                    draggingPos = null
                    onSeek(target)
                },
                valueRange = 0f..sliderMax,
                enabled = safeDur > 0L,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatAudioTimeMs(draggingPos?.toLong() ?: safePos),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (safeDur > 0L) formatAudioTimeMs(safeDur) else "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Transport Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = "Aleatório",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPrev) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Faixa anterior")
                }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Tocar"
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Próxima faixa")
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        when (repeatMode) {
                            FocusAudioStore.REPEAT_ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = "Repetição: $repeatMode",
                        tint = if (repeatMode != FocusAudioStore.REPEAT_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(
    currentRemainingMinutes: Int?,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep Timer (Desligamento Suave)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (currentRemainingMinutes != null) {
                        "Timer ativo: ~${currentRemainingMinutes} min restantes (com fade-out suave nos últimos 5s)."
                    } else {
                        "Pausa automaticamente sons ambientes e música com fade-out suave."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    options.forEach { mins ->
                        FilterChip(
                            selected = false,
                            onClick = { onSelectMinutes(mins) },
                            label = { Text("$mins min") }
                        )
                    }
                    FilterChip(
                        selected = currentRemainingMinutes == null,
                        onClick = { onSelectMinutes(0) },
                        label = { Text("Desligado") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun AutoAudioPreferencesDialog(
    snapshot: AudioPlaybackSnapshot,
    playlists: List<MusicPlaylist>,
    customMixes: List<CustomSoundMix>,
    onSave: (
        autoPlayStudy: Boolean,
        autoPlayReading: Boolean,
        autoPauseBreak: Boolean,
        studyTarget: String,
        readingTarget: String
    ) -> Unit,
    onDismiss: () -> Unit
) {
    var autoStudy by remember(snapshot) { mutableStateOf(snapshot.autoPlayOnStudy) }
    var autoReading by remember(snapshot) { mutableStateOf(snapshot.autoPlayOnReading) }
    var autoPauseBreak by remember(snapshot) { mutableStateOf(snapshot.autoPauseOnBreak) }
    var studyTarget by remember(snapshot) { mutableStateOf(snapshot.selectedStudyTarget) }
    var readingTarget by remember(snapshot) { mutableStateOf(snapshot.selectedReadingTarget) }

    val targetOptions = remember(playlists, customMixes) {
        buildList {
            add("" to "Mix ambiente atual")
            add("preset:binaural_alpha_focus" to "Preset: Foco Profundo (Alpha 10 Hz)")
            add("preset:binaural_theta_read" to "Preset: Leitura & Memória (Theta 6 Hz)")
            add("preset:binaural_beta_math" to "Preset: Cálculo & Lógica (Beta 16 Hz)")
            add("preset:binaural_gamma_sprint" to "Preset: Foco Máximo (Gamma 40 Hz)")
            add("preset:cosmic_focus" to "Preset: Imersão Cósmica")
            add("preset:waterfall_zen" to "Preset: Cachoeira & Tigela Tibetana")
            add("preset:mountain_cabin" to "Preset: Cabana na Montanha")
            add("preset:night_train_rain" to "Preset: Trem Noturno na Chuva")
            add("preset:lofi_rain" to "Preset: Lo-fi para Estudar")
            add("preset:lofi_coffee_real" to "Preset: Lo-fi & Cafeteria Real")
            add("preset:dawn_dove" to "Preset: Amanhecer com Pomba-Rolinha")
            add("preset:stream_birds" to "Preset: Riacho & Pássaros")
            add("preset:forest_rain_deep" to "Preset: Chuva na Mata")
            add("preset:forest_night_zen" to "Preset: Floresta Noturna")
            add("preset:piano_rain_window" to "Preset: Piano & Chuva na Janela")
            add("preset:library_focus" to "Preset: Biblioteca de Estudos")
            add("preset:rain_brown" to "Preset: Chuva Tranquila")
            customMixes.forEach { mix ->
                add("mix:${mix.id}" to "Meu Mix: ${mix.name}")
            }
            playlists.forEach { pl ->
                add("playlist:${pl.id}" to "Playlist: ${pl.name}")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automação Sonora (Estudo & Leitura)") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tocar ao iniciar Estudo", fontWeight = FontWeight.Medium)
                        Text(
                            "Inicia automaticamente ao dar play no Cronômetro Pomodoro.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoStudy, onCheckedChange = { autoStudy = it })
                }
                if (autoStudy) {
                    TargetDropdownSelector(
                        label = "Som para Estudo",
                        selectedKey = studyTarget,
                        options = targetOptions,
                        onSelect = { studyTarget = it }
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tocar ao iniciar Leitura", fontWeight = FontWeight.Medium)
                        Text(
                            "Inicia automaticamente ao abrir uma sessão de leitura de livro.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoReading, onCheckedChange = { autoReading = it })
                }
                if (autoReading) {
                    TargetDropdownSelector(
                        label = "Som para Leitura",
                        selectedKey = readingTarget,
                        options = targetOptions,
                        onSelect = { readingTarget = it }
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pausar nos intervalos", fontWeight = FontWeight.Medium)
                        Text(
                            "Pausa o áudio quando a sessão de estudo ou leitura é pausada/encerrada.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = autoPauseBreak, onCheckedChange = { autoPauseBreak = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(autoStudy, autoReading, autoPauseBreak, studyTarget, readingTarget) }
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun TargetDropdownSelector(
    label: String,
    selectedKey: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: "Mix ambiente atual"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SaveCustomMixDialog(
    activeSounds: Set<String>,
    volumes: Map<String, Float>,
    masterVolume: Float,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val summary = activeSounds.joinToString(" + ") { key ->
        val label = FocusAudioService.soundLabels[key] ?: key
        val defaultVol = soundCatalogDefaultVolumes[key] ?: FocusAudioStore.defaultSoundVolumes[key] ?: 0.35f
        val pct = ((volumes[key] ?: defaultVol) * 100).roundToInt()
        "$label ($pct%)"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Salvar como Mix") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Camadas: $summary · Master ${(masterVolume * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome do mix (ex.: Revisão Noturna)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome da playlist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

private fun extractAudioTrackMetadata(context: Context, uri: Uri): AudioTrackItem {
    val fallbackName = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
        ?.substringBeforeLast('.')
        ?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
        ?: "Faixa de música"

    var metaTitle = ""
    var metaArtist = ""
    var metaDuration = 0L

    runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
            metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
            metaDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    return AudioTrackItem(
        uri = uri.toString(),
        title = metaTitle.ifBlank { fallbackName },
        artist = metaArtist,
        durationMs = metaDuration.coerceAtLeast(0L)
    )
}

private fun formatAudioTimeMs(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1000L).toInt()
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
}

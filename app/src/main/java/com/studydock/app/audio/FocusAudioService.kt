package com.studydock.app.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.studydock.app.MainActivity
import com.studydock.app.R
import com.studydock.app.data.AudioTrackItem
import com.studydock.app.data.FocusAudioStore
import com.studydock.app.data.StudyLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Offline ambient sound synthesizer (v2 stereo + binaural beats) and local music playlist player.
 * Synchronizes full playback and sleep-timer state with [FocusAudioStore] (`"studydock_audio"`).
 */
class FocusAudioService : Service() {

    companion object {
        const val ACTION_PLAY = "com.studydock.app.audio.PLAY"
        const val ACTION_MIX = "com.studydock.app.audio.MIX"
        const val ACTION_PAUSE = "com.studydock.app.audio.PAUSE"
        const val ACTION_STOP = "com.studydock.app.audio.STOP"

        const val ACTION_PLAY_MUSIC = "com.studydock.app.audio.PLAY_MUSIC"
        const val ACTION_PAUSE_MUSIC = "com.studydock.app.audio.PAUSE_MUSIC"
        const val ACTION_STOP_MUSIC = "com.studydock.app.audio.STOP_MUSIC"
        const val ACTION_NEXT_MUSIC = "com.studydock.app.audio.NEXT_MUSIC"
        const val ACTION_PREV_MUSIC = "com.studydock.app.audio.PREV_MUSIC"
        const val ACTION_SEEK_MUSIC = "com.studydock.app.audio.SEEK_MUSIC"
        const val ACTION_SET_SHUFFLE = "com.studydock.app.audio.SET_SHUFFLE"
        const val ACTION_SET_REPEAT = "com.studydock.app.audio.SET_REPEAT"
        const val ACTION_UPDATE_PLAYLIST = "com.studydock.app.audio.UPDATE_PLAYLIST"

        const val ACTION_SET_SLEEP_TIMER = "com.studydock.app.audio.SET_SLEEP_TIMER"
        const val ACTION_SET_MASTER_VOLUME = "com.studydock.app.audio.SET_MASTER_VOLUME"
        const val ACTION_PAUSE_ALL = "com.studydock.app.audio.PAUSE_ALL"
        const val ACTION_PLAY_PRESET_TARGET = "com.studydock.app.audio.PLAY_PRESET_TARGET"

        const val EXTRA_SOUND = "sound"
        const val EXTRA_SOUNDS = "sounds"
        const val EXTRA_VOLUMES = "volumes"
        const val EXTRA_MASTER_VOLUME = "master_volume"
        const val EXTRA_PLAYLIST_ID = "playlist_id"
        const val EXTRA_TRACK_URIS = "track_uris"
        const val EXTRA_TRACK_TITLES = "track_titles"
        const val EXTRA_TRACK_ARTISTS = "track_artists"
        const val EXTRA_TRACK_INDEX = "track_index"
        const val EXTRA_SEEK_MS = "seek_ms"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_REPEAT_MODE = "repeat_mode"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_PRESET_TARGET = "preset_target"
        const val EXTRA_CONTEXT_TYPE = "context_type"

        const val CHANNEL = "study_audio"
        private const val TAG = "FocusAudioService"
        private const val NOTIFICATION_ID = 41
        private const val PROGRESS_INTERVAL_MS = 750L
        private const val FADE_OUT_WINDOW_MS = 5_000L

        val presetMixes: Map<String, Map<String, Float>> = mapOf(
            "lofi_rain" to mapOf("lofi" to 0.30f, "rain" to 0.36f),
            "rain_brown" to mapOf("rain" to 0.38f, "brown" to 0.35f),
            "binaural_alpha_focus" to mapOf("binaural_alpha" to 0.32f, "brown" to 0.24f),
            "binaural_theta_read" to mapOf("binaural_theta" to 0.30f, "rain" to 0.25f),
            "binaural_beta_math" to mapOf("binaural_beta" to 0.30f, "green_noise" to 0.26f),
            "binaural_gamma_sprint" to mapOf("binaural_gamma" to 0.26f, "rain_window" to 0.28f),
            "binaural_delta_rest" to mapOf("binaural_delta" to 0.32f, "waves" to 0.24f),
            "cosmic_focus" to mapOf("ambient_pad" to 0.28f, "green_noise" to 0.24f),
            "waterfall_zen" to mapOf("waterfall" to 0.34f, "singing_bowl" to 0.26f),
            "mountain_cabin" to mapOf("mountain_wind" to 0.30f, "fire" to 0.28f, "rain_window" to 0.20f),
            "night_train_rain" to mapOf("train" to 0.34f, "rain_window" to 0.26f),
            "library_focus" to mapOf("library" to 0.38f, "keyboard" to 0.20f),
            "storm_fire" to mapOf("thunder" to 0.36f, "fire" to 0.28f),
            "coffee_pink" to mapOf("coffee" to 0.28f, "pink" to 0.25f),
            "waves_pink" to mapOf("waves" to 0.35f, "pink" to 0.24f),
            "fire_rain" to mapOf("fire" to 0.28f, "rain" to 0.34f),
            "dawn_dove" to mapOf("mourning_dove" to 0.34f, "forest" to 0.22f, "wind_chimes" to 0.14f),
            "stream_birds" to mapOf("forest_stream" to 0.36f, "birds" to 0.28f, "forest" to 0.18f),
            "forest_night_zen" to mapOf("forest_night" to 0.34f, "soft_piano" to 0.20f),
            "lofi_coffee_real" to mapOf("lofi" to 0.30f, "coffee" to 0.26f, "rain_window" to 0.20f),
            "forest_rain_deep" to mapOf("forest_rain" to 0.40f, "brown" to 0.20f),
            "piano_rain_window" to mapOf("soft_piano" to 0.28f, "rain_window" to 0.34f, "fire" to 0.16f)
        )

        val defaultSoundVolumes: Map<String, Float> = mapOf(
            "lofi" to 0.24f,
            "soft_piano" to 0.26f,
            "wind_chimes" to 0.24f,
            "ambient_pad" to 0.26f,
            "singing_bowl" to 0.28f,
            "binaural_alpha" to 0.30f,
            "binaural_theta" to 0.30f,
            "binaural_beta" to 0.28f,
            "binaural_gamma" to 0.25f,
            "binaural_delta" to 0.30f,
            "birds" to 0.30f,
            "mourning_dove" to 0.32f,
            "forest" to 0.28f,
            "forest_stream" to 0.34f,
            "forest_rain" to 0.36f,
            "forest_night" to 0.30f,
            "mountain_wind" to 0.30f,
            "rain" to 0.38f,
            "rain_window" to 0.35f,
            "thunder" to 0.34f,
            "waves" to 0.35f,
            "waterfall" to 0.34f,
            "coffee" to 0.26f,
            "library" to 0.32f,
            "keyboard" to 0.28f,
            "fire" to 0.26f,
            "fan" to 0.34f,
            "train" to 0.32f,
            "brown" to 0.38f,
            "pink" to 0.30f,
            "green_noise" to 0.34f,
            "white" to 0.25f
        )

        val soundLabels: Map<String, String> = mapOf(
            "lofi" to "Lo-fi Quente",
            "soft_piano" to "Piano Ambiente",
            "wind_chimes" to "Sinos ao Vento",
            "ambient_pad" to "Pad Cósmico",
            "singing_bowl" to "Tigela Tibetana",
            "white" to "Ruído branco",
            "pink" to "Ruído rosa",
            "brown" to "Ruído marrom",
            "green_noise" to "Ruído verde",
            "rain" to "Chuva",
            "rain_window" to "Chuva na Janela",
            "thunder" to "Tempestade",
            "waves" to "Ondas",
            "waterfall" to "Cachoeira",
            "coffee" to "Cafeteria",
            "library" to "Biblioteca",
            "keyboard" to "Teclado mecânico",
            "fire" to "Lareira",
            "fan" to "Ventilador Suave",
            "train" to "Trem nos Trilhos",
            "forest" to "Floresta & Copa",
            "forest_stream" to "Riacho na Floresta",
            "forest_rain" to "Chuva na Mata",
            "forest_night" to "Floresta Noturna",
            "mountain_wind" to "Vento na Montanha",
            "birds" to "Pássaros da Manhã",
            "mourning_dove" to "Pomba-Rolinha",
            "binaural_alpha" to "Binaural Alpha 10Hz",
            "binaural_theta" to "Binaural Theta 6Hz",
            "binaural_beta" to "Binaural Beta 16Hz",
            "binaural_gamma" to "Binaural Gamma 40Hz",
            "binaural_delta" to "Binaural Delta 3Hz"
        )

        private const val SOUND_WHITE = 0
        private const val SOUND_PINK = 1
        private const val SOUND_BROWN = 2
        private const val SOUND_RAIN = 3
        private const val SOUND_THUNDER = 4
        private const val SOUND_LOFI = 5
        private const val SOUND_BINAURAL_ALPHA = 6
        private const val SOUND_BINAURAL_THETA = 7
        private const val SOUND_LIBRARY = 8
        private const val SOUND_KEYBOARD = 9
        private const val SOUND_COFFEE = 10
        private const val SOUND_FIRE = 11
        private const val SOUND_WAVES = 12
        private const val SOUND_FOREST = 13
        private const val SOUND_BIRDS = 14
        private const val SOUND_MOURNING_DOVE = 15
        private const val SOUND_FOREST_STREAM = 16
        private const val SOUND_FOREST_RAIN = 17
        private const val SOUND_FOREST_NIGHT = 18
        private const val SOUND_RAIN_WINDOW = 19
        private const val SOUND_WIND_CHIMES = 20
        private const val SOUND_SOFT_PIANO = 21
        private const val SOUND_BINAURAL_BETA = 22
        private const val SOUND_BINAURAL_GAMMA = 23
        private const val SOUND_BINAURAL_DELTA = 24
        private const val SOUND_GREEN_NOISE = 25
        private const val SOUND_WATERFALL = 26
        private const val SOUND_MOUNTAIN_WIND = 27
        private const val SOUND_SINGING_BOWL = 28
        private const val SOUND_FAN = 29
        private const val SOUND_TRAIN = 30
        private const val SOUND_AMBIENT_PAD = 31
        private const val SOUND_COUNT = 32

        private fun soundIndexOf(key: String): Int = when (key) {
            "white" -> SOUND_WHITE
            "pink" -> SOUND_PINK
            "brown" -> SOUND_BROWN
            "green_noise" -> SOUND_GREEN_NOISE
            "rain" -> SOUND_RAIN
            "thunder" -> SOUND_THUNDER
            "lofi" -> SOUND_LOFI
            "ambient_pad" -> SOUND_AMBIENT_PAD
            "singing_bowl" -> SOUND_SINGING_BOWL
            "binaural_alpha" -> SOUND_BINAURAL_ALPHA
            "binaural_theta" -> SOUND_BINAURAL_THETA
            "binaural_beta" -> SOUND_BINAURAL_BETA
            "binaural_gamma" -> SOUND_BINAURAL_GAMMA
            "binaural_delta" -> SOUND_BINAURAL_DELTA
            "library" -> SOUND_LIBRARY
            "keyboard" -> SOUND_KEYBOARD
            "coffee" -> SOUND_COFFEE
            "fire" -> SOUND_FIRE
            "fan" -> SOUND_FAN
            "train" -> SOUND_TRAIN
            "waves" -> SOUND_WAVES
            "waterfall" -> SOUND_WATERFALL
            "forest" -> SOUND_FOREST
            "mountain_wind" -> SOUND_MOUNTAIN_WIND
            "birds" -> SOUND_BIRDS
            "mourning_dove" -> SOUND_MOURNING_DOVE
            "forest_stream" -> SOUND_FOREST_STREAM
            "forest_rain" -> SOUND_FOREST_RAIN
            "forest_night" -> SOUND_FOREST_NIGHT
            "rain_window" -> SOUND_RAIN_WINDOW
            "wind_chimes" -> SOUND_WIND_CHIMES
            "soft_piano" -> SOUND_SOFT_PIANO
            else -> SOUND_BROWN
        }
    }

    @Volatile private var playing = false
    @Volatile private var activeSounds: Map<String, Float> = mapOf("brown" to 0.38f)
    @Volatile private var ambientMasterVolume: Float = 0.85f
    @Volatile private var sleepFadeFactor: Float = 1.0f
    @Volatile private var duckFactor: Float = 1.0f

    @Volatile private var activePlaylistId: String = FocusAudioStore.DEFAULT_PLAYLIST_ID
    @Volatile private var playlistTracks: List<AudioTrackItem> = emptyList()
    @Volatile private var musicPlaying = false
    @Volatile private var musicIndex = 0
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var shuffle = false
    @Volatile private var repeatMode = FocusAudioStore.REPEAT_ALL
    @Volatile private var sleepTimerEndAt: Long? = null

    private var mediaPlayer: MediaPlayer? = null
    private var musicPrepared = false
    @Volatile private var worker: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var playbackGeneration: Long = 0L
    private var consecutiveTrackFailures: Int = 0
    private val metadataExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var activeTrackRelease: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressLoopRunning = false
    private var lastNotificationRemainingMin = -1

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeAmbientOnFocusGain = false
    private var resumeMusicOnFocusGain = false
    private var noisyReceiverRegistered = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAmbientOnFocusGain = false
                resumeMusicOnFocusGain = false
                hasAudioFocus = false
                duckFactor = 1.0f
                applyMediaPlayerVolume()
                pauseAllInternal(updateNotif = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeAmbientOnFocusGain = resumeAmbientOnFocusGain || playing
                resumeMusicOnFocusGain = resumeMusicOnFocusGain || musicPlaying
                hasAudioFocus = false
                duckFactor = 1.0f
                applyMediaPlayerVolume()
                pauseAllInternal(updateNotif = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckFactor = 0.30f
                applyMediaPlayerVolume()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                duckFactor = 1.0f
                applyMediaPlayerVolume()
                if (resumeAmbientOnFocusGain && !playing) {
                    resumeAmbientOnFocusGain = false
                    startPlayback()
                    ensureProgressLoop()
                }
                if (resumeMusicOnFocusGain && !musicPlaying) {
                    resumeMusicOnFocusGain = false
                    resumeOrStartMusic(musicIndex)
                    ensureProgressLoop()
                }
            }
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeAmbientOnFocusGain = false
                resumeMusicOnFocusGain = false
                pauseAllInternal(updateNotif = true)
            }
        }
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val sleepEnd = sleepTimerEndAt
            if (sleepEnd != null) {
                val remaining = sleepEnd - now
                if (remaining <= 0L) {
                    sleepTimerEndAt = null
                    sleepFadeFactor = 1.0f
                    FocusAudioStore.setSleepTimerEndAt(this@FocusAudioService, null)
                    applyMediaPlayerVolume()
                    pauseAllInternal(updateNotif = true)
                    abandonAudioFocus()
                    progressLoopRunning = false
                    return
                } else if (remaining <= FADE_OUT_WINDOW_MS) {
                    sleepFadeFactor = (remaining.toFloat() / FADE_OUT_WINDOW_MS.toFloat()).coerceIn(0.04f, 1.0f)
                    applyMediaPlayerVolume()
                } else {
                    if (sleepFadeFactor != 1.0f) {
                        sleepFadeFactor = 1.0f
                        applyMediaPlayerVolume()
                    }
                }
                val remMin = ceil(remaining / 60_000.0).toInt()
                if (remMin != lastNotificationRemainingMin) {
                    lastNotificationRemainingMin = remMin
                    updateNotificationIfRunning()
                }
            } else {
                if (sleepFadeFactor != 1.0f) {
                    sleepFadeFactor = 1.0f
                    applyMediaPlayerVolume()
                }
                lastNotificationRemainingMin = -1
            }

            if (musicPlaying && mediaPlayer != null && musicPrepared) {
                val pos = runCatching { mediaPlayer?.currentPosition?.toLong() ?: positionMs }
                    .onFailure { StudyLog.w(TAG, "Failed to read MediaPlayer position in progress loop", it) }
                    .getOrDefault(positionMs)
                    .coerceAtLeast(0L)
                val dur = runCatching { mediaPlayer?.duration?.toLong() ?: durationMs }
                    .onFailure { StudyLog.w(TAG, "Failed to read MediaPlayer duration in progress loop", it) }
                    .getOrDefault(durationMs)
                    .coerceAtLeast(0L)
                positionMs = pos
                if (dur > 0L) durationMs = dur
                syncMusicStore()
            }

            if (playing || musicPlaying || sleepTimerEndAt != null) {
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            } else {
                progressLoopRunning = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            StudyLog.w(TAG, "AudioManager unavailable in FocusAudioService")
        }
        registerNoisyReceiver()
        runCatching { hydrateFromStore() }
            .onFailure { StudyLog.e(TAG, "Failed to hydrate FocusAudioStore state", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MIX -> {
                readMix(intent)
                syncAmbientStore()
                if (activeSounds.isEmpty() && playing) {
                    pausePlayback()
                } else {
                    updateNotificationIfRunning()
                }
            }
            ACTION_PLAY -> {
                if (!startForegroundSafely()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                readMix(intent)
                if (activeSounds.isEmpty()) {
                    val singleKey = intent.getStringExtra(EXTRA_SOUND)?.takeIf { it.isNotBlank() }
                    val snap = FocusAudioStore.readSnapshot(this)
                    val keys = if (singleKey != null) setOf(singleKey) else snap.activeSounds.ifEmpty { setOf("brown") }
                    activeSounds = keys.associateWith { key ->
                        (snap.soundVolumes[key] ?: defaultVolumeFor(key)).coerceIn(0.05f, 1f)
                    }
                }
                sleepFadeFactor = 1.0f
                duckFactor = 1.0f
                requestAudioFocus()
                startPlayback()
                ensureProgressLoop()
            }
            ACTION_PAUSE -> {
                pausePlayback()
            }
            ACTION_STOP -> {
                stopPlayback()
                if (mediaPlayer == null && !musicPlaying) {
                    abandonAudioFocus()
                    stopForegroundSafely("ACTION_STOP")
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
            ACTION_SET_MASTER_VOLUME -> {
                if (intent.hasExtra(EXTRA_MASTER_VOLUME)) {
                    ambientMasterVolume = intent.getFloatExtra(EXTRA_MASTER_VOLUME, ambientMasterVolume).coerceIn(0f, 1f)
                    FocusAudioStore.updateAmbientState(this, masterVolume = ambientMasterVolume)
                } else {
                    ambientMasterVolume = FocusAudioStore.readSnapshot(this).ambientMasterVolume
                }
                updateNotificationIfRunning()
            }
            ACTION_PLAY_MUSIC -> {
                readPlaylistFromIntentOrStore(intent)
                if (playlistTracks.isNotEmpty()) {
                    musicIndex = intent.getIntExtra(EXTRA_TRACK_INDEX, musicIndex)
                        .coerceIn(0, playlistTracks.lastIndex)
                }
                if (!startForegroundSafely()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                if (playlistTracks.isNotEmpty()) {
                    val requestedIndex = musicIndex
                    sleepFadeFactor = 1.0f
                    duckFactor = 1.0f
                    requestAudioFocus()
                    resumeOrStartMusic(requestedIndex)
                    ensureProgressLoop()
                } else {
                    stopSelfIfIdle()
                }
            }
            ACTION_UPDATE_PLAYLIST -> {
                readPlaylistFromIntentOrStore(intent)
                if (playlistTracks.isEmpty()) {
                    stopMusic()
                } else {
                    musicIndex = intent.getIntExtra(EXTRA_TRACK_INDEX, musicIndex)
                        .coerceIn(0, playlistTracks.lastIndex)
                    syncMusicStore()
                }
                updateNotificationIfRunning()
            }
            ACTION_PAUSE_MUSIC -> {
                pauseMusicPlayback()
                updateNotificationIfRunning()
            }
            ACTION_STOP_MUSIC -> {
                stopMusic()
                if (!playing) {
                    abandonAudioFocus()
                    stopForegroundSafely("ACTION_STOP_MUSIC")
                    stopSelf()
                } else {
                    updateNotification()
                }
            }
            ACTION_NEXT_MUSIC -> {
                if (!startForegroundSafely()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                readPlaylistFromIntentOrStore(intent)
                if (playlistTracks.isNotEmpty()) {
                    val nextIdx = computeNextTrackIndex(forceAdvance = true)
                    duckFactor = 1.0f
                    requestAudioFocus()
                    startMusic(nextIdx)
                    ensureProgressLoop()
                } else {
                    stopSelfIfIdle()
                }
            }
            ACTION_PREV_MUSIC -> {
                if (!startForegroundSafely()) {
                    stopSelfIfIdle()
                    return START_NOT_STICKY
                }
                readPlaylistFromIntentOrStore(intent)
                if (playlistTracks.isNotEmpty()) {
                    val currentPos = if (mediaPlayer != null && musicPrepared) {
                        runCatching { mediaPlayer?.currentPosition ?: 0 }
                            .onFailure { StudyLog.w(TAG, "Failed to read MediaPlayer position on prev", it) }
                            .getOrDefault(0)
                    } else 0
                    if (currentPos > 3_500) {
                        runCatching { mediaPlayer?.seekTo(0) }
                            .onFailure { StudyLog.w(TAG, "Failed to seek MediaPlayer to start on prev", it) }
                        positionMs = 0L
                        if (!musicPlaying && musicPrepared) {
                            duckFactor = 1.0f
                            requestAudioFocus()
                            runCatching { mediaPlayer?.start() }
                                .onSuccess { musicPlaying = true }
                                .onFailure {
                                    StudyLog.w(TAG, "Failed to start MediaPlayer on prev", it)
                                    musicPlaying = false
                                }
                        }
                        syncMusicStore()
                        updateNotification()
                    } else {
                        val prevIdx = computePrevTrackIndex()
                        duckFactor = 1.0f
                        requestAudioFocus()
                        startMusic(prevIdx)
                    }
                    ensureProgressLoop()
                } else {
                    stopSelfIfIdle()
                }
            }
            ACTION_SEEK_MUSIC -> {
                val targetMs = if (intent.hasExtra(EXTRA_SEEK_MS)) {
                    intent.getLongExtra(EXTRA_SEEK_MS, -1L).takeIf { it >= 0L }
                        ?: intent.getIntExtra(EXTRA_SEEK_MS, 0).toLong().coerceAtLeast(0L)
                } else {
                    positionMs
                }
                positionMs = if (durationMs > 0L) targetMs.coerceIn(0L, durationMs) else targetMs.coerceAtLeast(0L)
                if (mediaPlayer != null && musicPrepared) {
                    runCatching { mediaPlayer?.seekTo(positionMs.toInt()) }
                        .onFailure { StudyLog.w(TAG, "Failed to seek MediaPlayer to ${positionMs}ms", it) }
                }
                syncMusicStore()
            }
            ACTION_SET_SHUFFLE -> {
                shuffle = if (intent.hasExtra(EXTRA_SHUFFLE)) {
                    intent.getBooleanExtra(EXTRA_SHUFFLE, false)
                } else {
                    !shuffle
                }
                FocusAudioStore.updateMusicPlayback(this, shuffle = shuffle)
                FocusAudioStore.activePlaylist(this)?.let { activePl ->
                    FocusAudioStore.updatePlaylist(this, activePl.copy(shuffle = shuffle))
                }
                updateNotificationIfRunning()
            }
            ACTION_SET_REPEAT -> {
                repeatMode = if (intent.hasExtra(EXTRA_REPEAT_MODE)) {
                    FocusAudioStore.normalizeRepeatMode(intent.getStringExtra(EXTRA_REPEAT_MODE))
                } else {
                    when (repeatMode) {
                        FocusAudioStore.REPEAT_ALL -> FocusAudioStore.REPEAT_ONE
                        FocusAudioStore.REPEAT_ONE -> FocusAudioStore.REPEAT_OFF
                        else -> FocusAudioStore.REPEAT_ALL
                    }
                }
                FocusAudioStore.updateMusicPlayback(this, repeatMode = repeatMode)
                FocusAudioStore.activePlaylist(this)?.let { activePl ->
                    FocusAudioStore.updatePlaylist(this, activePl.copy(loopMode = repeatMode))
                }
                updateNotificationIfRunning()
            }
            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0)
                if (minutes <= 0) {
                    sleepTimerEndAt = null
                    sleepFadeFactor = 1.0f
                    FocusAudioStore.setSleepTimerEndAt(this, null)
                    applyMediaPlayerVolume()
                } else {
                    val endAt = System.currentTimeMillis() + minutes * 60_000L
                    sleepTimerEndAt = endAt
                    sleepFadeFactor = 1.0f
                    FocusAudioStore.setSleepTimerEndAt(this, endAt)
                    ensureProgressLoop()
                }
                updateNotificationIfRunning()
            }
            ACTION_PAUSE_ALL -> {
                pauseAllInternal(updateNotif = true)
            }
            ACTION_PLAY_PRESET_TARGET -> {
                handlePlayPresetTarget(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun hydrateFromStore() {
        val snap = FocusAudioStore.readSnapshot(this)
        ambientMasterVolume = snap.ambientMasterVolume
        val initialActive = snap.activeSounds.ifEmpty { setOf("brown") }
        activeSounds = initialActive.associateWith { key ->
            (snap.soundVolumes[key] ?: defaultVolumeFor(key)).coerceIn(0.05f, 1f)
        }
        activePlaylistId = snap.activePlaylistId
        musicIndex = snap.currentTrackIndex
        positionMs = snap.positionMs
        durationMs = snap.durationMs
        shuffle = snap.shuffle
        repeatMode = snap.repeatMode
        val now = System.currentTimeMillis()
        sleepTimerEndAt = snap.sleepTimerEndAt?.takeIf { it > now }
        if (snap.sleepTimerEndAt != null && sleepTimerEndAt == null) {
            FocusAudioStore.setSleepTimerEndAt(this, null)
        }
        val activePlaylist = FocusAudioStore.activePlaylist(this)
        if (activePlaylist != null) {
            activePlaylistId = activePlaylist.id
            playlistTracks = activePlaylist.tracks
            if (playlistTracks.isNotEmpty()) {
                musicIndex = musicIndex.coerceIn(0, playlistTracks.lastIndex)
            }
        }
    }

    private fun handlePlayPresetTarget(intent: Intent) {
        val snap = FocusAudioStore.readSnapshot(this)
        ambientMasterVolume = snap.ambientMasterVolume
        val explicitTarget = intent.getStringExtra(EXTRA_PRESET_TARGET)?.trim().orEmpty()
        val contextType = intent.getStringExtra(EXTRA_CONTEXT_TYPE)?.trim().orEmpty()
        val target = when {
            explicitTarget.isNotEmpty() -> explicitTarget
            contextType.equals("READING", ignoreCase = true) ||
                contextType.equals("Leitura", ignoreCase = true) -> snap.selectedReadingTarget.trim()
            else -> snap.selectedStudyTarget.trim()
        }

        sleepFadeFactor = 1.0f
        requestAudioFocus()
        startForegroundSafely()

        when {
            target.startsWith("playlist:", ignoreCase = true) -> {
                val plId = target.substringAfter(":").trim()
                val playlists = FocusAudioStore.readPlaylists(this)
                val chosen = playlists.firstOrNull { it.id == plId } ?: playlists.firstOrNull()
                if (chosen != null && chosen.tracks.isNotEmpty()) {
                    activePlaylistId = chosen.id
                    FocusAudioStore.setActivePlaylist(this, chosen.id)
                    playlistTracks = chosen.tracks
                    musicIndex = musicIndex.coerceIn(0, playlistTracks.lastIndex)
                    resumeOrStartMusic(musicIndex)
                    ensureProgressLoop()
                    return
                }
            }
            target.equals("MUSIC", ignoreCase = true) -> {
                val chosen = FocusAudioStore.activePlaylist(this)
                if (chosen != null && chosen.tracks.isNotEmpty()) {
                    activePlaylistId = chosen.id
                    playlistTracks = chosen.tracks
                    musicIndex = musicIndex.coerceIn(0, playlistTracks.lastIndex)
                    resumeOrStartMusic(musicIndex)
                    ensureProgressLoop()
                    return
                }
            }
            target.startsWith("mix:", ignoreCase = true) -> {
                val mixId = target.substringAfter(":").trim()
                val customMix = FocusAudioStore.readCustomMixes(this).firstOrNull { it.id == mixId }
                if (customMix != null && customMix.volumes.isNotEmpty()) {
                    activeSounds = customMix.volumes.filterValues { it > 0.001f }
                    ambientMasterVolume = customMix.masterVolume.coerceIn(0f, 1f)
                    syncAmbientStore()
                    startPlayback()
                    ensureProgressLoop()
                    return
                }
            }
            target.startsWith("preset:", ignoreCase = true) || presetMixes.containsKey(target) -> {
                val presetKey = target.removePrefix("preset:").trim()
                val mix = presetMixes[presetKey]
                if (mix != null && mix.isNotEmpty()) {
                    activeSounds = mix
                    syncAmbientStore()
                    startPlayback()
                    ensureProgressLoop()
                    return
                }
            }
            target in FocusAudioStore.allSoundKeys || soundLabels.containsKey(target) -> {
                val vol = (snap.soundVolumes[target]
                    ?: defaultSoundVolumes[target]
                    ?: FocusAudioStore.defaultSoundVolumes[target]
                    ?: 0.35f).coerceIn(0.05f, 1f)
                activeSounds = mapOf(target to vol)
                syncAmbientStore()
                startPlayback()
                ensureProgressLoop()
                return
            }
        }

        // Default fallback: play current active ambient mix
        val fallbackKeys = snap.activeSounds.ifEmpty { setOf("brown") }
        activeSounds = fallbackKeys.associateWith { key ->
            (snap.soundVolumes[key] ?: defaultVolumeFor(key)).coerceIn(0.05f, 1f)
        }
        syncAmbientStore()
        startPlayback()
        ensureProgressLoop()
    }

    private fun readMix(intent: Intent) {
        if (intent.hasExtra(EXTRA_MASTER_VOLUME)) {
            ambientMasterVolume = intent.getFloatExtra(EXTRA_MASTER_VOLUME, ambientMasterVolume).coerceIn(0f, 1f)
        }
        val keys = intent.getStringArrayListExtra(EXTRA_SOUNDS) ?: return
        val volumes = intent.getFloatArrayExtra(EXTRA_VOLUMES) ?: FloatArray(keys.size) { 0.38f }
        activeSounds = keys.mapIndexedNotNull { index, key ->
            val value = volumes.getOrNull(index)?.coerceIn(0f, 1f) ?: 0.38f
            if (value <= 0.001f) null else key to value
        }.toMap()
    }

    private fun readPlaylistFromIntentOrStore(intent: Intent) {
        val requestedPlId = intent.getStringExtra(EXTRA_PLAYLIST_ID)?.trim()?.takeIf { it.isNotEmpty() }
        if (requestedPlId != null) {
            activePlaylistId = requestedPlId
            FocusAudioStore.setActivePlaylist(this, requestedPlId)
        }
        if (intent.hasExtra(EXTRA_SHUFFLE)) {
            shuffle = intent.getBooleanExtra(EXTRA_SHUFFLE, shuffle)
        }
        if (intent.hasExtra(EXTRA_REPEAT_MODE)) {
            repeatMode = FocusAudioStore.normalizeRepeatMode(intent.getStringExtra(EXTRA_REPEAT_MODE))
        }

        val uris = intent.getStringArrayListExtra(EXTRA_TRACK_URIS)
        val titles = intent.getStringArrayListExtra(EXTRA_TRACK_TITLES)
        val artists = intent.getStringArrayListExtra(EXTRA_TRACK_ARTISTS)

        val storedPlaylist = FocusAudioStore.readPlaylists(this)
            .firstOrNull { it.id == activePlaylistId }
            ?: FocusAudioStore.activePlaylist(this)

        if (uris != null) {
            val byUri = storedPlaylist?.tracks?.associateBy { it.uri }.orEmpty()
            playlistTracks = uris.mapIndexed { idx, uri ->
                val existing = byUri[uri]
                AudioTrackItem(
                    uri = uri,
                    title = titles?.getOrNull(idx)?.takeIf { it.isNotBlank() }
                        ?: existing?.title
                        ?: "Faixa ${idx + 1}",
                    artist = artists?.getOrNull(idx)?.takeIf { it.isNotBlank() }
                        ?: existing?.artist
                        ?: "",
                    durationMs = existing?.durationMs ?: 0L
                )
            }
        } else if (storedPlaylist != null) {
            activePlaylistId = storedPlaylist.id
            playlistTracks = storedPlaylist.tracks
        }
    }

    private fun currentTrackTitle(): String =
        playlistTracks.getOrNull(musicIndex)?.title.orEmpty()

    private fun currentTrackArtist(): String =
        playlistTracks.getOrNull(musicIndex)?.artist.orEmpty()

    private fun syncAmbientStore() {
        FocusAudioStore.updateAmbientState(
            context = this,
            ambientPlaying = playing,
            activeSounds = activeSounds.keys,
            soundVolumes = activeSounds,
            masterVolume = ambientMasterVolume
        )
    }

    private fun syncMusicStore() {
        FocusAudioStore.updateMusicPlayback(
            context = this,
            musicPlaying = musicPlaying,
            activePlaylistId = activePlaylistId,
            currentTrackIndex = musicIndex,
            currentTrackTitle = currentTrackTitle(),
            currentTrackArtist = currentTrackArtist(),
            positionMs = positionMs,
            durationMs = durationMs,
            shuffle = shuffle,
            repeatMode = repeatMode
        )
    }

    private fun computeNextTrackIndex(forceAdvance: Boolean): Int {
        if (playlistTracks.isEmpty()) return 0
        if (!forceAdvance && repeatMode == FocusAudioStore.REPEAT_ONE) {
            return musicIndex.coerceIn(0, playlistTracks.lastIndex)
        }
        if (shuffle && playlistTracks.size > 1) {
            val candidates = playlistTracks.indices.filter { it != musicIndex }
            return candidates.random()
        }
        return (musicIndex + 1) % playlistTracks.size
    }

    private fun computePrevTrackIndex(): Int {
        if (playlistTracks.isEmpty()) return 0
        if (shuffle && playlistTracks.size > 1) {
            val candidates = playlistTracks.indices.filter { it != musicIndex }
            return candidates.random()
        }
        return if (musicIndex - 1 < 0) playlistTracks.lastIndex else musicIndex - 1
    }

    private fun resumeOrStartMusic(index: Int) {
        if (playlistTracks.isEmpty()) return
        val bounded = index.coerceIn(0, playlistTracks.lastIndex)
        if (mediaPlayer != null && musicPrepared && musicIndex == bounded) {
            applyMediaPlayerVolume()
            runCatching { mediaPlayer?.start() }
                .onSuccess { musicPlaying = true }
                .onFailure {
                    StudyLog.w(TAG, "Failed to resume MediaPlayer at index=$bounded", it)
                    musicPlaying = false
                }
            syncMusicStore()
            updateNotification()
        } else {
            startMusic(bounded)
        }
    }

    private fun startMusic(index: Int) {
        if (playlistTracks.isEmpty()) return
        musicIndex = index.coerceIn(0, playlistTracks.lastIndex)
        val trackItem = playlistTracks[musicIndex]
        positionMs = 0L
        durationMs = trackItem.durationMs.coerceAtLeast(0L)
        syncMusicStore()

        runCatching {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.release()
        }.onFailure {
            StudyLog.w(TAG, "Failed to release previous MediaPlayer", it)
        }
        musicPrepared = false
        musicPlaying = false

        // Extract metadata off main thread if missing artist or duration
        if ((trackItem.artist.isBlank() || trackItem.durationMs <= 0L) && !metadataExecutor.isShutdown) {
            val targetPlaylistId = activePlaylistId
            val targetIndex = musicIndex
            val targetUri = trackItem.uri
            metadataExecutor.execute {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(applicationContext, Uri.parse(targetUri))
                        val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim().orEmpty()
                        val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim().orEmpty()
                        val metaDur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (metaArtist.isNotEmpty() || metaDur > 0L) {
                            mainHandler.post {
                                if (activePlaylistId == targetPlaylistId &&
                                    targetIndex in playlistTracks.indices &&
                                    playlistTracks[targetIndex].uri == targetUri
                                ) {
                                    val current = playlistTracks[targetIndex]
                                    val updatedItem = current.copy(
                                        title = if (current.title.isBlank() && metaTitle.isNotEmpty()) metaTitle else current.title,
                                        artist = if (current.artist.isBlank()) metaArtist else current.artist,
                                        durationMs = if (metaDur > 0L) metaDur else current.durationMs
                                    )
                                    playlistTracks = playlistTracks.toMutableList().also { it[targetIndex] = updatedItem }
                                    if (musicIndex == targetIndex && updatedItem.durationMs > 0L) {
                                        durationMs = updatedItem.durationMs
                                    }
                                    FocusAudioStore.updatePlaylistTracks(this@FocusAudioService, targetPlaylistId, playlistTracks)
                                    syncMusicStore()
                                    updateNotificationIfRunning()
                                }
                            }
                        }
                    } finally {
                        runCatching { retriever.release() }
                            .onFailure { StudyLog.w(TAG, "Failed to release MediaMetadataRetriever", it) }
                    }
                }.onFailure {
                    StudyLog.w(TAG, "Failed to extract audio metadata for track index=$targetIndex: ${it::class.java.simpleName}", it)
                }
            }
        }

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(applicationContext, Uri.parse(playlistTracks[musicIndex].uri))
            player.setOnPreparedListener { prepared ->
                if (mediaPlayer === prepared) {
                    consecutiveTrackFailures = 0
                    musicPrepared = true
                    val dur = runCatching { prepared.duration.toLong() }
                        .onFailure { StudyLog.w(TAG, "Failed to read prepared duration", it) }
                        .getOrDefault(0L)
                        .coerceAtLeast(0L)
                    if (dur > 0L) durationMs = dur
                    applyMediaPlayerVolume()
                    try {
                        prepared.start()
                        musicPlaying = true
                        syncMusicStore()
                        ensureProgressLoop()
                        updateNotification()
                    } catch (e: Exception) {
                        StudyLog.e(TAG, "Failed to start prepared MediaPlayer at index=$musicIndex: ${e::class.java.simpleName}", e)
                        musicPlaying = false
                        syncMusicStore()
                        updateNotificationIfRunning()
                    }
                }
            }
            player.setOnCompletionListener { finished ->
                if (mediaPlayer === finished && playlistTracks.isNotEmpty()) {
                    when (repeatMode) {
                        FocusAudioStore.REPEAT_ONE -> startMusic(musicIndex)
                        FocusAudioStore.REPEAT_OFF -> {
                            if (shuffle && playlistTracks.size > 1) {
                                startMusic(computeNextTrackIndex(forceAdvance = false))
                            } else if (musicIndex + 1 < playlistTracks.size) {
                                startMusic(musicIndex + 1)
                            } else {
                                musicPlaying = false
                                positionMs = 0L
                                syncMusicStore()
                                updateNotificationIfRunning()
                            }
                        }
                        else -> startMusic(computeNextTrackIndex(forceAdvance = false))
                    }
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                StudyLog.w(TAG, "MediaPlayer error callback (what=$what, extra=$extra, index=$musicIndex)")
                runCatching {
                    failed.reset()
                    failed.release()
                }
                if (mediaPlayer === failed) {
                    mediaPlayer = null
                    musicPlaying = false
                    musicPrepared = false
                    positionMs = 0L
                    handleTrackFailureAndAdvance()
                }
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to prepare MediaPlayer for track index=$musicIndex: ${e::class.java.simpleName}", e)
            runCatching { player.release() }
                .onFailure { StudyLog.w(TAG, "Failed to release failed MediaPlayer", it) }
            if (mediaPlayer === player) mediaPlayer = null
            musicPrepared = false
            musicPlaying = false
            positionMs = 0L
            handleTrackFailureAndAdvance()
        }
    }

    private fun handleTrackFailureAndAdvance() {
        consecutiveTrackFailures++
        if (playlistTracks.size > 1 && consecutiveTrackFailures < playlistTracks.size) {
            val nextIdx = (musicIndex + 1) % playlistTracks.size
            startMusic(nextIdx)
        } else {
            consecutiveTrackFailures = 0
            syncMusicStore()
            if (!playing) {
                stopSelfIfIdle()
            } else {
                updateNotificationIfRunning()
            }
        }
    }

    private fun pauseMusicPlayback() {
        resumeMusicOnFocusGain = false
        if (musicPrepared) {
            positionMs = runCatching { mediaPlayer?.currentPosition?.toLong() ?: positionMs }
                .onFailure { StudyLog.w(TAG, "Failed to read MediaPlayer position on pause", it) }
                .getOrDefault(positionMs)
                .coerceAtLeast(0L)
            runCatching { mediaPlayer?.pause() }
                .onFailure { StudyLog.w(TAG, "Failed to pause MediaPlayer", it) }
        }
        musicPlaying = false
        syncMusicStore()
    }

    private fun stopMusic() {
        runCatching {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.release()
        }.onFailure {
            StudyLog.w(TAG, "Failed to release MediaPlayer on stop", it)
        }
        mediaPlayer = null
        musicPrepared = false
        musicPlaying = false
        positionMs = 0L
        syncMusicStore()
    }

    private fun applyMediaPlayerVolume() {
        val gain = (sleepFadeFactor * duckFactor).coerceIn(0f, 1f)
        if (mediaPlayer != null && musicPrepared) {
            runCatching { mediaPlayer?.setVolume(gain, gain) }
                .onFailure { StudyLog.w(TAG, "Failed to apply MediaPlayer volume", it) }
        }
    }

    private fun defaultVolumeFor(key: String): Float =
        defaultSoundVolumes[key] ?: FocusAudioStore.defaultSoundVolumes[key] ?: 0.38f

    private fun stopEngineOnly() {
        playing = false
        playbackGeneration++
        val oldTrack = track
        val oldWorker = worker
        val releaseOld = activeTrackRelease
        track = null
        worker = null
        activeTrackRelease = null
        runCatching { oldWorker?.interrupt() }
        if (oldTrack != null || releaseOld != null) {
            Thread({
                runCatching {
                    oldTrack?.pause()
                    oldTrack?.flush()
                    oldTrack?.stop()
                }
                runCatching { oldWorker?.join(80) }
                releaseOld?.invoke() ?: runCatching { oldTrack?.release() }
            }, "FocusAudioRelease").start()
        }
    }

    private fun startPlayback() {
        if (playing && worker?.isAlive == true && track != null) {
            syncAmbientStore()
            updateNotification()
            return
        }
        stopEngineOnly()
        playing = true
        val myGeneration = ++playbackGeneration
        syncAmbientStore()
        val sampleRate = 22_050
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val createdTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer.coerceAtLeast(4096) * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        } catch (e: Exception) {
            StudyLog.e(TAG, "Failed to initialize or start AudioTrack (minBuffer=$minBuffer): ${e::class.java.simpleName}", e)
            playing = false
            syncAmbientStore()
            track = null
            stopSelfIfIdle()
            return
        }
        val trackReleased = AtomicBoolean(false)
        val releaseTrackOnce = {
            if (trackReleased.compareAndSet(false, true)) {
                runCatching { createdTrack.release() }
            }
        }
        activeTrackRelease = releaseTrackOnce
        track = createdTrack
        updateNotification()
        worker = Thread {
            val frameCount = 1024
            val stereoBuffer = ShortArray(frameCount * 2)
            val dsp = MixerDspState(sampleRate.toDouble())
            val targetVols = DoubleArray(SOUND_COUNT)
            val currentVols = DoubleArray(SOUND_COUNT)
            val activeFlags = BooleanArray(SOUND_COUNT)
            var lastSnapshotRef: Map<String, Float>? = null
            var targetLayerNorm = 1.0
            var currentLayerNorm = 1.0
            var currentMasterGain = (ambientMasterVolume * sleepFadeFactor * duckFactor).coerceIn(0f, 1f).toDouble()

            try {
                while (playing && playbackGeneration == myGeneration && !Thread.currentThread().isInterrupted) {
                    val snapshot = activeSounds
                    if (snapshot !== lastSnapshotRef) {
                        lastSnapshotRef = snapshot
                        targetVols.fill(0.0)
                        var activeCount = 0
                        for ((key, volume) in snapshot) {
                            val idx = soundIndexOf(key)
                            val clamped = volume.toDouble().coerceIn(0.0, 1.0)
                            if (clamped > targetVols[idx]) {
                                targetVols[idx] = clamped
                            }
                            if (clamped > 0.001) activeCount++
                        }
                        targetLayerNorm = 1.0 + (activeCount - 1).coerceAtLeast(0) * 0.42
                    }

                    for (id in 0 until SOUND_COUNT) {
                        activeFlags[id] = targetVols[id] > 0.0005 || currentVols[id] > 0.0005
                    }
                    val targetMasterGain = (ambientMasterVolume * sleepFadeFactor * duckFactor).coerceIn(0f, 1f).toDouble()

                    for (frame in 0 until frameCount) {
                        currentMasterGain += (targetMasterGain - currentMasterGain) * 0.003
                        currentLayerNorm += (targetLayerNorm - currentLayerNorm) * 0.003
                        dsp.mixFrame(
                            targetVols = targetVols,
                            currentVols = currentVols,
                            activeFlags = activeFlags,
                            masterGain = currentMasterGain,
                            layerNorm = currentLayerNorm,
                            outStereo = stereoBuffer,
                            outOffset = frame * 2
                        )
                    }

                    if (!playing || playbackGeneration != myGeneration) break
                    try {
                        val written = createdTrack.write(stereoBuffer, 0, stereoBuffer.size, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) {
                            StudyLog.w(TAG, "AudioTrack.write returned error code=$written")
                            if (written == AudioTrack.ERROR_DEAD_OBJECT || written == AudioTrack.ERROR_INVALID_OPERATION) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        StudyLog.w(TAG, "AudioTrack write failed in mixer thread: ${e::class.java.simpleName}", e)
                        break
                    }
                }
            } finally {
                if (playbackGeneration != myGeneration) {
                    releaseTrackOnce()
                } else {
                    playing = false
                    if (track === createdTrack) {
                        track = null
                        worker = null
                        activeTrackRelease = null
                    }
                    releaseTrackOnce()
                    mainHandler.post {
                        syncAmbientStore()
                        if (!playing && !musicPlaying && mediaPlayer == null) {
                            stopSelfIfIdle()
                        } else {
                            updateNotificationIfRunning()
                        }
                    }
                }
            }
        }.apply {
            name = "StudyDockAudioMixer-$myGeneration"
            start()
        }
    }

    private fun pausePlayback() {
        if (!musicPlaying) abandonAudioFocus()
        resumeAmbientOnFocusGain = false
        stopEngineOnly()
        syncAmbientStore()
        if (mediaPlayer != null || musicPlaying) {
            updateNotification()
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
            updateNotification()
        }
    }

    private fun stopPlayback() {
        resumeAmbientOnFocusGain = false
        stopEngineOnly()
        syncAmbientStore()
    }

    private fun pauseAllInternal(updateNotif: Boolean) {
        val hadActiveNotification = playing || musicPlaying || track != null || mediaPlayer != null
        if (playing) {
            pausePlayback()
        } else {
            FocusAudioStore.updateAmbientState(this, ambientPlaying = false)
        }
        if (musicPlaying || musicPrepared) {
            pauseMusicPlayback()
        } else {
            FocusAudioStore.updateMusicPlayback(this, musicPlaying = false)
        }
        if (updateNotif && hadActiveNotification) {
            if (!playing && !musicPlaying) {
                runCatching { stopForeground(STOP_FOREGROUND_DETACH) }
            }
            updateNotification()
        }
    }

    private fun ensureProgressLoop() {
        if (!progressLoopRunning) {
            progressLoopRunning = true
            mainHandler.removeCallbacks(progressRunnable)
            mainHandler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager
        if (am == null) {
            StudyLog.w(TAG, "AudioManager unavailable; skipping audio focus request")
            return false
        }
        if (hasAudioFocus) return true
        return try {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()
                .also { audioFocusRequest = it }
            val res = am.requestAudioFocus(req)
            hasAudioFocus = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasAudioFocus) {
                StudyLog.w(TAG, "Audio focus request not granted (result=$res)")
            }
            hasAudioFocus
        } catch (e: Exception) {
            hasAudioFocus = false
            StudyLog.w(TAG, "Failed to request audio focus: ${e::class.java.simpleName}", e)
            false
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (!hasAudioFocus) return
        hasAudioFocus = false
        audioFocusRequest?.let { req ->
            runCatching { am.abandonAudioFocusRequest(req) }
                .onFailure { StudyLog.w(TAG, "Failed to abandon audio focus request", it) }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(becomingNoisyReceiver, filter)
            }
            noisyReceiverRegistered = true
        }.onFailure {
            StudyLog.w(TAG, "Failed to register ACTION_AUDIO_BECOMING_NOISY receiver", it)
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
            .onFailure { StudyLog.w(TAG, "Failed to unregister ACTION_AUDIO_BECOMING_NOISY receiver", it) }
    }

    private fun startForegroundSafely(): Boolean {
        return runCatching {
            val notif = notification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
            true
        }.onFailure {
            StudyLog.e(TAG, "Failed to start FocusAudioService in foreground: ${it::class.java.simpleName}", it)
        }.getOrDefault(false)
    }

    private fun stopSelfIfIdle() {
        if (!playing && !musicPlaying && mediaPlayer == null && track == null) {
            abandonAudioFocus()
            stopForegroundSafely("IDLE")
            stopSelf()
        }
    }

    private fun stopForegroundSafely(reason: String) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { StudyLog.w(TAG, "Failed to stop FocusAudioService foreground ($reason): ${it::class.java.simpleName}", it) }
    }

    private fun updateNotificationIfRunning() {
        if (track != null || mediaPlayer != null || playing || musicPlaying) {
            updateNotification()
        }
    }

    private fun updateNotification() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager == null) {
                StudyLog.w(TAG, "NotificationManager unavailable; cannot update audio notification")
                return
            }
            manager.notify(NOTIFICATION_ID, notification())
        }.onFailure {
            StudyLog.w(TAG, "Failed to update FocusAudioService notification: ${it::class.java.simpleName}", it)
        }
    }

    private fun serviceActionPendingIntent(
        requestCode: Int,
        intent: Intent,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ): PendingIntent {
        return if (Build.VERSION.SDK_INT >= 26) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager == null) {
            StudyLog.w(TAG, "NotificationManager unavailable while creating audio channel")
        } else {
            runCatching {
                val channel = NotificationChannel(
                    CHANNEL,
                    "Sons e músicas de foco",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controles contínuos para sons ambientes, frequências binaurais e músicas locais"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }.onFailure {
                StudyLog.w(TAG, "Failed to create FocusAudioService notification channel", it)
            }
        }
        val openApp = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_player_page", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundKeys = ArrayList(activeSounds.keys)
        val volumes = soundKeys.map { activeSounds[it] ?: defaultVolumeFor(it) }.toFloatArray()
        val mixSummary = if (soundKeys.isEmpty()) {
            "Sem sons selecionados"
        } else {
            soundKeys.take(3).map { soundLabels[it] ?: it }.joinToString(" + ") +
                if (soundKeys.size > 3) " +${soundKeys.size - 3}" else ""
        }

        val sleepSuffix = sleepTimerEndAt?.let { endAt ->
            val remMin = ceil((endAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000.0).toInt()
            if (remMin > 0) " · ⏱ ${remMin}m" else ""
        }.orEmpty()

        val trackTitle = currentTrackTitle().ifBlank { "Sua música" }
        val trackArtist = currentTrackArtist()
        val trackDisplay = if (trackArtist.isNotBlank()) "$trackTitle — $trackArtist" else trackTitle

        val status = when {
            musicPlaying && playing -> "🎵 $trackDisplay · 🎧 $mixSummary$sleepSuffix"
            musicPlaying -> "🎵 $trackDisplay$sleepSuffix"
            playing -> "🎧 $mixSummary · reproduzindo$sleepSuffix"
            mediaPlayer != null && playlistTracks.isNotEmpty() -> "Música pausada · $trackDisplay$sleepSuffix"
            else -> "$mixSummary · pausado$sleepSuffix"
        }

        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("StudyDock · Central Sonora")
            .setContentText(status)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(openApp)
            .setOngoing(playing || musicPlaying)
            .setStyle(Notification.BigTextStyle().bigText(status))

        if (musicPlaying || (mediaPlayer != null && playlistTracks.isNotEmpty())) {
            val prevPending = serviceActionPendingIntent(
                12,
                Intent(this, FocusAudioService::class.java).setAction(ACTION_PREV_MUSIC),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val toggleMusicAction = if (musicPlaying) ACTION_PAUSE_MUSIC else ACTION_PLAY_MUSIC
            val toggleMusicPending = serviceActionPendingIntent(
                13,
                Intent(this, FocusAudioService::class.java)
                    .setAction(toggleMusicAction)
                    .putExtra(EXTRA_PLAYLIST_ID, activePlaylistId)
                    .putExtra(EXTRA_TRACK_INDEX, musicIndex),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nextPending = serviceActionPendingIntent(
                14,
                Intent(this, FocusAudioService::class.java).setAction(ACTION_NEXT_MUSIC),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            fun notifAction(iconRes: Int, title: String, pi: PendingIntent): Notification.Action =
                Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, iconRes), title, pi).build()

            builder.addAction(notifAction(android.R.drawable.ic_media_previous, "Anterior", prevPending))
            builder.addAction(
                notifAction(
                    if (musicPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (musicPlaying) "Pausar" else "Tocar",
                    toggleMusicPending
                )
            )
            builder.addAction(notifAction(android.R.drawable.ic_media_next, "Próxima", nextPending))
        } else {
            fun notifAction(iconRes: Int, title: String, pi: PendingIntent): Notification.Action =
                Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, iconRes), title, pi).build()

            val toggleAction = if (playing) ACTION_PAUSE else ACTION_PLAY
            val toggleLabel = if (playing) "Pausar sons" else "Retomar sons"
            val audioIntent = Intent(this, FocusAudioService::class.java)
                .setAction(toggleAction)
                .putStringArrayListExtra(EXTRA_SOUNDS, soundKeys)
                .putExtra(EXTRA_VOLUMES, volumes)
            val togglePending = serviceActionPendingIntent(
                11,
                audioIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopPending = serviceActionPendingIntent(
                15,
                Intent(this, FocusAudioService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                notifAction(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    toggleLabel,
                    togglePending
                )
            )
            builder.addAction(notifAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stopPending))
        }

        return builder.build()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressRunnable)
        progressLoopRunning = false
        metadataExecutor.shutdownNow()
        unregisterNoisyReceiver()
        stopPlayback()
        stopMusic()
        abandonAudioFocus()
        super.onDestroy()
    }

    /**
     * Zero-allocation real-time stereo DSP engine operating at 22,050 Hz.
     * All buffers, filter states, delay lines, and voice pools are pre-allocated on construction.
     */
    private class MixerDspState(private val sampleRate: Double) {
        private val twoPi = 2.0 * PI
        private val dt = 1.0 / sampleRate

        private var rngState: Long = (System.nanoTime() xor 0x9E3779B97F4A7C15uL.toLong()).let {
            if (it == 0L) 0x5DEECE66DL else it
        }

        private var tmpL = 0.0
        private var tmpR = 0.0

        // Basic noise channels
        private val pinkL = DoubleArray(3)
        private val pinkR = DoubleArray(3)
        private var brownStateL = 0.0
        private var brownStateR = 0.0
        private var brownLpL = 0.0
        private var brownLpR = 0.0

        // Binaural incremental phases
        private var alphaPhaseL = 0.0
        private var alphaPhaseR = 0.0
        private var alphaSubPhase = 0.0
        private var thetaPhaseL = 0.0
        private var thetaPhaseR = 0.0
        private var thetaSubPhase = 0.0
        private var betaPhaseL = 0.0
        private var betaPhaseR = 0.0
        private var betaSubPhase = 0.0
        private var gammaPhaseL = 0.0
        private var gammaPhaseR = 0.0
        private var gammaSubPhase = 0.0
        private var deltaPhaseL = 0.0
        private var deltaPhaseR = 0.0
        private var deltaSubPhase = 0.0

        // Green noise & Waterfall & Mountain Wind
        private val greenPinkL = DoubleArray(3)
        private val greenPinkR = DoubleArray(3)
        private val greenSvfL = DoubleArray(2)
        private val greenSvfR = DoubleArray(2)
        private var greenLpL = 0.0
        private var greenLpR = 0.0

        private val wfPinkL = DoubleArray(3)
        private val wfPinkR = DoubleArray(3)
        private var wfDeepL = 0.0
        private var wfDeepR = 0.0
        private val wfSvfL = DoubleArray(2)
        private val wfSvfR = DoubleArray(2)
        private var wfSwellPhase = 0.0

        private val mwPinkL = DoubleArray(3)
        private val mwPinkR = DoubleArray(3)
        private val mwSvf1L = DoubleArray(2)
        private val mwSvf1R = DoubleArray(2)
        private val mwSvf2L = DoubleArray(2)
        private val mwSvf2R = DoubleArray(2)
        private var mwLfo1 = 0.0
        private var mwLfo2 = 0.25

        // Singing bowl (Tibetan bowl harmonic resonance)
        private var bowlAge = 0
        private var bowlWait = (sampleRate * 0.4).toInt()
        private var bowlBaseFreq = 220.0
        private var bowlPhase1 = 0.0
        private var bowlPhase2 = 0.0
        private var bowlPhase3 = 0.0
        private var bowlModPhase = 0.0
        private var bowlPan = 0.5
        private val bowlFreqs = doubleArrayOf(196.0, 220.0, 261.63, 293.66, 329.63)

        // Fan (steady room fan) & Train (rail cadence)
        private val fanPinkL = DoubleArray(3)
        private val fanPinkR = DoubleArray(3)
        private var fanLpL = 0.0
        private var fanLpR = 0.0
        private var fanHumPhase = 0.0
        private var fanBladeLfo = 0.0

        private val trainPinkL = DoubleArray(3)
        private val trainPinkR = DoubleArray(3)
        private var trainRumbleL = 0.0
        private var trainRumbleR = 0.0
        private var trainPulseLp = 0.0
        private var trainSamplePos = 0
        private val trainCycleSamples = (sampleRate * 1.18).toInt().coerceAtLeast(1)
        private val trainBeat2Start = (sampleRate * 0.16).toInt()
        private val trainBeat3Start = (sampleRate * 0.50).toInt()
        private val trainBeat4Start = (sampleRate * 0.66).toInt()
        private val trainBeatLen = (sampleRate * 0.065).toInt().coerceAtLeast(1)

        // Cosmic ambient synth pad
        private val padChords = arrayOf(
            doubleArrayOf(130.81, 196.00, 246.94, 329.63),
            doubleArrayOf(146.83, 220.00, 261.63, 392.00),
            doubleArrayOf(110.00, 164.81, 246.94, 329.63),
            doubleArrayOf(174.61, 220.00, 261.63, 329.63)
        )
        private val padPhaseL = DoubleArray(4)
        private val padPhaseR = DoubleArray(4)
        private var padChordIdx = 0
        private var padChordAge = 0
        private val padChordDuration = (sampleRate * 9.0).toInt().coerceAtLeast(1)
        private var padLpL = 0.0
        private var padLpR = 0.0
        private var padSweepLfo = 0.0

        // Rain & thunder
        private val rainPinkL = DoubleArray(3)
        private val rainPinkR = DoubleArray(3)
        private var rainLpL = 0.0
        private var rainLpR = 0.0
        private val rainDropActive = BooleanArray(4)
        private val rainDropAge = IntArray(4)
        private val rainDropDur = IntArray(4)
        private val rainDropFreq = DoubleArray(4)
        private val rainDropPhase = DoubleArray(4)
        private val rainDropPan = DoubleArray(4)
        private val rainDropAmp = DoubleArray(4)

        private val thunderPinkL = DoubleArray(3)
        private val thunderPinkR = DoubleArray(3)
        private var thunderRainLpL = 0.0
        private var thunderRainLpR = 0.0
        private var thunderRumbleEnv = 0.0
        private var thunderDeepBrown = 0.0
        private var thunderPhase = 0.0
        private var thunderCooldown = (sampleRate * 2.4).toInt()

        // Lo-fi Rhodes + bass + warm LP + stereo chorus + vinyl dust
        private val lofiRoots = doubleArrayOf(87.31, 82.41, 73.42, 65.41) // F2 -> E2 -> D2 -> C2
        private val lofiChords = arrayOf(
            doubleArrayOf(174.61, 261.63, 329.63, 440.00), // Fmaj7
            doubleArrayOf(164.81, 246.94, 293.66, 392.00), // Em7
            doubleArrayOf(146.83, 220.00, 261.63, 349.23), // Dm7
            doubleArrayOf(130.81, 196.00, 246.94, 329.63)  // Cmaj7
        )
        private val lofiVoicePanL = doubleArrayOf(0.58, 0.53, 0.47, 0.42)
        private val lofiSlotActive = booleanArrayOf(true, false)
        private val lofiSlotReleasing = booleanArrayOf(false, false)
        private val lofiSlotChord = intArrayOf(0, 0)
        private val lofiSlotAge = IntArray(2)
        private val lofiSlotRelease = doubleArrayOf(1.0, 0.0)
        private val lofiVoicePhases = Array(2) { DoubleArray(4) }
        private val lofiBassPhases = DoubleArray(2)
        private val lofiStepSamples = (sampleRate * 2.8).toInt()
        private var lofiStepCounter = 0
        private var lofiChordIndex = 0
        private var lofiActiveSlot = 0
        private var lofiWowPhase = 0.0
        private var lofiFlutterPhase = 0.0
        private var lofiFilterLfoPhase = 0.0
        private var lofiChorusLfoPhase = 0.0
        private var lofiLp1L = 0.0
        private var lofiLp2L = 0.0
        private var lofiLp1R = 0.0
        private var lofiLp2R = 0.0
        private val lofiChorusSize = 512
        private val lofiChorusMask = 511
        private val lofiChorusBufL = DoubleArray(lofiChorusSize)
        private val lofiChorusBufR = DoubleArray(lofiChorusSize)
        private var lofiChorusWriteIdx = 0
        private val lofiVinylPink = DoubleArray(3)
        private var lofiVinylHpLp = 0.0
        private var lofiVinylLp = 0.0
        private var lofiCrackleBurst = 0.0
        private var lofiCrackleSmooth = 0.0

        // Library & keyboard
        private var libBrownL = 0.0
        private var libBrownR = 0.0
        private val libPink = DoubleArray(3)
        private val libSvf = DoubleArray(2)
        private var libPageEnv = 0.0
        private var libPagePan = 0.5
        private val kbPink = DoubleArray(3)
        private var kbLp = 0.0
        private var kbEnv = 0.0
        private var kbPhase = 0.0
        private var kbPan = 0.5

        // Coffee shop: room floor + 3-band murmur + porcelain clink + espresso steam
        private val coffeePinkL = DoubleArray(3)
        private val coffeePinkR = DoubleArray(3)
        private var coffeeBrownL = 0.0
        private var coffeeBrownR = 0.0
        private var coffeeFloorLpL = 0.0
        private var coffeeFloorLpR = 0.0
        private val coffeeMurmurSvf = Array(3) { DoubleArray(2) }
        private val coffeeMurmurEnv = doubleArrayOf(0.5, 0.4, 0.45)
        private val coffeeMurmurTarget = doubleArrayOf(0.55, 0.45, 0.5)
        private val coffeeMurmurTimer = intArrayOf(2400, 4800, 6200)
        private val coffeeMurmurFreqs = doubleArrayOf(275.0, 530.0, 940.0)
        private val coffeeMurmurQs = doubleArrayOf(1.9, 2.3, 2.4)
        private val coffeeMurmurPanL = doubleArrayOf(0.62, 0.38, 0.56)
        private val coffeeLfoRates = doubleArrayOf(0.19, 0.27, 0.34)
        private val coffeeBandGains = doubleArrayOf(0.24, 0.22, 0.13)
        private val coffeeLfoPhase = doubleArrayOf(0.0, 0.25, 0.62)
        private var coffeeMurmurHiLpL = 0.0
        private var coffeeMurmurHiLpR = 0.0
        private var clinkActive = false
        private var clinkAge = 0
        private var clinkDur = 1400
        private var clinkFreq = 1650.0
        private var clinkPhase1 = 0.0
        private var clinkPhase2 = 0.0
        private var clinkPan = 0.5
        private var clinkAmp = 0.048
        private var clinkCooldown = (sampleRate * 1.6).toInt()
        private var clinkDoubleDelay = 0
        private var steamActive = false
        private var steamAge = 0
        private val steamDur = (sampleRate * 1.45).toInt()
        private var steamCooldown = (sampleRate * 4.5).toInt()
        private val steamSvfHi = DoubleArray(2)
        private val steamSvfBody = DoubleArray(2)
        private var steamPan = 0.42

        // Fire
        private val firePinkL = DoubleArray(3)
        private val firePinkR = DoubleArray(3)
        private var fireBrownL = 0.0
        private var fireBrownR = 0.0
        private var fireLpL = 0.0
        private var fireLpR = 0.0
        private var fireLfoA = 0.0
        private var fireLfoB = 0.35
        private val fireCrackleActive = BooleanArray(3)
        private val fireCrackleAge = IntArray(3)
        private val fireCrackleDur = IntArray(3)
        private val fireCrackleFreq = DoubleArray(3)
        private val fireCracklePhase = DoubleArray(3)
        private val fireCracklePan = DoubleArray(3)
        private val fireCrackleAmp = DoubleArray(3)

        // Waves
        private val wavesPinkL = DoubleArray(3)
        private val wavesPinkR = DoubleArray(3)
        private var wavesBrownL = 0.0
        private var wavesBrownR = 0.0
        private var wavesLpL = 0.0
        private var wavesLpR = 0.0
        private var wavesPhaseA = 0.0
        private var wavesPhaseB = 0.18

        // Forest canopy + distant subtle bird
        private val forestPinkL = DoubleArray(3)
        private val forestPinkR = DoubleArray(3)
        private var forestLpL = 0.0
        private var forestLpR = 0.0
        private val forestLeafSvf = DoubleArray(2)
        private var forestGustPhaseL = 0.0
        private var forestGustPhaseR = 0.37
        private var forestDistBirdState = 0 // 0=wait, 1=note1, 2=gap, 3=note2
        private var forestDistBirdCounter = (sampleRate * 2.2).toInt()
        private var forestDistBirdDur = 2400
        private var forestDistBirdFreq = 1520.0
        private var forestDistBirdPhase = 0.0
        private var forestDistBirdPan = 0.62
        private var forestDistBirdLp = 0.0

        // Morning birds (2 independent stereo voices + gentle morning breeze)
        private val birdsBreezePinkL = DoubleArray(3)
        private val birdsBreezePinkR = DoubleArray(3)
        private var birdsBreezeLpL = 0.0
        private var birdsBreezeLpR = 0.0
        private var birdsBreezeLfo = 0.0
        private val birdPentatonic = doubleArrayOf(1318.5, 1480.0, 1568.0, 1760.0, 1975.5, 2093.0, 2349.3)
        private val birdState = intArrayOf(0, 0) // 0=WAIT, 1=NOTE, 2=GAP
        private val birdTimer = intArrayOf((sampleRate * 0.55).toInt(), (sampleRate * 2.1).toInt())
        private val birdAge = IntArray(2)
        private val birdDur = IntArray(2)
        private val birdNotesLeft = IntArray(2)
        private val birdNoteIdx = intArrayOf(1, 3)
        private val birdBaseFreq = doubleArrayOf(1568.0, 1760.0)
        private val birdContour = IntArray(2)
        private val birdTrillRate = doubleArrayOf(28.0, 31.0)
        private val birdTrillPhase = DoubleArray(2)
        private val birdPhase = DoubleArray(2)
        private val birdPan = doubleArrayOf(0.68, 0.32)
        private val birdAmp = doubleArrayOf(0.16, 0.14)
        private var birdsToneLpL = 0.0
        private var birdsToneLpR = 0.0

        // Mourning dove (cuu-UUU-uu-uu + dawn breeze + outdoor reflection)
        private val doveBreezePinkL = DoubleArray(3)
        private val doveBreezePinkR = DoubleArray(3)
        private var doveBreezeLpL = 0.0
        private var doveBreezeLpR = 0.0
        private var doveBreezeLfo = 0.0
        private val doveStepDurations = intArrayOf(
            (sampleRate * 0.30).toInt(),
            (sampleRate * 0.09).toInt(),
            (sampleRate * 0.725).toInt(),
            (sampleRate * 0.163).toInt(),
            (sampleRate * 0.345).toInt(),
            (sampleRate * 0.140).toInt(),
            (sampleRate * 0.426).toInt(),
            0
        )
        private var doveStep = 7 // 7=REST
        private var doveAge = 0
        private var doveRestTarget = (sampleRate * 0.85).toInt()
        private var dovePitchMul = 1.0
        private var dovePan = 0.44
        private var dovePhase = 0.0
        private var doveVibratoPhase = 0.0
        private val doveBreathSvf = DoubleArray(2)
        private val doveDelaySize = 1024
        private val doveDelayMask = 1023
        private val doveDelayBufL = DoubleArray(doveDelaySize)
        private val doveDelayBufR = DoubleArray(doveDelaySize)
        private var doveDelayIdx = 0

        // Forest stream (babbling over rocks + woodland breeze)
        private val streamPinkL = DoubleArray(3)
        private val streamPinkR = DoubleArray(3)
        private var streamLpL = 0.0
        private var streamLpR = 0.0
        private val streamSvfA = DoubleArray(2)
        private val streamSvfB = DoubleArray(2)
        private var streamModPhaseA = 0.0
        private var streamModPhaseB = 0.31
        private val bubbleActive = BooleanArray(4)
        private val bubbleAge = IntArray(4)
        private val bubbleDur = IntArray(4)
        private val bubbleFreq = DoubleArray(4)
        private val bubblePhase = DoubleArray(4)
        private val bubblePan = DoubleArray(4)
        private val bubbleAmp = DoubleArray(4)

        // Forest rain (broad leaves + damp earth + organic drops)
        private val fRainPinkL = DoubleArray(3)
        private val fRainPinkR = DoubleArray(3)
        private var fRainBrownL = 0.0
        private var fRainBrownR = 0.0
        private var fRainEarthLpL = 0.0
        private var fRainEarthLpR = 0.0
        private var fRainLeafLpL = 0.0
        private var fRainLeafLpR = 0.0
        private val fRainSvfL = DoubleArray(2)
        private val fRainSvfR = DoubleArray(2)
        private val fRainDropActive = BooleanArray(6)
        private val fRainDropAge = IntArray(6)
        private val fRainDropDur = IntArray(6)
        private val fRainDropFreq = DoubleArray(6)
        private val fRainDropPhase = DoubleArray(6)
        private val fRainDropPan = DoubleArray(6)
        private val fRainDropAmp = DoubleArray(6)

        // Forest night (night breeze + 2 pulsed cricket choirs + occasional deep owl)
        private val nightPinkL = DoubleArray(3)
        private val nightPinkR = DoubleArray(3)
        private var nightBrownL = 0.0
        private var nightBrownR = 0.0
        private var nightLpL = 0.0
        private var nightLpR = 0.0
        private var nightBreezeLfo = 0.0
        private val cricketCarrierPhases = DoubleArray(2)
        private val cricketBaseFreqs = doubleArrayOf(2680.0, 2940.0)
        private val cricketMacroRates = doubleArrayOf(0.085, 0.067)
        private val cricketMacroPhases = doubleArrayOf(0.15, 0.65)
        private val cricketCyclePos = intArrayOf(0, (sampleRate * 0.24).toInt())
        private val cricketCycleLen = intArrayOf((sampleRate * 0.50).toInt(), (sampleRate * 0.57).toInt())
        private var cricketLpL = 0.0
        private var cricketLpR = 0.0
        private var owlStep = 0 // 0=WAIT, 1=HOO1, 2=GAP1, 3=HOO2, 4=GAP2, 5=HOO3
        private var owlCounter = (sampleRate * 3.2).toInt()
        private var owlStepDur = 1
        private var owlPhase = 0.0
        private var owlPan = 0.64
        private val owlDelaySize = 1024
        private val owlDelayMask = 1023
        private val owlDelayBufL = DoubleArray(owlDelaySize)
        private val owlDelayBufR = DoubleArray(owlDelaySize)
        private var owlDelayIdx = 0

        // Rain on window
        private val winPinkL = DoubleArray(3)
        private val winPinkR = DoubleArray(3)
        private var winRainLpL = 0.0
        private var winRainLpR = 0.0
        private var winRoomBrown = 0.0
        private var winRoomLp = 0.0
        private val winDropActive = BooleanArray(4)
        private val winDropAge = IntArray(4)
        private val winDropDur = IntArray(4)
        private val winDropFreq = DoubleArray(4)
        private val winDropPhase = DoubleArray(4)
        private val winDropPan = DoubleArray(4)
        private val winDropAmp = DoubleArray(4)

        // Wind chimes (pentatonic soft chimes in gentle breeze)
        private val chimeBreezePinkL = DoubleArray(3)
        private val chimeBreezePinkR = DoubleArray(3)
        private var chimeBreezeLpL = 0.0
        private var chimeBreezeLpR = 0.0
        private var chimeGustLfo = 0.0
        private val chimeNotes = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.00, 1046.50)
        private val chimeActive = BooleanArray(4)
        private val chimeAge = IntArray(4)
        private val chimeFreq = DoubleArray(4)
        private val chimePhase = DoubleArray(4)
        private val chimePan = DoubleArray(4)
        private val chimeAmp = DoubleArray(4)
        private var chimeNextSlot = 0
        private var chimeWaitSamples = (sampleRate * 0.45).toInt()
        private var chimeBurstLeft = 2

        // Soft felt piano (Cmaj9 -> Am7 -> Fmaj7 -> G6)
        private val pianoChords = arrayOf(
            doubleArrayOf(130.81, 196.00, 246.94, 293.66, 329.63),
            doubleArrayOf(110.00, 196.00, 220.00, 261.63, 329.63),
            doubleArrayOf(87.31, 174.61, 220.00, 261.63, 329.63),
            doubleArrayOf(98.00, 196.00, 246.94, 293.66, 329.63)
        )
        private val pianoActive = BooleanArray(4)
        private val pianoAge = IntArray(4)
        private val pianoFreq = DoubleArray(4)
        private val pianoPhase = DoubleArray(4)
        private val pianoPan = DoubleArray(4)
        private val pianoAmp = DoubleArray(4)
        private var pianoNextSlot = 0
        private var pianoChordIdx = 0
        private var pianoBeatIdx = 0
        private var pianoBeatWait = (sampleRate * 0.18).toInt()
        private var pianoLp1L = 0.0
        private var pianoLp2L = 0.0
        private var pianoLp1R = 0.0
        private var pianoLp2R = 0.0
        private val pianoDelaySize = 1024
        private val pianoDelayMask = 1023
        private val pianoDelayBufL = DoubleArray(pianoDelaySize)
        private val pianoDelayBufR = DoubleArray(pianoDelaySize)
        private var pianoDelayIdx = 0

        fun mixFrame(
            targetVols: DoubleArray,
            currentVols: DoubleArray,
            activeFlags: BooleanArray,
            masterGain: Double,
            layerNorm: Double,
            outStereo: ShortArray,
            outOffset: Int
        ) {
            var mixedL = 0.0
            var mixedR = 0.0

            for (id in 0 until SOUND_COUNT) {
                if (!activeFlags[id]) continue
                val vol = currentVols[id] + (targetVols[id] - currentVols[id]) * 0.003
                currentVols[id] = vol
                if (vol <= 0.0001) continue

                stepSound(id)
                if (tmpL.isFinite() && tmpR.isFinite()) {
                    mixedL += tmpL * vol
                    mixedR += tmpR * vol
                } else {
                    tmpL = 0.0
                    tmpR = 0.0
                }
            }

            val normL = (mixedL * masterGain) / layerNorm
            val normR = (mixedR * masterGain) / layerNorm
            outStereo[outOffset] = softLimitToShort(normL)
            outStereo[outOffset + 1] = softLimitToShort(normR)
        }

        private fun stepSound(soundId: Int) {
            when (soundId) {
                SOUND_WHITE -> stepWhite()
                SOUND_PINK -> stepPinkNoise()
                SOUND_BROWN -> stepBrownNoise()
                SOUND_RAIN -> stepRain()
                SOUND_THUNDER -> stepThunder()
                SOUND_LOFI -> stepLofi()
                SOUND_BINAURAL_ALPHA -> stepBinauralAlpha()
                SOUND_BINAURAL_THETA -> stepBinauralTheta()
                SOUND_BINAURAL_BETA -> stepBinauralBeta()
                SOUND_BINAURAL_GAMMA -> stepBinauralGamma()
                SOUND_BINAURAL_DELTA -> stepBinauralDelta()
                SOUND_GREEN_NOISE -> stepGreenNoise()
                SOUND_WATERFALL -> stepWaterfall()
                SOUND_MOUNTAIN_WIND -> stepMountainWind()
                SOUND_SINGING_BOWL -> stepSingingBowl()
                SOUND_FAN -> stepFan()
                SOUND_TRAIN -> stepTrain()
                SOUND_AMBIENT_PAD -> stepAmbientPad()
                SOUND_LIBRARY -> stepLibrary()
                SOUND_KEYBOARD -> stepKeyboard()
                SOUND_COFFEE -> stepCoffee()
                SOUND_FIRE -> stepFire()
                SOUND_WAVES -> stepWaves()
                SOUND_FOREST -> stepForest()
                SOUND_BIRDS -> stepBirds()
                SOUND_MOURNING_DOVE -> stepMourningDove()
                SOUND_FOREST_STREAM -> stepForestStream()
                SOUND_FOREST_RAIN -> stepForestRain()
                SOUND_FOREST_NIGHT -> stepForestNight()
                SOUND_RAIN_WINDOW -> stepRainWindow()
                SOUND_WIND_CHIMES -> stepWindChimes()
                SOUND_SOFT_PIANO -> stepSoftPiano()
                else -> stepBrownNoise()
            }
        }

        private fun stepWhite() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            tmpL = (wL * 0.86 + wR * 0.14) * 0.18
            tmpR = (wR * 0.86 + wL * 0.14) * 0.18
        }

        private fun stepPinkNoise() {
            val pL = stepPink(pinkL, nextBipolar())
            val pR = stepPink(pinkR, nextBipolar())
            tmpL = (pL * 0.80 + pR * 0.20) * 0.88
            tmpR = (pR * 0.80 + pL * 0.20) * 0.88
        }

        private fun stepBrownNoise() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            brownStateL = (brownStateL * 0.992 + wL * 0.032).coerceIn(-0.65, 0.65)
            brownStateR = (brownStateR * 0.992 + wR * 0.032).coerceIn(-0.65, 0.65)
            brownLpL += 0.14 * (brownStateL - brownLpL)
            brownLpR += 0.14 * (brownStateR - brownLpR)
            tmpL = (brownLpL * 0.82 + brownLpR * 0.18) * 0.58
            tmpR = (brownLpR * 0.82 + brownLpL * 0.18) * 0.58
        }

        private fun stepBinauralAlpha() {
            alphaPhaseL = wrapPhase(alphaPhaseL + 200.0 * dt)
            alphaPhaseR = wrapPhase(alphaPhaseR + 210.0 * dt)
            alphaSubPhase = wrapPhase(alphaSubPhase + 100.0 * dt)
            val sub = sin(twoPi * alphaSubPhase) * 0.048
            val leftSine = sin(twoPi * alphaPhaseL) * 0.22 + sub
            val rightSine = sin(twoPi * alphaPhaseR) * 0.22 + sub
            tmpL = leftSine * 0.85 + rightSine * 0.15
            tmpR = rightSine * 0.85 + leftSine * 0.15
        }

        private fun stepBinauralTheta() {
            thetaPhaseL = wrapPhase(thetaPhaseL + 180.0 * dt)
            thetaPhaseR = wrapPhase(thetaPhaseR + 186.0 * dt)
            thetaSubPhase = wrapPhase(thetaSubPhase + 90.0 * dt)
            val sub = sin(twoPi * thetaSubPhase) * 0.048
            val leftSine = sin(twoPi * thetaPhaseL) * 0.22 + sub
            val rightSine = sin(twoPi * thetaPhaseR) * 0.22 + sub
            tmpL = leftSine * 0.85 + rightSine * 0.15
            tmpR = rightSine * 0.85 + leftSine * 0.15
        }

        private fun stepBinauralBeta() {
            betaPhaseL = wrapPhase(betaPhaseL + 240.0 * dt)
            betaPhaseR = wrapPhase(betaPhaseR + 256.0 * dt)
            betaSubPhase = wrapPhase(betaSubPhase + 120.0 * dt)
            val sub = sin(twoPi * betaSubPhase) * 0.042
            val leftSine = sin(twoPi * betaPhaseL) * 0.20 + sub
            val rightSine = sin(twoPi * betaPhaseR) * 0.20 + sub
            tmpL = leftSine * 0.88 + rightSine * 0.12
            tmpR = rightSine * 0.88 + leftSine * 0.12
        }

        private fun stepBinauralGamma() {
            gammaPhaseL = wrapPhase(gammaPhaseL + 220.0 * dt)
            gammaPhaseR = wrapPhase(gammaPhaseR + 260.0 * dt)
            gammaSubPhase = wrapPhase(gammaSubPhase + 110.0 * dt)
            val sub = sin(twoPi * gammaSubPhase) * 0.04
            val leftSine = sin(twoPi * gammaPhaseL) * 0.18 + sub
            val rightSine = sin(twoPi * gammaPhaseR) * 0.18 + sub
            tmpL = leftSine * 0.88 + rightSine * 0.12
            tmpR = rightSine * 0.88 + leftSine * 0.12
        }

        private fun stepBinauralDelta() {
            deltaPhaseL = wrapPhase(deltaPhaseL + 140.0 * dt)
            deltaPhaseR = wrapPhase(deltaPhaseR + 143.0 * dt)
            deltaSubPhase = wrapPhase(deltaSubPhase + 70.0 * dt)
            val sub = sin(twoPi * deltaSubPhase) * 0.055
            val leftSine = sin(twoPi * deltaPhaseL) * 0.22 + sub
            val rightSine = sin(twoPi * deltaPhaseR) * 0.22 + sub
            tmpL = leftSine * 0.85 + rightSine * 0.15
            tmpR = rightSine * 0.85 + leftSine * 0.15
        }

        private fun stepGreenNoise() {
            val pL = stepPink(greenPinkL, nextBipolar())
            val pR = stepPink(greenPinkR, nextBipolar())
            val midL = stepSvfBand(greenSvfL, pL, 480.0, 0.72)
            val midR = stepSvfBand(greenSvfR, pR, 510.0, 0.72)
            greenLpL += 0.18 * (midL * 1.45 + pL * 0.35 - greenLpL)
            greenLpR += 0.18 * (midR * 1.45 + pR * 0.35 - greenLpR)
            tmpL = greenLpL * 0.74 + greenLpR * 0.26
            tmpR = greenLpR * 0.74 + greenLpL * 0.26
        }

        private fun stepWaterfall() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(wfPinkL, wL)
            val pR = stepPink(wfPinkR, wR)
            wfDeepL = wfDeepL * 0.985 + pL * 0.045
            wfDeepR = wfDeepR * 0.985 + pR * 0.045
            wfSwellPhase = wrapPhase(wfSwellPhase + 0.08 * dt)
            val mod = 0.88 + 0.12 * sin(twoPi * wfSwellPhase)
            val sprayL = stepSvfBand(wfSvfL, pL, 1250.0, 0.65) * 0.65
            val sprayR = stepSvfBand(wfSvfR, pR, 1320.0, 0.65) * 0.65
            val outL = (wfDeepL * 1.4 + pL * 0.44 + sprayL) * mod
            val outR = (wfDeepR * 1.4 + pR * 0.44 + sprayR) * mod
            tmpL = outL * 0.78 + outR * 0.22
            tmpR = outR * 0.78 + outL * 0.22
        }

        private fun stepMountainWind() {
            val pL = stepPink(mwPinkL, nextBipolar())
            val pR = stepPink(mwPinkR, nextBipolar())
            mwLfo1 = wrapPhase(mwLfo1 + 0.075 * dt)
            mwLfo2 = wrapPhase(mwLfo2 + 0.048 * dt)
            val swell1 = 0.5 + 0.5 * sin(twoPi * mwLfo1)
            val swell2 = 0.5 + 0.5 * sin(twoPi * mwLfo2)
            val freq1 = 230.0 + 320.0 * swell1
            val freq2 = 340.0 + 380.0 * swell2
            val gust1L = stepSvfBand(mwSvf1L, pL, freq1, 2.2) * (0.45 + 0.55 * swell1)
            val gust1R = stepSvfBand(mwSvf1R, pR, freq1 * 1.03, 2.2) * (0.45 + 0.55 * swell1)
            val gust2L = stepSvfBand(mwSvf2L, pL, freq2, 1.8) * (0.35 + 0.65 * swell2)
            val gust2R = stepSvfBand(mwSvf2R, pR, freq2 * 0.97, 1.8) * (0.35 + 0.65 * swell2)
            tmpL = (gust1L + gust2L * 0.7 + pL * 0.22) * 0.68
            tmpR = (gust1R + gust2R * 0.7 + pR * 0.22) * 0.68
        }

        private fun stepSingingBowl() {
            if (bowlWait > 0) {
                bowlWait--
            } else {
                bowlAge++
                if (bowlAge > (sampleRate * 8.5).toInt()) {
                    bowlAge = 0
                    bowlWait = (sampleRate * nextRange(1.2, 2.8)).toInt()
                    bowlBaseFreq = bowlFreqs[(nextDouble01() * bowlFreqs.size).toInt().coerceIn(0, bowlFreqs.lastIndex)]
                    bowlPan = nextRange(0.32, 0.68)
                }
            }
            if (bowlWait > 0) {
                tmpL = 0.0
                tmpR = 0.0
                return
            }
            val t = bowlAge * dt
            val attack = if (t < 0.06) t / 0.06 else 1.0
            val env1 = attack * exp(-t * 0.42)
            val env2 = attack * exp(-t * 0.78)
            val env3 = attack * exp(-t * 1.25)
            bowlPhase1 = wrapPhase(bowlPhase1 + bowlBaseFreq * dt)
            bowlPhase2 = wrapPhase(bowlPhase2 + (bowlBaseFreq * 2.756) * dt)
            bowlPhase3 = wrapPhase(bowlPhase3 + (bowlBaseFreq * 5.404) * dt)
            bowlModPhase = wrapPhase(bowlModPhase + 1.6 * dt)
            val shimmer = 0.88 + 0.12 * sin(twoPi * bowlModPhase)
            val s = (sin(twoPi * bowlPhase1) * 0.62 * env1 +
                sin(twoPi * bowlPhase2) * 0.24 * env2 +
                sin(twoPi * bowlPhase3) * 0.10 * env3) * shimmer * 0.34
            tmpL = s * (1.0 - bowlPan * 0.45)
            tmpR = s * (0.55 + bowlPan * 0.45)
        }

        private fun stepFan() {
            val pL = stepPink(fanPinkL, nextBipolar())
            val pR = stepPink(fanPinkR, nextBipolar())
            fanHumPhase = wrapPhase(fanHumPhase + 58.0 * dt)
            fanBladeLfo = wrapPhase(fanBladeLfo + 5.2 * dt)
            val bladeMod = 0.86 + 0.14 * sin(twoPi * fanBladeLfo)
            val hum = sin(twoPi * fanHumPhase) * 0.055 + sin(twoPi * fanHumPhase * 2.0) * 0.024
            fanLpL += 0.11 * (pL * bladeMod - fanLpL)
            fanLpR += 0.11 * (pR * bladeMod - fanLpR)
            val outL = fanLpL * 0.85 + hum
            val outR = fanLpR * 0.85 + hum * 0.92
            tmpL = outL * 0.78 + outR * 0.22
            tmpR = outR * 0.78 + outL * 0.22
        }

        private fun stepTrain() {
            val pL = stepPink(trainPinkL, nextBipolar())
            val pR = stepPink(trainPinkR, nextBipolar())
            trainRumbleL = (trainRumbleL * 0.990 + pL * 0.042).coerceIn(-0.6, 0.6)
            trainRumbleR = (trainRumbleR * 0.990 + pR * 0.042).coerceIn(-0.6, 0.6)

            val pos = trainSamplePos
            trainSamplePos = (pos + 1) % trainCycleSamples
            val hit = when {
                pos in 0 until trainBeatLen -> 1.0 - pos.toDouble() / trainBeatLen
                pos in trainBeat2Start until (trainBeat2Start + trainBeatLen) ->
                    0.85 * (1.0 - (pos - trainBeat2Start).toDouble() / trainBeatLen)
                pos in trainBeat3Start until (trainBeat3Start + trainBeatLen) ->
                    0.70 * (1.0 - (pos - trainBeat3Start).toDouble() / trainBeatLen)
                pos in trainBeat4Start until (trainBeat4Start + trainBeatLen) ->
                    0.55 * (1.0 - (pos - trainBeat4Start).toDouble() / trainBeatLen)
                else -> 0.0
            }
            val rawImpulse = hit * (0.55 + 0.45 * nextBipolar()) * 0.26
            trainPulseLp += 0.09 * (rawImpulse - trainPulseLp)
            val outL = trainRumbleL * 1.15 + trainPulseLp + pL * 0.18
            val outR = trainRumbleR * 1.15 + trainPulseLp * 0.92 + pR * 0.18
            tmpL = outL * 0.74 + outR * 0.26
            tmpR = outR * 0.74 + outL * 0.26
        }

        private fun stepAmbientPad() {
            padChordAge++
            if (padChordAge >= padChordDuration) {
                padChordAge = 0
                padChordIdx = (padChordIdx + 1) % padChords.size
            }
            val chord = padChords[padChordIdx]
            val chordU = padChordAge.toDouble() / padChordDuration.toDouble()
            val swell = when {
                chordU < 0.22 -> chordU / 0.22
                chordU > 0.78 -> (1.0 - chordU) / 0.22
                else -> 1.0
            }.coerceIn(0.18, 1.0)
            padSweepLfo = wrapPhase(padSweepLfo + 0.055 * dt)
            val cutoff = 0.08 + 0.10 * (0.5 + 0.5 * sin(twoPi * padSweepLfo))
            var rawL = 0.0
            var rawR = 0.0
            for (v in 0 until 4) {
                val freq = chord[v]
                padPhaseL[v] = wrapPhase(padPhaseL[v] + (freq * (1.0 - 0.0022 * (v + 1))) * dt)
                padPhaseR[v] = wrapPhase(padPhaseR[v] + (freq * (1.0 + 0.0022 * (v + 1))) * dt)
                val triL = (sin(twoPi * padPhaseL[v]) * 0.8 + sin(twoPi * 2.0 * padPhaseL[v]) * 0.2) * 0.12
                val triR = (sin(twoPi * padPhaseR[v]) * 0.8 + sin(twoPi * 2.0 * padPhaseR[v]) * 0.2) * 0.12
                rawL += triL
                rawR += triR
            }
            padLpL += cutoff * (rawL * swell - padLpL)
            padLpR += cutoff * (rawR * swell - padLpR)
            tmpL = padLpL * 0.82 + padLpR * 0.18
            tmpR = padLpR * 0.82 + padLpL * 0.18
        }

        private fun stepRain() {
            val pL = stepPink(rainPinkL, nextBipolar())
            val pR = stepPink(rainPinkR, nextBipolar())
            rainLpL += 0.28 * (pL - rainLpL)
            rainLpR += 0.28 * (pR - rainLpR)
            var outL = rainLpL * 0.56 + pL * 0.12
            var outR = rainLpR * 0.56 + pR * 0.12

            for (i in 0 until 4) {
                if (!rainDropActive[i]) {
                    if (nextDouble01() < 0.00055) {
                        rainDropActive[i] = true
                        rainDropAge[i] = 0
                        rainDropDur[i] = nextRange(210.0, 440.0).toInt()
                        rainDropFreq[i] = nextRange(480.0, 1280.0)
                        rainDropPhase[i] = 0.0
                        rainDropPan[i] = nextRange(0.18, 0.82)
                        rainDropAmp[i] = nextRange(0.025, 0.058)
                    }
                } else {
                    val age = rainDropAge[i]
                    val dur = rainDropDur[i]
                    if (age >= dur) {
                        rainDropActive[i] = false
                    } else {
                        val u = age.toDouble() / dur.toDouble()
                        val env = if (u < 0.15) u / 0.15 else exp(-(u - 0.15) * 4.8)
                        val f = rainDropFreq[i] * (1.0 + 0.14 * u)
                        rainDropPhase[i] = wrapPhase(rainDropPhase[i] + f * dt)
                        val s = sin(twoPi * rainDropPhase[i]) * env * rainDropAmp[i]
                        val pan = rainDropPan[i]
                        outL += s * (1.0 - pan * 0.5)
                        outR += s * (0.5 + pan * 0.5)
                        rainDropAge[i] = age + 1
                    }
                }
            }
            tmpL = outL * 0.82 + outR * 0.18
            tmpR = outR * 0.82 + outL * 0.18
        }

        private fun stepThunder() {
            val pL = stepPink(thunderPinkL, nextBipolar())
            val pR = stepPink(thunderPinkR, nextBipolar())
            thunderRainLpL += 0.26 * (pL - thunderRainLpL)
            thunderRainLpR += 0.26 * (pR - thunderRainLpR)

            thunderCooldown--
            if (thunderCooldown <= 0 || nextDouble01() > 0.999935) {
                thunderRumbleEnv = (thunderRumbleEnv + nextRange(0.32, 0.55)).coerceIn(0.0, 1.0)
                thunderCooldown = nextRange(sampleRate * 9.0, sampleRate * 20.0).toInt()
            } else {
                thunderRumbleEnv *= 0.99968
            }

            thunderDeepBrown = (thunderDeepBrown * 0.994 + nextBipolar() * 0.026).coerceIn(-0.70, 0.70)
            thunderPhase = wrapPhase(thunderPhase + 46.0 * dt)
            val subTone = sin(twoPi * thunderPhase) * 0.40 + sin(twoPi * 2.0 * thunderPhase) * 0.12
            val rumble = (subTone + thunderDeepBrown * 0.68) * thunderRumbleEnv * 0.48
            tmpL = thunderRainLpL * 0.52 + rumble * 0.95
            tmpR = thunderRainLpR * 0.52 + rumble * 0.90
        }

        private fun stepLofi() {
            lofiStepCounter++
            if (lofiStepCounter >= lofiStepSamples) {
                lofiStepCounter = 0
                lofiSlotReleasing[lofiActiveSlot] = true
                val nextSlot = 1 - lofiActiveSlot
                lofiChordIndex = (lofiChordIndex + 1) % lofiChords.size
                lofiSlotActive[nextSlot] = true
                lofiSlotReleasing[nextSlot] = false
                lofiSlotChord[nextSlot] = lofiChordIndex
                lofiSlotAge[nextSlot] = 0
                lofiSlotRelease[nextSlot] = 1.0
                for (v in 0 until 4) {
                    lofiVoicePhases[nextSlot][v] = 0.0
                }
                lofiBassPhases[nextSlot] = 0.0
                lofiActiveSlot = nextSlot
            }

            lofiWowPhase = wrapPhase(lofiWowPhase + 0.36 * dt)
            lofiFlutterPhase = wrapPhase(lofiFlutterPhase + 1.13 * dt)
            val pitchMult = 1.0 + 0.0014 * sin(twoPi * lofiWowPhase) + 0.0004 * sin(twoPi * lofiFlutterPhase)

            var rawL = 0.0
            var rawR = 0.0

            for (s in 0 until 2) {
                if (!lofiSlotActive[s]) continue
                if (lofiSlotReleasing[s]) {
                    lofiSlotRelease[s] *= 0.99945
                    if (lofiSlotRelease[s] < 0.0005) {
                        lofiSlotActive[s] = false
                        continue
                    }
                }
                val age = lofiSlotAge[s]
                lofiSlotAge[s] = age + 1
                val tNote = age * dt
                val attack = if (tNote < 0.032) sin((PI * 0.5) * (tNote / 0.032)) else 1.0
                val decay = 0.36 + 0.64 * exp(-tNote * 0.62)
                val bell2 = exp(-tNote * 2.4)
                val bell3 = exp(-tNote * 7.5)
                val slotGain = attack * decay * lofiSlotRelease[s]

                val chordIdx = lofiSlotChord[s]
                val notes = lofiChords[chordIdx]
                val voicePhases = lofiVoicePhases[s]

                for (v in 0 until 4) {
                    val f = notes[v] * pitchMult
                    val p = wrapPhase(voicePhases[v] + f * dt)
                    voicePhases[v] = p
                    val tine = sin(twoPi * p) * 0.78 +
                        sin(twoPi * 2.0 * p) * (0.22 * bell2) +
                        sin(twoPi * 3.0 * p) * (0.045 * bell3)
                    val contrib = tine * slotGain * 0.135
                    val panL = lofiVoicePanL[v]
                    rawL += contrib * panL
                    rawR += contrib * (1.0 - panL)
                }

                val bassFreq = lofiRoots[chordIdx] * pitchMult
                val bp = wrapPhase(lofiBassPhases[s] + bassFreq * dt)
                lofiBassPhases[s] = bp
                val bassAttack = if (tNote < 0.045) sin((PI * 0.5) * (tNote / 0.045)) else 1.0
                val bassDecay = 0.42 + 0.58 * exp(-tNote * 0.55)
                val bassTone = (sin(twoPi * bp) * 0.74 + sin(twoPi * 2.0 * bp) * 0.22) *
                    bassAttack * bassDecay * lofiSlotRelease[s] * 0.145
                rawL += bassTone * 0.50
                rawR += bassTone * 0.50
            }

            // Warm 2-pole breathing low-pass filter (~950-1250 Hz)
            lofiFilterLfoPhase = wrapPhase(lofiFilterLfoPhase + 0.11 * dt)
            val lpAlpha = 0.25 + 0.035 * sin(twoPi * lofiFilterLfoPhase)
            lofiLp1L += lpAlpha * (rawL - lofiLp1L)
            lofiLp2L += lpAlpha * (lofiLp1L - lofiLp2L)
            lofiLp1R += lpAlpha * (rawR - lofiLp1R)
            lofiLp2R += lpAlpha * (lofiLp1R - lofiLp2R)

            // Gentle stereo chorus via pre-allocated 512-sample ring buffer
            lofiChorusBufL[lofiChorusWriteIdx] = lofiLp2L
            lofiChorusBufR[lofiChorusWriteIdx] = lofiLp2R
            lofiChorusLfoPhase = wrapPhase(lofiChorusLfoPhase + 0.42 * dt)
            val mod = sin(twoPi * lofiChorusLfoPhase)
            val modR = sin(twoPi * lofiChorusLfoPhase + 1.8)
            val wetL = readRingInterp(lofiChorusBufL, lofiChorusWriteIdx, lofiChorusMask, 205.0 + 44.0 * mod)
            val wetR = readRingInterp(lofiChorusBufR, lofiChorusWriteIdx, lofiChorusMask, 235.0 + 44.0 * modR)
            lofiChorusWriteIdx = (lofiChorusWriteIdx + 1) and lofiChorusMask

            val musicL = lofiLp2L * 0.78 + wetL * 0.24
            val musicR = lofiLp2R * 0.78 + wetR * 0.24

            // Soft vinyl dust (band-limited pink) + rounded low-volume micro-crackles
            val vinylRaw = stepPink(lofiVinylPink, nextBipolar())
            lofiVinylHpLp += 0.06 * (vinylRaw - lofiVinylHpLp)
            val vinylHp = vinylRaw - lofiVinylHpLp
            lofiVinylLp += 0.46 * (vinylHp - lofiVinylLp)
            val dust = lofiVinylLp * 0.011

            if (nextDouble01() > 0.99945) {
                lofiCrackleBurst = nextBipolar() * 0.048
            } else {
                lofiCrackleBurst *= 0.72
            }
            lofiCrackleSmooth = lofiCrackleSmooth * 0.91 + lofiCrackleBurst * 0.09

            tmpL = musicL + dust + lofiCrackleSmooth * 0.80
            tmpR = musicR + dust - lofiCrackleSmooth * 0.55
        }

        private fun stepLibrary() {
            val w = nextBipolar()
            libBrownL = (libBrownL * 0.993 + w * 0.012).coerceIn(-0.45, 0.45)
            libBrownR = (libBrownR * 0.993 + nextBipolar() * 0.012).coerceIn(-0.45, 0.45)
            val pink = stepPink(libPink, w)
            if (nextDouble01() > 0.99978 && libPageEnv < 0.08) {
                libPageEnv = nextRange(0.45, 0.85)
                libPagePan = nextRange(0.28, 0.72)
            } else {
                libPageEnv *= 0.9980
            }
            val rustle = stepSvfBand(libSvf, pink, 1550.0, 1.4) * libPageEnv * 0.36
            tmpL = libBrownL * 0.22 + pink * 0.04 + rustle * (1.0 - libPagePan * 0.45)
            tmpR = libBrownR * 0.22 + pink * 0.04 + rustle * (0.55 + libPagePan * 0.45)
        }

        private fun stepKeyboard() {
            val w = nextBipolar()
            val p = stepPink(kbPink, w)
            kbLp += 0.06 * (p - kbLp)
            if (nextDouble01() > 0.99915) {
                kbEnv = nextRange(0.42, 0.88)
                kbPan = nextRange(0.35, 0.65)
            } else {
                kbEnv *= 0.972
            }
            kbPhase = wrapPhase(kbPhase + 165.0 * dt)
            val thock = (sin(twoPi * kbPhase) * 0.42 + p * 0.32) * kbEnv * 0.44
            tmpL = kbLp * 0.16 + thock * (1.0 - kbPan * 0.3)
            tmpR = kbLp * 0.16 + thock * (0.7 + kbPan * 0.3)
        }

        private fun stepCoffee() {
            // 1. Warm acoustic room floor (pink + brown)
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(coffeePinkL, wL)
            val pR = stepPink(coffeePinkR, wR)
            coffeeBrownL = (coffeeBrownL * 0.992 + wL * 0.024).coerceIn(-0.55, 0.55)
            coffeeBrownR = (coffeeBrownR * 0.992 + wR * 0.024).coerceIn(-0.55, 0.55)
            coffeeFloorLpL += 0.18 * (pL * 0.55 + coffeeBrownL * 0.32 - coffeeFloorLpL)
            coffeeFloorLpR += 0.18 * (pR * 0.55 + coffeeBrownR * 0.32 - coffeeFloorLpR)

            var outL = coffeeFloorLpL * 0.44
            var outR = coffeeFloorLpR * 0.44

            // 2. Multi-band distant conversational murmur (no harsh peaks)
            var murmurL = 0.0
            var murmurR = 0.0
            val src = (pL + pR) * 0.5
            for (b in 0 until 3) {
                coffeeMurmurTimer[b]--
                if (coffeeMurmurTimer[b] <= 0) {
                    coffeeMurmurTarget[b] = nextRange(0.22, 0.92)
                    coffeeMurmurTimer[b] = nextRange(sampleRate * 0.24, sampleRate * 0.55).toInt()
                }
                coffeeMurmurEnv[b] += (coffeeMurmurTarget[b] - coffeeMurmurEnv[b]) * 0.00045
                coffeeLfoPhase[b] = wrapPhase(coffeeLfoPhase[b] + coffeeLfoRates[b] * dt)
                val breath = 0.60 + 0.40 * sin(twoPi * coffeeLfoPhase[b])
                val band = stepSvfBand(coffeeMurmurSvf[b], src, coffeeMurmurFreqs[b], coffeeMurmurQs[b])
                val modBand = band * coffeeMurmurEnv[b] * breath * coffeeBandGains[b]
                val panL = coffeeMurmurPanL[b]
                murmurL += modBand * panL
                murmurR += modBand * (1.0 - panL)
            }
            coffeeMurmurHiLpL += 0.30 * (murmurL - coffeeMurmurHiLpL)
            coffeeMurmurHiLpR += 0.30 * (murmurR - coffeeMurmurHiLpR)
            outL += coffeeMurmurHiLpL
            outR += coffeeMurmurHiLpR

            // 3. Soft, rare porcelain cup/spoon clink (rounded harmonics, fast decay)
            if (!clinkActive) {
                if (clinkDoubleDelay > 0) {
                    clinkDoubleDelay--
                    if (clinkDoubleDelay == 0) {
                        clinkActive = true
                        clinkAge = 0
                        clinkDur = nextRange(900.0, 1300.0).toInt()
                        clinkFreq *= nextRange(0.97, 1.03)
                        clinkPhase1 = 0.0
                        clinkPhase2 = 0.0
                        clinkAmp *= 0.62
                    }
                } else {
                    clinkCooldown--
                    if (clinkCooldown <= 0 && nextDouble01() < 0.00008) {
                        clinkActive = true
                        clinkAge = 0
                        clinkDur = nextRange(1100.0, 1750.0).toInt()
                        clinkFreq = nextRange(1450.0, 1880.0)
                        clinkPhase1 = 0.0
                        clinkPhase2 = 0.0
                        clinkPan = nextRange(0.26, 0.74)
                        clinkAmp = nextRange(0.036, 0.060)
                        clinkCooldown = nextRange(sampleRate * 3.5, sampleRate * 7.5).toInt()
                        if (nextDouble01() < 0.38) {
                            clinkDoubleDelay = nextRange(sampleRate * 0.12, sampleRate * 0.22).toInt()
                        }
                    }
                }
            }
            if (clinkActive) {
                if (clinkAge >= clinkDur) {
                    clinkActive = false
                } else {
                    val t = clinkAge * dt
                    val attack = if (t < 0.003) t / 0.003 else 1.0
                    val env1 = attack * exp(-t * 36.0)
                    val env2 = attack * exp(-t * 88.0) * 0.26
                    clinkPhase1 = wrapPhase(clinkPhase1 + clinkFreq * dt)
                    clinkPhase2 = wrapPhase(clinkPhase2 + clinkFreq * 2.38 * dt)
                    val clinkSample = (sin(twoPi * clinkPhase1) * env1 + sin(twoPi * clinkPhase2) * env2) * clinkAmp
                    outL += clinkSample * (1.0 - clinkPan * 0.45)
                    outR += clinkSample * (0.55 + clinkPan * 0.45)
                    clinkAge++
                }
            }

            // 4. Occasional gentle espresso steam burst
            if (!steamActive) {
                steamCooldown--
                if (steamCooldown <= 0 && nextDouble01() < 0.00004) {
                    steamActive = true
                    steamAge = 0
                    steamPan = nextRange(0.28, 0.72)
                    steamCooldown = nextRange(sampleRate * 12.0, sampleRate * 22.0).toInt()
                }
            } else {
                if (steamAge >= steamDur) {
                    steamActive = false
                } else {
                    val u = steamAge.toDouble() / steamDur.toDouble()
                    val s = sin(PI * u)
                    val env = s * s * 0.75 + s * 0.25
                    val air = stepSvfBand(steamSvfHi, wL, 2150.0, 1.1)
                    val body = stepSvfBand(steamSvfBody, pR, 440.0, 1.3)
                    val steamSample = (air * 0.060 + body * 0.032) * env
                    outL += steamSample * (1.0 - steamPan * 0.45)
                    outR += steamSample * (0.55 + steamPan * 0.45)
                    steamAge++
                }
            }

            tmpL = outL
            tmpR = outR
        }

        private fun stepFire() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(firePinkL, wL)
            val pR = stepPink(firePinkR, wR)
            fireBrownL = (fireBrownL * 0.994 + wL * 0.025).coerceIn(-0.65, 0.65)
            fireBrownR = (fireBrownR * 0.994 + wR * 0.025).coerceIn(-0.65, 0.65)
            fireLpL += 0.08 * (fireBrownL * 0.75 + pL * 0.25 - fireLpL)
            fireLpR += 0.08 * (fireBrownR * 0.75 + pR * 0.25 - fireLpR)

            fireLfoA = wrapPhase(fireLfoA + 0.38 * dt)
            fireLfoB = wrapPhase(fireLfoB + 0.74 * dt)
            val flicker = 0.78 + 0.14 * sin(twoPi * fireLfoA) + 0.08 * sin(twoPi * fireLfoB)
            var outL = fireLpL * 0.68 * flicker
            var outR = fireLpR * 0.68 * flicker

            for (i in 0 until 3) {
                if (!fireCrackleActive[i]) {
                    if (nextDouble01() < 0.00038) {
                        fireCrackleActive[i] = true
                        fireCrackleAge[i] = 0
                        fireCrackleDur[i] = nextRange(70.0, 210.0).toInt()
                        fireCrackleFreq[i] = nextRange(680.0, 1580.0)
                        fireCracklePhase[i] = 0.0
                        fireCracklePan[i] = nextRange(0.22, 0.78)
                        fireCrackleAmp[i] = nextRange(0.035, 0.085)
                    }
                } else {
                    val age = fireCrackleAge[i]
                    val dur = fireCrackleDur[i]
                    if (age >= dur) {
                        fireCrackleActive[i] = false
                    } else {
                        val u = age.toDouble() / dur.toDouble()
                        val env = if (u < 0.12) u / 0.12 else exp(-(u - 0.12) * 5.2)
                        fireCracklePhase[i] = wrapPhase(fireCracklePhase[i] + fireCrackleFreq[i] * dt)
                        val snap = (sin(twoPi * fireCracklePhase[i]) * 0.65 + nextBipolar() * 0.35) * env * fireCrackleAmp[i]
                        val pan = fireCracklePan[i]
                        outL += snap * (1.0 - pan * 0.5)
                        outR += snap * (0.5 + pan * 0.5)
                        fireCrackleAge[i] = age + 1
                    }
                }
            }
            tmpL = outL
            tmpR = outR
        }

        private fun stepWaves() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(wavesPinkL, wL)
            val pR = stepPink(wavesPinkR, wR)
            wavesBrownL = (wavesBrownL * 0.992 + wL * 0.024).coerceIn(-0.62, 0.62)
            wavesBrownR = (wavesBrownR * 0.992 + wR * 0.024).coerceIn(-0.62, 0.62)

            wavesPhaseA = wrapPhase(wavesPhaseA + 0.13 * dt)
            wavesPhaseB = wrapPhase(wavesPhaseB + 0.095 * dt)
            val swellL = 0.5 + 0.5 * sin(twoPi * wavesPhaseA)
            val swellR = 0.5 + 0.5 * sin(twoPi * wavesPhaseA + 0.25)
            val macro = 0.78 + 0.22 * sin(twoPi * wavesPhaseB)

            val alphaL = 0.04 + 0.28 * swellL * swellL
            val alphaR = 0.04 + 0.28 * swellR * swellR
            val srcL = wavesBrownL * 0.55 + pL * 0.45
            val srcR = wavesBrownR * 0.55 + pR * 0.45
            wavesLpL += alphaL * (srcL - wavesLpL)
            wavesLpR += alphaR * (srcR - wavesLpR)

            val gainL = (0.34 + 0.66 * swellL) * macro * 0.62
            val gainR = (0.34 + 0.66 * swellR) * macro * 0.62
            tmpL = wavesLpL * gainL
            tmpR = wavesLpR * gainR
        }

        private fun stepForest() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(forestPinkL, wL)
            val pR = stepPink(forestPinkR, wR)

            forestGustPhaseL = wrapPhase(forestGustPhaseL + 0.085 * dt)
            forestGustPhaseR = wrapPhase(forestGustPhaseR + 0.125 * dt)
            val gustL = 0.5 + 0.5 * sin(twoPi * forestGustPhaseL)
            val gustR = 0.5 + 0.5 * sin(twoPi * forestGustPhaseR)

            val alphaL = 0.07 + 0.16 * gustL
            val alphaR = 0.07 + 0.16 * gustR
            forestLpL += alphaL * (pL - forestLpL)
            forestLpR += alphaR * (pR - forestLpR)

            val leafShimmer = stepSvfBand(forestLeafSvf, (pL + pR) * 0.5, 1650.0, 1.3)
            var outL = forestLpL * (0.48 + 0.26 * gustL) + leafShimmer * (0.04 + 0.06 * gustL)
            var outR = forestLpR * (0.48 + 0.26 * gustR) + leafShimmer * (0.04 + 0.06 * gustR)

            // Subtle distant canopy bird (very calm & non-intrusive)
            var distBird = 0.0
            when (forestDistBirdState) {
                0 -> {
                    forestDistBirdCounter--
                    if (forestDistBirdCounter <= 0) {
                        forestDistBirdState = 1
                        forestDistBirdCounter = 0
                        forestDistBirdDur = nextRange(2100.0, 3200.0).toInt()
                        forestDistBirdFreq = nextRange(1380.0, 1820.0)
                        forestDistBirdPhase = 0.0
                        forestDistBirdPan = nextRange(0.24, 0.76)
                    }
                }
                1 -> {
                    if (forestDistBirdCounter >= forestDistBirdDur) {
                        forestDistBirdState = 2
                        forestDistBirdCounter = nextRange(1200.0, 2200.0).toInt()
                    } else {
                        val u = forestDistBirdCounter.toDouble() / forestDistBirdDur.toDouble()
                        val env = sin(PI * u)
                        val f = forestDistBirdFreq * (1.0 + 0.12 * sin(PI * u))
                        forestDistBirdPhase = wrapPhase(forestDistBirdPhase + f * dt)
                        distBird = sin(twoPi * forestDistBirdPhase) * env * 0.055
                        forestDistBirdCounter++
                    }
                }
                2 -> {
                    forestDistBirdCounter--
                    if (forestDistBirdCounter <= 0) {
                        forestDistBirdState = 3
                        forestDistBirdCounter = 0
                        forestDistBirdDur = nextRange(2000.0, 3000.0).toInt()
                        forestDistBirdFreq *= nextRange(0.88, 1.12)
                        forestDistBirdPhase = 0.0
                    }
                }
                else -> {
                    if (forestDistBirdCounter >= forestDistBirdDur) {
                        forestDistBirdState = 0
                        forestDistBirdCounter = nextRange(sampleRate * 5.0, sampleRate * 10.5).toInt()
                    } else {
                        val u = forestDistBirdCounter.toDouble() / forestDistBirdDur.toDouble()
                        val env = sin(PI * u)
                        val f = forestDistBirdFreq * (1.04 - 0.12 * u)
                        forestDistBirdPhase = wrapPhase(forestDistBirdPhase + f * dt)
                        distBird = sin(twoPi * forestDistBirdPhase) * env * 0.050
                        forestDistBirdCounter++
                    }
                }
            }
            forestDistBirdLp += 0.42 * (distBird - forestDistBirdLp)
            outL += forestDistBirdLp * (1.0 - forestDistBirdPan * 0.5)
            outR += forestDistBirdLp * (0.5 + forestDistBirdPan * 0.5)

            tmpL = outL
            tmpR = outR
        }

        private fun stepBirds() {
            val pL = stepPink(birdsBreezePinkL, nextBipolar())
            val pR = stepPink(birdsBreezePinkR, nextBipolar())
            birdsBreezeLfo = wrapPhase(birdsBreezeLfo + 0.09 * dt)
            val breezeGain = 0.24 + 0.08 * sin(twoPi * birdsBreezeLfo)
            birdsBreezeLpL += 0.15 * (pL - birdsBreezeLpL)
            birdsBreezeLpR += 0.15 * (pR - birdsBreezeLpR)

            var birdsL = 0.0
            var birdsR = 0.0

            for (v in 0 until 2) {
                when (birdState[v]) {
                    0 -> { // WAIT
                        birdTimer[v]--
                        if (birdTimer[v] <= 0) {
                            birdState[v] = 1
                            birdAge[v] = 0
                            birdDur[v] = nextRange(1750.0, 4100.0).toInt()
                            birdNotesLeft[v] = 2 + (nextDouble01() * 3.8).toInt()
                            birdNoteIdx[v] = (nextDouble01() * birdPentatonic.size).toInt().coerceIn(0, birdPentatonic.lastIndex)
                            birdBaseFreq[v] = birdPentatonic[birdNoteIdx[v]] * nextRange(0.98, 1.02)
                            birdContour[v] = (nextDouble01() * 4.0).toInt().coerceIn(0, 3)
                            birdTrillRate[v] = nextRange(24.0, 34.0)
                            birdTrillPhase[v] = 0.0
                            birdPhase[v] = 0.0
                            birdPan[v] = if (v == 0) nextRange(0.56, 0.80) else nextRange(0.20, 0.44)
                            birdAmp[v] = nextRange(0.11, 0.165)
                        }
                    }
                    1 -> { // NOTE
                        val age = birdAge[v]
                        val dur = birdDur[v]
                        if (age >= dur) {
                            if (birdNotesLeft[v] > 1) {
                                birdNotesLeft[v]--
                                birdState[v] = 2
                                birdTimer[v] = nextRange(850.0, 2200.0).toInt()
                            } else {
                                birdState[v] = 0
                                birdTimer[v] = nextRange(sampleRate * 1.8, sampleRate * 4.4).toInt()
                            }
                        } else {
                            val u = age.toDouble() / dur.toDouble()
                            val env = sin(PI * u)
                            val f0 = birdBaseFreq[v]
                            val instFreq = when (birdContour[v]) {
                                0 -> f0 * (0.94 + 0.18 * u)
                                1 -> f0 * (1.07 - 0.19 * u)
                                2 -> f0 * (0.96 + 0.15 * sin(PI * u))
                                else -> {
                                    birdTrillPhase[v] = wrapPhase(birdTrillPhase[v] + birdTrillRate[v] * dt)
                                    f0 * (1.0 + 0.052 * sin(twoPi * birdTrillPhase[v]))
                                }
                            }.coerceAtMost(2720.0)
                            birdPhase[v] = wrapPhase(birdPhase[v] + instFreq * dt)
                            val tone = (sin(twoPi * birdPhase[v]) * 0.88 + sin(twoPi * 2.0 * birdPhase[v]) * 0.12) * env * birdAmp[v]
                            val pan = birdPan[v]
                            birdsL += tone * pan
                            birdsR += tone * (1.0 - pan)
                            birdAge[v] = age + 1
                        }
                    }
                    else -> { // GAP
                        birdTimer[v]--
                        if (birdTimer[v] <= 0) {
                            birdState[v] = 1
                            birdAge[v] = 0
                            birdDur[v] = nextRange(1650.0, 3800.0).toInt()
                            val delta = ((nextDouble01() * 5.0).toInt() - 2)
                            birdNoteIdx[v] = (birdNoteIdx[v] + delta).coerceIn(0, birdPentatonic.lastIndex)
                            birdBaseFreq[v] = birdPentatonic[birdNoteIdx[v]] * nextRange(0.985, 1.015)
                            birdContour[v] = (nextDouble01() * 4.0).toInt().coerceIn(0, 3)
                            birdPhase[v] = 0.0
                        }
                    }
                }
            }

            // Gentle low-pass (~2900 Hz) keeps all songbird chirps sweet and never shrill
            birdsToneLpL += 0.56 * (birdsL - birdsToneLpL)
            birdsToneLpR += 0.56 * (birdsR - birdsToneLpR)
            tmpL = birdsBreezeLpL * breezeGain + birdsToneLpL
            tmpR = birdsBreezeLpR * breezeGain + birdsToneLpR
        }

        private fun stepMourningDove() {
            // Soft dawn breeze
            val pL = stepPink(doveBreezePinkL, nextBipolar())
            val pR = stepPink(doveBreezePinkR, nextBipolar())
            doveBreezeLfo = wrapPhase(doveBreezeLfo + 0.07 * dt)
            val breezeGain = 0.24 + 0.08 * sin(twoPi * doveBreezeLfo)
            doveBreezeLpL += 0.13 * (pL - doveBreezeLpL)
            doveBreezeLpR += 0.13 * (pR - doveBreezeLpR)

            var cooL = 0.0
            var cooR = 0.0

            if (doveStep == 7) { // REST
                doveAge++
                if (doveAge >= doveRestTarget) {
                    doveStep = 0
                    doveAge = 0
                    dovePitchMul = nextRange(0.96, 1.04)
                    dovePan = nextRange(0.36, 0.64)
                    dovePhase = 0.0
                }
            } else {
                val dur = doveStepDurations[doveStep]
                if (doveAge >= dur) {
                    doveStep++
                    doveAge = 0
                    if (doveStep == 7) {
                        doveRestTarget = nextRange(sampleRate * 4.8, sampleRate * 8.8).toInt()
                    }
                } else {
                    if (doveStep == 0 || doveStep == 2 || doveStep == 4 || doveStep == 6) {
                        val u = doveAge.toDouble() / dur.toDouble()
                        val baseFreq = when (doveStep) {
                            0 -> 465.0 + 50.0 * u                                 // cuu
                            2 -> 495.0 + 58.0 * sin(PI * 0.85 * u) - 16.0 * u     // UUU
                            4 -> 486.0 - 14.0 * u                                 // uu
                            else -> 474.0 - 18.0 * u                              // uu
                        }
                        val noteGain = when (doveStep) {
                            0 -> 0.78
                            2 -> 1.00
                            4 -> 0.74
                            else -> 0.66
                        }
                        doveVibratoPhase = wrapPhase(doveVibratoPhase + 5.2 * dt)
                        val vib = 1.0 + 0.0045 * sin(twoPi * doveVibratoPhase)
                        val instFreq = baseFreq * dovePitchMul * vib
                        dovePhase = wrapPhase(dovePhase + instFreq * dt)

                        val env = sin(PI * u)
                        val breath = stepSvfBand(doveBreathSvf, nextBipolar(), 520.0, 2.5)
                        val coo = (sin(twoPi * dovePhase) * 0.92 +
                            sin(twoPi * 2.0 * dovePhase) * 0.055 +
                            breath * 0.025) * env * noteGain * 0.21
                        cooL = coo * dovePan
                        cooR = coo * (1.0 - dovePan)
                    }
                    doveAge++
                }
            }

            // Gentle outdoor dawn cross-reflection (~42 ms)
            val reflIdx = (doveDelayIdx - 920) and doveDelayMask
            val reflL = doveDelayBufR[reflIdx] * 0.20
            val reflR = doveDelayBufL[reflIdx] * 0.20
            doveDelayBufL[doveDelayIdx] = cooL
            doveDelayBufR[doveDelayIdx] = cooR
            doveDelayIdx = (doveDelayIdx + 1) and doveDelayMask

            tmpL = doveBreezeLpL * breezeGain + cooL + reflL
            tmpR = doveBreezeLpR * breezeGain + cooR + reflR
        }

        private fun stepForestStream() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(streamPinkL, wL)
            val pR = stepPink(streamPinkR, wR)
            streamModPhaseA = wrapPhase(streamModPhaseA + 0.23 * dt)
            streamModPhaseB = wrapPhase(streamModPhaseB + 0.37 * dt)
            val modA = 0.5 + 0.5 * sin(twoPi * streamModPhaseA)
            val modB = 0.5 + 0.5 * sin(twoPi * streamModPhaseB)

            streamLpL += (0.18 + 0.08 * modA) * (pL - streamLpL)
            streamLpR += (0.18 + 0.08 * modB) * (pR - streamLpR)

            val riffleA = stepSvfBand(streamSvfA, pL, 640.0 + 130.0 * modA, 2.2) * 0.14
            val riffleB = stepSvfBand(streamSvfB, pR, 1340.0 + 240.0 * modB, 2.0) * 0.11

            var outL = streamLpL * 0.44 + riffleA * 0.72 + riffleB * 0.28
            var outR = streamLpR * 0.44 + riffleB * 0.72 + riffleA * 0.28

            for (i in 0 until 4) {
                if (!bubbleActive[i]) {
                    if (nextDouble01() < 0.0014) {
                        bubbleActive[i] = true
                        bubbleAge[i] = 0
                        bubbleDur[i] = nextRange(380.0, 1080.0).toInt()
                        bubbleFreq[i] = nextRange(360.0, 960.0)
                        bubblePhase[i] = 0.0
                        bubblePan[i] = nextRange(0.20, 0.80)
                        bubbleAmp[i] = nextRange(0.025, 0.062)
                    }
                } else {
                    val age = bubbleAge[i]
                    val dur = bubbleDur[i]
                    if (age >= dur) {
                        bubbleActive[i] = false
                    } else {
                        val u = age.toDouble() / dur.toDouble()
                        val env = if (u < 0.15) u / 0.15 else exp(-(u - 0.15) * 4.5)
                        val f = bubbleFreq[i] * (1.0 + 0.24 * u)
                        bubblePhase[i] = wrapPhase(bubblePhase[i] + f * dt)
                        val s = sin(twoPi * bubblePhase[i]) * env * bubbleAmp[i]
                        val pan = bubblePan[i]
                        outL += s * (1.0 - pan * 0.45)
                        outR += s * (0.55 + pan * 0.45)
                        bubbleAge[i] = age + 1
                    }
                }
            }
            tmpL = outL
            tmpR = outR
        }

        private fun stepForestRain() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(fRainPinkL, wL)
            val pR = stepPink(fRainPinkR, wR)
            fRainBrownL = (fRainBrownL * 0.992 + wL * 0.026).coerceIn(-0.62, 0.62)
            fRainBrownR = (fRainBrownR * 0.992 + wR * 0.026).coerceIn(-0.62, 0.62)

            fRainEarthLpL += 0.12 * (fRainBrownL * 0.72 + pL * 0.28 - fRainEarthLpL)
            fRainEarthLpR += 0.12 * (fRainBrownR * 0.72 + pR * 0.28 - fRainEarthLpR)
            fRainLeafLpL += 0.34 * (pL - fRainLeafLpL)
            fRainLeafLpR += 0.34 * (pR - fRainLeafLpR)
            val patterL = stepSvfBand(fRainSvfL, pL, 1220.0, 1.3) * 0.12
            val patterR = stepSvfBand(fRainSvfR, pR, 1260.0, 1.3) * 0.12

            var outL = fRainEarthLpL * 0.46 + fRainLeafLpL * 0.36 + patterL
            var outR = fRainEarthLpR * 0.46 + fRainLeafLpR * 0.36 + patterR

            for (i in 0 until 6) {
                if (!fRainDropActive[i]) {
                    if (nextDouble01() < 0.00024) {
                        fRainDropActive[i] = true
                        fRainDropAge[i] = 0
                        fRainDropDur[i] = nextRange(300.0, 740.0).toInt()
                        fRainDropFreq[i] = nextRange(340.0, 1080.0)
                        fRainDropPhase[i] = 0.0
                        fRainDropPan[i] = nextRange(0.16, 0.84)
                        fRainDropAmp[i] = nextRange(0.030, 0.068)
                    }
                } else {
                    val age = fRainDropAge[i]
                    val dur = fRainDropDur[i]
                    if (age >= dur) {
                        fRainDropActive[i] = false
                    } else {
                        val u = age.toDouble() / dur.toDouble()
                        val env = if (u < 0.16) u / 0.16 else exp(-(u - 0.16) * 4.4)
                        val f = fRainDropFreq[i] * (1.0 + 0.10 * u)
                        fRainDropPhase[i] = wrapPhase(fRainDropPhase[i] + f * dt)
                        val s = sin(twoPi * fRainDropPhase[i]) * env * fRainDropAmp[i]
                        val pan = fRainDropPan[i]
                        outL += s * (1.0 - pan * 0.5)
                        outR += s * (0.5 + pan * 0.5)
                        fRainDropAge[i] = age + 1
                    }
                }
            }
            tmpL = outL
            tmpR = outR
        }

        private fun stepForestNight() {
            // Calm night air
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(nightPinkL, wL)
            val pR = stepPink(nightPinkR, wR)
            nightBrownL = (nightBrownL * 0.993 + wL * 0.018).coerceIn(-0.48, 0.48)
            nightBrownR = (nightBrownR * 0.993 + wR * 0.018).coerceIn(-0.48, 0.48)
            nightBreezeLfo = wrapPhase(nightBreezeLfo + 0.07 * dt)
            val nightSwell = 0.78 + 0.22 * sin(twoPi * nightBreezeLfo)
            nightLpL += 0.11 * (nightBrownL * 0.45 + pL * 0.55 - nightLpL)
            nightLpR += 0.11 * (nightBrownR * 0.45 + pR * 0.55 - nightLpR)

            // 2 gentle stereo pulsed cricket clusters
            var rawCricketL = 0.0
            var rawCricketR = 0.0
            val pulseSamples = (sampleRate * 0.024).toInt() // ~529 samples per pulse (14ms on, 10ms off)
            val pulseOnSamples = (sampleRate * 0.014).toInt()
            val chirpBurstSamples = pulseSamples * 4
            for (c in 0 until 2) {
                cricketMacroPhases[c] = wrapPhase(cricketMacroPhases[c] + cricketMacroRates[c] * dt)
                val macroEnv = 0.25 + 0.75 * (0.5 + 0.5 * sin(twoPi * cricketMacroPhases[c]))
                val cycPos = cricketCyclePos[c]
                cricketCyclePos[c] = (cycPos + 1) % cricketCycleLen[c]
                if (cycPos < chirpBurstSamples) {
                    val inPulse = cycPos % pulseSamples
                    if (inPulse < pulseOnSamples) {
                        val u = inPulse.toDouble() / pulseOnSamples.toDouble()
                        val pEnv = sin(PI * u)
                        cricketCarrierPhases[c] = wrapPhase(cricketCarrierPhases[c] + cricketBaseFreqs[c] * dt)
                        val tone = sin(twoPi * cricketCarrierPhases[c]) * pEnv * macroEnv * 0.055
                        if (c == 0) {
                            rawCricketL += tone * 0.70
                            rawCricketR += tone * 0.30
                        } else {
                            rawCricketL += tone * 0.30
                            rawCricketR += tone * 0.70
                        }
                    }
                }
            }
            cricketLpL += 0.60 * (rawCricketL - cricketLpL)
            cricketLpR += 0.60 * (rawCricketR - cricketLpR)

            // Occasional distant deep owl hoot (Hoo... Hoo... Hoooo)
            var owlDryL = 0.0
            var owlDryR = 0.0
            when (owlStep) {
                0 -> {
                    owlCounter--
                    if (owlCounter <= 0) {
                        owlStep = 1
                        owlCounter = 0
                        owlStepDur = (sampleRate * 0.34).toInt()
                        owlPhase = 0.0
                        owlPan = nextRange(0.24, 0.76)
                    }
                }
                1, 3, 5 -> {
                    if (owlCounter >= owlStepDur) {
                        owlStep++
                        owlCounter = 0
                        when (owlStep) {
                            2 -> owlStepDur = (sampleRate * 0.18).toInt()
                            4 -> owlStepDur = (sampleRate * 0.10).toInt()
                            6 -> {
                                owlStep = 0
                                owlCounter = nextRange(sampleRate * 10.0, sampleRate * 17.5).toInt()
                            }
                        }
                    } else {
                        val u = owlCounter.toDouble() / owlStepDur.toDouble()
                        val env = sin(PI * u)
                        val f = when (owlStep) {
                            1 -> 338.0 - 12.0 * u
                            3 -> 342.0 - 8.0 * u
                            else -> 336.0 - 24.0 * u
                        }
                        val amp = if (owlStep == 5) 0.080 else 0.068
                        owlPhase = wrapPhase(owlPhase + f * dt)
                        val hoot = (sin(twoPi * owlPhase) * 0.94 + sin(twoPi * 2.0 * owlPhase) * 0.04) * env * amp
                        owlDryL = hoot * owlPan
                        owlDryR = hoot * (1.0 - owlPan)
                        owlCounter++
                    }
                }
                else -> { // 2 or 4 (gaps)
                    owlCounter++
                    if (owlCounter >= owlStepDur) {
                        owlStep++
                        owlCounter = 0
                        owlStepDur = if (owlStep == 3) (sampleRate * 0.22).toInt() else (sampleRate * 0.58).toInt()
                    }
                }
            }
            val owlRefIdx = (owlDelayIdx - 840) and owlDelayMask
            val owlRefL = owlDelayBufR[owlRefIdx] * 0.22
            val owlRefR = owlDelayBufL[owlRefIdx] * 0.22
            owlDelayBufL[owlDelayIdx] = owlDryL
            owlDelayBufR[owlDelayIdx] = owlDryR
            owlDelayIdx = (owlDelayIdx + 1) and owlDelayMask

            tmpL = nightLpL * 0.34 * nightSwell + cricketLpL + owlDryL + owlRefL
            tmpR = nightLpR * 0.34 * nightSwell + cricketLpR + owlDryR + owlRefR
        }

        private fun stepRainWindow() {
            val wL = nextBipolar()
            val wR = nextBipolar()
            val pL = stepPink(winPinkL, wL)
            val pR = stepPink(winPinkR, wR)
            winRainLpL += 0.17 * (pL - winRainLpL)
            winRainLpR += 0.17 * (pR - winRainLpR)
            winRoomBrown = (winRoomBrown * 0.994 + wL * 0.016).coerceIn(-0.42, 0.42)
            winRoomLp += 0.07 * (winRoomBrown - winRoomLp)

            var outL = winRainLpL * 0.62 + winRoomLp * 0.22
            var outR = winRainLpR * 0.62 + winRoomLp * 0.22

            for (i in 0 until 4) {
                if (!winDropActive[i]) {
                    if (nextDouble01() < 0.00042) {
                        winDropActive[i] = true
                        winDropAge[i] = 0
                        winDropDur[i] = nextRange(200.0, 440.0).toInt()
                        winDropFreq[i] = nextRange(380.0, 1040.0)
                        winDropPhase[i] = 0.0
                        winDropPan[i] = nextRange(0.20, 0.80)
                        winDropAmp[i] = nextRange(0.022, 0.048)
                    }
                } else {
                    val age = winDropAge[i]
                    val dur = winDropDur[i]
                    if (age >= dur) {
                        winDropActive[i] = false
                    } else {
                        val u = age.toDouble() / dur.toDouble()
                        val env = if (u < 0.15) u / 0.15 else exp(-(u - 0.15) * 5.2)
                        winDropPhase[i] = wrapPhase(winDropPhase[i] + winDropFreq[i] * dt)
                        val tap = sin(twoPi * winDropPhase[i]) * env * winDropAmp[i]
                        val pan = winDropPan[i]
                        outL += tap * (1.0 - pan * 0.5)
                        outR += tap * (0.5 + pan * 0.5)
                        winDropAge[i] = age + 1
                    }
                }
            }
            tmpL = outL
            tmpR = outR
        }

        private fun stepWindChimes() {
            val pL = stepPink(chimeBreezePinkL, nextBipolar())
            val pR = stepPink(chimeBreezePinkR, nextBipolar())
            chimeGustLfo = wrapPhase(chimeGustLfo + 0.075 * dt)
            val gust = 0.5 + 0.5 * sin(twoPi * chimeGustLfo)
            chimeBreezeLpL += 0.19 * (pL - chimeBreezeLpL)
            chimeBreezeLpR += 0.19 * (pR - chimeBreezeLpR)

            var outL = chimeBreezeLpL * (0.20 + 0.12 * gust)
            var outR = chimeBreezeLpR * (0.20 + 0.12 * gust)

            chimeWaitSamples--
            if (chimeWaitSamples <= 0) {
                val slot = chimeNextSlot
                chimeNextSlot = (chimeNextSlot + 1) % 4
                chimeActive[slot] = true
                chimeAge[slot] = 0
                val idx = (nextDouble01() * chimeNotes.size).toInt().coerceIn(0, chimeNotes.lastIndex)
                chimeFreq[slot] = chimeNotes[idx]
                chimePhase[slot] = 0.0
                chimePan[slot] = nextRange(0.24, 0.76)
                chimeAmp[slot] = nextRange(0.08, 0.125)
                if (chimeBurstLeft > 1) {
                    chimeBurstLeft--
                    chimeWaitSamples = nextRange(sampleRate * 0.22, sampleRate * 0.65).toInt()
                } else {
                    chimeBurstLeft = 1 + (nextDouble01() * 2.5).toInt()
                    chimeWaitSamples = nextRange(sampleRate * 2.8, sampleRate * 5.8).toInt()
                }
            }

            for (i in 0 until 4) {
                if (!chimeActive[i]) continue
                val t = chimeAge[i] * dt
                if (t > 4.2) {
                    chimeActive[i] = false
                    continue
                }
                val attack = if (t < 0.014) sin((PI * 0.5) * (t / 0.014)) else 1.0
                val env = attack * exp(-t * 1.15)
                chimePhase[i] = wrapPhase(chimePhase[i] + chimeFreq[i] * dt)
                val p = chimePhase[i]
                val tone = (sin(twoPi * p) * 0.86 +
                    sin(twoPi * 2.0 * p) * (0.11 * exp(-t * 2.8)) +
                    sin(twoPi * 3.0 * p) * (0.03 * exp(-t * 5.5))) * env * chimeAmp[i]
                val pan = chimePan[i]
                outL += tone * pan
                outR += tone * (1.0 - pan)
                chimeAge[i]++
            }

            tmpL = outL
            tmpR = outR
        }

        private fun stepSoftPiano() {
            pianoBeatWait--
            if (pianoBeatWait <= 0) {
                val chord = pianoChords[pianoChordIdx]
                val noteFreq = when (pianoBeatIdx) {
                    0 -> chord[0] // warm bass root
                    1 -> chord[2]
                    2 -> chord[3]
                    else -> chord[1 + (nextDouble01() * 3.9).toInt().coerceIn(0, 3)]
                }
                val slot = pianoNextSlot
                pianoNextSlot = (pianoNextSlot + 1) % 4
                pianoActive[slot] = true
                pianoAge[slot] = 0
                pianoFreq[slot] = noteFreq
                pianoPhase[slot] = 0.0
                pianoPan[slot] = if (pianoBeatIdx == 0) 0.50 else nextRange(0.34, 0.66)
                pianoAmp[slot] = if (pianoBeatIdx == 0) 0.18 else nextRange(0.11, 0.16)

                pianoBeatIdx = (pianoBeatIdx + 1) % 4
                if (pianoBeatIdx == 0) {
                    pianoChordIdx = (pianoChordIdx + 1) % pianoChords.size
                    pianoBeatWait = nextRange(sampleRate * 1.25, sampleRate * 1.75).toInt()
                } else {
                    pianoBeatWait = nextRange(sampleRate * 0.85, sampleRate * 1.35).toInt()
                }
            }

            var rawL = 0.0
            var rawR = 0.0
            for (i in 0 until 4) {
                if (!pianoActive[i]) continue
                val t = pianoAge[i] * dt
                if (t > 5.0) {
                    pianoActive[i] = false
                    continue
                }
                val attack = if (t < 0.024) sin((PI * 0.5) * (t / 0.024)) else 1.0
                val decay = exp(-t * 0.72)
                pianoPhase[i] = wrapPhase(pianoPhase[i] + pianoFreq[i] * dt)
                val p = pianoPhase[i]
                val feltTone = (sin(twoPi * p) * 0.75 +
                    sin(twoPi * 2.0 * p) * (0.20 * exp(-t * 1.6)) +
                    sin(twoPi * 3.0 * p) * (0.05 * exp(-t * 3.8))) * attack * decay * pianoAmp[i]
                val pan = pianoPan[i]
                rawL += feltTone * pan
                rawR += feltTone * (1.0 - pan)
                pianoAge[i]++
            }

            // Warm 2-pole felt low-pass (~920 Hz)
            pianoLp1L += 0.23 * (rawL - pianoLp1L)
            pianoLp2L += 0.23 * (pianoLp1L - pianoLp2L)
            pianoLp1R += 0.23 * (rawR - pianoLp1R)
            pianoLp2R += 0.23 * (pianoLp1R - pianoLp2R)

            val tailIdx = (pianoDelayIdx - 930) and pianoDelayMask
            val tailL = pianoDelayBufR[tailIdx] * 0.20
            val tailR = pianoDelayBufL[tailIdx] * 0.20
            pianoDelayBufL[pianoDelayIdx] = pianoLp2L + tailL * 0.25
            pianoDelayBufR[pianoDelayIdx] = pianoLp2R + tailR * 0.25
            pianoDelayIdx = (pianoDelayIdx + 1) and pianoDelayMask

            tmpL = pianoLp2L + tailL
            tmpR = pianoLp2R + tailR
        }

        private fun nextDouble01(): Double {
            var x = rngState
            x = x xor (x ushr 12)
            x = x xor (x shl 25)
            x = x xor (x ushr 27)
            rngState = x
            val positive = (x * 0x2545F4914F6CDD1DL) ushr 11
            return positive.toDouble() * (1.0 / (1L shl 53).toDouble())
        }

        private fun nextBipolar(): Double = nextDouble01() * 2.0 - 1.0

        private fun nextRange(min: Double, max: Double): Double = min + nextDouble01() * (max - min)

        private fun wrapPhase(p: Double): Double = if (p >= 1.0) p - 1.0 else if (p < 0.0) p + 1.0 else p

        private fun stepPink(state: DoubleArray, white: Double): Double {
            state[0] = 0.99765 * state[0] + white * 0.0990460
            state[1] = 0.96300 * state[1] + white * 0.2965164
            state[2] = 0.57000 * state[2] + white * 1.0526913
            return (state[0] + state[1] + state[2] + white * 0.1848) * 0.16
        }

        private fun stepSvfBand(state: DoubleArray, input: Double, freqHz: Double, q: Double): Double {
            val clampedFreq = freqHz.coerceIn(30.0, 6200.0)
            val f = 2.0 * sin(PI * clampedFreq / (sampleRate * 2.0))
            val damp = (1.0 / q.coerceIn(0.4, 8.0)).coerceAtLeast(0.125)
            var low = state[0]
            var band = state[1]
            low += f * band
            var high = input - low - damp * band
            band += f * high
            low += f * band
            high = input - low - damp * band
            band += f * high
            state[0] = low
            state[1] = band
            return band * damp
        }

        private fun readRingInterp(buf: DoubleArray, writeIdx: Int, mask: Int, delaySamples: Double): Double {
            val intD = delaySamples.toInt()
            val frac = delaySamples - intD
            val idx0 = (writeIdx - intD) and mask
            val idx1 = (idx0 - 1) and mask
            return buf[idx0] * (1.0 - frac) + buf[idx1] * frac
        }

        private fun softLimitToShort(sample: Double): Short {
            if (!sample.isFinite()) return 0
            val limited = if (sample > 0.78) {
                0.78 + 0.14 * tanh((sample - 0.78) / 0.14)
            } else if (sample < -0.78) {
                -0.78 + 0.14 * tanh((sample + 0.78) / 0.14)
            } else {
                sample
            }
            val clamped = limited.toFloat().coerceIn(-0.92f, 0.92f)
            return (clamped * Short.MAX_VALUE).toInt().toShort()
        }
    }
}

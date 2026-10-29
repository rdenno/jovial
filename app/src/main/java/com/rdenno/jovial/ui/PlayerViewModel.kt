package com.rdenno.jovial.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rdenno.jovial.network.ChatComment
import com.rdenno.jovial.network.GanymedeApi
import com.rdenno.jovial.network.GanymedeEmote
import com.rdenno.jovial.network.GanymedeVod
import com.rdenno.jovial.network.ThirdPartyEmoteFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

// Chat is fetched this far ahead of playback, then revealed as the video reaches each message
private const val CHAT_FETCH_WINDOW_SECONDS = 30.0
// Fetch the next window once fewer than this many seconds of chat remain buffered
private const val CHAT_PREFETCH_MARGIN_SECONDS = 15.0
private const val MAX_VISIBLE_CHAT = 150

private const val KEY_DISPLAY_MODE = "layout_display_mode"
private const val KEY_CHAT_SIDE = "layout_chat_side"
private const val KEY_CHAT_WIDTH = "layout_chat_width"
private const val KEY_OVERLAY_OPACITY = "layout_overlay_opacity"
private const val KEY_FONT_SIZE = "layout_font_size"

enum class ChatDisplayMode {
    HIDDEN,
    SIDE_BY_SIDE,
    OVERLAY
}

enum class ChatSide {
    LEFT,
    RIGHT
}

data class PlayerLayoutConfig(
    val displayMode: ChatDisplayMode = ChatDisplayMode.SIDE_BY_SIDE,
    val chatSide: ChatSide = ChatSide.RIGHT,
    val chatWidthFraction: Float = 0.28f, // 18% to 45% of screen width
    val overlayOpacity: Float = 0.55f,    // Background alpha in Overlay mode
    val fontSizeSp: Float = 13f,
    val useRenderedChatVideo: Boolean = false
)

data class EmoteCatalog(
    val byName: Map<String, GanymedeEmote> = emptyMap(),
    val byId: Map<String, GanymedeEmote> = emptyMap()
)

class PlayerViewModel : ViewModel() {
    private var api: GanymedeApi? = null
    private var sharedPrefs: android.content.SharedPreferences? = null

    var serverUrl: String = ""
        private set

    private val _vods = MutableStateFlow<List<GanymedeVod>>(emptyList())
    val vods: StateFlow<List<GanymedeVod>> = _vods.asStateFlow()

    private val _savedProgress = MutableStateFlow<Map<String, Long>>(emptyMap())
    val savedProgress: StateFlow<Map<String, Long>> = _savedProgress.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _layoutConfig = MutableStateFlow(PlayerLayoutConfig())
    val layoutConfig: StateFlow<PlayerLayoutConfig> = _layoutConfig.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatComment>>(emptyList())
    val chatMessages: StateFlow<List<ChatComment>> = _chatMessages.asStateFlow()

    private val _emoteCatalog = MutableStateFlow(EmoteCatalog())
    val emoteCatalog: StateFlow<EmoteCatalog> = _emoteCatalog.asStateFlow()

    private var lastSyncedSecond: Double = -1.0
    private var chatJob: Job? = null
    private var prefetchJob: Job? = null
    private var emoteJob: Job? = null

    // Fetched comments sorted by offset, including ones playback has not reached yet
    private var chatBuffer: List<ChatComment> = emptyList()
    // Chat is loaded up to this playback second; negative while a seek fetch is in flight
    private var bufferedUntil: Double = -1.0
    // Bumped on every seek/reset so responses for an old position are discarded
    private var chatGeneration = 0

    fun initPreferences(prefs: android.content.SharedPreferences, cacheDir: File) {
        // The ViewModel outlives activity recreation; only load the saved layout the first time
        val firstInit = sharedPrefs == null
        sharedPrefs = prefs
        GanymedeApi.cacheDir = cacheDir
        loadAllSavedProgress()
        if (firstInit) {
            loadLayoutConfig()
            viewModelScope.launch(Dispatchers.IO) { GanymedeApi.purgeCachedMedia() }
        }
    }

    private fun loadLayoutConfig() {
        val prefs = sharedPrefs ?: return
        val defaults = PlayerLayoutConfig()
        _layoutConfig.value = defaults.copy(
            displayMode = prefs.getString(KEY_DISPLAY_MODE, null)
                ?.let { name -> ChatDisplayMode.entries.firstOrNull { it.name == name } }
                ?: defaults.displayMode,
            chatSide = prefs.getString(KEY_CHAT_SIDE, null)
                ?.let { name -> ChatSide.entries.firstOrNull { it.name == name } }
                ?: defaults.chatSide,
            chatWidthFraction = prefs.getFloat(KEY_CHAT_WIDTH, defaults.chatWidthFraction),
            overlayOpacity = prefs.getFloat(KEY_OVERLAY_OPACITY, defaults.overlayOpacity),
            fontSizeSp = prefs.getFloat(KEY_FONT_SIZE, defaults.fontSizeSp)
        )
    }

    // The rendered-chat video toggle is per VOD, so it is deliberately not persisted, and neither
    // is the HIDDEN chat mode it forces
    private fun saveLayoutConfig(config: PlayerLayoutConfig) {
        val editor = sharedPrefs?.edit() ?: return
        if (!config.useRenderedChatVideo) {
            editor.putString(KEY_DISPLAY_MODE, config.displayMode.name)
        }
        editor
            .putString(KEY_CHAT_SIDE, config.chatSide.name)
            .putFloat(KEY_CHAT_WIDTH, config.chatWidthFraction)
            .putFloat(KEY_OVERLAY_OPACITY, config.overlayOpacity)
            .putFloat(KEY_FONT_SIZE, config.fontSizeSp)
            .apply()
    }

    private fun loadAllSavedProgress() {
        val prefs = sharedPrefs ?: return
        val map = mutableMapOf<String, Long>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("progress_") && value is Long) {
                val vodId = key.removePrefix("progress_")
                map[vodId] = value
            }
        }
        _savedProgress.value = map
    }

    fun getSavedPlaybackPosition(vodId: String): Long {
        return sharedPrefs?.getLong("progress_$vodId", 0L) ?: 0L
    }

    fun savePlaybackPosition(vodId: String, positionMs: Long) {
        if (positionMs <= 0L) return
        sharedPrefs?.edit()?.putLong("progress_$vodId", positionMs)?.apply()
        _savedProgress.update { it + (vodId to positionMs) }
    }

    fun connectToServer(rawUrl: String, apiKey: String?, onConnected: (() -> Unit)? = null) {
        val normalized = GanymedeApi.normalizeServerUrl(rawUrl)
        if (normalized.isBlank()) {
            _connectionError.value = "Please enter a valid server URL or IP:port."
            return
        }

        serverUrl = normalized
        _isConnecting.value = true
        _connectionError.value = null

        val clientApi = try {
            GanymedeApi.create(serverUrl, apiKey)
        } catch (e: Exception) {
            _isConnecting.value = false
            _connectionError.value = "Invalid URL ($serverUrl): ${e.localizedMessage ?: e.javaClass.simpleName}"
            return
        }
        api = clientApi

        viewModelScope.launch {
            // Try /api/v1/vod/paginate first, then fall back to /api/v1/vod
            val paginateResult = runCatching {
                clientApi.getVodsPaginate(limit = 100, offset = 0).data?.data
                    ?: throw IllegalStateException("Empty pagination response from server")
            }

            val finalResult = if (paginateResult.isSuccess) {
                paginateResult
            } else {
                runCatching {
                    clientApi.getAllVods().data
                        ?: throw (paginateResult.exceptionOrNull()
                            ?: IllegalStateException("Empty VOD response from server"))
                }
            }

            finalResult
                .onSuccess { vodList ->
                    _vods.value = vodList
                    _isConnected.value = true
                    _isConnecting.value = false
                    _connectionError.value = null
                    onConnected?.invoke()
                }
                .onFailure { err ->
                    _isConnecting.value = false
                    _connectionError.value =
                        "Could not connect to $serverUrl (${err.localizedMessage ?: err.javaClass.simpleName})"
                }
        }
    }

    fun fetchVods() {
        val currentApi = api ?: return
        _isConnecting.value = true
        _connectionError.value = null
        viewModelScope.launch {
            runCatching {
                currentApi.getVodsPaginate(limit = 100, offset = 0).data?.data
                    ?: currentApi.getAllVods().data.orEmpty()
            }.onSuccess { vodList ->
                _vods.value = vodList
                _isConnecting.value = false
            }.onFailure { err ->
                _isConnecting.value = false
                _connectionError.value =
                    "Failed to refresh VODs: ${err.localizedMessage ?: err.javaClass.simpleName}"
            }
        }
    }

    fun disconnect() {
        _isConnected.value = false
        _connectionError.value = null
    }

    fun updateLayout(transform: (PlayerLayoutConfig) -> PlayerLayoutConfig) {
        val updated = _layoutConfig.updateAndGet(transform)
        saveLayoutConfig(updated)
    }

    /**
     * Loads VOD emotes from both the Ganymede server (/api/v1/vod/{id}/chat/emotes)
     * and directly from 7TV, BTTV, and FFZ (Global + Channel) so 3rd-party emotes
     * always render even when Ganymede only embeds 1st-party Twitch emotes.
     */
    fun loadEmotesForVod(vod: GanymedeVod) {
        val currentApi = api ?: return
        emoteJob?.cancel()
        _emoteCatalog.value = EmoteCatalog()

        emoteJob = viewModelScope.launch {
            var serverEmotes = emptyList<GanymedeEmote>()
            var thirdPartyEmotes = emptyList<GanymedeEmote>()

            // Third-party emotes take precedence over server emotes with the same name or ID
            fun publishCatalog() {
                val byName = HashMap<String, GanymedeEmote>()
                val byId = HashMap<String, GanymedeEmote>()
                for (emote in serverEmotes + thirdPartyEmotes) {
                    if (emote.id.isNotBlank()) {
                        byId[emote.id] = emote
                    }
                    if (emote.name.isNotBlank()) {
                        byName[emote.name] = emote
                    }
                }
                _emoteCatalog.value = EmoteCatalog(byName = byName, byId = byId)
            }

            // Fetch both sources concurrently: the server catalog embeds every image as base64 and
            // can be tens of MB, so it must not hold up the small 7TV / BTTV / FFZ lists
            launch {
                serverEmotes = runCatching { currentApi.getChatEmotes(vod.id).data.orEmpty() }
                    .getOrDefault(emptyList())
                if (serverEmotes.isNotEmpty()) {
                    publishCatalog()
                }
            }

            launch {
                val channelTwitchId = vod.edges?.channel?.extId?.takeIf { it.isNotBlank() }
                    ?: runCatching { currentApi.getChatUserId(vod.id).data?.toString() }.getOrNull()
                thirdPartyEmotes = ThirdPartyEmoteFetcher.fetchAllThirdPartyEmotes(channelTwitchId)
                if (thirdPartyEmotes.isNotEmpty()) {
                    publishCatalog()
                }
            }
        }
    }

    /**
     * Called with the ExoPlayer's current playback timestamp in seconds.
     * Chat is fetched ahead into a buffer and only shown once playback reaches each message.
     */
    fun onPlaybackPositionUpdate(vodId: String, currentSeconds: Double) {
        if (_layoutConfig.value.displayMode == ChatDisplayMode.HIDDEN) return
        val currentApi = api ?: return

        // Detect seek or initial load (jump > 3 seconds backward or forward, or first sync)
        if (lastSyncedSecond < 0 || abs(currentSeconds - lastSyncedSecond) > 3.0) {
            lastSyncedSecond = currentSeconds
            chatJob?.cancel()
            prefetchJob?.cancel()
            val generation = ++chatGeneration
            chatBuffer = emptyList()
            bufferedUntil = -1.0
            val windowEnd = currentSeconds + CHAT_FETCH_WINDOW_SECONDS

            chatJob = viewModelScope.launch {
                val comments = if (currentSeconds <= 5.0) {
                    // Initial stream playback: Ganymede /chat/seek?start=0 returns empty list because it searches backwards before 0s!
                    // Instead, fetch comments from 0 to the window end so chat is populated immediately!
                    runCatching {
                        currentApi.getChatRange(vodId, 0.0, windowEnd).data.orEmpty()
                    }.getOrDefault(emptyList())
                } else {
                    // Mid-stream seek: fetch comments right before seek AND a window ahead
                    val beforeDeferred = async {
                        runCatching { currentApi.getSeekChat(vodId, currentSeconds, count = 35).data.orEmpty() }.getOrDefault(emptyList())
                    }
                    val afterDeferred = async {
                        runCatching { currentApi.getChatRange(vodId, currentSeconds, windowEnd).data.orEmpty() }.getOrDefault(emptyList())
                    }
                    beforeDeferred.await() + afterDeferred.await()
                }
                if (generation != chatGeneration) return@launch
                chatBuffer = comments.distinctBy { it.id }.sortedBy { it.offsetSeconds }
                bufferedUntil = windowEnd
                revealChat(lastSyncedSecond)
            }
            return
        }

        lastSyncedSecond = currentSeconds
        revealChat(currentSeconds)

        // Normal forward playback: top up the buffer before it runs dry
        val start = bufferedUntil
        if (start >= 0 && start - currentSeconds < CHAT_PREFETCH_MARGIN_SECONDS && prefetchJob?.isActive != true) {
            val end = start + CHAT_FETCH_WINDOW_SECONDS
            val generation = chatGeneration
            prefetchJob = viewModelScope.launch {
                // On failure bufferedUntil is unchanged, so the next tick retries
                runCatching {
                    currentApi.getChatRange(vodId, start, end).data.orEmpty()
                }.onSuccess { newComments ->
                    if (generation != chatGeneration) return@onSuccess
                    chatBuffer = (chatBuffer + newComments)
                        .distinctBy { it.id }
                        .sortedBy { it.offsetSeconds }
                    bufferedUntil = end
                }
            }
        }
    }

    /** Publishes the buffered comments that playback has reached. */
    private fun revealChat(currentSeconds: Double) {
        var revealedCount = chatBuffer.indexOfFirst { it.offsetSeconds > currentSeconds }
            .let { if (it < 0) chatBuffer.size else it }
        // Drop revealed messages beyond the display limit to keep memory footprint tiny
        if (revealedCount > MAX_VISIBLE_CHAT) {
            chatBuffer = chatBuffer.drop(revealedCount - MAX_VISIBLE_CHAT)
            revealedCount = MAX_VISIBLE_CHAT
        }
        val visible = chatBuffer.subList(0, revealedCount)
        val shown = _chatMessages.value
        if (visible.size != shown.size || visible.lastOrNull()?.id != shown.lastOrNull()?.id) {
            _chatMessages.value = visible.toList()
        }
    }

    fun resetChatState() {
        chatJob?.cancel()
        prefetchJob?.cancel()
        emoteJob?.cancel()
        chatGeneration++
        chatBuffer = emptyList()
        bufferedUntil = -1.0
        lastSyncedSecond = -1.0
        _chatMessages.value = emptyList()
        _emoteCatalog.value = EmoteCatalog()
    }
}

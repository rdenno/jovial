package com.rdenno.jovial.ui

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.os.Build
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.SpeakerNotesOff
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.rdenno.jovial.network.ChatComment
import com.rdenno.jovial.network.GanymedeApi
import com.rdenno.jovial.network.GanymedeEmote
import com.rdenno.jovial.network.GanymedeVod
import kotlinx.coroutines.delay

@Composable
fun GanymedePlayerScreen(
    vod: GanymedeVod,
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    val config by viewModel.layoutConfig.collectAsState()
    val messages by viewModel.chatMessages.collectAsState()
    val emoteCatalog by viewModel.emoteCatalog.collectAsState()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastVisibleMode by remember {
        mutableStateOf(
            if (config.displayMode != ChatDisplayMode.HIDDEN) config.displayMode else ChatDisplayMode.SIDE_BY_SIDE
        )
    }

    // Shared Coil ImageLoader with GIF + Animated WebP support for 7TV / BTTV / FFZ emotes
    val emoteImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .crossfade(false)
            .build()
    }

    // Initialize ExoPlayer with optimized buffer durations and OkHttpDataSource for fast video start
    val exoPlayer = remember {
        val dataSourceFactory = OkHttpDataSource.Factory(GanymedeApi.activeClient)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 500, // Starts playback in 0.5s instead of waiting 2.5-5s
                /* bufferForPlaybackAfterRebufferMs = */ 1_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
    }

    // Hide Status & Navigation bars (Immersive Fullscreen Mode) while watching video
    DisposableEffect(Unit) {
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            viewModel.savePlaybackPosition(vod.id, exoPlayer.currentPosition)
            viewModel.resetChatState()
            exoPlayer.release()
        }
    }

    fun handleBack() {
        viewModel.savePlaybackPosition(vod.id, exoPlayer.currentPosition)
        onBack()
    }

    BackHandler {
        handleBack()
    }

    // Load 7TV / BTTV / FFZ / Twitch emotes for this VOD in background
    LaunchedEffect(vod.id) {
        viewModel.loadEmotesForVod(vod)
    }

    // Restore saved playback position and start video + chat sync immediately
    LaunchedEffect(vod.id, config.useRenderedChatVideo) {
        val savedPos = viewModel.getSavedPlaybackPosition(vod.id)
        val startPos = if (savedPos > 3000L && (vod.duration <= 0 || savedPos < (vod.duration * 1000L - 10000L))) {
            savedPos
        } else {
            0L
        }

        val videoUrl = vod.getFullVideoUrl(viewModel.serverUrl, config.useRenderedChatVideo)
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl), startPos)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        // Trigger chat immediately so chat appears in parallel with video start!
        viewModel.onPlaybackPositionUpdate(vod.id, startPos / 1000.0)
    }

    // Poll ExoPlayer position every 500ms to sync chat & periodically persist playback place
    LaunchedEffect(vod.id, config.displayMode) {
        var lastSavedSec = -1L
        while (true) {
            val currentMs = exoPlayer.currentPosition
            val currentSec = currentMs / 1000.0
            if (exoPlayer.isPlaying || currentSec > 0.0) {
                viewModel.onPlaybackPositionUpdate(vod.id, currentSec)

                val nowSec = currentMs / 1000L
                if (nowSec > 0L && kotlin.math.abs(nowSec - lastSavedSec) >= 5L) {
                    lastSavedSec = nowSec
                    viewModel.savePlaybackPosition(vod.id, currentMs)
                }
            }
            delay(500L)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        when (config.displayMode) {
            ChatDisplayMode.HIDDEN -> {
                VideoSurface(
                    exoPlayer = exoPlayer,
                    onControlsVisibilityChanged = { controlsVisible = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            ChatDisplayMode.SIDE_BY_SIDE -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (config.chatSide == ChatSide.LEFT) {
                        ChatPane(
                            messages = messages,
                            emoteCatalog = emoteCatalog,
                            emoteImageLoader = emoteImageLoader,
                            fontSizeSp = config.fontSizeSp,
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(config.chatWidthFraction)
                                .background(Color(0xFF121216))
                        )
                        VideoSurface(
                            exoPlayer = exoPlayer,
                            onControlsVisibilityChanged = { controlsVisible = it },
                            modifier = Modifier.fillMaxHeight().weight(1f)
                        )
                    } else {
                        VideoSurface(
                            exoPlayer = exoPlayer,
                            onControlsVisibilityChanged = { controlsVisible = it },
                            modifier = Modifier.fillMaxHeight().weight(1f)
                        )
                        ChatPane(
                            messages = messages,
                            emoteCatalog = emoteCatalog,
                            emoteImageLoader = emoteImageLoader,
                            fontSizeSp = config.fontSizeSp,
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(config.chatWidthFraction)
                                .background(Color(0xFF121216))
                        )
                    }
                }
            }

            ChatDisplayMode.OVERLAY -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    VideoSurface(
                        exoPlayer = exoPlayer,
                        onControlsVisibilityChanged = { controlsVisible = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    val alignment = if (config.chatSide == ChatSide.LEFT) {
                        Alignment.CenterStart
                    } else {
                        Alignment.CenterEnd
                    }

                    ChatPane(
                        messages = messages,
                        emoteCatalog = emoteCatalog,
                        emoteImageLoader = emoteImageLoader,
                        fontSizeSp = config.fontSizeSp,
                        modifier = Modifier
                            .align(alignment)
                            .fillMaxHeight(0.85f)
                            .fillMaxWidth(config.chatWidthFraction)
                            .padding(12.dp)
                            .background(
                                Color.Black.copy(alpha = config.overlayOpacity),
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                }
            }
        }

        // Top Floating Quick-Controls Bar (Shown only when player controls are visible)
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                IconButton(onClick = { handleBack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                // Quick Show / Hide Chat button
                IconButton(
                    onClick = {
                        if (config.displayMode == ChatDisplayMode.HIDDEN) {
                            viewModel.updateLayout { it.copy(displayMode = lastVisibleMode) }
                        } else {
                            lastVisibleMode = config.displayMode
                            viewModel.updateLayout { it.copy(displayMode = ChatDisplayMode.HIDDEN) }
                        }
                    }
                ) {
                    if (config.displayMode == ChatDisplayMode.HIDDEN) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Show Chat",
                            tint = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.SpeakerNotesOff,
                            contentDescription = "Hide Chat",
                            tint = Color.White
                        )
                    }
                }

                // One-tap button to swap Chat Left / Right (only when chat is visible)
                if (config.displayMode != ChatDisplayMode.HIDDEN) {
                    IconButton(
                        onClick = {
                            viewModel.updateLayout {
                                it.copy(
                                    chatSide = if (it.chatSide == ChatSide.LEFT) ChatSide.RIGHT else ChatSide.LEFT
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Swap Chat Side", tint = Color.White)
                    }
                }

                // Open full layout customization sheet
                IconButton(onClick = { showSettingsSheet = !showSettingsSheet }) {
                    Icon(Icons.Default.Tune, contentDescription = "Customize Layout", tint = Color.White)
                }
            }
        }

        // Customization Overlay Panel
        AnimatedVisibility(
            visible = showSettingsSheet,
            modifier = Modifier.align(Alignment.Center)
        ) {
            LayoutCustomizationCard(
                config = config,
                hasRenderedChatVideo = !vod.chatVideoPath.isNullOrBlank(),
                loadedEmoteCount = emoteCatalog.byName.size + emoteCatalog.byId.size,
                onUpdate = { viewModel.updateLayout(it) },
                onClose = { showSettingsSheet = false }
            )
        }
    }
}

@Composable
fun VideoSurface(
    exoPlayer: ExoPlayer,
    onControlsVisibilityChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                    onControlsVisibilityChanged(visibility == android.view.View.VISIBLE)
                })
            }
        },
        modifier = modifier
    )
}

private sealed interface ParsedChatSegment {
    data class Text(val text: String) : ParsedChatSegment
    data class Emote(val key: String, val emote: GanymedeEmote) : ParsedChatSegment
}

private fun parseChatSegments(
    comment: ChatComment,
    emoteCatalog: EmoteCatalog
): List<ParsedChatSegment> {
    val result = mutableListOf<ParsedChatSegment>()

    fun appendTextWithThirdPartyEmotes(rawText: String) {
        if (rawText.isEmpty()) return
        val words = rawText.split(" ")
        val textBuffer = StringBuilder()

        fun flushBuffer() {
            if (textBuffer.isNotEmpty()) {
                result.add(ParsedChatSegment.Text(textBuffer.toString()))
                textBuffer.clear()
            }
        }

        for ((index, word) in words.withIndex()) {
            val matchedEmote = if (word.isNotEmpty()) emoteCatalog.byName[word] else null
            if (matchedEmote != null) {
                if (index > 0) textBuffer.append(' ')
                flushBuffer()
                result.add(ParsedChatSegment.Emote("emote_${matchedEmote.source}_${matchedEmote.id}_${matchedEmote.name}", matchedEmote))
                if (index < words.lastIndex) {
                    textBuffer.append(' ')
                }
            } else {
                if (index > 0 && (textBuffer.isEmpty() || textBuffer.last() != ' ')) {
                    textBuffer.append(' ')
                }
                textBuffer.append(word)
            }
        }
        flushBuffer()
    }

    val fragments = comment.message.fragments
    if (!fragments.isNullOrEmpty()) {
        for (fragment in fragments) {
            val emoticonId = fragment.emoticon?.emoticonId
            if (!emoticonId.isNullOrBlank()) {
                val emote = emoteCatalog.byId[emoticonId]
                    ?: emoteCatalog.byName[fragment.text.trim()]
                    ?: GanymedeEmote(
                        id = emoticonId,
                        name = fragment.text.trim(),
                        url = "https://static-cdn.jtvnw.net/emoticons/v2/$emoticonId/default/dark/2.0",
                        type = "twitch",
                        source = "twitch",
                        width = 28,
                        height = 28
                    )
                result.add(ParsedChatSegment.Emote("emote_twitch_${emote.id}", emote))
            } else {
                appendTextWithThirdPartyEmotes(fragment.text)
            }
        }
    } else {
        appendTextWithThirdPartyEmotes(comment.message.body)
    }

    return result
}

@Composable
fun ChatPane(
    messages: List<ChatComment>,
    emoteCatalog: EmoteCatalog,
    emoteImageLoader: ImageLoader,
    fontSizeSp: Float,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive.
    // Tracking by the last message's ID ensures continuous scrolling even after reaching the 150-message buffer limit.
    val lastMessageId = remember(messages) { messages.lastOrNull()?.id }
    LaunchedEffect(lastMessageId) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(messages, key = { it.id }) { comment ->
            ChatCommentRow(
                comment = comment,
                emoteCatalog = emoteCatalog,
                emoteImageLoader = emoteImageLoader,
                fontSizeSp = fontSizeSp
            )
        }
    }
}

@Composable
private fun ChatCommentRow(
    comment: ChatComment,
    emoteCatalog: EmoteCatalog,
    emoteImageLoader: ImageLoader,
    fontSizeSp: Float
) {
    val usernameColor = remember(comment.message.userColor) {
        try {
            val rawColor = comment.message.userColor
            if (rawColor.isNullOrBlank()) Color(0xFF9146FF)
            else Color(AndroidColor.parseColor(rawColor))
        } catch (e: Exception) {
            Color(0xFF9146FF)
        }
    }

    val segments = remember(comment.id, emoteCatalog) {
        parseChatSegments(comment, emoteCatalog)
    }

    val emoteHeightSp = fontSizeSp * 1.65f
    val inlineContentMap = remember(segments, fontSizeSp, emoteImageLoader) {
        val map = HashMap<String, InlineTextContent>()
        for (segment in segments) {
            if (segment is ParsedChatSegment.Emote && !map.containsKey(segment.key)) {
                val emote = segment.emote
                val ratio = if (emote.height > 0 && emote.width > 0) {
                    (emote.width.toFloat() / emote.height.toFloat()).coerceIn(0.5f, 4.0f)
                } else {
                    1.0f
                }
                val emoteWidthSp = emoteHeightSp * ratio

                map[segment.key] = InlineTextContent(
                    Placeholder(
                        width = emoteWidthSp.sp,
                        height = emoteHeightSp.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                    )
                ) {
                    val imageModel: Any = remember(emote.url, emote.type) {
                        if (emote.type == "embed") {
                            val rawBase64 = emote.url.substringAfter("base64,", emote.url)
                            runCatching { Base64.decode(rawBase64, Base64.DEFAULT) }
                                .getOrDefault(emote.getResolvedUrl())
                        } else {
                            emote.getResolvedUrl()
                        }
                    }

                    AsyncImage(
                        model = imageModel,
                        imageLoader = emoteImageLoader,
                        contentDescription = emote.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 1.dp)
                    )
                }
            }
        }
        map
    }

    val annotatedText = remember(comment.id, segments, usernameColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = usernameColor, fontWeight = FontWeight.Bold)) {
                append("${comment.commenter.displayName}: ")
            }
            for (segment in segments) {
                when (segment) {
                    is ParsedChatSegment.Text -> {
                        withStyle(SpanStyle(color = Color.White)) {
                            append(segment.text)
                        }
                    }
                    is ParsedChatSegment.Emote -> {
                        appendInlineContent(segment.key, segment.emote.name.ifBlank { "[emote]" })
                    }
                }
            }
        }
    }

    Text(
        text = annotatedText,
        inlineContent = inlineContentMap,
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp + 6).sp
    )
}

@Composable
fun LayoutCustomizationCard(
    config: PlayerLayoutConfig,
    hasRenderedChatVideo: Boolean,
    loadedEmoteCount: Int,
    onUpdate: ((PlayerLayoutConfig) -> PlayerLayoutConfig) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier
            .widthIn(max = 380.dp)
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE1E1E24))
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Player & Chat Layout", style = MaterialTheme.typography.titleMedium, color = Color.White)
                if (loadedEmoteCount > 0) {
                    Text(
                        "$loadedEmoteCount Emotes (7TV/BTTV/FFZ)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9146FF)
                    )
                }
            }

            // Display Mode Selector
            Text("Chat Mode", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatDisplayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.displayMode == mode,
                        onClick = { onUpdate { it.copy(displayMode = mode) } },
                        label = { Text(mode.name.replace("_", " ")) }
                    )
                }
            }

            if (config.displayMode != ChatDisplayMode.HIDDEN) {
                // Left / Right Selector
                Text("Chat Position", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatSide.entries.forEach { side ->
                        FilterChip(
                            selected = config.chatSide == side,
                            onClick = { onUpdate { it.copy(chatSide = side) } },
                            label = { Text(side.name) }
                        )
                    }
                }

                // Chat Width Slider
                Text(
                    "Chat Width: ${(config.chatWidthFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.LightGray
                )
                Slider(
                    value = config.chatWidthFraction,
                    onValueChange = { w -> onUpdate { it.copy(chatWidthFraction = w) } },
                    valueRange = 0.18f..0.45f
                )

                // Chat Font & Emote Size Slider
                Text(
                    "Chat Font & Emote Size: ${config.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.LightGray
                )
                Slider(
                    value = config.fontSizeSp,
                    onValueChange = { sz -> onUpdate { it.copy(fontSizeSp = sz) } },
                    valueRange = 10f..22f
                )

                // Overlay Opacity Slider (only in OVERLAY mode)
                if (config.displayMode == ChatDisplayMode.OVERLAY) {
                    Text(
                        "Overlay Background Opacity: ${(config.overlayOpacity * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.LightGray
                    )
                    Slider(
                        value = config.overlayOpacity,
                        onValueChange = { a -> onUpdate { it.copy(overlayOpacity = a) } },
                        valueRange = 0.1f..0.9f
                    )
                }
            }

            // Optional switch for Ganymede's pre-rendered chat MP4
            if (hasRenderedChatVideo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Stream Rendered Chat MP4", color = Color.White, fontSize = 14.sp)
                    Switch(
                        checked = config.useRenderedChatVideo,
                        onCheckedChange = { useRendered ->
                            onUpdate {
                                it.copy(
                                    useRenderedChatVideo = useRendered,
                                    displayMode = if (useRendered) ChatDisplayMode.HIDDEN else it.displayMode
                                )
                            }
                        }
                    )
                }
            }

            Button(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("Done")
            }
        }
    }
}

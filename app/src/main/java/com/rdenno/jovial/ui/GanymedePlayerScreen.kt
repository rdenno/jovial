package com.rdenno.jovial.ui

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.SpeakerNotesOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.rdenno.jovial.MainActivity
import com.rdenno.jovial.PlaybackService
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
            // Pause for calls / other media, and when headphones are unplugged
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // Keep the CPU and Wi-Fi awake so playback continues with the screen off
            .setWakeMode(C.WAKE_MODE_NETWORK)
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

    // The media session drives the notification / lock screen controls and the PiP window's
    // play/pause button; PlaybackService keeps it alive in the background.
    // Declared after the effect above so it is disposed (released) before the player is.
    DisposableEffect(exoPlayer) {
        val session = buildMediaSession(context, exoPlayer)
        PlaybackService.attach(context, session)
        onDispose {
            PlaybackService.detach(context, session)
            session.release()
        }
    }

    val activity = context as? ComponentActivity
    val pipSupported = remember(activity) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    }
    var isPlaying by remember { mutableStateOf(false) }
    var isInPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    // Android rejects PiP aspect ratios outside 1:2.39 .. 2.39:1
    val pipAspect = remember(videoAspect) {
        Rational((videoAspect.coerceIn(1f / 2.39f, 2.39f) * 10_000).toInt(), 10_000)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Keep the screen awake while the video is playing, like YouTube
    DisposableEffect(isPlaying) {
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Android 12+: the system enters PiP on its own when leaving the app while this is enabled
    LaunchedEffect(isPlaying, pipAspect, pipSupported) {
        if (pipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity?.setPictureInPictureParams(buildPipParams(pipAspect, autoEnter = isPlaying))
        }
    }

    DisposableEffect(activity, pipSupported) {
        if (activity == null) return@DisposableEffect onDispose { }

        // Android 8-11 has no auto-enter, so enter PiP manually when the user leaves the app
        val userLeaveListener = Runnable {
            if (pipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S && exoPlayer.isPlaying
            ) {
                activity.enterPictureInPictureMode(buildPipParams(pipAspect, autoEnter = false))
            }
        }
        // Playback continues in the background (screen off, other apps) with a media notification,
        // but dismissing the PiP window means the user is done watching: pause then.
        val pipModeListener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPip = info.isInPictureInPictureMode
            if (!info.isInPictureInPictureMode &&
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            ) {
                exoPlayer.pause()
            }
        }
        activity.addOnUserLeaveHintListener(userLeaveListener)
        activity.addOnPictureInPictureModeChangedListener(pipModeListener)

        onDispose {
            activity.removeOnUserLeaveHintListener(userLeaveListener)
            activity.removeOnPictureInPictureModeChangedListener(pipModeListener)
            // Leaving the player must not auto-enter PiP from the VOD list
            if (pipSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.setPictureInPictureParams(buildPipParams(pipAspect, autoEnter = false))
            }
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
        val metadata = MediaMetadata.Builder()
            .setTitle(vod.title)
            .setArtist(vod.edges?.channel?.displayName?.ifBlank { null } ?: vod.edges?.channel?.name)
            .apply {
                val thumbnail = vod.getFullThumbnailUrl(viewModel.serverUrl)
                if (!thumbnail.endsWith("/")) setArtworkUri(Uri.parse(thumbnail))
            }
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .setMediaId(vod.id)
            .setMediaMetadata(metadata)
            .build()
        exoPlayer.setMediaItem(mediaItem, startPos)
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

    // The PiP window shows only the video, without chat or controls
    if (isInPip) {
        VideoSurface(
            exoPlayer = exoPlayer,
            modifier = Modifier.fillMaxSize().background(Color.Black)
        )
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // In portrait, chat always goes above / below the video instead of beside it
        val isPortrait = maxHeight > maxWidth
        val maxVideoHeight = maxHeight * 0.6f
        val chatFirst = config.chatSide == ChatSide.LEFT

        @Composable
        fun RowScope.TopBar() {
            IconButton(onClick = { handleBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = vod.title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

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
                    Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = "Show Chat", tint = Color.White)
                } else {
                    Icon(Icons.Rounded.SpeakerNotesOff, contentDescription = "Hide Chat", tint = Color.White)
                }
            }

            // One-tap button to swap chat side (left / right, or top / bottom in portrait)
            if (config.displayMode != ChatDisplayMode.HIDDEN) {
                IconButton(
                    onClick = {
                        viewModel.updateLayout {
                            it.copy(chatSide = if (it.chatSide == ChatSide.LEFT) ChatSide.RIGHT else ChatSide.LEFT)
                        }
                    }
                ) {
                    Icon(
                        if (isPortrait) Icons.Rounded.SwapVert else Icons.Rounded.SwapHoriz,
                        contentDescription = "Swap Chat Side",
                        tint = Color.White
                    )
                }
            }

            // Open full layout customization sheet
            IconButton(onClick = { showSettingsSheet = !showSettingsSheet }) {
                Icon(Icons.Rounded.Tune, contentDescription = "Customize Layout", tint = Color.White)
            }
        }

        @Composable
        fun Chat(modifier: Modifier) {
            ChatPane(
                messages = messages,
                emoteCatalog = emoteCatalog,
                emoteImageLoader = emoteImageLoader,
                fontSizeSp = config.fontSizeSp,
                modifier = modifier
            )
        }

        // Video with controls on top; `overlayContent` draws between the two (overlay chat)
        @Composable
        fun VideoPlayer(modifier: Modifier, overlayContent: @Composable BoxScope.() -> Unit = {}) {
            Box(modifier = modifier) {
                VideoSurface(exoPlayer = exoPlayer, modifier = Modifier.fillMaxSize())
                overlayContent()
                PlayerControlsOverlay(
                    player = exoPlayer,
                    visible = controlsVisible,
                    onVisibleChange = { controlsVisible = it },
                    modifier = Modifier.fillMaxSize(),
                    topBar = { TopBar() }
                )
            }
        }

        val chatBackground = Modifier.background(Color(0xFF121216))

        when (config.displayMode) {
            ChatDisplayMode.HIDDEN -> VideoPlayer(Modifier.fillMaxSize())

            ChatDisplayMode.SIDE_BY_SIDE -> if (isPortrait) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (chatFirst) Chat(Modifier.fillMaxWidth().weight(1f).then(chatBackground))
                    VideoPlayer(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxVideoHeight)
                            .aspectRatio(videoAspect.coerceIn(0.5f, 3f))
                    )
                    if (!chatFirst) Chat(Modifier.fillMaxWidth().weight(1f).then(chatBackground))
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    if (chatFirst) {
                        Chat(Modifier.fillMaxHeight().fillMaxWidth(config.chatWidthFraction).then(chatBackground))
                    }
                    VideoPlayer(Modifier.fillMaxHeight().weight(1f))
                    if (!chatFirst) {
                        Chat(Modifier.fillMaxHeight().fillMaxWidth(config.chatWidthFraction).then(chatBackground))
                    }
                }
            }

            ChatDisplayMode.OVERLAY -> VideoPlayer(Modifier.fillMaxSize()) {
                val overlayShape = RoundedCornerShape(8.dp)
                val overlayBackground = Modifier.background(Color.Black.copy(alpha = config.overlayOpacity), overlayShape)
                if (isPortrait) {
                    Chat(
                        Modifier
                            .align(if (chatFirst) Alignment.TopCenter else Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(0.4f)
                            .padding(12.dp)
                            .then(overlayBackground)
                    )
                } else {
                    Chat(
                        Modifier
                            .align(if (chatFirst) Alignment.CenterStart else Alignment.CenterEnd)
                            .fillMaxHeight(0.85f)
                            .fillMaxWidth(config.chatWidthFraction)
                            .padding(12.dp)
                            .then(overlayBackground)
                    )
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
                isPortrait = isPortrait,
                hasRenderedChatVideo = !vod.chatVideoPath.isNullOrBlank(),
                loadedEmoteCount = emoteCatalog.byName.size + emoteCatalog.byId.size,
                onUpdate = { viewModel.updateLayout(it) },
                onClose = { showSettingsSheet = false }
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun buildMediaSession(context: Context, player: Player): MediaSession {
    // Tapping the notification brings the app (and the playing video) back
    val openApp = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    // Load notification artwork through the API client so the server's API key is sent
    val bitmapLoader = CacheBitmapLoader(
        DataSourceBitmapLoader(
            DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
            OkHttpDataSource.Factory(GanymedeApi.activeClient)
        )
    )
    return MediaSession.Builder(context, player)
        .setSessionActivity(openApp)
        .setBitmapLoader(bitmapLoader)
        .build()
}

@RequiresApi(Build.VERSION_CODES.O)
private fun buildPipParams(aspectRatio: Rational, autoEnter: Boolean): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setAspectRatio(aspectRatio)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
    }
    return builder.build()
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
    isPortrait: Boolean,
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
                        label = {
                            Text(
                                if (isPortrait && mode == ChatDisplayMode.SIDE_BY_SIDE) "STACKED"
                                else mode.name.replace("_", " ")
                            )
                        }
                    )
                }
            }

            if (config.displayMode != ChatDisplayMode.HIDDEN) {
                // Left / Right Selector (Top / Bottom in portrait)
                Text("Chat Position", style = MaterialTheme.typography.labelMedium, color = Color.LightGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChatSide.entries.forEach { side ->
                        FilterChip(
                            selected = config.chatSide == side,
                            onClick = { onUpdate { it.copy(chatSide = side) } },
                            label = {
                                Text(
                                    when {
                                        !isPortrait -> side.name
                                        side == ChatSide.LEFT -> "TOP"
                                        else -> "BOTTOM"
                                    }
                                )
                            }
                        )
                    }
                }

                // Chat Width Slider (portrait chat always spans the full width)
                if (!isPortrait) {
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
                }

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

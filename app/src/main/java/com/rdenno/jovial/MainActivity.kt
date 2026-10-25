package com.rdenno.jovial

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rdenno.jovial.network.GanymedeVod
import com.rdenno.jovial.ui.GanymedePlayerScreen
import com.rdenno.jovial.ui.PlayerViewModel

private const val PREFS_NAME = "jovial_prefs"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_API_KEY = "api_key"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw behind the system bars (the default from Android 15) on every version; screens pad
        // themselves. The app is always dark, so keep light status / navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JovialApp()
                }
            }
        }
    }
}

@Composable
fun JovialApp(viewModel: PlayerViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    LaunchedEffect(Unit) {
        viewModel.initPreferences(prefs, context.cacheDir)
    }

    var selectedVod by remember { mutableStateOf<GanymedeVod?>(null) }
    var serverUrlInput by remember {
        mutableStateOf(prefs.getString(KEY_SERVER_URL, "http://192.168.1.100:4000") ?: "http://192.168.1.100:4000")
    }
    var apiKeyInput by remember {
        mutableStateOf(prefs.getString(KEY_API_KEY, "") ?: "")
    }

    val vods by viewModel.vods.collectAsState()
    val savedProgress by viewModel.savedProgress.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    fun triggerConnect() {
        viewModel.connectToServer(
            rawUrl = serverUrlInput,
            apiKey = apiKeyInput,
            onConnected = {
                prefs.edit()
                    .putString(KEY_SERVER_URL, viewModel.serverUrl)
                    .putString(KEY_API_KEY, apiKeyInput.trim())
                    .apply()
                serverUrlInput = viewModel.serverUrl
            }
        )
    }

    if (selectedVod != null) {
        GanymedePlayerScreen(
            vod = selectedVod!!,
            viewModel = viewModel,
            onBack = { selectedVod = null }
        )
    } else {
        // The player handles the system bars itself; everything else stays clear of them
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            if (!isConnected) {
                StartupConnectionScreen(
                    serverUrlInput = serverUrlInput,
                    onServerUrlChange = { serverUrlInput = it },
                    apiKeyInput = apiKeyInput,
                    onApiKeyChange = { apiKeyInput = it },
                    isConnecting = isConnecting,
                    connectionError = connectionError,
                    onConnect = { triggerConnect() }
                )
            } else {
                VodBrowserScreen(
                    serverUrl = viewModel.serverUrl,
                    vods = vods,
                    savedProgress = savedProgress,
                    isRefreshing = isConnecting,
                    connectionError = connectionError,
                    onRefresh = { viewModel.fetchVods() },
                    onChangeServer = { viewModel.disconnect() },
                    onSelectVod = { selectedVod = it }
                )
            }
        }
    }
}

@Composable
private fun StartupConnectionScreen(
    serverUrlInput: String,
    onServerUrlChange: (String) -> Unit,
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    isConnecting: Boolean,
    connectionError: String?,
    onConnect: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text(
                            text = "Jovial",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Connect to your Ganymede server over LAN or ZeroTier",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = onServerUrlChange,
                    label = { Text("Ganymede Server URL or IP:Port") },
                    placeholder = { Text("http://10.147.17.1:4000") },
                    supportingText = {
                        Text("Example: http://10.x.x.x:4000 or 192.168.1.100:4000")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = onApiKeyChange,
                    label = { Text("API Key (Optional)") },
                    placeholder = { Text("Leave blank if public read access is enabled") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            focusManager.clearFocus()
                            if (!isConnecting) onConnect()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (!connectionError.isNullOrBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = connectionError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onConnect()
                    },
                    enabled = !isConnecting && serverUrlInput.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Connecting...")
                    } else {
                        Text("Connect to Server", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun VodBrowserScreen(
    serverUrl: String,
    vods: List<GanymedeVod>,
    savedProgress: Map<String, Long>,
    isRefreshing: Boolean,
    connectionError: String?,
    onRefresh: () -> Unit,
    onChangeServer: () -> Unit,
    onSelectVod: (GanymedeVod) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Connected Server Top Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.SettingsEthernet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = serverUrl,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${vods.size} archived streams",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh VODs")
                        }
                    }
                    OutlinedButton(onClick = onChangeServer) {
                        Text("Change Server")
                    }
                }
            }
        }

        if (!connectionError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = connectionError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (vods.isEmpty() && !isRefreshing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "No VODs found on this Ganymede server.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(onClick = onRefresh) {
                        Text("Refresh")
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(vods, key = { it.id }) { vod ->
                    val progressMs = savedProgress[vod.id] ?: 0L
                    val totalMs = vod.duration * 1000L
                    val progressFraction = if (totalMs > 0) (progressMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectVod(vod) }
                    ) {
                        Column {
                            Box {
                                AsyncImage(
                                    model = vod.getFullThumbnailUrl(serverUrl),
                                    contentDescription = vod.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(135.dp)
                                )

                                if (vod.duration > 0) {
                                    Text(
                                        text = formatDuration(vod.duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .background(
                                                Color.Black.copy(alpha = 0.75f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                if (progressMs > 3000L && progressFraction > 0.01f) {
                                    // Saved watch progress indicator bar
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .background(Color.Black.copy(alpha = 0.5f))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(progressFraction)
                                                .background(Color(0xFFFF0044))
                                        )
                                    }
                                }
                            }
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = vod.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = vod.edges?.channel?.displayName ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (progressMs > 3000L && progressFraction in 0.02f..0.96f) {
                                        Text(
                                            text = "Resume at ${formatDuration((progressMs / 1000).toInt())}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.LightGray
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

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

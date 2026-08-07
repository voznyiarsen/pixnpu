package com.pixnpu.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixnpu.ui.components.RuntimeStatusBar
import com.pixnpu.ui.screens.InferenceScreen
import com.pixnpu.ui.screens.ModelSelectorScreen
import com.pixnpu.ui.screens.SettingsScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

enum class Screen(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    CHAT("Chat", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    MODELS("Models", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
    SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val models by vm.models.collectAsStateWithLifecycle()
    val downloadState by vm.downloadState.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val params by vm.params.collectAsStateWithLifecycle()
    val systemPrompt by vm.systemPrompt.collectAsStateWithLifecycle()
    val template by vm.template.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val isGenerating by vm.isGenerating.collectAsStateWithLifecycle()
    val isLoadingModel by vm.isLoadingModel.collectAsStateWithLifecycle()
    val modelLoadStatus by vm.modelLoadStatus.collectAsStateWithLifecycle()
    val engineMessage by vm.engineMessage.collectAsStateWithLifecycle()
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val pendingImageUri by vm.pendingImageUri.collectAsStateWithLifecycle()
    val pendingAudio by vm.pendingAudio.collectAsStateWithLifecycle()
    val pendingTextFile by vm.pendingTextFile.collectAsStateWithLifecycle()
    val pendingVideo by vm.pendingVideo.collectAsStateWithLifecycle()
    val selectedModality by vm.selectedModality.collectAsStateWithLifecycle()
    val apiServerEnabled by vm.apiServerEnabled.collectAsStateWithLifecycle()
    val apiRouterEnabled by vm.apiRouterEnabled.collectAsStateWithLifecycle()
    val apiHost by vm.apiHost.collectAsStateWithLifecycle()
    val apiPort by vm.apiPort.collectAsStateWithLifecycle()
    val apiServerUrl by vm.apiServerUrl.collectAsStateWithLifecycle()
    val apiToken by vm.apiToken.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val markdownEnabled by vm.markdownEnabled.collectAsStateWithLifecycle()
    val samplingPresets by vm.samplingPresets.collectAsStateWithLifecycle()

    var tabRowHeight by remember { mutableStateOf(56.dp) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.isImeVisible
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { Screen.entries.size })
    val currentTab = Screen.entries[pagerState.currentPage.coerceIn(0, Screen.entries.size - 1)]

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { vm.setPendingImage(uri) }
    }

    // Errors and status messages surface as toasts instead of inline text.
    val context = LocalContext.current
    var lastToastMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(engineMessage) {
        val message = engineMessage
        if (message != null && message != lastToastMessage) {
            lastToastMessage = message
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("pix[npu]", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = selectedModel?.name ?: "No Model Selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (apiServerEnabled) {
                            Text(
                                text = apiServerUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(
                        onClick = { vm.clearChat() },
                        enabled = messages.isNotEmpty(),
                    ) {
                        Text("Clear")
                    }
                },
            )
        },
        bottomBar = {
            if (!imeVisible) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(36.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            // Sliding pill: tracks the pager so it glides between
                            // buttons on swipe and animates on tab clicks. Progress
                            // spans 0..lastTab so the pill reaches every tab.
                            val stepPx = with(LocalDensity.current) { (TabWidth + TabSpacing).toPx() }
                            val maxProgress = (Screen.entries.size - 1).toFloat()
                            val progress = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                                .coerceIn(0f, maxProgress)
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset((progress * stepPx).roundToInt(), 0) }
                                    .width(TabWidth)
                                    .height(tabRowHeight)
                                    .clip(RoundedCornerShape(36.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                            )
                            Row(
                                modifier = Modifier.onSizeChanged { size ->
                                    tabRowHeight = with(density) { size.height.toDp() }
                                },
                                horizontalArrangement = Arrangement.spacedBy(TabSpacing),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Screen.entries.forEach { screen ->
                                    val selected = currentTab == screen
                                    NavTabButton(
                                        screen = screen,
                                        selected = selected,
                                        onClick = {
                                            scope.launch { pagerState.animateScrollToPage(screen.ordinal) }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (keepScreenOn) Modifier.keepScreenOn() else Modifier)
        ) {
            RuntimeStatusBar(
                metrics = metrics,
                params = params,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f)) {
                HorizontalPager(state = pagerState) { page ->
                    when (Screen.entries[page]) {
                             Screen.CHAT -> InferenceScreen(
                                 messages = messages,
                                 isGenerating = isGenerating,
                                 isLoadingModel = isLoadingModel != null,
                                 selectedModel = selectedModel?.name,
                                 pendingImageUri = pendingImageUri,
                            pendingAudio = pendingAudio,
                            pendingTextFile = pendingTextFile,
                            pendingVideo = pendingVideo,
                            onSend = vm::send,
                            onStop = vm::stop,
                            onPickImage = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                             onClearImage = { vm.setPendingImage(null) },
                            onSetAudio = { vm.setPendingAudio(it) },
                            onSetTextFile = { vm.setPendingTextFile(it) },
                            onSetVideo = { vm.setPendingVideo(it) },
                             supportsVision = metrics.supportsVision,
                             supportsAudio = metrics.supportsAudio,
                             supportsVideo = metrics.supportsVideo,
                             markdownEnabled = markdownEnabled,
                         )
                             Screen.MODELS -> ModelSelectorScreen(
                                 models = models,
                                 downloadState = downloadState,
                                 selectedPath = selectedModel?.absolutePath,
                                 modelLoadStatus = modelLoadStatus,
                                 onLoad = { model -> vm.loadModel(model) },
                                 onUnload = vm::unloadModel,
                                 onVerify = vm::verifyModel,
                                 onDelete = vm::deleteModel,
                                 onDownload = vm::downloadModel,
                                 onImport = vm::importModel,
                                 onPause = vm::pauseDownload,
                                 onCancelDownload = vm::cancelDownload,
                             )
                             Screen.SETTINGS -> SettingsScreen(
                                 params = params,
                                 systemPrompt = systemPrompt,
                                 template = template,
                                 modality = selectedModality,
                                 isGenerating = isGenerating,
                                 apiServerEnabled = apiServerEnabled,
                                 apiRouterEnabled = apiRouterEnabled,
                                 apiHost = apiHost,
                                 apiPort = apiPort,
                                 apiServerUrl = apiServerUrl,
                                 apiToken = apiToken,
                                 keepScreenOn = keepScreenOn,
                                 markdownEnabled = markdownEnabled,
                                 presets = samplingPresets,
                                 onChangeParams = vm::updateParams,
                                 onChangeSystemPrompt = vm::updateSystemPrompt,
                                 onChangeTemplate = vm::setTemplate,
                                 onModalityChange = vm::setSelectedModality,
                                 onToggleApiServer = vm::toggleApiServer,
                                 onToggleApiRouter = vm::setApiRouterEnabled,
                                 onApiHostChange = vm::setApiHost,
                                 onApiPortChange = vm::setApiPort,
                                 onApiTokenChange = vm::setApiToken,
                                 onKeepScreenOnChange = vm::setKeepScreenOn,
                                 onMarkdownEnabledChange = vm::setMarkdownEnabled,
                                 onCreatePreset = { name -> vm.createSamplingPreset(name, params) },
                                 onDeletePreset = vm::deleteSamplingPreset,
                                 onApplyPreset = vm::applySamplingPreset,
                                 onResetSettings = vm::resetSettings,
                             )
                    }
                }
            }
        }
    }
}

private val TabWidth = 110.dp
private val TabSpacing = 12.dp

/**
 * Single bottom-nav button. Colors animate with selection, the icon crossfades
 * between the outlined and filled variants and scales up slightly when the
 * button becomes the active tab.
 */
@Composable
private fun NavTabButton(
    screen: Screen,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val onPill = MaterialTheme.colorScheme.onSecondaryContainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(if (selected) onPill else muted, label = "navTint")
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.82f,
        label = "navIconScale",
    )
    Column(
        modifier = Modifier
            .width(TabWidth)
            .clip(RoundedCornerShape(36.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "navIcon",
        ) { isSelected ->
            Icon(
                imageVector = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                // The visible label below is the accessible name — don't read it twice.
                contentDescription = null,
                tint = tint,
                modifier = Modifier.graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
            )
        }
        Text(
            text = screen.label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

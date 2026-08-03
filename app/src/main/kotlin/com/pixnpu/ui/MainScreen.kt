package com.pixnpu.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixnpu.ui.components.ParameterSheet
import com.pixnpu.ui.components.RuntimeStatusBar
import com.pixnpu.ui.screens.InferenceScreen
import com.pixnpu.ui.screens.ModelSelectorScreen

enum class Screen(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    CHAT("Chat", Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    MODELS("Models", Icons.Filled.Inventory2, Icons.Outlined.Inventory2),
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
    val engineMessage by vm.engineMessage.collectAsStateWithLifecycle()
    val metrics by vm.metrics.collectAsStateWithLifecycle()
    val pendingImageUri by vm.pendingImageUri.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Screen.CHAT) }
    var showParams by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { vm.setPendingImage(uri) }
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
                    }
                },
                actions = {
                    TextButton(onClick = { showParams = true }) {
                        Text("Params", color = MaterialTheme.colorScheme.primary)
                    }
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
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Screen.entries.forEach { screen ->
                                val selected = tab == screen
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clip(RoundedCornerShape(36.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        )
                                        .clickable { tab = screen }
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                        contentDescription = screen.label,
                                        tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = screen.label,
                                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
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
            Modifier.fillMaxSize()
        ) {
            if (isLoadingModel != null) {
                val transition = rememberInfiniteTransition(label = "loading-think")
                val shimmerX by transition.animateFloat(
                    initialValue = -1f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "loading-shimmer",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(40.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.tertiary.copy(0f),
                                        MaterialTheme.colorScheme.tertiary,
                                        MaterialTheme.colorScheme.secondary,
                                        MaterialTheme.colorScheme.tertiary,
                                        MaterialTheme.colorScheme.tertiary.copy(0f),
                                    ),
                                    startX = shimmerX * 100,
                                    endX = (shimmerX + 1) * 100,
                                ),
                            ),
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding)
            ) {
                RuntimeStatusBar(
                    metrics = metrics,
                    params = params,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Box(Modifier.weight(1f)) {
                    Crossfade(targetState = tab, label = "tab-transition") { currentTab ->
                        when (currentTab) {
                            Screen.CHAT -> InferenceScreen(
                                messages = messages,
                                isGenerating = isGenerating,
                                isLoadingModel = isLoadingModel != null,
                                selectedModel = selectedModel?.name,
                                engineMessage = engineMessage,
                                pendingImageUri = pendingImageUri,
                                onSend = vm::send,
                                onStop = vm::stop,
                                onPickImage = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onClearImage = { vm.setPendingImage(null) },
                            )
                            Screen.MODELS -> ModelSelectorScreen(
                                models = models,
                                downloadState = downloadState,
                                selectedPath = selectedModel?.absolutePath,
                                loadingModelName = isLoadingModel,
                                onLoad = vm::loadModel,
                                onVerify = vm::verifyModel,
                                onDelete = vm::deleteModel,
                                onDownload = vm::downloadModel,
                                onImport = vm::importModel,
                                onPause = vm::pauseDownload,
                                onCancelDownload = vm::cancelDownload,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showParams) {
        ParameterSheet(
            params = params,
            systemPrompt = systemPrompt,
            template = template,
            isGenerating = isGenerating,
            onChangeParams = vm::updateParams,
            onChangeSystemPrompt = vm::updateSystemPrompt,
            onChangeTemplate = vm::setTemplate,
            onDismiss = { showParams = false },
        )
    }
}

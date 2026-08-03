package com.pixnpu.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pixnpu.ui.components.ParameterSheet
import com.pixnpu.ui.components.RuntimeStatusBar
import com.pixnpu.ui.screens.InferenceScreen
import com.pixnpu.ui.screens.ModelSelectorScreen

private enum class Screen(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
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
        uri?.let { vm.setPendingImage(it) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("pix[npu]", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = selectedModel?.name ?: "No model selected",
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
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = tab == screen,
                            onClick = { tab = screen },
                            icon = {
                                Icon(
                                    imageVector = if (tab == screen) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.label,
                                )
                            },
                            alwaysShowLabel = true,
                            label = { Text(screen.label) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            RuntimeStatusBar(
                metrics = metrics,
                params = params,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f)) {
                when (tab) {
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
                        onPause = vm::pauseDownload,
                        onCancelDownload = vm::cancelDownload,
                    )
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
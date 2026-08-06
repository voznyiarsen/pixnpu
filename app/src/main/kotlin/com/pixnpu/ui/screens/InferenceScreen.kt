package com.pixnpu.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pixnpu.ui.AudioClip
import com.pixnpu.ui.ChatMessage
import com.pixnpu.ui.ChatRole
import com.pixnpu.ui.TextFileClip
import com.pixnpu.ui.VideoClip
import com.pixnpu.ui.components.AudioFileDecodeResult
import com.pixnpu.ui.components.AudioRecorder
import com.pixnpu.ui.components.AudioRecorderPanel
import com.pixnpu.ui.components.StreamingText
import com.pixnpu.ui.components.decodeAudioFileToPcm
import com.pixnpu.ui.components.pcmBytesToDurationMs
import com.pixnpu.ui.components.readVideoClip
import com.pixnpu.util.Fmt
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InferenceScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isLoadingModel: Boolean,
    selectedModel: String?,
    pendingImageUri: Uri?,
    pendingAudio: AudioClip?,
    pendingTextFile: TextFileClip?,
    pendingVideo: VideoClip?,
    supportsVision: Boolean = true,
    supportsAudio: Boolean = true,
    supportsVideo: Boolean = false,
    markdownEnabled: Boolean = true,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onSetAudio: (AudioClip?) -> Unit,
    onSetTextFile: (TextFileClip?) -> Unit,
    onSetVideo: (VideoClip?) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { AudioRecorder(scope) }
    var isRecording by remember { mutableStateOf(false) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var attachError by remember { mutableStateOf<String?>(null) }

    val audioFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = decodeAudioFileToPcm(context, uri)) {
                    is AudioFileDecodeResult.Success -> {
                        attachError = null
                        onSetAudio(result.clip)
                    }
                    is AudioFileDecodeResult.Failure -> attachError = result.message
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            isRecording = true
            recorder.start()
        }
    }

    val textFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = readTextFileClip(context, uri)) {
                    null -> attachError = "Failed to read file"
                    else -> {
                        attachError = null
                        onSetTextFile(result)
                    }
                }
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = readVideoClip(context, uri)) {
                    null -> attachError = "Failed to read video"
                    else -> {
                        attachError = null
                        onSetVideo(result)
                    }
                }
            }
        }
    }

    val startRecording: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            isRecording = true
            recorder.start()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.release() }
    }

    // Follow the conversation: when a new message is added, jump to it so the
    // latest turn is visible. While streaming (the last message grows in place),
    // only keep pinning to the bottom if the user is already at the bottom —
    // never fight an explicit scroll.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }
    LaunchedEffect(messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            val atBottom = lastVisible == null || lastVisible.index >= layout.totalItemsCount - 1
            if (atBottom) {
                listState.scrollToItem(messages.lastIndex, scrollOffset = Int.MAX_VALUE)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && selectedModel == null) {
                EmptyPrompt()
            } else if (messages.isEmpty()) {
                ModelLoadedPrompt()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message, markdownEnabled)
                    }
                }
            }
        }

        if (isRecording) {
            AudioRecorderPanel(
                recorder = recorder,
                onSend = { clip ->
                    isRecording = false
                    onSetAudio(clip)
                },
                onClose = {
                    recorder.stop()
                    isRecording = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        attachError?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        InputBar(
            value = input,
            isGenerating = isGenerating,
            isRecording = isRecording,
            pendingImageUri = pendingImageUri,
            pendingAudio = pendingAudio,
            pendingTextFile = pendingTextFile,
            pendingVideo = pendingVideo,
            supportsVision = supportsVision,
            supportsAudio = supportsAudio,
            onValueChange = { input = it },
            onSend = {
                if (input.isNotBlank() || pendingImageUri != null || pendingAudio != null || pendingTextFile != null || pendingVideo != null) {
                    onSend(input)
                    input = ""
                }
            },
            onStop = onStop,
            onAttachClick = { showAttachSheet = true },
            onClearImage = onClearImage,
            onClearAudio = { onSetAudio(null) },
            onClearTextFile = { onSetTextFile(null) },
            onClearVideo = { onSetVideo(null) },
            focusRequester = focusRequester,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }

    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            val options = buildList {
                if (supportsVision) {
                    add(AttachOptionSpec(Icons.Outlined.PhotoLibrary, "Gallery") {
                        showAttachSheet = false
                        onPickImage()
                    })
                }
                if (supportsAudio) {
                    add(AttachOptionSpec(Icons.Outlined.Mic, "Audio") {
                        showAttachSheet = false
                        startRecording()
                    })
                    add(AttachOptionSpec(Icons.Outlined.AudioFile, "Audio file") {
                        showAttachSheet = false
                        audioFileLauncher.launch(arrayOf("audio/*"))
                    })
                }
                add(AttachOptionSpec(Icons.Outlined.Description, "File") {
                    showAttachSheet = false
                    textFileLauncher.launch(arrayOf("text/*"))
                })
                if (supportsVideo) {
                    add(AttachOptionSpec(Icons.Outlined.VideoFile, "Video") {
                        showAttachSheet = false
                        videoLauncher.launch(arrayOf("video/*"))
                    })
                }
            }
            if (options.isEmpty()) {
                Text(
                    "This model only supports text input",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .padding(bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    options.forEach { spec ->
                        AttachOption(
                            icon = spec.icon,
                            label = spec.label,
                            onClick = spec.onClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPrompt() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "pix[npu]",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Local LLM Inference on Google Tensor NPU.\nPull a Model, then Start a Conversation.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelLoadedPrompt() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Model Loaded",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Ask a Question to Start the Conversation.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: ChatMessage, markdownEnabled: Boolean = true) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    when (message.role) {
        ChatRole.USER -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .combinedClickable(
                                onClickLabel = "Copy message",
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                },
                                onLongClickLabel = "Copy message",
                                onLongClick = {
                                    clipboardManager.setText(AnnotatedString(message.text))
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                            )
                    ) {
                        message.imageUri?.let { uri ->
                            AsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(uri)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 16.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        message.audioBytes?.let { bytes ->
                            AudioClipChip(
                                durationMs = pcmBytesToDurationMs(bytes),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        message.textFile?.let { clip ->
                            FileClipChip(
                                name = clip.name,
                                truncated = clip.truncated,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                        message.video?.let { clip ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.VideoFile,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = clip.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (clip.durationMs > 0L) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = Fmt.duration(clip.durationMs),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                        if (message.text.isNotBlank()) {
                            Text(
                                text = message.text,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        ChatRole.ASSISTANT -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = "Copy message",
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.text))
                        },
                        onLongClickLabel = "Copy message",
                        onLongClick = {
                            clipboardManager.setText(AnnotatedString(message.text))
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        }
                    )
            ) {
                StreamingText(
                    text = message.text,
                    caretVisible = message.streaming,
                    markdownEnabled = markdownEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    value: String,
    isGenerating: Boolean,
    isRecording: Boolean,
    pendingImageUri: Uri?,
    pendingAudio: AudioClip?,
    pendingTextFile: TextFileClip?,
    pendingVideo: VideoClip?,
    supportsVision: Boolean = true,
    supportsAudio: Boolean = true,
    supportsVideo: Boolean = true,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttachClick: () -> Unit,
    onClearImage: () -> Unit,
    onClearAudio: () -> Unit,
    onClearTextFile: () -> Unit,
    onClearVideo: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(36.dp),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            pendingAudio?.let { clip ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AudioClipChip(durationMs = clip.durationMs)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Audio Attached",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearAudio) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            pendingImageUri?.let { uri ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(uri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Pending image",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Image Attached",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearImage) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            pendingTextFile?.let { clip ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileClipChip(
                        name = clip.name,
                        truncated = clip.truncated,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "File Attached",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearTextFile) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            pendingVideo?.let { clip ->
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.VideoFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = clip.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (clip.durationMs > 0L) {
                        Text(
                            text = Fmt.duration(clip.durationMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    TextButton(onClick = onClearVideo) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

             Row(verticalAlignment = Alignment.CenterVertically) {
                val canAttach = supportsVision || supportsAudio || supportsVideo
                IconButton(
                    onClick = onAttachClick,
                    enabled = canAttach && !isRecording,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .heightIn(min = 56.dp, max = 160.dp)
                        .onPreviewKeyEvent { event ->
                            // Catch both backspace (KEYCODE_DEL) and shift+backspace
                            // (KEYCODE_FORWARD_DEL): with an empty input they clear the
                            // pending attachment instead of doing nothing. Otherwise the
                            // event falls through to normal text editing.
                            if (event.type == KeyEventType.KeyUp &&
                                (event.key == Key.Backspace || event.key == Key.Delete) &&
                                value.isEmpty()
                            ) {
                                when {
                                    pendingImageUri != null -> { onClearImage(); true }
                                    pendingAudio != null -> { onClearAudio(); true }
                                    pendingTextFile != null -> { onClearTextFile(); true }
                                    pendingVideo != null -> { onClearVideo(); true }
                                    else -> false
                                }
                            } else {
                                false
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    singleLine = false,
                    maxLines = 6,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp, max = 160.dp)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value.isEmpty()) {
                                Text(
                                    "Ask Anything",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            innerTextField()
                        }
                    },
                )

                if (isGenerating) {
                    FilledIconButton(
                        onClick = onStop,
                        shape = RoundedCornerShape(36.dp),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(24.dp),
                        )
                    }
                } else {
                    val canSend = value.isNotBlank() || pendingImageUri != null || pendingAudio != null || pendingTextFile != null || pendingVideo != null
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        shape = RoundedCornerShape(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.Unspecified,
                        ),
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                brush = if (canSend) {
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary,
                                        ),
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        ),
                                    )
                                },
                                shape = RoundedCornerShape(36.dp),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioClipChip(
    durationMs: Long,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Mic,
            contentDescription = "Voice note",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = String.format(Locale.ROOT, "%.1fs", durationMs / 1000f),
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

@Composable
private fun FileClipChip(
    name: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Description,
            contentDescription = "Text file",
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = if (truncated) "$name · truncated" else name,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
    }
}

private data class AttachOptionSpec(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun AttachOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Maximum bytes of a text file to attach as context (≈4k tokens at 4 bytes/token). */
private const val maxTextFileBytes = 16 * 1024

/**
 * Reads a picked text file (capped at [maxTextFileBytes]) as UTF-8 so it can be
 * injected into the prompt as context. Returns null if the file cannot be read.
 */
private suspend fun readTextFileClip(context: Context, uri: Uri): TextFileClip? =
    withContext(Dispatchers.IO) {
        try {
            val name = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "file.txt"
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(maxTextFileBytes + 1)
                var total = 0
                while (total < buf.size) {
                    val read = input.read(buf, total, buf.size - total)
                    if (read == -1) break
                    total += read
                }
                buf.copyOf(total)
            } ?: return@withContext null
            val truncated = bytes.size > maxTextFileBytes
            val limited = if (truncated) bytes.copyOfRange(0, maxTextFileBytes) else bytes
            TextFileClip(name = name, content = String(limited, Charsets.UTF_8), truncated = truncated)
        } catch (e: Exception) {
            Log.e("InferenceScreen", "Failed to read text file: $uri", e)
            null
        }
    }
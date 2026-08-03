package com.pixnpu.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.pixnpu.ui.ChatMessage
import com.pixnpu.ui.ChatRole
import com.pixnpu.ui.components.StreamingText
import com.pixnpu.ui.theme.TerminalDanger
import com.pixnpu.ui.theme.TerminalLine
import com.pixnpu.ui.theme.TerminalPrimary
import com.pixnpu.ui.theme.TerminalTextDim
import com.pixnpu.ui.theme.TerminalUser
import com.pixnpu.ui.theme.TerminalUserText

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InferenceScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isLoadingModel: Boolean,
    selectedModel: String?,
    engineMessage: String?,
    pendingImageUri: Uri?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isLoadingModel) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = TerminalPrimary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && selectedModel == null) {
                EmptyPrompt()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }

        engineMessage?.let { message ->
            Text(
                text = "[!] $message",
                color = TerminalDanger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        InputBar(
            value = input,
            isGenerating = isGenerating,
            pendingImageUri = pendingImageUri,
            onValueChange = { input = it },
            onSend = {
                if (input.isNotBlank() || pendingImageUri != null) {
                    onSend(input)
                    input = ""
                }
            },
            onStop = onStop,
            onPickImage = onPickImage,
            onClearImage = onClearImage,
            focusRequester = focusRequester,
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
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
            text = "pix[npu]>_",
            style = MaterialTheme.typography.bodyLarge.copy(
                color = com.pixnpu.ui.theme.TerminalPrimary,
                fontSize = 20.sp,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Local LLM inference on Google Tensor NPU.\nPull a .litertlm model, then start chatting.",
            style = MaterialTheme.typography.bodyMedium,
            color = TerminalTextDim,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    when (message.role) {
        ChatRole.USER -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp),
                    color = TerminalUser,
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Column {
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
                                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        if (message.text.isNotBlank()) {
                            Text(
                                text = message.text,
                                color = TerminalUserText,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
        ChatRole.ASSISTANT -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                StreamingText(
                    text = message.text,
                    caretVisible = message.streaming,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InputBar(
    value: String,
    isGenerating: Boolean,
    pendingImageUri: Uri?,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
            pendingImageUri?.let { uri ->
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "image attached",
                        style = MaterialTheme.typography.bodySmall,
                        color = TerminalTextDim,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearImage) {
                        Text("remove", color = TerminalDanger, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.Bottom,
            ) {
                FilledIconButton(
                    onClick = onPickImage,
                    modifier = Modifier.padding(start = 4.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Text("+", fontSize = 18.sp, color = TerminalPrimary)
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            ">>> prompt",
                            color = TerminalTextDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TerminalPrimary,
                        unfocusedBorderColor = TerminalLine,
                        cursorColor = TerminalPrimary,
                    ),
                    minLines = 1,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    singleLine = false,
                )
                if (isGenerating) {
                    TextButton(onClick = onStop) {
                        Text(
                            "\u25A0 STOP",
                            color = TerminalDanger,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    val canSend = value.isNotBlank() || pendingImageUri != null
                    TextButton(
                        onClick = onSend,
                        enabled = canSend,
                    ) {
                        Text(
                            "\u25B6",
                            color = if (canSend) TerminalPrimary else TerminalTextDim,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }
}
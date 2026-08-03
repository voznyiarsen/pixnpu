package com.pixnpu.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pixnpu.model.DownloadState
import com.pixnpu.model.LocalModel
import com.pixnpu.model.progress
import com.pixnpu.ui.theme.TerminalAccent
import com.pixnpu.ui.theme.TerminalDanger
import com.pixnpu.ui.theme.TerminalPrimary
import com.pixnpu.ui.theme.TerminalText
import com.pixnpu.ui.theme.TerminalTextDim
import com.pixnpu.util.Fmt

@Composable
fun ModelSelectorScreen(
    models: List<LocalModel>,
    downloadState: DownloadState,
    selectedPath: String?,
    loadingModelName: String?,
    onLoad: (LocalModel) -> Unit,
    onVerify: (LocalModel) -> Unit,
    onDelete: (LocalModel) -> Unit,
    onDownload: (String, String?) -> Unit,
    onPause: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "local::models",
                style = MaterialTheme.typography.titleMedium.copy(color = TerminalAccent),
            )
            Button(onClick = { showAddDialog = true }) {
                Text("+ ADD", fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (downloadState !is DownloadState.Idle) {
            DownloadControl(downloadState, onPause, onCancelDownload)
            Spacer(Modifier.height(10.dp))
        }

        if (models.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("no models yet", color = TerminalTextDim)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add a .litertlm URL, e.g. a Hugging Face resolve link",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextDim,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.name }) { model ->
                    ModelCard(
                        model = model,
                        selected = model.absolutePath == selectedPath,
                        loading = model.name == loadingModelName,
                        onLoad = { onLoad(model) },
                        onVerify = { onVerify(model) },
                        onDelete = { onDelete(model) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddModelDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { url, sha ->
                onDownload(url, sha.ifBlank { null })
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    selected: Boolean,
    loading: Boolean,
    onLoad: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            TerminalPrimary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TerminalAccent,
                    )
                } else if (selected) {
                    Text("● loaded", color = TerminalPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    Fmt.bytes(model.fileSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalTextDim,
                )
                Text(
                    if (model.verified) "sha256 verified" else "sha256 unverified",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.verified) TerminalPrimary else TerminalAccent,
                )
            }
            if (model.sha256 != null) {
                Text(
                    "sha256: ${Fmt.sha(model.sha256)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextDim,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onLoad,
                    enabled = !selected && !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TerminalAccent,
                        )
                    } else {
                        Text("LOAD", fontFamily = FontFamily.Monospace)
                    }
                }
                TextButton(onClick = onVerify) {
                    Text("verify", fontFamily = FontFamily.Monospace, color = TerminalTextDim)
                }
                TextButton(onClick = onDelete) {
                    Text("delete", fontFamily = FontFamily.Monospace, color = TerminalDanger)
                }
            }
        }
    }
}

@Composable
private fun DownloadControl(
    download: DownloadState,
    onPause: () -> Unit,
    onCancel: () -> Unit,
) {
    val fileName = when (download) {
        is DownloadState.Downloading -> download.fileName
        is DownloadState.Verifying -> "${download.fileName} · verifying"
        else -> downloadStateLabel(download)
    }
    val isPaused = download is DownloadState.Paused

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isPaused) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(fileName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Text(
                    "${(download.progress() * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = TerminalPrimary,
                )
            }
            LinearProgressIndicator(
                progress = { download.progress() },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    statusText(download),
                    style = MaterialTheme.typography.labelMedium,
                    color = TerminalTextDim,
                    modifier = Modifier.weight(1f),
                )
                if (!isPaused) {
                    TextButton(onClick = onPause) { Text("pause", fontFamily = FontFamily.Monospace) }
                }
                TextButton(onClick = onCancel) { Text("cancel", color = TerminalDanger, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}

private fun downloadStateLabel(state: DownloadState): String = when (state) {
    is DownloadState.Downloading -> state.fileName
    is DownloadState.Verifying -> state.fileName
    is DownloadState.Paused -> "${state.fileName} · paused"
    is DownloadState.Complete -> state.fileName
    is DownloadState.Failed -> "${state.fileName} · failed"
    DownloadState.Idle -> ""
}

private fun statusText(state: DownloadState): String = when (state) {
    is DownloadState.Downloading -> {
        val total = state.totalBytes
        val size = total?.let { Fmt.bytes(it) } ?: Fmt.bytes(state.bytesReceived)
        "$size @ ${Fmt.speed(state.bytesPerSecond)}"
    }
    is DownloadState.Verifying -> "sha-256 ${Fmt.bytes(state.totalBytes)}"
    is DownloadState.Paused -> "paused — resume with 'start' again"
    is DownloadState.Complete -> "done ✓ moved to models"
    is DownloadState.Failed -> state.message
    DownloadState.Idle -> ""
}

@Composable
private fun AddModelDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("add .litertlm model", fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("model URL") },
                    placeholder = { Text("https://…/model.litertlm", color = TerminalTextDim) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sha,
                    onValueChange = { sha = it },
                    label = { Text("expected SHA-256 (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim(), sha.trim()) }, enabled = url.isNotBlank()) {
                Text("start", color = TerminalPrimary, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("cancel", fontFamily = FontFamily.Monospace) }
        },
    )
}
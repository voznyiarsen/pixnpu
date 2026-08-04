package com.pixnpu.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import com.pixnpu.model.DownloadState
import com.pixnpu.model.LocalModel
import com.pixnpu.model.ModelLoadStatus
import com.pixnpu.model.progress
import com.pixnpu.util.Fmt

@Composable
fun ModelSelectorScreen(
    models: List<LocalModel>,
    downloadState: DownloadState,
    selectedPath: String?,
    modelLoadStatus: Map<String, ModelLoadStatus>,
    onLoad: (LocalModel) -> Unit,
    onUnload: (LocalModel) -> Unit,
    onVerify: (LocalModel) -> Unit,
    onDelete: (LocalModel) -> Unit,
    onDownload: (String, String?) -> Unit,
    onImport: (Uri) -> Unit,
    onPause: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LocalModel?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Models",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Import")
                }
                Button(
                    onClick = { showAddDialog = true },
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Text("Add")
                }
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
                Text("No Models Yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add a .litertlm URL or Import from Device",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
             LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(models, key = { it.name }) { model ->
                    ModelCard(
                        model = model,
                        selected = model.absolutePath == selectedPath,
                        loadStatus = modelLoadStatus[model.name] ?: ModelLoadStatus.Idle,
                        onLoad = { onLoad(model) },
                        onUnload = { onUnload(model) },
                        onVerify = { onVerify(model) },
                        onDelete = { pendingDelete = model },
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

    pendingDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Model?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(model.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        Fmt.bytes(model.fileSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(model)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    selected: Boolean,
    loadStatus: ModelLoadStatus,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = when (loadStatus) {
            ModelLoadStatus.Success -> Color.Green.copy(alpha = 0.4f)
            ModelLoadStatus.Failed -> Color.Red.copy(alpha = 0.4f)
            ModelLoadStatus.Loading -> Color.Yellow.copy(alpha = 0.4f)
            ModelLoadStatus.Unloading -> Color.Magenta.copy(alpha = 0.4f)
            else -> if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    Fmt.bytes(model.fileSizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (model.verified) "SHA-256 Verified" else "SHA-256 Unverified",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.verified) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }
            if (model.sha256 != null) {
                Text(
                    "SHA-256: ${Fmt.sha(model.sha256)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = if (selected || loadStatus == ModelLoadStatus.Loading) onUnload else onLoad,
                    shape = RoundedCornerShape(20.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text(if (selected || loadStatus == ModelLoadStatus.Loading) "Stop" else "Load")
                }
                OutlinedButton(
                    onClick = onVerify,
                    shape = RoundedCornerShape(20.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text("Verify", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(
                    onClick = onDelete,
                    shape = RoundedCornerShape(20.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
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
        is DownloadState.Verifying -> "${download.fileName} · Verifying"
        is DownloadState.Importing -> "${download.fileName} · Importing"
        else -> downloadStateLabel(download)
    }
    val isPaused = download is DownloadState.Paused
    val isDownloading = download is DownloadState.Downloading

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
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
                    color = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!isPaused && isDownloading) {
                    TextButton(onClick = onPause) { Text("Pause") }
                }
                TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun downloadStateLabel(state: DownloadState): String = when (state) {
    is DownloadState.Downloading -> state.fileName
    is DownloadState.Verifying -> state.fileName
    is DownloadState.Paused -> "${state.fileName} · Paused"
    is DownloadState.Complete -> state.fileName
    is DownloadState.Failed -> "${state.fileName} · Failed"
    is DownloadState.Importing -> "${state.fileName} · Importing"
    DownloadState.Idle -> ""
}

private fun statusText(state: DownloadState): String = when (state) {
    is DownloadState.Downloading -> {
        val total = state.totalBytes
        val size = total?.let { Fmt.bytes(it) } ?: Fmt.bytes(state.bytesReceived)
        val speed = Fmt.speed(state.bytesPerSecond)
        if (state.attempt > 1) {
            "Retrying (${state.attempt}/${state.maxAttempts}) · $size @ $speed"
        } else {
            "$size @ $speed"
        }
    }
    is DownloadState.Verifying -> "SHA-256 ${Fmt.bytes(state.totalBytes)}"
    is DownloadState.Paused -> "Paused — Resume by Pressing Start Again"
    is DownloadState.Complete -> "Done ✓ Moved to Models"
    is DownloadState.Failed -> state.message
    is DownloadState.Importing -> {
        val total = state.totalBytes
        val size = total?.let { Fmt.bytes(it) } ?: Fmt.bytes(state.bytesRead)
        "Copying from Device · SHA-256 $size"
    }
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
        title = { Text("Add .litertlm Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Model URL") },
                    placeholder = { Text("https://…/model.litertlm") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = sha,
                    onValueChange = { sha = it },
                    label = { Text("Expected SHA-256 (Optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url.trim(), sha.trim()) }, enabled = url.isNotBlank()) {
                Text("Start", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
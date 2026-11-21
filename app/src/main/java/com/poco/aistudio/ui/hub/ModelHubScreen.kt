package com.poco.aistudio.ui.hub

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poco.aistudio.data.DownloadStatus
import com.poco.aistudio.data.ModelCatalog
import com.poco.aistudio.data.ModelCategory
import com.poco.aistudio.data.ModelPreset
import com.poco.aistudio.data.ModelRepository
import com.poco.aistudio.ui.theme.AmoledBlack
import com.poco.aistudio.ui.theme.CoralError
import com.poco.aistudio.ui.theme.ElectricCyan
import com.poco.aistudio.ui.theme.EmeraldGreen
import com.poco.aistudio.ui.theme.PocoYellow
import com.poco.aistudio.ui.theme.SurfaceDark
import com.poco.aistudio.ui.theme.SurfaceElevated
import com.poco.aistudio.ui.theme.SurfaceOutline
import com.poco.aistudio.ui.theme.TextPrimary
import com.poco.aistudio.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@Composable
fun ModelHubScreen(
    onBack: () -> Unit,
    onModelsChanged: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { ModelRepository(context) }
    val scope = rememberCoroutineScope()
    val downloads by repo.observeDownloads().collectAsState(initial = emptyMap())

    var selectedCategory by remember { mutableStateOf<ModelCategory?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }

    val importCategory = selectedCategory ?: ModelCategory.LLM
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = repo.importLocalFile(uri, importCategory)
                res.onSuccess {
                    Toast.makeText(context, "Импортировано: ${it.name}", Toast.LENGTH_SHORT).show()
                    refreshTick++
                    onModelsChanged()
                }.onFailure { err ->
                    Toast.makeText(context, "Ошибка импорта: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val filteredPresets = remember(selectedCategory) {
        if (selectedCategory == null) ModelCatalog.presets
        else ModelCatalog.presets.filter { it.category == selectedCategory }
    }
    val customLocalModels = remember(selectedCategory, refreshTick, downloads) {
        repo.listInstalledModels(selectedCategory).filter { !it.isPreset }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = TextPrimary)
                }
                Column {
                    Text("Model Hub", style = MaterialTheme.typography.titleLarge, color = PocoYellow)
                    Text(
                        "Hugging Face (докачка Range) + локальный импорт",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Action row: Import local file + Custom HF URL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = PocoYellow, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Импорт файла", color = PocoYellow, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { showCustomUrlDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Link, null, tint = ElectricCyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("По ссылке HF", color = ElectricCyan, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        // Category filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = selectedCategory == null,
                onClick = { selectedCategory = null },
                label = { Text("Все", fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PocoYellow,
                    selectedLabelColor = Color.Black
                )
            )
            ModelCategory.entries.forEach { cat ->
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = { selectedCategory = cat },
                    label = {
                        Text(
                            when (cat) {
                                ModelCategory.LLM -> "LLM"
                                ModelCategory.IMAGE -> "Фото"
                                ModelCategory.AUDIO -> "Музыка"
                            },
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ElectricCyan,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredPresets, key = { it.id }) { preset ->
                val installed = remember(preset.id, refreshTick, downloads) {
                    repo.isPresetInstalled(preset)
                }
                val dl = downloads[preset.id]
                PresetModelCard(
                    preset = preset,
                    isInstalled = installed,
                    download = dl,
                    onDownload = { repo.enqueuePresetDownload(preset) },
                    onCancel = { repo.cancelDownload(preset.id) },
                    onDelete = {
                        repo.deleteModel(repo.targetPathForPreset(preset).absolutePath)
                        refreshTick++
                        onModelsChanged()
                    }
                )
            }

            if (customLocalModels.isNotEmpty()) {
                item {
                    Text(
                        text = "Импортированные / Пользовательские модели",
                        style = MaterialTheme.typography.labelMedium,
                        color = PocoYellow,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(customLocalModels, key = { it.path }) { local ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceDark)
                            .border(1.dp, SurfaceOutline, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(local.name, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(
                                "${local.category.titleRu} • ${"%.2f".format(local.sizeBytes / 1e9)} ГБ",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        IconButton(onClick = {
                            repo.deleteModel(local.path)
                            refreshTick++
                            onModelsChanged()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = CoralError)
                        }
                    }
                }
            }
        }
    }

    if (showCustomUrlDialog) {
        CustomUrlDialog(
            initialCategory = importCategory,
            onDismiss = { showCustomUrlDialog = false },
            onStart = { name, url, cat ->
                repo.enqueueCustomUrlDownload(name, url, cat)
                showCustomUrlDialog = false
            }
        )
    }
}

@Composable
private fun PresetModelCard(
    preset: ModelPreset,
    isInstalled: Boolean,
    download: DownloadStatus?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .border(
                1.dp,
                if (isInstalled) EmeraldGreen.copy(alpha = 0.5f) else SurfaceOutline,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(preset.name, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 15.sp)
                Text(preset.subtitle, color = TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${preset.category.titleRu} • ${preset.quantLabel} • ${preset.approxSizeGb}",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan
                )
            }

            if (isInstalled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = EmeraldGreen, modifier = Modifier.size(20.dp))
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = CoralError)
                    }
                }
            } else if (download?.isRunning == true) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Cancel, contentDescription = "Отмена", tint = CoralError)
                }
            } else {
                Button(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocoYellow, contentColor = Color.Black)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Скачать", fontSize = 12.sp)
                }
            }
        }

        if (download?.isRunning == true) {
            LinearProgressIndicator(
                progress = { (download.progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PocoYellow,
                trackColor = SurfaceElevated
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${download.progress}%",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = PocoYellow
                )
                Text(
                    "${"%.1f".format(download.speedMbSec)} МБ/с",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = EmeraldGreen
                )
            }
        } else if (!download?.error.isNullOrBlank() && !isInstalled) {
            Text(
                text = "Ошибка: ${download?.error}",
                color = CoralError,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun CustomUrlDialog(
    initialCategory: ModelCategory,
    onDismiss: () -> Unit,
    onStart: (String, String, ModelCategory) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(initialCategory) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = { Text("Скачать по прямой ссылке HF", color = ElectricCyan) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя файла (например model.gguf)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Прямой URL (https://huggingface.co/.../resolve/main/...)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ModelCategory.LLM, ModelCategory.IMAGE).forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(if (cat == ModelCategory.LLM) "LLM" else "Фото") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (url.startsWith("http")) onStart(name, url, category) },
                colors = ButtonDefaults.buttonColors(containerColor = PocoYellow, contentColor = Color.Black)
            ) { Text("Загрузить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
        }
    )
}

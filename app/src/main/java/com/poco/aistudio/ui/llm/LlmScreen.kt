package com.poco.aistudio.ui.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.poco.aistudio.ui.theme.AmoledBlack
import com.poco.aistudio.ui.theme.ElectricCyan
import com.poco.aistudio.ui.theme.EmeraldGreen
import com.poco.aistudio.ui.theme.PocoYellow
import com.poco.aistudio.ui.theme.SurfaceDark
import com.poco.aistudio.ui.theme.SurfaceElevated
import com.poco.aistudio.ui.theme.SurfaceOutline
import com.poco.aistudio.ui.theme.TextPrimary
import com.poco.aistudio.ui.theme.TextSecondary

@Composable
fun LlmScreen(
    viewModel: LlmViewModel,
    onOpenModelHub: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledModels()
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .padding(horizontal = 12.dp)
    ) {
        // Model selector + tok/s + settings bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(
                    onClick = {
                        if (state.installedModels.isEmpty()) onOpenModelHub()
                        else showModelMenu = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (state.isLoadingModel) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = PocoYellow
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = state.selectedModel?.name ?: "Скачать GGUF-модель",
                        maxLines = 1,
                        color = PocoYellow,
                        fontSize = 13.sp
                    )
                }
                DropdownMenu(
                    expanded = showModelMenu,
                    onDismissRequest = { showModelMenu = false }
                ) {
                    state.installedModels.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Text("${m.name} (${"%.2f".format(m.sizeBytes / 1e9)} ГБ)")
                            },
                            onClick = {
                                showModelMenu = false
                                viewModel.loadModel(m)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Открыть Model Hub...", color = ElectricCyan) },
                        leadingIcon = { Icon(Icons.Default.Download, null, tint = ElectricCyan) },
                        onClick = {
                            showModelMenu = false
                            onOpenModelHub()
                        }
                    )
                }
            }

            if (state.liveTokPerSec > 0.1f) {
                Text(
                    text = "${"%.1f".format(state.liveTokPerSec)} tok/s",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = EmeraldGreen,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceElevated)
                        .border(1.dp, EmeraldGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            IconButton(onClick = { showSettingsDialog = true }) {
                Icon(Icons.Default.Tune, contentDescription = "Настройки LLM", tint = TextPrimary)
            }
            IconButton(onClick = { viewModel.clearChat() }) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Очистить чат", tint = TextSecondary)
            }
        }

        state.statusMessage?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Chat messages
        if (state.messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Оффлайн LLM • llama.cpp + KleidiAI",
                        color = PocoYellow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Контекст: ${state.contextSize} • Temp: ${"%.2f".format(state.temperature)} • 4 Big Cores",
                        color = TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    val bg = if (msg.isUser) Color(0xFF1A1912) else SurfaceDark
                    val borderColor = if (msg.isUser) PocoYellow.copy(alpha = 0.35f) else SurfaceOutline
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(bg)
                            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (msg.isUser) "ВЫ" else (state.selectedModel?.name ?: "LLM"),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (msg.isUser) PocoYellow else ElectricCyan
                            )
                            if (!msg.isUser && msg.tokPerSec > 0.1f) {
                                Text(
                                    text = "${"%.1f".format(msg.tokPerSec)} tok/s",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldGreen
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (msg.isUser) {
                            Text(text = msg.text, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            MarkdownMessageContent(text = msg.text.ifEmpty { "▍" })
                        }
                    }
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Напиши запрос или задачу по коду...", color = TextSecondary) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PocoYellow,
                    unfocusedBorderColor = SurfaceOutline,
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark
                )
            )
            Button(
                onClick = {
                    if (state.isGenerating) {
                        viewModel.stopGeneration()
                    } else if (inputText.isNotBlank()) {
                        val toSend = inputText
                        inputText = ""
                        viewModel.sendPrompt(toSend)
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isGenerating) Color(0xFFFF5252) else PocoYellow,
                    contentColor = Color.Black
                ),
                contentPadding = PaddingValues(14.dp)
            ) {
                Icon(
                    imageVector = if (state.isGenerating) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    contentDescription = if (state.isGenerating) "Стоп" else "Отправить"
                )
            }
        }
    }

    if (showSettingsDialog) {
        LlmSettingsDialog(
            initialCtx = state.contextSize,
            initialTemp = state.temperature,
            initialMaxTokens = state.maxTokens,
            initialSystemPrompt = state.systemPrompt,
            onDismiss = { showSettingsDialog = false },
            onSave = { ctx, temp, maxTok, sys ->
                viewModel.updateSettings(ctx, temp, maxTok, sys)
                showSettingsDialog = false
            }
        )
    }
}

@Composable
private fun LlmSettingsDialog(
    initialCtx: Int,
    initialTemp: Float,
    initialMaxTokens: Int,
    initialSystemPrompt: String,
    onDismiss: () -> Unit,
    onSave: (Int, Float, Int, String) -> Unit
) {
    var ctx by remember { mutableIntStateOf(initialCtx) }
    var temp by remember { mutableFloatStateOf(initialTemp) }
    var maxTok by remember { mutableIntStateOf(initialMaxTokens) }
    var sysPrompt by remember { mutableStateOf(initialSystemPrompt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceElevated,
        title = { Text("Параметры LLM", color = PocoYellow) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Контекст (n_ctx): $ctx токенов", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = ctx.toFloat(),
                    onValueChange = { ctx = (it.toInt() / 512) * 512 },
                    valueRange = 512f..8192f,
                    colors = SliderDefaults.colors(thumbColor = PocoYellow, activeTrackColor = PocoYellow)
                )

                Text("Температура: ${"%.2f".format(temp)}", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = temp,
                    onValueChange = { temp = it },
                    valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
                )

                Text("Макс. токенов ответа: $maxTok", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = maxTok.toFloat(),
                    onValueChange = { maxTok = (it.toInt() / 128) * 128 },
                    valueRange = 128f..4096f,
                    colors = SliderDefaults.colors(thumbColor = EmeraldGreen, activeTrackColor = EmeraldGreen)
                )

                OutlinedTextField(
                    value = sysPrompt,
                    onValueChange = { sysPrompt = it },
                    label = { Text("Системный промпт") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(ctx, temp, maxTok, sysPrompt) },
                colors = ButtonDefaults.buttonColors(containerColor = PocoYellow, contentColor = Color.Black)
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена", color = TextSecondary) }
        }
    )
}

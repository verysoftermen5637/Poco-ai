package com.poco.aistudio.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.poco.aistudio.ui.theme.AmoledBlack
import com.poco.aistudio.ui.theme.CoralError
import com.poco.aistudio.ui.theme.ElectricCyan
import com.poco.aistudio.ui.theme.EmeraldGreen
import com.poco.aistudio.ui.theme.PocoYellow
import com.poco.aistudio.ui.theme.SurfaceDark
import com.poco.aistudio.ui.theme.SurfaceElevated
import com.poco.aistudio.ui.theme.SurfaceOutline
import com.poco.aistudio.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioScreen(
    viewModel: AudioViewModel,
    onOpenModelHub: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showModelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledModels()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Model selector
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    if (state.installedModels.isEmpty()) onOpenModelHub()
                    else showModelMenu = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = state.selectedModel?.name ?: "Скачать MusicGen-Small / Stereo",
                    color = PocoYellow
                )
            }
            DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                state.installedModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.name) },
                        onClick = {
                            showModelMenu = false
                            viewModel.selectModel(m)
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

        // Waveform player card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, SurfaceOutline, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Candle MusicGen + EnCodec • 32 кГц PCM WAV",
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )

            WaveformVisualizer(
                peaks = state.waveformPeaks,
                progress = state.playbackProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            )

            if (state.generatedWavPath != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { viewModel.togglePlayback() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.padding(4.dp))
                        Text(if (state.isPlaying) "Пауза" else "Слушать")
                    }

                    OutlinedButton(
                        onClick = { viewModel.exportWavToMusic() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null, tint = EmeraldGreen)
                        Spacer(Modifier.padding(4.dp))
                        Text("В Music (.wav)", color = EmeraldGreen)
                    }
                }
            }
        }

        if (state.isGenerating) {
            val frac = (state.currentStep.toFloat() / state.totalSteps.coerceAtLeast(1).toFloat())
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = ElectricCyan,
                trackColor = SurfaceElevated
            )
        }

        state.statusMessage?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
        }

        // Quick style presets
        val quickPrompts = listOf(
            "Lo-Fi Chill Beat" to "relaxing lo-fi hip-hop beat, warm electric piano chords, vinyl crackle, 85 bpm",
            "Cyberpunk Phonk" to "aggressive dark drift phonk cowbell melody, distorted 808 bass, 140 bpm",
            "Cinematic Synth" to "epic sci-fi cinematic soundtrack with deep brass and pulsing analog synths",
            "Rain & Thunder SFX" to "heavy midnight rainstorm on window glass with distant rolling thunder, ambient sfx"
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            quickPrompts.forEach { (label, fullPrompt) ->
                AssistChip(
                    onClick = { viewModel.updatePrompt(fullPrompt) },
                    label = { Text(label, fontFamily = FontFamily.Monospace) }
                )
            }
        }

        OutlinedTextField(
            value = state.prompt,
            onValueChange = { viewModel.updatePrompt(it) },
            label = { Text("Описание трека, бита или SFX (лучше на англ.)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = SurfaceOutline
            )
        )

        // Duration & Guidance controls
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .border(1.dp, SurfaceOutline, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "Длительность: ${state.durationSec.toInt()} сек (5–20 с)",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = state.durationSec,
                onValueChange = { viewModel.updateDuration(it) },
                valueRange = 5f..20f,
                steps = 14,
                colors = SliderDefaults.colors(thumbColor = PocoYellow, activeTrackColor = PocoYellow)
            )

            Text(
                text = "Guidance Scale (CFG): ${"%.1f".format(state.guidanceScale)}",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = state.guidanceScale,
                onValueChange = { viewModel.updateGuidance(it) },
                valueRange = 1.0f..6.0f,
                colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
            )
        }

        Button(
            onClick = {
                if (state.isGenerating) viewModel.cancelGeneration() else viewModel.generateAudio()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.isGenerating) CoralError else PocoYellow,
                contentColor = Color.Black
            )
        ) {
            Icon(
                imageVector = if (state.isGenerating) Icons.Default.Stop else Icons.Default.GraphicEq,
                contentDescription = null
            )
            Spacer(Modifier.padding(4.dp))
            Text(if (state.isGenerating) "Остановить генерацию" else "Сгенерировать аудио")
        }
    }
}

@Composable
private fun WaveformVisualizer(
    peaks: List<Float>,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val displayPeaks = remember(peaks) {
        if (peaks.isNotEmpty()) peaks
        else List(64) { i -> 0.12f + 0.08f * kotlin.math.sin(i * 0.4f).toFloat().let { kotlin.math.abs(it) } }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF07070B))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barCount = displayPeaks.size.coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(2f)
            val centerY = size.height / 2f

            displayPeaks.forEachIndexed { idx, amp ->
                val ratio = idx.toFloat() / barCount.toFloat()
                val isPlayed = peaks.isNotEmpty() && ratio <= progress
                val barHeight = (amp.coerceIn(0.08f, 1f) * size.height).coerceAtLeast(4f)
                val x = idx * (barWidth + gap)
                val y = centerY - barHeight / 2f

                drawRoundRect(
                    color = when {
                        peaks.isEmpty() -> TextSecondary.copy(alpha = 0.25f)
                        isPlayed -> PocoYellow
                        else -> ElectricCyan.copy(alpha = 0.65f)
                    },
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}

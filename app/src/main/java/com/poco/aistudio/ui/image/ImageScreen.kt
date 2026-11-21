package com.poco.aistudio.ui.image

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.poco.aistudio.ui.theme.AmoledBlack
import com.poco.aistudio.ui.theme.ElectricCyan
import com.poco.aistudio.ui.theme.EmeraldGreen
import com.poco.aistudio.ui.theme.PocoYellow
import com.poco.aistudio.ui.theme.SurfaceDark
import com.poco.aistudio.ui.theme.SurfaceElevated
import com.poco.aistudio.ui.theme.SurfaceOutline
import com.poco.aistudio.ui.theme.TextSecondary

@Composable
fun ImageScreen(
    viewModel: ImageViewModel,
    onOpenModelHub: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var showModelMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshInstalledModels()
    }

    val previewBitmap = remember(state.generatedPngPath) {
        state.generatedPngPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
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
                    text = state.selectedModel?.name ?: "Скачать SDXL-Turbo / SD-1.5-LCM",
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

        // Image preview box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(
                    if (state.resolution == ImageResolution.SQUARE_512) 1f else 512f / 768f
                )
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceDark)
                .border(1.dp, SurfaceOutline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap,
                    contentDescription = "Сгенерированное изображение",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = "stable-diffusion.cpp • CPU KleidiAI\n${state.resolution.label} • ${state.steps} шагов",
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
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
                color = PocoYellow,
                trackColor = SurfaceElevated
            )
        }

        state.statusMessage?.let {
            Text(text = it, style = MaterialTheme.typography.labelMedium, color = ElectricCyan)
        }

        if (previewBitmap != null && !state.isGenerating) {
            OutlinedButton(
                onClick = { viewModel.saveToGallery() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.SaveAlt, contentDescription = null, tint = EmeraldGreen)
                Spacer(Modifier.padding(4.dp))
                Text("Сохранить в Галерею (Pictures/PocoAIStudio)", color = EmeraldGreen)
            }
        }

        // Prompts
        OutlinedTextField(
            value = state.prompt,
            onValueChange = { viewModel.updatePrompt(it) },
            label = { Text("Промпт") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PocoYellow,
                unfocusedBorderColor = SurfaceOutline
            )
        )

        OutlinedTextField(
            value = state.negativePrompt,
            onValueChange = { viewModel.updateNegativePrompt(it) },
            label = { Text("Negative-промпт") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ElectricCyan,
                unfocusedBorderColor = SurfaceOutline
            )
        )

        // Resolution chips
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ImageResolution.entries.forEach { res ->
                FilterChip(
                    selected = state.resolution == res,
                    onClick = { viewModel.updateResolution(res) },
                    label = { Text(res.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PocoYellow,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        // Steps & CFG sliders
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark)
                .border(1.dp, SurfaceOutline, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Шаги (Steps): ${state.steps}  (рекомендуется 4 для Turbo/LCM)",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = state.steps.toFloat(),
                onValueChange = { viewModel.updateSteps(it.toInt()) },
                valueRange = 1f..20f,
                colors = SliderDefaults.colors(thumbColor = PocoYellow, activeTrackColor = PocoYellow)
            )

            Text(
                text = "CFG Scale: ${"%.1f".format(state.cfgScale)}",
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = state.cfgScale,
                onValueChange = { viewModel.updateCfg(it) },
                valueRange = 1.0f..9.0f,
                colors = SliderDefaults.colors(thumbColor = ElectricCyan, activeTrackColor = ElectricCyan)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = if (state.seed < 0) "" else state.seed.toString(),
                    onValueChange = { txt ->
                        val parsed = txt.toLongOrNull() ?: -1L
                        viewModel.updateSeed(parsed)
                    },
                    label = { Text("Seed (-1 = случайный)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.randomizeSeed() }) {
                    Icon(Icons.Default.Casino, contentDescription = "Случайный Seed", tint = PocoYellow)
                }
            }
        }

        Button(
            onClick = { viewModel.generateImage() },
            enabled = !state.isGenerating,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PocoYellow, contentColor = Color.Black)
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.padding(4.dp))
            Text(if (state.isGenerating) "Генерация..." else "Сгенерировать изображение")
        }
    }
}

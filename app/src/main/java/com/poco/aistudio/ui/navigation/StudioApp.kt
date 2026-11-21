package com.poco.aistudio.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.poco.aistudio.nativebridge.ActiveEngineType
import com.poco.aistudio.nativebridge.NativeAiBridge
import com.poco.aistudio.ui.audio.AudioScreen
import com.poco.aistudio.ui.audio.AudioViewModel
import com.poco.aistudio.ui.hub.ModelHubScreen
import com.poco.aistudio.ui.image.ImageScreen
import com.poco.aistudio.ui.image.ImageViewModel
import com.poco.aistudio.ui.llm.LlmScreen
import com.poco.aistudio.ui.llm.LlmViewModel
import com.poco.aistudio.ui.theme.AmoledBlack
import com.poco.aistudio.ui.theme.ElectricCyan
import com.poco.aistudio.ui.theme.EmeraldGreen
import com.poco.aistudio.ui.theme.PocoYellow
import com.poco.aistudio.ui.theme.SurfaceDark
import com.poco.aistudio.ui.theme.SurfaceElevated
import com.poco.aistudio.ui.theme.SurfaceOutline
import com.poco.aistudio.ui.theme.TextPrimary
import com.poco.aistudio.ui.theme.TextSecondary
import com.poco.aistudio.util.CpuRamMonitor
import kotlinx.coroutines.delay

enum class StudioTab(val label: String, val icon: ImageVector) {
    LLM("Текст и Код", Icons.Default.Code),
    IMAGE("Фото", Icons.Default.Image),
    AUDIO("Музыка", Icons.Default.MusicNote)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp() {
    val context = LocalContext.current
    val llmVm: LlmViewModel = viewModel()
    val imageVm: ImageViewModel = viewModel()
    val audioVm: AudioViewModel = viewModel()

    var currentTab by remember { mutableStateOf(StudioTab.LLM) }
    var showModelHub by remember { mutableStateOf(false) }

    val activeModel by NativeAiBridge.activeModel.collectAsState()
    var hwStats by remember { mutableStateOf(CpuRamMonitor.readStats(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            hwStats = CpuRamMonitor.readStats(context)
            delay(3000L)
        }
    }

    if (showModelHub) {
        ModelHubScreen(
            onBack = { showModelHub = false },
            onModelsChanged = {
                llmVm.refreshInstalledModels()
                imageVm.refreshInstalledModels()
                audioVm.refreshInstalledModels()
            }
        )
        return
    }

    Scaffold(
        containerColor = AmoledBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AmoledBlack,
                    titleContentColor = TextPrimary
                ),
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "POCO AI Studio",
                                fontWeight = FontWeight.ExtraBold,
                                color = PocoYellow,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "D9500s",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = ElectricCyan,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceElevated)
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "${hwStats.perfCoreSummary} • ОЗУ: ${"%.1f".format(hwStats.availRamGb)} / ${"%.0f".format(hwStats.totalRamGb)} ГБ своб.",
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 10.5.sp,
                            color = TextSecondary
                        )
                    }
                },
                actions = {
                    if (activeModel.engine != ActiveEngineType.NONE) {
                        IconButton(onClick = { NativeAiBridge.unloadAllModels() }) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = "Выгрузить модель из ОЗУ",
                                tint = EmeraldGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    IconButton(onClick = { showModelHub = true }) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = "Model Hub",
                            tint = PocoYellow
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                contentColor = TextPrimary
            ) {
                StudioTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.Black,
                            selectedTextColor = PocoYellow,
                            indicatorColor = PocoYellow,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                StudioTab.LLM -> LlmScreen(
                    viewModel = llmVm,
                    onOpenModelHub = { showModelHub = true }
                )
                StudioTab.IMAGE -> ImageScreen(
                    viewModel = imageVm,
                    onOpenModelHub = { showModelHub = true }
                )
                StudioTab.AUDIO -> AudioScreen(
                    viewModel = audioVm,
                    onOpenModelHub = { showModelHub = true }
                )
            }
        }
    }
}

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private enum class StudioSectionTab(val label: String) {
    COLORS("Cores"),
    STYLE("Estilo"),
    LAYOUT("Barra & Hoje")
}

/**
 * Interactive Theme Studio bottom sheet usable from MoreHub, Home palette shortcut,
 * and Command Palette (`> tema`). Updates persist immediately via [StudyThemeStore]
 * unless a custom [onConfigChange] handler is supplied.
 */
@Composable
fun ThemeStudioSheet(
    onDismiss: () -> Unit,
    isTimerRunning: Boolean = false,
    config: StudyThemeConfig = rememberStudyThemeConfig(),
    onConfigChange: ((StudyThemeConfig) -> Unit)? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val applyConfig: (StudyThemeConfig) -> Unit = { next ->
        val normalized = next.normalized()
        if (onConfigChange != null) {
            onConfigChange(normalized)
        } else {
            StudyThemeStore.save(context, normalized)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = 680.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        ThemeStudioContent(
            config = config,
            isTimerRunning = isTimerRunning,
            onApplyConfig = applyConfig,
            onReset = {
                val resetConfig = StudyThemeConfig()
                if (onConfigChange != null) {
                    onConfigChange(resetConfig)
                } else {
                    StudyThemeStore.reset(context)
                }
                Toast.makeText(context, "Tema restaurado para o padrão.", Toast.LENGTH_SHORT).show()
            },
            onDismiss = onDismiss
        )
    }
}

/**
 * Compatibility entry point delegating to [ThemeStudioSheet].
 */
@Composable
fun ThemeStudioDialog(
    onDismiss: () -> Unit,
    isTimerRunning: Boolean = false,
    config: StudyThemeConfig = rememberStudyThemeConfig(),
    onConfigChange: ((StudyThemeConfig) -> Unit)? = null
) {
    ThemeStudioSheet(
        onDismiss = onDismiss,
        isTimerRunning = isTimerRunning,
        config = config,
        onConfigChange = onConfigChange
    )
}

@Composable
private fun ThemeStudioContent(
    config: StudyThemeConfig,
    isTimerRunning: Boolean,
    onApplyConfig: (StudyThemeConfig) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var activeTab by rememberSaveable { mutableStateOf(StudioSectionTab.COLORS) }
    var simulateTimerFocus by rememberSaveable { mutableStateOf(false) }

    val triggerHaptic: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        val pinPreviewAboveScroll = maxHeight >= 580.dp
        val horizontalPad = if (maxWidth < 361.dp) 14.dp else 18.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad)
        ) {
            // Top Studio Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Estúdio de Tema",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Personalize cores, leitura, densidade e atalhos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = {
                        triggerHaptic()
                        onReset()
                    }
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = "Restaurar tema padrão")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar estúdio")
                }
            }

            if (pinPreviewAboveScroll) {
                LiveThemePreviewCard(
                    config = config,
                    isTimerRunning = isTimerRunning || simulateTimerFocus,
                    simulateTimerFocus = simulateTimerFocus,
                    onToggleSimulateTimer = {
                        triggerHaptic()
                        simulateTimerFocus = !simulateTimerFocus
                    }
                )
                Spacer(Modifier.height(10.dp))
            }

            // Section Tabs
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                StudioSectionTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = activeTab == tab,
                        onClick = {
                            triggerHaptic()
                            activeTab = tab
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = StudioSectionTab.entries.size
                        ),
                        icon = {}
                    ) {
                        Text(
                            text = tab.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Scrollable Studio Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!pinPreviewAboveScroll) {
                    LiveThemePreviewCard(
                        config = config,
                        isTimerRunning = isTimerRunning || simulateTimerFocus,
                        simulateTimerFocus = simulateTimerFocus,
                        onToggleSimulateTimer = {
                            triggerHaptic()
                            simulateTimerFocus = !simulateTimerFocus
                        }
                    )
                }

                when (activeTab) {
                    StudioSectionTab.COLORS -> ColorsSection(
                        config = config,
                        onApplyConfig = onApplyConfig,
                        onHaptic = triggerHaptic
                    )
                    StudioSectionTab.STYLE -> StyleSection(
                        config = config,
                        onApplyConfig = onApplyConfig,
                        onHaptic = triggerHaptic
                    )
                    StudioSectionTab.LAYOUT -> LayoutAndCodeSection(
                        context = context,
                        config = config,
                        onApplyConfig = onApplyConfig,
                        onReset = onReset,
                        onHaptic = triggerHaptic
                    )
                }
            }
        }
    }
}

// ============================================================================
// Live Interactive Preview Card
// ============================================================================

@Composable
private fun LiveThemePreviewCard(
    config: StudyThemeConfig,
    isTimerRunning: Boolean,
    simulateTimerFocus: Boolean,
    onToggleSimulateTimer: () -> Unit
) {
    var previewCompleted by remember { mutableStateOf(false) }

    StudyDockTheme(
        config = config,
        isTimerRunning = isTimerRunning,
        animateColors = true
    ) {
        val scheme = MaterialTheme.colorScheme
        val semantic = LocalStudySemanticColors.current
        val effectiveSurface = config.effectiveBaseSurface(isTimerRunning)

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = studyCardShape(config.cornerStyle, isHero = true),
            color = scheme.background,
            border = BorderStroke(1.dp, scheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(StudyTokens.cardPadding(config.density)),
                verticalArrangement = Arrangement.spacedBy(if (config.density == StudyUiDensity.COMPACT) 8.dp else 10.dp)
            ) {
                // Header row inside preview
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (config.immersiveFocusTheme && isTimerRunning) {
                            "Prévia · Modo Foco AMOLED"
                        } else {
                            "Prévia · ${effectiveSurface.label}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = semantic.successContainer
                    ) {
                        Text(
                            text = if (previewCompleted) "Concluído ✓" else "Meta 85%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = semantic.onSuccessContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = semantic.streak.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "🔥 7d",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = semantic.streak,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                // Simulated study card honoring CardStyle + CornerStyle + Density
                StudyCard(
                    modifier = Modifier.fillMaxWidth(),
                    isHero = false
                ) {
                    val innerPad = StudyTokens.cardPadding(config.density)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(innerPad),
                        verticalArrangement = Arrangement.spacedBy(
                            if (config.density == StudyUiDensity.COMPACT) 6.dp else 8.dp
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "Sessão · Revisão Espaçada",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${config.cardStyle.label} · ${config.cornerStyle.label} · ${config.density.label}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = studyControlShape(config.cornerStyle),
                                color = scheme.tertiaryContainer
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = scheme.onTertiaryContainer,
                                    modifier = Modifier
                                        .padding(7.dp)
                                        .size(16.dp)
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { if (previewCompleted) 1f else 0.72f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = scheme.primary,
                            trackColor = scheme.surfaceContainerHighest
                        )

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { previewCompleted = !previewCompleted },
                                shape = studyControlShape(config.cornerStyle),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (previewCompleted) Icons.Default.Check else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (previewCompleted) "Feito" else "Iniciar",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                            Surface(
                                onClick = { previewCompleted = !previewCompleted },
                                shape = studyControlShape(config.cornerStyle),
                                color = scheme.secondaryContainer
                            ) {
                                Text(
                                    text = "24 cartões",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                )
                            }
                            if (config.immersiveFocusTheme) {
                                Surface(
                                    onClick = onToggleSimulateTimer,
                                    shape = studyControlShape(config.cornerStyle),
                                    color = if (simulateTimerFocus) scheme.primaryContainer else scheme.surfaceContainerHigh
                                ) {
                                    Text(
                                        text = if (simulateTimerFocus) "Foco ON" else "Simular Foco",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (simulateTimerFocus) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Tab 1: Base Surface Themes, Material You, 10 Presets, HSV + Hex Picker
// ============================================================================

@Composable
private fun ColorsSection(
    config: StudyThemeConfig,
    onApplyConfig: (StudyThemeConfig) -> Unit,
    onHaptic: () -> Unit
) {
    val context = LocalContext.current
    val supportsDynamicYou = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var customPickerExpanded by rememberSaveable {
        mutableStateOf(config.accentMode == StudyAccentMode.CUSTOM)
    }

    // 1. Base Surfaces (2-column grid, collapsing to 1-column on very narrow screens + huge font scale)
    StudioSectionHeader(
        title = "Superfície de leitura",
        subtitle = "Escolha a base de fundo e contraste dos cartões"
    )
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cols = if (maxWidth < 310.dp && fontScale >= 1.25f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudyBaseSurface.entries.chunked(cols).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { surfaceOption ->
                        val selected = config.baseSurface == surfaceOption
                        BaseSurfaceOptionCard(
                            surface = surfaceOption,
                            accentDotColor = MaterialTheme.colorScheme.primary,
                            selected = selected,
                            cornerStyle = config.cornerStyle,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onHaptic()
                                onApplyConfig(config.copy(baseSurface = surfaceOption))
                            }
                        )
                    }
                    if (rowItems.size < cols) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // 2. Material You (API 31+)
    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = supportsDynamicYou) {
                    onHaptic()
                    val nextMode = if (config.accentMode == StudyAccentMode.DYNAMIC_YOU) {
                        StudyAccentMode.PRESET
                    } else {
                        StudyAccentMode.DYNAMIC_YOU
                    }
                    onApplyConfig(config.copy(accentMode = nextMode))
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ColorLens,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = "Material You (Cores do sistema)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (supportsDynamicYou) {
                        "Adapta os destaques ao papel de parede mantendo sua superfície"
                    } else {
                        "Disponível no Android 12 ou superior"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = supportsDynamicYou && config.accentMode == StudyAccentMode.DYNAMIC_YOU,
                enabled = supportsDynamicYou,
                onCheckedChange = { enabled ->
                    onHaptic()
                    onApplyConfig(
                        config.copy(
                            accentMode = if (enabled) StudyAccentMode.DYNAMIC_YOU else StudyAccentMode.PRESET
                        )
                    )
                }
            )
        }
    }

    // 3. 10 Curated Accent Swatches
    val activePreset = StudyAccentPresets.byId(config.accentPresetId)
    val accentHeaderLabel = when (config.accentMode) {
        StudyAccentMode.DYNAMIC_YOU -> "Paleta: Material You"
        StudyAccentMode.CUSTOM -> "Cor personalizada: ${formatHexColor(config.customAccentArgb)}"
        StudyAccentMode.PRESET -> "Paleta: ${activePreset.label}"
    }
    StudioSectionHeader(
        title = "Cor de destaque",
        subtitle = accentHeaderLabel
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StudyAccentPresets.all.forEach { preset ->
            val selected = config.accentMode == StudyAccentMode.PRESET && config.accentPresetId == preset.id
            val swatchColor = if (config.baseSurface.isDark) preset.darkColor else preset.lightColor
            val checkTint = if (contrastRatio(swatchColor, Color.White) >= 4.0) Color.White else Color(0xFF141318)

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
                    .then(
                        if (selected) {
                            Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        }
                    )
                    .clickable {
                        onHaptic()
                        onApplyConfig(
                            config.copy(
                                accentMode = StudyAccentMode.PRESET,
                                accentPresetId = preset.id,
                                customAccentArgb = preset.seedColor.toArgb()
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = preset.label,
                        tint = checkTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // 4. Custom HSV + Hex Color Picker
    StudyCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = config.accentMode == StudyAccentMode.CUSTOM
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { customPickerExpanded = !customPickerExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(config.customAccentArgb or 0xFF000000.toInt()))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Editor HSV & Hex personalizado",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Qualquer cor com ajuste automático de contraste WCAG",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (customPickerExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = customPickerExpanded) {
                CustomHsvHexPicker(
                    context = context,
                    config = config,
                    onApplyCustomColor = { argb ->
                        onApplyConfig(
                            config.copy(
                                accentMode = StudyAccentMode.CUSTOM,
                                customAccentArgb = argb
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun BaseSurfaceOptionCard(
    surface: StudyBaseSurface,
    accentDotColor: Color,
    selected: Boolean,
    cornerStyle: StudyCornerStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = studyControlShape(cornerStyle),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 0.8.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Double mini-swatch showing background + card surface + primary dot (with check badge when selected)
            Box(modifier = Modifier.size(34.dp)) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(surface.previewBackground)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(surface.previewSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(accentDotColor)
                        )
                    }
                }
                if (selected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 3.dp, y = (-3).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selecionado",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = surface.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = surface.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CustomHsvHexPicker(
    context: Context,
    config: StudyThemeConfig,
    onApplyCustomColor: (Int) -> Unit
) {
    val semantic = LocalStudySemanticColors.current
    val initialColor = Color(config.customAccentArgb or 0xFF000000.toInt())
    val initialHsv = remember { colorToHsv(initialColor) }

    var lastEmittedArgb by remember { mutableIntStateOf(config.customAccentArgb or 0xFF000000.toInt()) }
    var hue by remember { mutableFloatStateOf(initialHsv.h) }
    var sat by remember { mutableFloatStateOf(initialHsv.s) }
    var value by remember { mutableFloatStateOf(initialHsv.v.coerceAtLeast(0.12f)) }
    var hexText by remember {
        mutableStateOf(formatHexColor(config.customAccentArgb, includeHash = false))
    }
    var hexError by remember { mutableStateOf<String?>(null) }

    // Sync from external changes (preset click, theme code import, reset) without clobbering slider drag state
    LaunchedEffect(config.customAccentArgb) {
        val normalizedArgb = config.customAccentArgb or 0xFF000000.toInt()
        if (normalizedArgb != lastEmittedArgb) {
            val externalHsv = colorToHsv(Color(normalizedArgb))
            if (externalHsv.s >= 0.02f) hue = externalHsv.h
            sat = externalHsv.s
            value = externalHsv.v.coerceAtLeast(0.08f)
            hexText = formatHexColor(normalizedArgb, includeHash = false)
            hexError = null
            lastEmittedArgb = normalizedArgb
        }
    }

    fun emitHsv(newH: Float, newS: Float, newV: Float) {
        hue = newH.coerceIn(0f, 360f)
        sat = newS.coerceIn(0f, 1f)
        value = newV.coerceIn(0.08f, 1f)
        val argb = hsvToColor(hue, sat, value).toArgb() or 0xFF000000.toInt()
        lastEmittedArgb = argb
        hexText = formatHexColor(argb, includeHash = false)
        hexError = null
        onApplyCustomColor(argb)
    }

    fun applyParsedHex(argb: Int) {
        val normalized = argb or 0xFF000000.toInt()
        val parsedHsv = colorToHsv(Color(normalized))
        if (parsedHsv.s >= 0.02f) hue = parsedHsv.h
        sat = parsedHsv.s
        value = parsedHsv.v.coerceAtLeast(0.08f)
        lastEmittedArgb = normalized
        hexError = null
        onApplyCustomColor(normalized)
    }

    val previewConfig = config.copy(
        accentMode = StudyAccentMode.CUSTOM,
        customAccentArgb = hsvToColor(hue, sat, value).toArgb()
    )
    val solvedScheme = remember(previewConfig, context) {
        buildStudyColorScheme(previewConfig, context, isTimerRunning = false)
    }
    val surfaceContrast = remember(solvedScheme) {
        contrastRatio(solvedScheme.primary, solvedScheme.surface)
    }
    val onContrast = remember(solvedScheme) {
        contrastRatio(solvedScheme.primary, solvedScheme.onPrimary)
    }
    val minContrast = minOf(surfaceContrast, onContrast)
    val wcagLabel = when {
        minContrast >= 7.0 -> "WCAG AAA"
        minContrast >= 4.5 -> "WCAG AA"
        else -> "WCAG AA Grande"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StudyHsvGradientSlider(
            label = "Matiz (Hue)",
            valueLabel = "${hue.roundToInt()}°",
            value = hue,
            valueRange = 0f..360f,
            gradientColors = listOf(
                Color.Red,
                Color.Yellow,
                Color.Green,
                Color.Cyan,
                Color.Blue,
                Color.Magenta,
                Color.Red
            ),
            thumbColor = hsvToColor(hue, 1f, 1f),
            onValueChange = { emitHsv(it, sat, value) }
        )

        StudyHsvGradientSlider(
            label = "Saturação",
            valueLabel = "${(sat * 100f).roundToInt()}%",
            value = sat,
            valueRange = 0f..1f,
            gradientColors = listOf(
                hsvToColor(hue, 0f, value),
                hsvToColor(hue, 1f, value)
            ),
            thumbColor = hsvToColor(hue, sat, value),
            onValueChange = { emitHsv(hue, it, value) }
        )

        StudyHsvGradientSlider(
            label = "Luminosidade",
            valueLabel = "${(value * 100f).roundToInt()}%",
            value = value,
            valueRange = 0.08f..1f,
            gradientColors = listOf(
                hsvToColor(hue, sat, 0.08f),
                hsvToColor(hue, sat, 1f)
            ),
            thumbColor = hsvToColor(hue, sat, value),
            onValueChange = { emitHsv(hue, sat, it) }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = hexText,
                onValueChange = { input ->
                    val filtered = input.uppercase(Locale.ROOT)
                        .removePrefix("#")
                        .filter { it in '0'..'9' || it in 'A'..'F' }
                        .take(6)
                    hexText = filtered
                    if (filtered.length == 6) {
                        val parsed = parseHexColorOrNull(filtered)
                        if (parsed != null) {
                            applyParsedHex(parsed)
                        } else {
                            hexError = "Hex inválido"
                        }
                    } else {
                        hexError = null
                    }
                },
                label = { Text("Código Hex") },
                prefix = { Text("#", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                isError = hexError != null,
                supportingText = hexError?.let { err -> { Text(err) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val parsed = parseHexColorOrNull(hexText)
                    if (parsed != null) {
                        applyParsedHex(parsed)
                    } else {
                        hexError = "Use 6 dígitos (ex.: 6156D9)"
                    }
                }),
                shape = studyControlShape(config.cornerStyle),
                modifier = Modifier.weight(1f)
            )

            Surface(
                shape = studyControlShape(config.cornerStyle),
                color = semantic.successContainer
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f:1", minContrast),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = semantic.onSuccessContainer
                    )
                    Text(
                        text = "$wcagLabel ✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.onSuccessContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun StudyHsvGradientSlider(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    gradientColors: List<Color>,
    thumbColor: Color,
    onValueChange: (Float) -> Unit
) {
    val latestOnChange by rememberUpdatedState(onValueChange)
    val latestRange by rememberUpdatedState(valueRange)

    fun mapOffsetToValue(x: Float, widthPx: Int): Float {
        val span = (latestRange.endInclusive - latestRange.start).coerceAtLeast(1e-4f)
        val fraction = (x / widthPx.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        return latestRange.start + fraction * span
    }

    val normalizedFraction = run {
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
        ((value - valueRange.start) / span).coerceIn(0f, 1f)
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .pointerInput(valueRange) {
                    detectTapGestures { offset ->
                        latestOnChange(mapOffsetToValue(offset.x, size.width))
                    }
                }
                .pointerInput(valueRange) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        latestOnChange(mapOffsetToValue(change.position.x, size.width))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val outlineColor = MaterialTheme.colorScheme.outlineVariant
            val thumbBorder = MaterialTheme.colorScheme.onSurface
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
            ) {
                val trackHeight = 14.dp.toPx()
                val trackTop = (size.height - trackHeight) / 2f
                val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                drawRoundRect(
                    brush = Brush.horizontalGradient(gradientColors),
                    topLeft = Offset(0f, trackTop),
                    size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = outlineColor,
                    topLeft = Offset(0f, trackTop),
                    size = androidx.compose.ui.geometry.Size(size.width, trackHeight),
                    cornerRadius = corner,
                    style = Stroke(width = 1.dp.toPx())
                )

                val thumbRadius = 10.dp.toPx()
                val usableWidth = (size.width - thumbRadius * 2f).coerceAtLeast(1f)
                val centerX = thumbRadius + usableWidth * normalizedFraction
                val center = Offset(centerX, size.height / 2f)

                drawCircle(
                    color = Color.White,
                    radius = thumbRadius,
                    center = center
                )
                drawCircle(
                    color = thumbColor,
                    radius = thumbRadius - 2.5.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = thumbBorder.copy(alpha = 0.45f),
                    radius = thumbRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
    }
}

// ============================================================================
// Tab 2: Card Style, Corner Radius, UI Density, Focus Auto-Immersive Theme
// ============================================================================

@Composable
private fun StyleSection(
    config: StudyThemeConfig,
    onApplyConfig: (StudyThemeConfig) -> Unit,
    onHaptic: () -> Unit
) {
    // 1. Card Style
    StudioSectionHeader(
        title = "Estilo dos cartões",
        subtitle = config.cardStyle.description
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        StudyCardStyle.entries.forEachIndexed { index, style ->
            SegmentedButton(
                selected = config.cardStyle == style,
                onClick = {
                    onHaptic()
                    onApplyConfig(config.copy(cardStyle = style))
                },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = StudyCardStyle.entries.size
                ),
                icon = {}
            ) {
                Text(style.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // 2. Corner Radius
    StudioSectionHeader(
        title = "Arredondamento",
        subtitle = "Raio dos cartões: ${config.cornerStyle.cardDp.value.roundToInt()} dp · Controles: ${config.cornerStyle.controlDp.value.roundToInt()} dp"
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        StudyCornerStyle.entries.forEachIndexed { index, corner ->
            SegmentedButton(
                selected = config.cornerStyle == corner,
                onClick = {
                    onHaptic()
                    onApplyConfig(config.copy(cornerStyle = corner))
                },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = StudyCornerStyle.entries.size
                ),
                icon = {}
            ) {
                Text(corner.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // 3. UI Density
    StudioSectionHeader(
        title = "Densidade da interface",
        subtitle = config.density.description
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        StudyUiDensity.entries.forEachIndexed { index, densityOption ->
            SegmentedButton(
                selected = config.density == densityOption,
                onClick = {
                    onHaptic()
                    onApplyConfig(config.copy(density = densityOption))
                },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = StudyUiDensity.entries.size
                ),
                icon = {}
            ) {
                Text(densityOption.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    // 4. Focus Auto-Immersive Theme
    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onHaptic()
                    onApplyConfig(config.copy(immersiveFocusTheme = !config.immersiveFocusTheme))
                }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.NightsStay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = "Modo imersivo no Timer",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Alterna automaticamente para AMOLED Preto enquanto o cronômetro estiver ativo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = config.immersiveFocusTheme,
                onCheckedChange = { enabled ->
                    onHaptic()
                    onApplyConfig(config.copy(immersiveFocusTheme = enabled))
                }
            )
        }
    }
}

// ============================================================================
// Tab 3: Bottom Nav 5-Slot Customizer, Home Dashboard Modules, SDTHEME Code
// ============================================================================

@Composable
private fun LayoutAndCodeSection(
    context: Context,
    config: StudyThemeConfig,
    onApplyConfig: (StudyThemeConfig) -> Unit,
    onReset: () -> Unit,
    onHaptic: () -> Unit
) {
    // 1. Bottom Nav 5-Slot Customizer
    StudioSectionHeader(
        title = "Barra inferior (5 atalhos)",
        subtitle = "Personalize os 3 atalhos centrais mantendo Hoje e Mais acessíveis"
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val navPresets = listOf(
            "Padrão" to StudyNavSlots.defaultIds,
            "Foco & Notas" to listOf(
                StudyNavSlots.SLOT_HOJE,
                StudyNavSlots.SLOT_TIMER,
                StudyNavSlots.SLOT_TAREFAS,
                StudyNavSlots.SLOT_NOTAS,
                StudyNavSlots.SLOT_MAIS
            ),
            "Anki & Livros" to listOf(
                StudyNavSlots.SLOT_HOJE,
                StudyNavSlots.SLOT_TIMER,
                StudyNavSlots.SLOT_CARTOES,
                StudyNavSlots.SLOT_LIVROS,
                StudyNavSlots.SLOT_MAIS
            )
        )
        navPresets.forEach { (presetLabel, presetSlots) ->
            FilterChip(
                selected = config.bottomNavSlots == presetSlots,
                onClick = {
                    onHaptic()
                    onApplyConfig(config.copy(bottomNavSlots = presetSlots))
                },
                label = { Text(presetLabel, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }

    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            config.bottomNavSlots.forEachIndexed { index, slotId ->
                val slot = StudyNavSlots.byId(slotId)
                var menuOpen by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(studyControlShape(config.cornerStyle))
                        .then(
                            if (slot.locked) Modifier
                            else Modifier.clickable { menuOpen = true }
                        )
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (slot.locked) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ) {
                        Icon(
                            imageVector = slot.icon,
                            contentDescription = null,
                            tint = if (slot.locked) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier
                                .padding(7.dp)
                                .size(16.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Espaço ${index + 1} · ${slot.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (slot.locked) {
                        Text(
                            text = "Fixo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Box {
                            FilledTonalButton(
                                onClick = { menuOpen = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Trocar", style = MaterialTheme.typography.labelSmall)
                                Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                StudyNavSlots.swappableMiddleOptions.forEach { candidate ->
                                    val currentAtOther = config.bottomNavSlots.indexOf(candidate.id)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = if (currentAtOther >= 0 && currentAtOther != index) {
                                                    "${candidate.label} (trocar posição)"
                                                } else {
                                                    candidate.label
                                                }
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(candidate.icon, null, modifier = Modifier.size(18.dp))
                                        },
                                        trailingIcon = if (candidate.id == slotId) {
                                            { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        onClick = {
                                            menuOpen = false
                                            onHaptic()
                                            val updated = config.bottomNavSlots.toMutableList()
                                            if (currentAtOther >= 0 && currentAtOther != index) {
                                                updated[currentAtOther] = updated[index]
                                            }
                                            updated[index] = candidate.id
                                            onApplyConfig(config.copy(bottomNavSlots = updated))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 2. Today Dashboard Modular Visibility & Order
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Módulos da aba Hoje",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Oculte ou reordene os cartões da tela inicial",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (config.hiddenDashboardWidgets.isNotEmpty() ||
            config.dashboardWidgetOrder != StudyDashboardWidgets.defaultOrder
        ) {
            TextButton(onClick = {
                onHaptic()
                onApplyConfig(
                    config.copy(
                        hiddenDashboardWidgets = emptySet(),
                        dashboardWidgetOrder = StudyDashboardWidgets.defaultOrder
                    )
                )
            }) {
                Text("Mostrar tudo")
            }
        }
    }

    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val orderedIds = config.dashboardWidgetOrder
            orderedIds.forEachIndexed { idx, widgetId ->
                val info = StudyDashboardWidgets.info(widgetId) ?: return@forEachIndexed
                val visible = config.isWidgetVisible(widgetId)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = visible,
                        onCheckedChange = { checked ->
                            onHaptic()
                            val nextHidden = config.hiddenDashboardWidgets.toMutableSet()
                            if (checked) nextHidden.remove(widgetId) else nextHidden.add(widgetId)
                            onApplyConfig(config.copy(hiddenDashboardWidgets = nextHidden))
                        }
                    )
                    Column(Modifier.weight(1f).padding(end = 4.dp)) {
                        Text(
                            text = info.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (visible) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = info.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            if (idx > 0) {
                                onHaptic()
                                val swapped = orderedIds.toMutableList()
                                val prev = swapped[idx - 1]
                                swapped[idx - 1] = swapped[idx]
                                swapped[idx] = prev
                                onApplyConfig(config.copy(dashboardWidgetOrder = swapped))
                            }
                        },
                        enabled = idx > 0,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowUp,
                            contentDescription = "Mover para cima",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            if (idx < orderedIds.lastIndex) {
                                onHaptic()
                                val swapped = orderedIds.toMutableList()
                                val next = swapped[idx + 1]
                                swapped[idx + 1] = swapped[idx]
                                swapped[idx] = next
                                onApplyConfig(config.copy(dashboardWidgetOrder = swapped))
                            }
                        },
                        enabled = idx < orderedIds.lastIndex,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Mover para baixo",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

    // 3. Shareable Theme Code (SDTHEME:v1:...)
    StudioSectionHeader(
        title = "Compartilhar tema (SDTHEME)",
        subtitle = "Exporte ou importe uma configuração completa com 1 toque"
    )

    val currentCode = remember(config) { encodeThemeCode(config) }
    var importCodeInput by remember { mutableStateOf("") }
    var importFeedback by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    StudyCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = studyControlShape(config.cornerStyle),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Text(
                    text = currentCode,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        onHaptic()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("StudyDock Theme", currentCode))
                        Toast.makeText(context, "Código de tema copiado!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    shape = studyControlShape(config.cornerStyle)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copiar código", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        onHaptic()
                        onReset()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    shape = studyControlShape(config.cornerStyle)
                ) {
                    Icon(Icons.Default.RestartAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Restaurar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = importCodeInput,
                onValueChange = {
                    importCodeInput = it
                    importFeedback = null
                },
                label = { Text("Colar código SDTHEME:...") },
                placeholder = { Text("SDTHEME:v1:...") },
                singleLine = true,
                shape = studyControlShape(config.cornerStyle),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clipText = clipboard?.primaryClip
                                ?.takeIf { it.itemCount > 0 }
                                ?.getItemAt(0)
                                ?.coerceToText(context)
                                ?.toString()
                                ?.trim()
                                .orEmpty()
                            if (clipText.isNotEmpty()) {
                                importCodeInput = clipText
                                importFeedback = null
                            } else {
                                importFeedback = false to "Área de transferência vazia."
                            }
                        }
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Colar da área de transferência")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val decoded = decodeThemeCode(importCodeInput)
                    if (decoded != null) {
                        onHaptic()
                        onApplyConfig(decoded)
                        importFeedback = true to "Tema aplicado com sucesso!"
                    } else {
                        importFeedback = false to "Código SDTHEME inválido."
                    }
                }),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val decoded = decodeThemeCode(importCodeInput)
                    if (decoded != null) {
                        onHaptic()
                        onApplyConfig(decoded)
                        importFeedback = true to "Tema aplicado com sucesso!"
                    } else {
                        importFeedback = false to "Código SDTHEME inválido. Confira o texto colado."
                    }
                },
                enabled = importCodeInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = studyControlShape(config.cornerStyle)
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Aplicar código de tema")
            }

            importFeedback?.let { (ok, msg) ->
                val semantic = LocalStudySemanticColors.current
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) semantic.success else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun StudioSectionHeader(
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

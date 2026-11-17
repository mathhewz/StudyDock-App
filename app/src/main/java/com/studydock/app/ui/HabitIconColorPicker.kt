@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.studydock.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checkroom
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.HistoryEdu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PhonelinkOff
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.Habit
import com.studydock.app.data.HabitIconCatalog
import java.text.Normalizer
import java.util.Locale

internal const val HABIT_EMOJI_ICON_KEY = "emoji"

internal fun habitColorOrDefault(rawColor: Long): Color =
    if (rawColor == 0L) Color(HabitIconCatalog.colors.first()) else Color(rawColor)

internal fun habitContrastContentColor(background: Color): Color =
    if (background.luminance() > 0.62f) Color(0xFF111827) else Color.White

internal fun isHabitEmojiMode(iconKey: String?, emoji: String?): Boolean {
    val cleanKey = iconKey?.trim().orEmpty()
    if (cleanKey == HABIT_EMOJI_ICON_KEY || cleanKey.isEmpty()) return true
    // Preserva hábitos criados antes da v28 que já tinham emoji personalizado e iconKey "default".
    return cleanKey == HabitIconCatalog.DEFAULT_KEY && !emoji.isNullOrBlank() && emoji != "✨"
}

internal fun habitIconVectorForKey(key: String?): ImageVector = when (key?.trim()) {
    "book" -> Icons.Rounded.AutoStories
    "write" -> Icons.Rounded.Draw
    "water" -> Icons.Rounded.WaterDrop
    "document" -> Icons.Rounded.Description
    "edit_tools" -> Icons.Rounded.DesignServices
    "stretch" -> Icons.Rounded.AccessibilityNew
    "leaf" -> Icons.Rounded.Eco
    "heart_break" -> Icons.Rounded.HeartBroken
    "workout" -> Icons.Rounded.FitnessCenter
    "meditate" -> Icons.Rounded.SelfImprovement
    "paint" -> Icons.Rounded.Brush
    "chat" -> Icons.AutoMirrored.Rounded.Chat
    "run" -> Icons.AutoMirrored.Rounded.DirectionsRun
    "sleep" -> Icons.Rounded.Bedtime
    "science" -> Icons.Rounded.Science
    "knit" -> Icons.Rounded.Checkroom
    "scroll" -> Icons.Rounded.HistoryEdu
    "food_clean" -> Icons.Rounded.Restaurant
    "brain" -> Icons.Rounded.Psychology
    "music" -> Icons.Rounded.MusicNote
    "code" -> Icons.Rounded.Code
    "timer" -> Icons.Rounded.Timer
    "sun" -> Icons.Rounded.WbSunny
    "moon" -> Icons.Rounded.NightsStay
    "no_phone" -> Icons.Rounded.PhonelinkOff
    else -> Icons.Rounded.AutoAwesome
}

/** Badge visual unificado do hábito (renderiza ícone vetorial ou emoji com a cor do hábito). */
@Composable
internal fun HabitVisualBadge(
    habit: Habit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    cornerRadius: Dp = 12.dp,
    solidFill: Boolean = false
) {
    HabitVisualPreviewBadge(
        iconKey = habit.iconKey,
        emoji = habit.emoji,
        color = habit.color,
        modifier = modifier,
        size = size,
        cornerRadius = cornerRadius,
        solidFill = solidFill
    )
}

@Composable
internal fun HabitVisualPreviewBadge(
    iconKey: String,
    emoji: String,
    color: Long,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    cornerRadius: Dp = 12.dp,
    solidFill: Boolean = false
) {
    val accent = habitColorOrDefault(color)
    val bgColor = if (solidFill) accent else accent.copy(alpha = 0.16f)
    val fgColor = if (solidFill) habitContrastContentColor(accent) else accent
    val border = if (solidFill) null else BorderStroke(1.dp, accent.copy(alpha = 0.34f))
    val useEmoji = isHabitEmojiMode(iconKey, emoji)

    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(cornerRadius),
        color = bgColor,
        border = border
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (useEmoji) {
                Text(
                    text = emoji.ifBlank { "✨" },
                    fontSize = (size.value * 0.46f).sp,
                    textAlign = TextAlign.Center
                )
            } else {
                Icon(
                    imageVector = habitIconVectorForKey(iconKey),
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(size * 0.54f)
                )
            }
        }
    }
}

private data class HabitEmojiSpec(
    val emoji: String,
    val label: String,
    val keywords: List<String>
)

private val habitEmojiCatalog: List<HabitEmojiSpec> = listOf(
    HabitEmojiSpec("✨", "Brilho", listOf("padrao", "habito", "brilho", "meta")),
    HabitEmojiSpec("📚", "Livros", listOf("livro", "estudo", "estudar", "leitura", "ler")),
    HabitEmojiSpec("📖", "Leitura", listOf("ler", "capitulo", "pagina", "livro")),
    HabitEmojiSpec("✍️", "Escrever", listOf("escrever", "redacao", "diario", "anotar")),
    HabitEmojiSpec("🧠", "Foco", listOf("cerebro", "mente", "memoria", "foco", "aprender")),
    HabitEmojiSpec("🎯", "Objetivo", listOf("alvo", "meta", "objetivo", "foco")),
    HabitEmojiSpec("💡", "Ideia", listOf("ideia", "criatividade", "insight")),
    HabitEmojiSpec("🧪", "Ciências", listOf("quimica", "ciencia", "fisica", "lab")),
    HabitEmojiSpec("📐", "Matemática", listOf("matematica", "geometria", "calculo", "exatas")),
    HabitEmojiSpec("💻", "Programar", listOf("codigo", "programacao", "computador", "dev")),
    HabitEmojiSpec("🎧", "Áudio", listOf("musica", "podcast", "audiobook", "ouvir")),
    HabitEmojiSpec("📝", "Notas", listOf("nota", "resumo", "revisao", "caderno")),
    HabitEmojiSpec("📅", "Agenda", listOf("planejamento", "dia", "rotina", "calendario")),
    HabitEmojiSpec("⏱️", "Pomodoro", listOf("tempo", "cronometro", "pomodoro", "timer")),
    HabitEmojiSpec("💧", "Água", listOf("agua", "hidratacao", "beber", "saude")),
    HabitEmojiSpec("🏃", "Correr", listOf("corrida", "correr", "cardio", "exercicio")),
    HabitEmojiSpec("🏋️", "Treino", listOf("academia", "musculacao", "forca", "peso")),
    HabitEmojiSpec("🧘", "Meditar", listOf("meditacao", "respirar", "calma", "yoga")),
    HabitEmojiSpec("🚶", "Caminhar", listOf("caminhada", "passos", "andar")),
    HabitEmojiSpec("🚴", "Pedalar", listOf("bicicleta", "ciclismo", "bike")),
    HabitEmojiSpec("🤸", "Alongar", listOf("alongamento", "mobilidade", "corpo")),
    HabitEmojiSpec("🥗", "Alimentação", listOf("salada", "dieta", "saudavel", "comida")),
    HabitEmojiSpec("🍎", "Fruta", listOf("maca", "fruta", "lanche", "nutricao")),
    HabitEmojiSpec("☕", "Café", listOf("cafe", "manha", "energia")),
    HabitEmojiSpec("💤", "Sono", listOf("dormir", "sono", "descanso", "noite")),
    HabitEmojiSpec("☀️", "Manhã", listOf("sol", "acordar", "cedo", "manha")),
    HabitEmojiSpec("🌙", "Noite", listOf("lua", "noite", "dormir", "rotina")),
    HabitEmojiSpec("🪥", "Higiene", listOf("escovar", "dentes", "higiene")),
    HabitEmojiSpec("💊", "Saúde", listOf("vitamina", "remedio", "suplemento")),
    HabitEmojiSpec("🧹", "Organizar", listOf("limpar", "quarto", "organizacao", "casa")),
    HabitEmojiSpec("🌱", "Crescer", listOf("planta", "natureza", "crescimento", "vida")),
    HabitEmojiSpec("🍃", "Bem-estar", listOf("folha", "natureza", "leve", "ar")),
    HabitEmojiSpec("🐶", "Pet", listOf("cachorro", "passear", "pet")),
    HabitEmojiSpec("🎸", "Violão", listOf("musica", "tocar", "instrumento", "guitarra")),
    HabitEmojiSpec("🎹", "Piano", listOf("musica", "teclado", "piano")),
    HabitEmojiSpec("🎨", "Arte", listOf("pintar", "desenho", "criatividade")),
    HabitEmojiSpec("🧶", "Artesanato", listOf("trico", "croche", "manual")),
    HabitEmojiSpec("📵", "Sem celular", listOf("celular", "detox", "tela", "redes", "parar")),
    HabitEmojiSpec("🚭", "Sem fumar", listOf("cigarro", "parar", "vicio", "saude")),
    HabitEmojiSpec("🍺", "Sem álcool", listOf("beber", "alcool", "cerveja", "parar")),
    HabitEmojiSpec("🍔", "Sem fast food", listOf("lanche", "doce", "acucar", "parar")),
    HabitEmojiSpec("💔", "Superar", listOf("recaida", "vicio", "parar", "controle")),
    HabitEmojiSpec("⏰", "Acordar cedo", listOf("despertador", "horario", "pontual")),
    HabitEmojiSpec("💰", "Finanças", listOf("dinheiro", "economizar", "poupar")),
    HabitEmojiSpec("🙏", "Gratidão", listOf("oracao", "gratidao", "fe", "espiritual")),
    HabitEmojiSpec("❤️", "Autocuidado", listOf("amor", "coracao", "familia", "saude")),
    HabitEmojiSpec("💬", "Social", listOf("conversar", "amigos", "familia", "falar")),
    HabitEmojiSpec("🔥", "Sequência", listOf("fogo", "energia", "disciplina", "streak")),
    HabitEmojiSpec("🏆", "Conquista", listOf("trofeu", "vitoria", "meta")),
    HabitEmojiSpec("🌿", "Chá / Calma", listOf("cha", "ervas", "relaxar"))
)

private fun normalizePickerSearch(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .trim()

private enum class HabitPickerTab(val label: String) {
    ICONS("Ícones"),
    EMOJIS("Emojis")
}

/**
 * Seletor modal estilo Habitify (`Ícone` ... `Concluído`) com busca PT-BR,
 * suporte dual a ícones vetoriais ([HabitIconCatalog]) e emojis, e paleta de cores inferior.
 */
@Composable
fun HabitIconColorPicker(
    selectedIconKey: String,
    selectedEmoji: String,
    selectedColor: Long,
    onConfirm: (iconKey: String, emoji: String, color: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialTab = remember(selectedIconKey, selectedEmoji) {
        if (isHabitEmojiMode(selectedIconKey, selectedEmoji)) HabitPickerTab.EMOJIS else HabitPickerTab.ICONS
    }
    var activeTab by remember { mutableStateOf(initialTab) }
    var draftIconKey by remember {
        mutableStateOf(
            if (selectedIconKey == HABIT_EMOJI_ICON_KEY || selectedIconKey.isBlank()) HabitIconCatalog.DEFAULT_KEY
            else selectedIconKey
        )
    }
    var draftEmoji by remember { mutableStateOf(selectedEmoji.ifBlank { "✨" }) }
    var draftColor by remember {
        mutableLongStateOf(if (selectedColor == 0L) HabitIconCatalog.colors.first() else selectedColor)
    }
    val primaryColors = remember { HabitIconCatalog.colors.take(6) }
    val extraColors = remember { HabitIconCatalog.colors.drop(6) }
    var query by remember { mutableStateOf("") }
    var showAllColors by remember { mutableStateOf(draftColor in extraColors) }

    val filteredIcons = remember(query) { HabitIconCatalog.search(query) }
    val filteredEmojis = remember(query) {
        val q = normalizePickerSearch(query)
        if (q.isEmpty()) habitEmojiCatalog
        else {
            val tokens = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
            habitEmojiCatalog.filter { spec ->
                val haystack = (listOf(spec.label, spec.emoji) + spec.keywords).map(::normalizePickerSearch)
                tokens.all { token -> haystack.any { it.contains(token) } }
            }
        }
    }

    val activeColor = habitColorOrDefault(draftColor)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .clickable(enabled = false) {}
                    .imePadding(),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                        )
                    }

                    // Cabeçalho: "Ícone" + seletor Ícones/Emojis + "Concluído"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Ícone",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                HabitPickerTab.entries.forEach { tab ->
                                    val selected = activeTab == tab
                                    Surface(
                                        onClick = { activeTab = tab },
                                        shape = RoundedCornerShape(9.dp),
                                        color = if (selected) activeColor else Color.Transparent
                                    ) {
                                        Text(
                                            text = tab.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (selected) habitContrastContentColor(activeColor) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                if (activeTab == HabitPickerTab.EMOJIS) {
                                    onConfirm(HABIT_EMOJI_ICON_KEY, draftEmoji.ifBlank { "✨" }, draftColor)
                                } else {
                                    val spec = HabitIconCatalog.findIcon(draftIconKey)
                                    onConfirm(spec.key, spec.fallbackEmoji, draftColor)
                                }
                            }
                        ) {
                            Text(
                                text = "Concluído",
                                color = activeColor,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Barra de busca estilizada (estilo imagem de referência)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = if (activeTab == HabitPickerTab.ICONS) "Buscar ícone..." else "Buscar emoji...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(activeColor),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (query.isNotEmpty()) {
                                IconButton(
                                    onClick = { query = "" },
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Limpar busca",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Grade 5 colunas de Ícones Vetoriais ou Emojis
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (activeTab == HabitPickerTab.ICONS) {
                            if (filteredIcons.isEmpty()) {
                                item(span = { GridItemSpan(5) }) {
                                    Text(
                                        text = "Nenhum ícone encontrado para \"$query\".",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 28.dp)
                                    )
                                }
                            } else {
                                items(filteredIcons, key = { it.key }) { spec ->
                                    val isSelected = spec.key == draftIconKey
                                    val tileBg = if (isSelected) activeColor else MaterialTheme.colorScheme.surfaceContainerHigh
                                    val iconTint = if (isSelected) habitContrastContentColor(activeColor) else MaterialTheme.colorScheme.onSurface
                                    Surface(
                                        onClick = {
                                            draftIconKey = spec.key
                                            draftEmoji = spec.fallbackEmoji
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        color = tileBg,
                                        modifier = Modifier.aspectRatio(1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = habitIconVectorForKey(spec.key),
                                                contentDescription = spec.label,
                                                tint = iconTint,
                                                modifier = Modifier.size(26.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            if (filteredEmojis.isEmpty()) {
                                item(span = { GridItemSpan(5) }) {
                                    Text(
                                        text = "Nenhum emoji encontrado para \"$query\".",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 18.dp)
                                    )
                                }
                            } else {
                                items(filteredEmojis, key = { it.emoji }) { spec ->
                                    val isSelected = spec.emoji == draftEmoji
                                    val tileBg = if (isSelected) activeColor else MaterialTheme.colorScheme.surfaceContainerHigh
                                    Surface(
                                        onClick = { draftEmoji = spec.emoji },
                                        shape = RoundedCornerShape(14.dp),
                                        color = tileBg,
                                        modifier = Modifier.aspectRatio(1f)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = spec.emoji,
                                                fontSize = 24.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                            item(span = { GridItemSpan(5) }) {
                                OutlinedTextField(
                                    value = draftEmoji,
                                    onValueChange = { input ->
                                        val picked = lastGrapheme(input)
                                        if (picked.isNotEmpty()) draftEmoji = picked
                                    },
                                    label = { Text("Ou digite qualquer emoji do teclado") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                    // Barra inferior de cores (estilo imagem 2: bolinhas coloridas + check branco circular + "...")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AnimatedVisibility(visible = showAllColors && extraColors.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                extraColors.take(6).forEach { colorHex ->
                                    ColorSwatchCircle(
                                        colorValue = colorHex,
                                        selected = draftColor == colorHex,
                                        onClick = { draftColor = colorHex }
                                    )
                                }
                                repeat((6 - extraColors.size.coerceAtMost(6)).coerceAtLeast(0)) {
                                    Spacer(Modifier.size(36.dp))
                                }
                                Spacer(Modifier.size(36.dp))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            primaryColors.forEach { colorHex ->
                                ColorSwatchCircle(
                                    colorValue = colorHex,
                                    selected = draftColor == colorHex,
                                    onClick = { draftColor = colorHex }
                                )
                            }
                            IconButton(
                                onClick = { showAllColors = !showAllColors },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreHoriz,
                                    contentDescription = if (showAllColors) "Recolher cores" else "Mais cores",
                                    tint = if (showAllColors) activeColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchCircle(
    colorValue: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    val swatch = Color(colorValue)
    val checkTint = habitContrastContentColor(swatch)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(swatch)
            .then(
                if (selected) Modifier.border(2.5.dp, Color.White, CircleShape)
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Cor selecionada",
                tint = checkTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

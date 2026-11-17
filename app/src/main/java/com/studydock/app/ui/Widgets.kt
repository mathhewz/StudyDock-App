@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.Habit
import java.text.BreakIterator

// ---------- Pomodoro ajustável ----------

data class PomodoroConfig(val focusMin: Int = 25, val shortMin: Int = 5, val longMin: Int = 15, val cycles: Int = 4) {
    fun sanitized() = PomodoroConfig(focusMin.coerceIn(1, 180), shortMin.coerceIn(1, 60), longMin.coerceIn(1, 120), cycles.coerceIn(2, 10))
}

@Composable
fun PomodoroSettingsDialog(config: PomodoroConfig, onDismiss: () -> Unit, onSave: (PomodoroConfig) -> Unit) {
    var current by remember { mutableStateOf(config) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustar Pomodoro", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "Clássico" to PomodoroConfig(25, 5, 15, 4),
                        "Foco profundo" to PomodoroConfig(50, 10, 30, 3),
                        "Curto" to PomodoroConfig(15, 3, 10, 4)
                    ).forEach { (label, preset) ->
                        FilterChip(
                            selected = current == preset,
                            onClick = { current = preset },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PomodoroNumberField("Foco", current.focusMin, "min", Modifier.weight(1f)) { current = current.copy(focusMin = it) }
                    PomodoroNumberField("Pausa curta", current.shortMin, "min", Modifier.weight(1f)) { current = current.copy(shortMin = it) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PomodoroNumberField("Pausa longa", current.longMin, "min", Modifier.weight(1f)) { current = current.copy(longMin = it) }
                    PomodoroNumberField("Ciclos p/ longa", current.cycles, "", Modifier.weight(1f)) { current = current.copy(cycles = it) }
                }
                val s = current.sanitized()
                Text(
                    "${s.focusMin} min de foco, pausa de ${s.shortMin} min e, a cada ${s.cycles} blocos, pausa de ${s.longMin} min.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(current.sanitized()) }) { Text("Salvar", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    )
}

@Composable
private fun PomodoroNumberField(label: String, value: Int, suffix: String, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(3)
            text.toIntOrNull()?.let(onValue)
        },
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = { if (suffix.isNotEmpty()) Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

// ---------- seletor de emoji ----------

private val emojiPalette = listOf(
    "📚", "📖", "✍️", "🧠", "🎯", "💡", "🧪",
    "📐", "🧮", "💻", "🎧", "🗂️", "📝", "📅",
    "💧", "🏃", "🏋️", "🧘", "🚶", "🚴", "🥗",
    "🍎", "💤", "☀️", "🌙", "🪥", "💊", "🧹",
    "🌱", "🐶", "🎸", "🎹", "🎨", "📵", "🚭",
    "🍺", "🧋", "⏰", "💰", "🙏", "❤️", "✨"
)

/** Último "caractere" (grafema) digitado, para o campo aceitar qualquer emoji do teclado. */
fun lastGrapheme(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(trimmed)
    val end = iterator.last()
    val start = iterator.previous()
    return if (start == BreakIterator.DONE || end == BreakIterator.DONE) trimmed else trimmed.substring(start, end)
}

@Composable
fun EmojiPicker(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        emojiPalette.distinct().chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    Surface(
                        onClick = { onSelect(option) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected == option) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Box(contentAlignment = Alignment.Center) { Text(option, style = MaterialTheme.typography.titleLarge) } }
                }
                repeat(6 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        OutlinedTextField(
            value = selected,
            onValueChange = { onSelect(lastGrapheme(it)) },
            label = { Text("Ou use o emoji do seu teclado") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun HabitEditDialog(habit: Habit, onDismiss: () -> Unit, onSave: (Habit) -> Unit) {
    var title by remember(habit.id) { mutableStateOf(habit.title) }
    var emoji by remember(habit.id) { mutableStateOf(habit.emoji) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar hábito", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Nome do hábito") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                EmojiPicker(emoji) { emoji = it }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(habit.copy(title = title.trim(), emoji = emoji.ifBlank { "✨" })) }, enabled = title.isNotBlank()) {
                Text("Salvar", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}

// ---------- hábitos de hoje (tela inicial) ----------

@Composable
fun HabitsTodayCard(habits: List<Habit>, completedIds: Set<Long>, onClick: () -> Unit) {
    val dow = java.time.LocalDate.now().dayOfWeek.value - 1
    val scheduled = habits.filter { (it.repeatDaysMask and (1 shl dow)) != 0 }
    if (scheduled.isEmpty()) return
    val done = scheduled.count { it.id in completedIds }
    val percent = done * 100 / scheduled.size
    val cardPad = StudyTokens.cardPadding()
    StudyCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.fillMaxWidth().padding(cardPad)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Hábitos de hoje",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("$done/${scheduled.size} · $percent%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { done.toFloat() / scheduled.size }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scheduled.forEach { habit ->
                    val isDone = habit.id in completedIds
                    Surface(
                        shape = studyControlShape(),
                        color = if (isDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            if (isDone) "${habit.emoji} ✓" else habit.emoji,
                            color = if (isDone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

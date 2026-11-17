@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil

private val TABS_PT_BR_LOCALE: Locale = Locale("pt", "BR")
private val TABS_SHORT_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", TABS_PT_BR_LOCALE)
private val TABS_FULL_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", TABS_PT_BR_LOCALE)

private enum class MistakeFilterMode(val label: String) {
    PENDING("Pendentes"),
    ALL("Todos"),
    RESOLVED("Resolvidos")
}

@Composable
internal fun SubjectSummaryTab(
    subject: Subject,
    overview: SubjectEcosystemOverview?,
    subjectColor: Color,
    period: Int,
    onPeriodChange: (Int) -> Unit,
    periodSessions: List<StudySession>,
    totalQuestions: Int,
    correctQuestions: Int,
    accuracy: Int,
    subjectFlashcards: List<Flashcard>,
    dueSubjectCards: List<Flashcard>,
    effectiveCardCount: Int,
    subjectTasks: List<StudyTask>,
    subjectMistakes: List<Mistake>,
    syllabusTopics: List<VerticalSyllabusTopic>,
    linkedNotes: List<LinkedNoteMatch>,
    subjectGoals: List<Goal>,
    now: Long,
    onOpenCards: () -> Unit,
    onReviewSubjectCards: ((List<Flashcard>) -> Unit)?,
    onSelectTab: (Int) -> Unit,
    childSubjects: List<Subject> = emptyList(),
    onOpenChildSubject: ((Long) -> Unit)? = null,
    onAddSubSubject: (() -> Unit)? = null
) {
    if (subject.parentId == null && (childSubjects.isNotEmpty() || onAddSubSubject != null)) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (childSubjects.isEmpty()) "Submatérias"
                        else "Submatérias (${childSubjects.size})",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (onAddSubSubject != null) {
                        TextButton(
                            onClick = onAddSubSubject,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.heightIn(min = 34.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Text(" Nova submatéria", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (childSubjects.isEmpty()) {
                    Text(
                        "Divida \"${subject.name}\" em frentes menores (ex.: Álgebra, Geometria, Termodinâmica).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    childSubjects.forEach { child ->
                        Surface(
                            onClick = { onOpenChildSubject?.invoke(child.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color(child.color.toInt()))
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        child.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val hint = child.nextStep?.trim().takeIf { !it.isNullOrEmpty() }
                                        ?: if (child.archived) "Arquivada" else "Abrir submatéria"
                                    Text(
                                        hint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val targetMin = if (subject.targetMinutesPerDay > 0) {
        subject.targetMinutesPerDay
    } else {
        overview?.recommendedMinutesPerDay ?: 0
    }
    val todaySec = overview?.todayFocusSeconds ?: 0L
    if (targetMin > 0) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Meta de hoje", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatMinutesBadge(todaySec)} / ${targetMin}m",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = subjectColor
                    )
                }
                LinearProgressIndicator(
                    progress = { (todaySec.toFloat() / (targetMin * 60f)).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = subjectColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }

    PeriodPicker(period, onPeriodChange)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        val totalDurationLabel = formatSubjectDuration(periodSessions.sumOf { it.durationSeconds })
        val sessionsCountLabel = periodSessions.size.toString()
        val activeDaysLabel = periodSessions.map { subjectDateKey(it.startedAt) }.distinct().size.toString()
        if (maxWidth < 340.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SubjectMetric("Tempo", totalDurationLabel, Modifier.weight(1f))
                    SubjectMetric("Sessões", sessionsCountLabel, Modifier.weight(1f))
                }
                SubjectMetric("Dias ativos", activeDaysLabel, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SubjectMetric("Tempo", totalDurationLabel, Modifier.weight(1f))
                SubjectMetric("Sessões", sessionsCountLabel, Modifier.weight(1f))
                SubjectMetric("Dias ativos", activeDaysLabel, Modifier.weight(1f))
            }
        }
    }
    SubjectStudyChart(periodSessions, period)

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (onReviewSubjectCards != null && subjectFlashcards.isNotEmpty()) {
            FilledTonalButton(
                onClick = {
                    onReviewSubjectCards(dueSubjectCards.ifEmpty { subjectFlashcards })
                }
            ) {
                Icon(Icons.Default.Style, null, modifier = Modifier.size(18.dp))
                Text(
                    if (dueSubjectCards.isNotEmpty()) " Revisar Anki (${dueSubjectCards.size})"
                    else " Praticar Anki (${subjectFlashcards.size})"
                )
            }
        }
        OutlinedButton(onClick = onOpenCards) {
            Text("Flashcards · $effectiveCardCount")
        }
    }

    Text(
        "Questões neste período",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccuracyRing(correctQuestions, totalQuestions, Modifier.size(82.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text("$accuracy% de acertos", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$correctQuestions certas de $totalQuestions questões",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    val pendingTasks = subjectTasks.count { !it.completed }
    val pendingMistakes = subjectMistakes.count { MistakeSpacedReview.isDue(it, now) }
    val masteredTopics = syllabusTopics.count { it.mastery == TopicMastery.MASTERED }
    val soon = subjectTasks.firstOrNull { !it.completed }

    Text(
        "Atalhos do ecossistema",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelectTab(1) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Checklist, null, tint = MaterialTheme.colorScheme.primary)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        "$pendingTasks tarefas · $masteredTopics/${syllabusTopics.size} tópicos dominados",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        soon?.title ?: "Edital verticalizado, metas e tarefas",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
        if (pendingMistakes > 0 || linkedNotes.isNotEmpty() || subjectGoals.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTab(if (pendingMistakes > 0) 2 else 4) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Psychology, null, tint = MaterialTheme.colorScheme.secondary)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(
                            "${linkedNotes.size} notas · ${subjectGoals.size} metas · $pendingMistakes erros p/ revisar",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (pendingMistakes > 0) "Toque para revisar erros no Caderno"
                            else "Toque para abrir a aba Conhecimento",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
internal fun SubjectBooksTab(
    subjectBooks: List<Book>,
    onOpenBooks: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Livros desta matéria", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Sua leitura fica ligada à disciplina.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpenBooks) {
            Icon(Icons.Default.Add, null)
            Text(" Livro")
        }
    }
    if (subjectBooks.isEmpty()) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                Text("Nenhum livro ligado ainda", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 7.dp))
                Text("Adicione um livro e escolha esta matéria no campo Matéria.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
            }
        }
    } else {
        subjectBooks.forEach { book ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable(onClick = onOpenBooks),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoStories, null, tint = MaterialTheme.colorScheme.primary)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 11.dp)
                    ) {
                        Text(book.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${book.currentPage} de ${book.totalPages ?: "—"} páginas · ${
                                when (book.status) {
                                    "DONE" -> "Lido"
                                    "WISHLIST" -> "Quero ler"
                                    else -> "Lendo"
                                }
                            }",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
private fun PeriodPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(7 to "Semana", 30 to "Mês", 90 to "3 meses", 365 to "Ano").forEach { (days, label) ->
            FilterChip(selected = selected == days, onClick = { onSelect(days) }, label = { Text(label) })
        }
    }
}

@Composable
private fun SubjectMetric(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(11.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun SubjectStudyChart(sessions: List<StudySession>, period: Int) {
    val count = when (period) {
        7 -> 7
        30 -> 10
        90 -> 9
        else -> 12
    }
    val (totals, maxValue) = remember(sessions, period) {
        val buckets = LongArray(count)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        for (session in sessions) {
            val sessionDate = Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
            val daysAgo = ChronoUnit.DAYS.between(sessionDate, today).toInt().coerceAtLeast(0)
            if (daysAgo < period) {
                val index = if (period == 7) {
                    6 - daysAgo
                } else {
                    (((period - 1 - daysAgo).toFloat() / period) * count).toInt().coerceIn(0, count - 1)
                }
                buckets[index] += session.durationSeconds
            }
        }
        buckets to (buckets.maxOrNull() ?: 0L).coerceAtLeast(1L)
    }
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    Text("Tempo estudado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 7.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            val barColor = LocalStudySemanticColors.current.info
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(130.dp)
            ) {
                val bottom = size.height - 5.dp.toPx()
                val gap = 5.dp.toPx()
                val barWidth = (size.width - gap * (count - 1)) / count
                drawLine(axisColor, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
                totals.forEachIndexed { index, value ->
                    val barHeight = (bottom * value.toFloat() / maxValue).coerceAtLeast(if (value > 0) 4.dp.toPx() else 1.dp.toPx())
                    drawRoundRect(
                        barColor,
                        Offset(index * (barWidth + gap), bottom - barHeight),
                        Size(barWidth.coerceAtLeast(1f), barHeight),
                        cornerRadius = CornerRadius(4.dp.toPx())
                    )
                }
            }
            Text(
                if (period == 7) "Cada barra é um dia"
                else "${if (period == 30) "Agrupado por alguns dias" else if (period == 90) "Agrupado por semana" else "Agrupado por mês"} · ${formatSubjectDuration(totals.sum())}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun AccuracyRing(correct: Int, total: Int, modifier: Modifier = Modifier) {
    val green = LocalStudySemanticColors.current.success
    val red = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val pct = if (total > 0) correct * 100 / total else 0
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            val stroke = size.minDimension * .17f
            drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            if (total > 0) {
                val goodSweep = 360f * correct.toFloat() / total
                if (goodSweep > 0f) drawArc(green, -90f, goodSweep, false, style = Stroke(stroke, cap = StrokeCap.Round))
                if (goodSweep < 360f) drawArc(red, -90f + goodSweep, 360f - goodSweep, false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(if (total > 0) "$pct%" else "—", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatSubjectDuration(seconds: Long): String {
    val minutesTotal = (seconds.coerceAtLeast(0) + 59) / 60
    val hours = minutesTotal / 60
    val minutes = minutesTotal % 60
    return if (hours > 0) "${hours}h ${minutes}min" else "${minutes}min"
}

private fun subjectDateKey(time: Long): Int {
    val date = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
}

@Composable
internal fun SubjectPlanTab(
    subject: Subject,
    syllabusTopics: List<VerticalSyllabusTopic>,
    subjectGoals: List<Goal>,
    subjectItems: List<SubjectItem>,
    subjectTasks: List<StudyTask>,
    sessions: List<StudySession>,
    questions: List<QuestionLog>,
    books: List<Book>,
    readingLogs: List<ReadingLog>,
    flashcards: List<Flashcard>,
    cardReviews: List<CardReview>,
    tasks: List<StudyTask>,
    habitCompletions: List<HabitCompletion>,
    todayMidnight: Long,
    studiedMinutesByTaskId: Map<Long, Long>,
    now: Long,
    onStart: () -> Unit,
    onStartTopic: ((String) -> Unit)?,
    onSaveItem: (SubjectItem) -> Unit,
    onDeleteItem: (SubjectItem) -> Unit,
    onCompleteTask: (Long, Boolean) -> Unit,
    onDeleteTask: (StudyTask) -> Unit,
    onStartTask: ((StudyTask) -> Unit)?,
    onSaveTask: ((StudyTask) -> Unit)?,
    onToggleSubtask: ((StudyTask, String) -> Unit)?,
    onOpenGoals: (() -> Unit)?,
    onGenerateCycle: (String) -> Unit,
    onOpenNewTopicDialog: () -> Unit,
    onOpenEditItemDialog: (SubjectItem) -> Unit,
    onOpenNewGoalItemDialog: () -> Unit,
    onOpenQuestionDialogForTopic: (String) -> Unit,
    onOpenNewTaskDialog: () -> Unit,
    onOpenEditTaskDialog: (StudyTask) -> Unit
) {
    // Planejar: Edital Verticalizado + Metas + To-do
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Edital Verticalizado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val mastered = syllabusTopics.count { it.mastery == TopicMastery.MASTERED }
            Text(
                if (syllabusTopics.isEmpty()) "Adicione os tópicos do edital para acompanhar domínio"
                else "$mastered de ${syllabusTopics.size} tópicos dominados · toque no estágio para avançar",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        FilledTonalButton(onClick = onOpenNewTopicDialog) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Text(" Tópico")
        }
    }

    if (syllabusTopics.isEmpty()) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Nenhum tópico no edital ainda", fontWeight = FontWeight.SemiBold)
                Text(
                    "Cadastre os assuntos desta matéria ou registre questões/erros por assunto para acompanhar automaticamente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        val masteredFraction = syllabusTopics.count { it.mastery == TopicMastery.MASTERED }.toFloat() /
            syllabusTopics.size.coerceAtLeast(1)
        LinearProgressIndicator(
            progress = { masteredFraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color = LocalStudySemanticColors.current.success,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        syllabusTopics.forEach { topic ->
            VerticalSyllabusTopicCard(
                topic = topic,
                onCycleMastery = {
                    val nextMastery = topic.mastery.next()
                    val baseItem = topic.sourceItem ?: SubjectItem(
                        subjectId = subject.id,
                        type = "TOPIC",
                        title = topic.title
                    )
                    onSaveItem(SyllabusEngine.applyMastery(baseItem, nextMastery))
                },
                onStudyTopic = {
                    if (onStartTopic != null) onStartTopic(topic.title) else onStart()
                },
                onLogQuestions = { onOpenQuestionDialogForTopic(topic.title) },
                onEditItem = topic.sourceItem?.let { item -> { onOpenEditItemDialog(item) } },
                onDeleteItem = topic.sourceItem?.let { item -> { onDeleteItem(item) } },
                onGenerateCycle = { onGenerateCycle(topic.title) }
            )
        }
    }

    // Metas & Objetivos da Matéria
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Metas & Objetivos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Metas vinculadas e objetivos desta matéria", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        FilledTonalButton(onClick = onOpenNewGoalItemDialog) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Text(" Novo")
        }
    }

    var linkedGoalSnapshots by remember(subject.id) { mutableStateOf<List<GoalProgressSnapshot>>(emptyList()) }
    LaunchedEffect(subjectGoals, sessions, questions, books, readingLogs, flashcards, cardReviews, tasks, habitCompletions, now) {
        linkedGoalSnapshots = if (subjectGoals.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                subjectGoals.map { goal ->
                    GoalProgressEngine.compute(
                        goal = goal,
                        sessions = sessions,
                        questionLogs = questions,
                        books = books,
                        readingLogs = readingLogs,
                        flashcards = flashcards,
                        cardReviews = cardReviews,
                        tasks = tasks,
                        habitCompletions = habitCompletions,
                        now = now
                    )
                }
            }
        }
    }
    if (linkedGoalSnapshots.isNotEmpty()) {
        linkedGoalSnapshots.forEach { snapshot ->
            SubjectLinkedGoalCard(snapshot = snapshot, onClick = onOpenGoals)
        }
    }

    val subjectItemsByType = remember(subjectItems) { subjectItems.groupBy { it.type } }
    val planKinds = listOf("GOAL" to "Metas locais", "OBJECTIVE" to "Objetivos")
    planKinds.forEach { (kind, label) ->
        val group = subjectItemsByType[kind].orEmpty()
        if (group.isNotEmpty() || (kind == "OBJECTIVE" && subjectGoals.isEmpty())) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
        }
        if (group.isEmpty() && kind == "OBJECTIVE" && subjectGoals.isEmpty() && subjectItemsByType["GOAL"].isNullOrEmpty()) {
            Text(
                "Ainda não há metas ou objetivos locais.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
            )
        }
        group.forEach { item ->
            SubjectItemCard(
                item = item,
                onToggle = {
                    onSaveItem(
                        item.copy(
                            completed = !item.completed,
                            currentValue = if (!item.completed && item.type == "GOAL") {
                                item.targetValue ?: item.currentValue
                            } else {
                                item.currentValue
                            }
                        )
                    )
                },
                onEdit = { onOpenEditItemDialog(item) },
                onDelete = { onDeleteItem(item) },
                onGenerateCycle = if (item.type == "GOAL") {
                    { onGenerateCycle(item.title) }
                } else null
            )
        }
    }

    // To-do da matéria
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("To-do", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Aparece também na sua lista geral de tarefas.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onOpenNewTaskDialog) {
            Icon(Icons.Default.Add, "Adicionar tarefa", tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (subjectTasks.isEmpty()) {
        Text("Nenhuma tarefa nesta matéria ainda.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    subjectTasks.sortedWith(
        compareBy<StudyTask> { it.completed }
            .thenBy { !it.hasDueDate }
            .thenBy { it.priority }
            .thenBy { it.dueAt }
    ).forEach { task ->
        SubjectTaskCard(
            task = task,
            todayMidnight = todayMidnight,
            studiedMinutes = studiedMinutesByTaskId[task.id] ?: 0L,
            onToggleComplete = { onCompleteTask(task.id, !task.completed) },
            onStart = onStartTask?.let { start -> { start(task) } },
            onEdit = { onOpenEditTaskDialog(task) },
            onDelete = { onDeleteTask(task) },
            onToggleSubtask = { subId ->
                if (onToggleSubtask != null) {
                    onToggleSubtask(task, subId)
                } else if (onSaveTask != null) {
                    onSaveTask(task.copy(subtasksJson = TaskSubtasks.toggle(task.subtasksJson, subId)))
                }
            }
        )
    }
}

@Composable
internal fun SubjectQuestionsTab(
    subject: Subject,
    period: Int,
    onPeriodChange: (Int) -> Unit,
    periodQuestions: List<QuestionLog>,
    totalQuestions: Int,
    correctQuestions: Int,
    accuracy: Int,
    subjectMistakes: List<Mistake>,
    topicSuggestions: List<String>,
    now: Long,
    onOpenQuestionDialog: () -> Unit,
    onMistake: (Mistake) -> Unit,
    onUpdateMistake: ((Mistake) -> Unit)?,
    onDeleteMistake: ((Mistake) -> Unit)?,
    onReviewMistake: ((Mistake, Boolean) -> Unit)?,
    onMistakeToCard: ((Subject, Mistake) -> Unit)?,
    onMistakeToTask: ((Subject, Mistake) -> Unit)?,
    onMistakeToTutor: ((Subject, Mistake) -> Unit)?,
    onSaveTask: ((StudyTask) -> Unit)?,
    onAddTask: (String, Long) -> Unit
) {
    val context = LocalContext.current
    // Questões & Caderno de Erros Turbinado
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Questões", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Registre simulados e revise erros com repetição espaçada.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        FilledTonalIconButton(onClick = onOpenQuestionDialog) {
            Icon(Icons.Default.Add, "Registrar questões")
        }
    }
    PeriodPicker(period, onPeriodChange)
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AccuracyRing(correctQuestions, totalQuestions, Modifier.size(110.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp)
            ) {
                Text("$accuracy%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("de acertos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "$correctQuestions certas · ${(totalQuestions - correctQuestions).coerceAtLeast(0)} erradas",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    MistakeNotebook(
        subject = subject,
        entries = subjectMistakes,
        topicSuggestions = topicSuggestions,
        now = now,
        onAddMistake = onMistake,
        onUpdateMistake = onUpdateMistake,
        onDeleteMistake = onDeleteMistake,
        onReviewMistake = onReviewMistake,
        onMistakeToCard = onMistakeToCard,
        onMistakeToTask = onMistakeToTask ?: { subj, m ->
            val title = "Revisar erro: ${m.topic} — ${m.question.take(48)}"
            if (onSaveTask != null) {
                onSaveTask(
                    StudyTask(
                        subjectId = subj.id,
                        title = title,
                        dueAt = noonTimestamp(offsetDays = 1),
                        hasDueDate = true,
                        priority = 2,
                        details = m.solution,
                        tags = TaskTagPresets.REVISAO
                    )
                )
            } else {
                onAddTask(title, noonTimestamp(offsetDays = 1))
            }
            Toast.makeText(context, "Tarefa criada para revisar este erro!", Toast.LENGTH_SHORT).show()
        },
        onMistakeToTutor = onMistakeToTutor
    )

    Text(
        "Registros recentes",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 7.dp)
    )
    if (periodQuestions.isEmpty()) {
        Text("Registre seu primeiro bloco de questões para começar o histórico.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    periodQuestions.forEach { log ->
        val missed = (log.questionCount - log.correctCount).coerceAtLeast(0)
        Card(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccuracyRing(log.correctCount, log.questionCount, Modifier.size(46.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(log.topic, fontWeight = FontWeight.SemiBold)
                    val loggedDateStr = remember(log.loggedAt) {
                        Instant.ofEpochMilli(log.loggedAt)
                            .atZone(ZoneId.systemDefault())
                            .format(TABS_SHORT_DATE_FORMATTER)
                    }
                    Text(
                        "${log.correctCount}/${log.questionCount} certas · $missed erradas · $loggedDateStr",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerticalSyllabusTopicCard(
    topic: VerticalSyllabusTopic,
    onCycleMastery: () -> Unit,
    onStudyTopic: () -> Unit,
    onLogQuestions: () -> Unit,
    onEditItem: (() -> Unit)?,
    onDeleteItem: (() -> Unit)?,
    onGenerateCycle: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val semantic = LocalStudySemanticColors.current

    val (masteryBg, masteryFg) = when (topic.mastery) {
        TopicMastery.NOT_STARTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        TopicMastery.LEARNING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        TopicMastery.REVIEWING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        TopicMastery.MASTERED -> semantic.successContainer to semantic.onSuccessContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        topic.title,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (topic.isInferred) {
                        Text(
                            "Identificado automaticamente em questões/erros/Anki",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (!topic.sourceItem?.details.isNullOrBlank()) {
                        Text(
                            topic.sourceItem!!.details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Alterna estágio em 1 toque
                Surface(
                    onClick = onCycleMastery,
                    modifier = Modifier.heightIn(min = 36.dp),
                    color = masteryBg,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            topic.mastery.label,
                            color = masteryFg,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Avançar estágio",
                            tint = masteryFg,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, "Opções do tópico", modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Gerar ciclo (Teoria + Questões + Revisão)") },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                            onClick = { menuOpen = false; onGenerateCycle() }
                        )
                        if (onEditItem != null) {
                            DropdownMenuItem(
                                text = { Text("Editar tópico") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menuOpen = false; onEditItem() }
                            )
                        }
                        if (onDeleteItem != null) {
                            DropdownMenuItem(
                                text = { Text("Excluir tópico") },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                                onClick = { menuOpen = false; onDeleteItem() }
                            )
                        }
                    }
                }
            }

            // Métricas do tópico (acurácia, Anki devidos, erros em aberto)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val qLabel = if (topic.questionsTotal > 0) {
                    "${topic.accuracyPercent}% acertos (${topic.questionsCorrect}/${topic.questionsTotal}q)"
                } else {
                    "0 questões"
                }
                SubjectBadgePill(
                    text = qLabel,
                    containerColor = when {
                        topic.accuracyPercent == null -> MaterialTheme.colorScheme.surfaceVariant
                        topic.accuracyPercent >= 75 -> semantic.successContainer
                        topic.accuracyPercent < 55 -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = when {
                        topic.accuracyPercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        topic.accuracyPercent >= 75 -> semantic.onSuccessContainer
                        topic.accuracyPercent < 55 -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (topic.flashcardsCount > 0) {
                    SubjectBadgePill(
                        text = "${topic.dueFlashcardsCount} Anki devidos / ${topic.flashcardsCount}",
                        containerColor = if (topic.dueFlashcardsCount > 0) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (topic.dueFlashcardsCount > 0) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (topic.openMistakesCount > 0) {
                    SubjectBadgePill(
                        text = "${topic.openMistakesCount} ${if (topic.openMistakesCount == 1) "erro aberto" else "erros abertos"}",
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else if (topic.resolvedMistakesCount > 0) {
                    SubjectBadgePill(
                        text = "${topic.resolvedMistakesCount} erros resolvidos",
                        containerColor = semantic.successContainer,
                        contentColor = semantic.onSuccessContainer
                    )
                }
            }

            // Botões rápidos: Estudar tópico / Registrar questões
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = onStudyTopic,
                    leadingIcon = { Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Estudar tópico") }
                )
                AssistChip(
                    onClick = onLogQuestions,
                    leadingIcon = { Icon(Icons.Default.Quiz, null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Registrar questões") }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubjectKnowledgeTab(
    subject: Subject,
    linkedNotes: List<LinkedNoteMatch>,
    subjectFlashcards: List<Flashcard>,
    dueSubjectCards: List<Flashcard>,
    fallbackCardCount: Int,
    syllabusTopics: List<VerticalSyllabusTopic>,
    subjectGoals: List<Goal>,
    sessions: List<StudySession>,
    questions: List<QuestionLog>,
    books: List<Book>,
    readingLogs: List<ReadingLog>,
    allFlashcards: List<Flashcard>,
    cardReviews: List<CardReview>,
    tasks: List<StudyTask>,
    habitCompletions: List<HabitCompletion>,
    now: Long,
    onOpenNote: ((Long) -> Unit)?,
    onCreateNoteForSubject: ((Subject) -> Unit)?,
    onOpenCards: () -> Unit,
    onReviewSubjectCards: ((List<Flashcard>) -> Unit)?,
    onOpenGoals: (() -> Unit)?,
    onGoToPlanTab: () -> Unit,
    onGoToQuestionsTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1) Notas do Segundo Cérebro ligadas à matéria
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Notas do Segundo Cérebro (${linkedNotes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Notas com menção, tag, pasta ou livro desta matéria",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onCreateNoteForSubject != null) {
                    FilledTonalButton(onClick = { onCreateNoteForSubject(subject) }) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, null, modifier = Modifier.size(16.dp))
                        Text(" Nova nota")
                    }
                }
            }

            if (linkedNotes.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Nenhuma nota ligada a ${subject.name} ainda", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Cite \"${subject.name}\" no título/texto de uma nota, use a pasta/tag correspondente ou vincule um livro desta matéria.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                linkedNotes.forEach { match ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = onOpenNote != null) { onOpenNote?.invoke(match.note.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (match.note.pinned) Icons.Default.PushPin else Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    match.note.title.ifBlank { "Nota sem título" },
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (match.preview.isNotBlank()) {
                                    Text(
                                        match.preview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    SubjectBadgePill(
                                        text = match.reason,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    val noteDateStr = remember(match.note.updatedAt) {
                                        Instant.ofEpochMilli(match.note.updatedAt)
                                            .atZone(ZoneId.systemDefault())
                                            .format(TABS_SHORT_DATE_FORMATTER)
                                    }
                                    Text(
                                        noteDateStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    )
                                }
                            }
                            if (onOpenNote != null) {
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
            }
        }

        // 2) Flashcards da matéria & Tópicos fracos
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Flashcards da matéria",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            val totalCards = if (allFlashcards.isNotEmpty()) subjectFlashcards.size else fallbackCardCount
            val newCount = subjectFlashcards.count { it.reviewState == "NEW" }
            val learningCount = subjectFlashcards.count { it.reviewState == "LEARNING" || it.reviewState == "RELEARNING" }
            val reviewCount = subjectFlashcards.count { it.reviewState == "REVIEW" }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth < 340.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SubjectMetric("Total", totalCards.toString(), Modifier.weight(1f))
                                    SubjectMetric("Devidos hoje", dueSubjectCards.size.toString(), Modifier.weight(1f))
                                }
                                SubjectMetric("Novos", newCount.toString(), Modifier.fillMaxWidth())
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SubjectMetric("Total", totalCards.toString(), Modifier.weight(1f))
                                SubjectMetric("Devidos hoje", dueSubjectCards.size.toString(), Modifier.weight(1f))
                                SubjectMetric("Novos", newCount.toString(), Modifier.weight(1f))
                            }
                        }
                    }
                    if (subjectFlashcards.isNotEmpty()) {
                        Text(
                            "$learningCount em aprendizagem · $reviewCount em revisão · ${subjectFlashcards.count { it.lapses > 0 }} com lapsos",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (onReviewSubjectCards != null && subjectFlashcards.isNotEmpty()) {
                                    onReviewSubjectCards(dueSubjectCards.ifEmpty { subjectFlashcards })
                                } else {
                                    onOpenCards()
                                }
                            },
                            enabled = totalCards > 0
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Text(
                                if (dueSubjectCards.isNotEmpty()) " Revisar baralho (${dueSubjectCards.size})"
                                else " Revisar baralho da matéria"
                            )
                        }
                        OutlinedButton(onClick = onOpenCards) {
                            Text("Abrir baralhos")
                        }
                    }
                }
            }

            val weakTopics = remember(syllabusTopics) {
                syllabusTopics.filter { topic ->
                    (topic.accuracyPercent != null && topic.accuracyPercent < 75) ||
                        topic.openMistakesCount > 0 ||
                        topic.dueFlashcardsCount > 0
                }.sortedWith(
                    compareByDescending<VerticalSyllabusTopic> { it.openMistakesCount * 3 }
                        .thenBy { it.accuracyPercent ?: 100 }
                        .thenByDescending { it.dueFlashcardsCount }
                ).take(5)
            }
            val hardCards = remember(subjectFlashcards) {
                subjectFlashcards
                    .filter { it.lapses > 0 || it.easeFactor < 2.1 }
                    .sortedWith(compareByDescending<Flashcard> { it.lapses }.thenBy { it.easeFactor })
                    .take(4)
            }

            if (weakTopics.isNotEmpty() || hardCards.isNotEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Resumo de tópicos fracos", fontWeight = FontWeight.SemiBold)
                        weakTopics.forEach { topic ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (topic.openMistakesCount > 0) onGoToQuestionsTab() else onGoToPlanTab()
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(topic.title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                                    val parts = buildList {
                                        topic.accuracyPercent?.let { add("$it% acertos") }
                                        if (topic.openMistakesCount > 0) add("${topic.openMistakesCount} erros abertos")
                                        if (topic.dueFlashcardsCount > 0) add("${topic.dueFlashcardsCount} Anki devidos")
                                    }
                                    Text(
                                        parts.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (hardCards.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text(
                                "Cartões com mais lapsos",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            hardCards.forEach { card ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        card.front.replace('\n', ' '),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    SubjectBadgePill(
                                        text = "${card.lapses} lapsos",
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3) Metas vinculadas à matéria
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Metas vinculadas (${subjectGoals.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Metas da central vinculadas a ${subject.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onOpenGoals != null) {
                    TextButton(onClick = onOpenGoals) {
                        Text("Abrir Metas")
                    }
                }
            }

            if (subjectGoals.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Nenhuma meta vinculada a ${subject.name} ainda", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Na Central de Metas você pode criar metas de horas, questões, acurácia, cartões ou contagem ligadas a esta matéria.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onOpenGoals != null) {
                            OutlinedButton(onClick = onOpenGoals) {
                                Icon(Icons.Default.Flag, null, modifier = Modifier.size(16.dp))
                                Text(" Ir para Central de Metas")
                            }
                        }
                    }
                }
            } else {
                var knowledgeGoalSnapshots by remember { mutableStateOf<List<GoalProgressSnapshot>>(emptyList()) }
                LaunchedEffect(subjectGoals, sessions, questions, books, readingLogs, allFlashcards, cardReviews, tasks, habitCompletions, now) {
                    knowledgeGoalSnapshots = withContext(Dispatchers.Default) {
                        subjectGoals.map { goal ->
                            GoalProgressEngine.compute(
                                goal = goal,
                                sessions = sessions,
                                questionLogs = questions,
                                books = books,
                                readingLogs = readingLogs,
                                flashcards = allFlashcards,
                                cardReviews = cardReviews,
                                tasks = tasks,
                                habitCompletions = habitCompletions,
                                now = now
                            )
                        }
                    }
                }
                knowledgeGoalSnapshots.forEach { snapshot ->
                    SubjectLinkedGoalCard(snapshot = snapshot, onClick = onOpenGoals)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectLinkedGoalCard(
    snapshot: GoalProgressSnapshot,
    onClick: (() -> Unit)?
) {
    val goal = snapshot.goal
    val accent = Color(goal.color.toInt())
    val doneMilestones = snapshot.milestones.count { it.done }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(goal.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        snapshot.metricType.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "${snapshot.displayCurrent} / ${snapshot.displayTarget}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val semantic = LocalStudySemanticColors.current
            LinearProgressIndicator(
                progress = { snapshot.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50)),
                color = if (goal.completed) semantic.success else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                snapshot.paceStatusLabel?.let { status ->
                    SubjectBadgePill(
                        text = status,
                        containerColor = if (snapshot.isOnTrack) {
                            semantic.successContainer
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        },
                        contentColor = if (snapshot.isOnTrack) {
                            semantic.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
                snapshot.requiredDailyPaceLabel?.let { pace ->
                    SubjectBadgePill(
                        text = "Ritmo: $pace",
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (snapshot.milestones.isNotEmpty()) {
                    SubjectBadgePill(
                        text = "$doneMilestones/${snapshot.milestones.size} marcos",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SubjectItemCard(
    item: SubjectItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onGenerateCycle: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 5.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = item.completed, onCheckedChange = { onToggle() })
                Column(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp)
                ) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    if (item.type == "GOAL" && item.targetValue != null) {
                        val ratio = (item.currentValue / item.targetValue.coerceAtLeast(.1)).toFloat().coerceIn(0f, 1f)
                        Text("${item.currentValue.toInt()} de ${item.targetValue.toInt()} ${item.unit}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { ratio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    } else if (item.details.isNotBlank()) {
                        Text(item.details, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    item.targetAt?.let { targetMs ->
                        val targetLabel = remember(targetMs) {
                            Instant.ofEpochMilli(targetMs)
                                .atZone(ZoneId.systemDefault())
                                .format(TABS_FULL_DATE_FORMATTER)
                        }
                        Text("Até $targetLabel", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Opções") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onGenerateCycle != null) {
                            DropdownMenuItem(
                                text = { Text("Gerar ciclo (Teoria + Questões + Revisão)") },
                                leadingIcon = { Icon(Icons.Default.AutoAwesome, null) },
                                onClick = { menuOpen = false; onGenerateCycle() }
                            )
                        }
                        DropdownMenuItem(text = { Text("Editar") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("Excluir") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            if (onGenerateCycle != null && !item.completed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, bottom = 6.dp)
                ) {
                    AssistChip(
                        onClick = onGenerateCycle,
                        label = {
                            Text(
                                "Gerar ciclo de tarefas (Teoria + Questões + Revisão)",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp)) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectTaskCard(
    task: StudyTask,
    todayMidnight: Long,
    studiedMinutes: Long,
    onToggleComplete: () -> Unit,
    onStart: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleSubtask: (String) -> Unit
) {
    val priColor = themePriorityColor(task.priority)
    val isOverdue = !task.completed && task.hasDueDate && task.dueAt < todayMidnight
    val tags = remember(task.tags) { TaskTagPresets.parseTags(task.tags) }
    val subtasks = remember(task.subtasksJson) { TaskSubtasks.parse(task.subtasksJson) }
    val (doneSub, totalSub) = remember(subtasks) { TaskSubtasks.summary(subtasks) }
    var expandSubtasks by remember(task.id) { mutableStateOf(false) }

    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (task.completed) MaterialTheme.colorScheme.outlineVariant else priColor)
                )
                Checkbox(
                    checked = task.completed,
                    onCheckedChange = { onToggleComplete() },
                    colors = CheckboxDefaults.colors(checkedColor = priColor, uncheckedColor = priColor)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onEdit)
                ) {
                    Text(
                        task.title,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                        color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (task.priority in 1..3) {
                            Surface(color = priColor.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "P${task.priority}",
                                    color = priColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        val dateText = if (task.hasDueDate) taskDateLabel(task.dueAt) else "Inbox"
                        val dateBg = when {
                            isOverdue -> MaterialTheme.colorScheme.errorContainer
                            !task.hasDueDate -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val dateFg = when {
                            isOverdue -> MaterialTheme.colorScheme.onErrorContainer
                            !task.hasDueDate -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Surface(color = dateBg, shape = RoundedCornerShape(6.dp)) {
                            Text(dateText, color = dateFg, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                        if (task.estimatedMinutes > 0) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                                Text("~${task.estimatedMinutes}m", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                        if (studiedMinutes > 0L) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                                Text("${studiedMinutes}m estudado", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                        if (totalSub > 0) {
                            Surface(
                                modifier = Modifier
                                    .heightIn(min = 28.dp)
                                    .clickable { expandSubtasks = !expandSubtasks },
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("$doneSub/$totalSub subtarefas", color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                        tags.take(3).forEach { tag ->
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                                Text(tag, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
                if (!task.completed && onStart != null) {
                    IconButton(onClick = onStart, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.PlayArrow, "Estudar tarefa", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Edit, "Editar tarefa", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, "Excluir tarefa", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            if (expandSubtasks && subtasks.isNotEmpty()) {
                Column(Modifier.padding(start = 42.dp, end = 8.dp, top = 4.dp)) {
                    subtasks.forEach { sub ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp)
                                .clickable { onToggleSubtask(sub.id) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = sub.done, onCheckedChange = { onToggleSubtask(sub.id) })
                            Text(
                                sub.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                color = if (sub.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SubjectItemDialog(
    subjectId: Long,
    item: SubjectItem?,
    defaultType: String = "GOAL",
    onDismiss: () -> Unit,
    onSave: (SubjectItem) -> Unit
) {
    var type by remember(item?.id, defaultType) { mutableStateOf(item?.type ?: defaultType) }
    var title by remember(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var details by remember(item?.id) { mutableStateOf(item?.details.orEmpty()) }
    var target by remember(item?.id) { mutableStateOf(item?.targetValue?.toString().orEmpty()) }
    var current by remember(item?.id) { mutableStateOf(item?.currentValue?.toString().orEmpty()) }
    var unit by remember(item?.id) { mutableStateOf(item?.unit ?: "vezes") }
    var targetAt by remember(item?.id) { mutableStateOf(item?.targetAt) }
    var topicMastery by remember(item?.id) {
        mutableStateOf(if (item != null) SyllabusEngine.masteryOf(item) else TopicMastery.NOT_STARTED)
    }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                when {
                    item == null && type == "TOPIC" -> "Novo tópico do edital"
                    item == null -> "Adicionar ao planejamento"
                    else -> "Editar item"
                }
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("GOAL" to "Meta", "OBJECTIVE" to "Objetivo", "TOPIC" to "Tópico").forEachIndexed { index, (code, label) ->
                        SegmentedButton(
                            selected = type == code,
                            onClick = { type = code },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)
                        ) { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    }
                }
                OutlinedTextField(
                    title,
                    { title = it },
                    label = {
                        Text(
                            when (type) {
                                "GOAL" -> "O que você quer alcançar?"
                                "TOPIC" -> "Nome do tópico do edital"
                                else -> "Objetivo"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(details, { details = it }, label = { Text("Detalhes ou observações") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                if (type == "TOPIC") {
                    Text("Estágio de domínio", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TopicMastery.entries.forEach { stage ->
                            FilterChip(
                                selected = topicMastery == stage,
                                onClick = { topicMastery = stage },
                                label = { Text(stage.label) }
                            )
                        }
                    }
                }
                if (type == "GOAL") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            current,
                            { current = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text("Já fiz") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            target,
                            { target = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                            label = { Text("Meta") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unidade (h, páginas...) ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedButton(
                    onClick = {
                        val zone = java.time.ZoneId.systemDefault()
                        val d0 = targetAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                            ?: java.time.LocalDate.now(zone)
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                targetAt = java.time.LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                            },
                            d0.year,
                            d0.monthValue - 1,
                            d0.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    val targetAtLabel = remember(targetAt) {
                        targetAt?.let {
                            Instant.ofEpochMilli(it)
                                .atZone(ZoneId.systemDefault())
                                .format(TABS_FULL_DATE_FORMATTER)
                        } ?: "Adicionar prazo (opcional)"
                    }
                    Text("  $targetAtLabel")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val goalTarget = if (type == "GOAL") target.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } else null
                    val base = (item ?: SubjectItem(subjectId = subjectId, type = type, title = title.trim())).copy(
                        type = type,
                        title = title.trim(),
                        details = details.trim(),
                        targetAt = targetAt,
                        currentValue = current.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                        targetValue = goalTarget,
                        unit = unit.ifBlank { "vezes" }
                    )
                    val entry = if (type == "TOPIC") SyllabusEngine.applyMastery(base, topicMastery) else base
                    if (title.isNotBlank() && (type != "GOAL" || goalTarget != null)) onSave(entry)
                },
                enabled = title.isNotBlank() && (type != "GOAL" || (target.replace(',', '.').toDoubleOrNull() ?: 0.0) > 0)
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubjectTaskDialog(
    subjectId: Long,
    subjectName: String,
    editingTask: StudyTask?,
    supportsFullEdit: Boolean,
    onDismiss: () -> Unit,
    onSave: (StudyTask) -> Unit
) {
    val context = LocalContext.current
    var title by remember(editingTask?.id) { mutableStateOf(editingTask?.title.orEmpty()) }
    var hasDueDate by remember(editingTask?.id) { mutableStateOf(editingTask?.hasDueDate ?: true) }
    var dueAt by remember(editingTask?.id) { mutableLongStateOf(editingTask?.dueAt ?: noonTimestamp()) }
    var priority by remember(editingTask?.id) { mutableIntStateOf(editingTask?.priority ?: 4) }
    var estimatedMinutes by remember(editingTask?.id) { mutableIntStateOf(editingTask?.estimatedMinutes ?: 0) }
    var tags by remember(editingTask?.id) { mutableStateOf(editingTask?.tags.orEmpty()) }
    var subtasks by remember(editingTask?.id) { mutableStateOf(TaskSubtasks.parse(editingTask?.subtasksJson.orEmpty())) }
    var newSubtaskTitle by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("${if (editingTask == null) "Nova tarefa" else "Editar tarefa"} · $subjectName") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("O que você precisa fazer?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !hasDueDate, onClick = { hasDueDate = !hasDueDate }, label = { Text("Inbox") })
                    OutlinedButton(
                        onClick = {
                            val zone = java.time.ZoneId.systemDefault()
                            val d0 = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
                            DatePickerDialog(context, { _, y, m, d ->
                                dueAt = java.time.LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                hasDueDate = true
                            }, d0.year, d0.monthValue - 1, d0.dayOfMonth).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(16.dp))
                        val dueLabel = remember(hasDueDate, dueAt) {
                            if (hasDueDate) {
                                "  ${Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault()).format(TABS_FULL_DATE_FORMATTER)}"
                            } else {
                                "  Definir prazo"
                            }
                        }
                        Text(
                            dueLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (supportsFullEdit) {
                    Text("Prioridade", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 2, 3, 4).forEach { p ->
                            val c = themePriorityColor(p)
                            FilterChip(
                                selected = priority == p,
                                onClick = { priority = p },
                                label = { Text("P$p", color = if (p <= 3) c else MaterialTheme.colorScheme.onSurface) }
                            )
                        }
                    }
                    Text("Estimativa", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0 to "Sem", 15 to "15m", 25 to "25m", 45 to "45m", 60 to "60m", 90 to "90m").forEach { (mins, label) ->
                            FilterChip(selected = estimatedMinutes == mins, onClick = { estimatedMinutes = mins }, label = { Text(label) })
                        }
                    }
                    Text("Tags", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    val activeTags = remember(tags) { TaskTagPresets.parseTags(tags).toSet() }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TaskTagPresets.all.forEach { tag ->
                            FilterChip(
                                selected = TaskTagPresets.normalizeTag(tag) in activeTags,
                                onClick = { tags = TaskTagPresets.toggleTag(tags, tag) },
                                label = { Text(tag) }
                            )
                        }
                    }
                    Text("Subtarefas (${subtasks.count { it.done }}/${subtasks.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    subtasks.forEach { sub ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = sub.done,
                                onCheckedChange = { checked ->
                                    subtasks = subtasks.map { if (it.id == sub.id) it.copy(done = checked) else it }
                                }
                            )
                            Text(
                                sub.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (sub.done) TextDecoration.LineThrough else null,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { subtasks = subtasks.filterNot { it.id == sub.id } }, modifier = Modifier.size(40.dp)) {
                                Icon(Icons.Default.Close, "Remover subtarefa", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = newSubtaskTitle,
                            onValueChange = { newSubtaskTitle = it },
                            label = { Text("Nova subtarefa") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            onClick = {
                                val trimmed = newSubtaskTitle.trim()
                                if (trimmed.isNotEmpty()) {
                                    subtasks = subtasks + TaskSubtask(
                                        id = "s_${System.nanoTime()}",
                                        title = trimmed,
                                        done = false
                                    )
                                    newSubtaskTitle = ""
                                }
                            },
                            enabled = newSubtaskTitle.isNotBlank()
                        ) {
                            Icon(Icons.Default.Add, "Adicionar subtarefa")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = title.trim()
                    if (trimmed.isNotBlank()) {
                        val saved = (editingTask ?: StudyTask(subjectId = subjectId, title = trimmed, dueAt = dueAt)).copy(
                            subjectId = editingTask?.subjectId ?: subjectId,
                            title = trimmed,
                            dueAt = dueAt,
                            hasDueDate = hasDueDate,
                            priority = priority,
                            estimatedMinutes = estimatedMinutes,
                            tags = tags,
                            subtasksJson = TaskSubtasks.encode(subtasks)
                        )
                        onSave(saved)
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text(if (editingTask == null) "Adicionar" else "Salvar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
internal fun SubjectQuestionDialog(
    initialTopic: String = "",
    topicSuggestions: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (String, Int, Int) -> Unit
) {
    var topic by remember(initialTopic) { mutableStateOf(initialTopic) }
    var total by remember { mutableStateOf("") }
    var correct by remember { mutableStateOf("") }
    val totalNumber = total.toIntOrNull() ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Registrar questões") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(topic, { topic = it }, label = { Text("Assunto (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (topicSuggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        topicSuggestions.take(6).forEach { suggestion ->
                            FilterChip(
                                selected = topic.equals(suggestion, ignoreCase = true),
                                onClick = { topic = suggestion },
                                label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        total,
                        { total = it.filter(Char::isDigit) },
                        label = { Text("Feitas") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        correct,
                        { correct = it.filter(Char::isDigit) },
                        label = { Text("Certas") },
                        supportingText = { Text("Erros: ${(totalNumber - (correct.toIntOrNull() ?: 0)).coerceAtLeast(0)}") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(topic, totalNumber, (correct.toIntOrNull() ?: 0).coerceIn(0, totalNumber)) },
                enabled = totalNumber > 0 && (correct.toIntOrNull() ?: -1) <= totalNumber
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MistakeNotebook(
    subject: Subject,
    entries: List<Mistake>,
    topicSuggestions: List<String>,
    now: Long,
    onAddMistake: (Mistake) -> Unit,
    onUpdateMistake: ((Mistake) -> Unit)?,
    onDeleteMistake: ((Mistake) -> Unit)?,
    onReviewMistake: ((Mistake, Boolean) -> Unit)?,
    onMistakeToCard: ((Subject, Mistake) -> Unit)?,
    onMistakeToTask: ((Subject, Mistake) -> Unit)?,
    onMistakeToTutor: ((Subject, Mistake) -> Unit)?
) {
    val context = LocalContext.current
    var addingOrEditing by rememberSaveable(subject.id) { mutableStateOf(false) }
    var editingMistake by remember(subject.id) { mutableStateOf<Mistake?>(null) }
    var deleteTarget by remember(subject.id) { mutableStateOf<Mistake?>(null) }
    var undoPreviousMistake by remember(subject.id) { mutableStateOf<Mistake?>(null) }
    var selectedTopic by rememberSaveable(subject.id) { mutableStateOf<String?>(null) }
    var filterName by rememberSaveable(subject.id) { mutableStateOf(MistakeFilterMode.PENDING.name) }
    val filterMode = MistakeFilterMode.entries.firstOrNull { it.name == filterName } ?: MistakeFilterMode.PENDING

    val dueCount = remember(entries, now) { entries.count { MistakeSpacedReview.isDue(it, now) } }
    val resolvedCount = remember(entries) { entries.count { it.resolved } }
    val scheduledFutureCount = remember(entries, now) { entries.count { !it.resolved && !MistakeSpacedReview.isDue(it, now) } }

    val availableTopics = remember(entries) { entries.map { it.topic }.distinct() }
    val effectiveTopic = selectedTopic?.takeIf { it in availableTopics }

    val visibleEntries = remember(entries, filterMode, effectiveTopic, now) {
        entries.filter { entry ->
            val matchesStatus = when (filterMode) {
                MistakeFilterMode.PENDING -> MistakeSpacedReview.isDue(entry, now)
                MistakeFilterMode.ALL -> true
                MistakeFilterMode.RESOLVED -> entry.resolved
            }
            val matchesTopic = effectiveTopic == null || effectiveTopic == entry.topic
            matchesStatus && matchesTopic
        }.sortedWith(
            compareBy<Mistake> { it.resolved }
                .thenByDescending { MistakeSpacedReview.isDue(it, now) }
                .thenBy { it.nextReviewAt }
                .thenByDescending { it.createdAt }
        )
    }

    fun performSpacedReview(mistake: Mistake, rememberedWell: Boolean) {
        undoPreviousMistake = mistake
        val next = MistakeSpacedReview.scheduleNextReview(mistake, rememberedWell, now)
        if (onReviewMistake != null) {
            onReviewMistake(mistake, rememberedWell)
        } else {
            onUpdateMistake?.invoke(next)
        }
        val days = MistakeSpacedReview.nextIntervalDays(mistake, rememberedWell)
        val msg = when {
            next.resolved && rememberedWell -> "Erro marcado como resolvido! ✅"
            rememberedWell -> "Lembrou! Próxima revisão em $days ${if (days == 1) "dia" else "dias"}."
            else -> "Agendado para revisar novamente amanhã (1 dia)."
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Caderno de erros", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        entries.isEmpty() -> "Anote erros para revisar nos ciclos 1, 3, 7, 15 e 30 dias."
                        dueCount > 0 -> "$dueCount ${if (dueCount == 1) "erro pronto" else "erros prontos"} para revisão agora"
                        else -> "Revisões em dia · $scheduledFutureCount agendado(s)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = {
                    editingMistake = null
                    addingOrEditing = true
                }
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Text(" Anotar erro")
            }
        }

        undoPreviousMistake?.let { prevMistake ->
            if (onUpdateMistake != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Revisão registrada em \"${prevMistake.topic}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                onUpdateMistake(prevMistake)
                                undoPreviousMistake = null
                            }
                        ) {
                            Text("Desfazer", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (entries.isNotEmpty()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(
                    MistakeFilterMode.PENDING to "Pendentes ($dueCount)",
                    MistakeFilterMode.ALL to "Todos (${entries.size})",
                    MistakeFilterMode.RESOLVED to "Resolvidos ($resolvedCount)"
                )
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = filterMode == mode,
                        onClick = { filterName = mode.name },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size)
                    ) {
                        Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (availableTopics.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(effectiveTopic == null, { selectedTopic = null }, label = { Text("Todos") })
                    availableTopics.forEach { name ->
                        FilterChip(effectiveTopic == name, { selectedTopic = name }, label = { Text(name) })
                    }
                }
            }
        }

        if (entries.isNotEmpty() && visibleEntries.isEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        when (filterMode) {
                            MistakeFilterMode.PENDING ->
                                if (scheduledFutureCount > 0) "Nenhum erro vencido agora ($scheduledFutureCount agendado(s) para depois)."
                                else "Nenhum erro pendente de revisão neste filtro."
                            MistakeFilterMode.RESOLVED -> "Nenhum erro resolvido ainda."
                            MistakeFilterMode.ALL -> "Nenhum erro encontrado neste assunto."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (filterMode == MistakeFilterMode.PENDING && scheduledFutureCount > 0) {
                        OutlinedButton(onClick = { filterName = MistakeFilterMode.ALL.name }) {
                            Text("Ver todos (${entries.size})")
                        }
                    }
                }
            }
        }

        visibleEntries.forEach { entry ->
            key(entry.id) {
                var revealed by rememberSaveable(entry.id) { mutableStateOf(false) }
                val isDue = MistakeSpacedReview.isDue(entry, now)
                val statusLabel = when {
                    entry.resolved -> "Resolvido ✅"
                    isDue -> "Revisar agora"
                    else -> {
                        val daysLeft = ceil(((entry.nextReviewAt - now).coerceAtLeast(1L)) / 86_400_000.0).toInt().coerceAtLeast(1)
                        "Em ${daysLeft}d · rev ${entry.reviewCount}/${MistakeSpacedReview.intervalsDays.size}"
                    }
                }
                val canReview = onReviewMistake != null || onUpdateMistake != null

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(13.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val mistakeSemantic = LocalStudySemanticColors.current
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlowRow(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                SubjectBadgePill(
                                    text = entry.topic,
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                SubjectBadgePill(
                                    text = statusLabel,
                                    containerColor = when {
                                        entry.resolved -> mistakeSemantic.successContainer
                                        isDue -> MaterialTheme.colorScheme.tertiaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    contentColor = when {
                                        entry.resolved -> mistakeSemantic.onSuccessContainer
                                        isDue -> MaterialTheme.colorScheme.onTertiaryContainer
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            if (onUpdateMistake != null) {
                                IconButton(
                                    onClick = {
                                        editingMistake = entry
                                        addingOrEditing = true
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.Edit, "Editar erro", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (onDeleteMistake != null) {
                                IconButton(
                                    onClick = { deleteTarget = entry },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, "Excluir erro", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Text(entry.question, style = MaterialTheme.typography.bodyMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { revealed = !revealed },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.heightIn(min = 40.dp)
                            ) {
                                Text(
                                    when {
                                        revealed -> "Ocultar solução"
                                        isDue && canReview -> "Revisar agora"
                                        else -> "Conferir solução"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        if (revealed) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (entry.reason.isNotBlank()) {
                                        Text(
                                            "Onde errei · ${entry.reason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    Text(entry.solution, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)

                                    if (canReview) {
                                        val nextGoodDays = MistakeSpacedReview.nextIntervalDays(entry, true)
                                        val nextBadDays = MistakeSpacedReview.nextIntervalDays(entry, false)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    performSpacedReview(entry, true)
                                                    revealed = false
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 40.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    "Lembrei ✅ (${nextGoodDays}d)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    performSpacedReview(entry, false)
                                                    revealed = false
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 40.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    "Errei de novo 🔁 (${nextBadDays}d)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Ações de ecossistema: Virar Flashcard, Criar Tarefa, Explicar no Tutor IA
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (onMistakeToCard != null) {
                                AssistChip(
                                    onClick = { onMistakeToCard(subject, entry) },
                                    leadingIcon = { Icon(Icons.Default.Style, null, modifier = Modifier.size(15.dp)) },
                                    label = { Text("Virar Flashcard (Anki)", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            if (onMistakeToTask != null) {
                                AssistChip(
                                    onClick = { onMistakeToTask(subject, entry) },
                                    leadingIcon = { Icon(Icons.Default.AddTask, null, modifier = Modifier.size(15.dp)) },
                                    label = { Text("Criar Tarefa", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                            if (onMistakeToTutor != null) {
                                AssistChip(
                                    onClick = { onMistakeToTutor(subject, entry) },
                                    leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(15.dp)) },
                                    label = { Text("Explicar no Tutor IA", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (addingOrEditing) {
        val editing = editingMistake
        var tag by remember(editing?.id) { mutableStateOf(editing?.topic ?: effectiveTopic.orEmpty()) }
        var question by remember(editing?.id) { mutableStateOf(editing?.question.orEmpty()) }
        var reason by remember(editing?.id) { mutableStateOf(editing?.reason.orEmpty()) }
        var solution by remember(editing?.id) { mutableStateOf(editing?.solution.orEmpty()) }
        var resolved by remember(editing?.id) { mutableStateOf(editing?.resolved ?: false) }

        val combinedTopics = remember(availableTopics, topicSuggestions) {
            (topicSuggestions + availableTopics).distinct().take(8)
        }

        AlertDialog(
            onDismissRequest = {
                addingOrEditing = false
                editingMistake = null
            },
            modifier = Modifier.imePadding(),
            title = { Text(if (editing == null) "Anotar erro" else "Editar erro") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(tag, { tag = it }, label = { Text("Assunto / Tópico") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (combinedTopics.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            combinedTopics.forEach { t ->
                                FilterChip(
                                    selected = tag.equals(t, ignoreCase = true),
                                    onClick = { tag = t },
                                    label = { Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(question, { question = it }, label = { Text("Questão / Enunciado") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(reason, { reason = it }, label = { Text("Onde errei (causa)") }, minLines = 1, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(solution, { solution = it }, label = { Text("Como resolver / Resposta") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    if (editing != null) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { resolved = !resolved }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = resolved, onCheckedChange = { resolved = it })
                            Text("Marcar como resolvido", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = tag.trim().ifBlank { "Geral" }
                        if (editing == null) {
                            onAddMistake(
                                Mistake(
                                    subjectId = subject.id,
                                    topic = name,
                                    question = question.trim(),
                                    reason = reason.trim(),
                                    solution = solution.trim()
                                )
                            )
                        } else {
                            onUpdateMistake?.invoke(
                                editing.copy(
                                    topic = name,
                                    question = question.trim(),
                                    reason = reason.trim(),
                                    solution = solution.trim(),
                                    resolved = resolved
                                )
                            )
                        }
                        selectedTopic = if (selectedTopic != null) name else null
                        addingOrEditing = false
                        editingMistake = null
                    },
                    enabled = question.isNotBlank() && solution.isNotBlank()
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    addingOrEditing = false
                    editingMistake = null
                }) { Text("Cancelar") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            modifier = Modifier.imePadding(),
            title = { Text("Excluir erro?") },
            text = { Text("Esta anotação de erro sobre \"${target.topic}\" será removida permanentemente.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteMistake?.invoke(target)
                        deleteTarget = null
                    }
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

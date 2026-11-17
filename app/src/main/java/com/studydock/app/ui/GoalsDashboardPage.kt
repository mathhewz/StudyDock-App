@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val GOAL_PT_BR_LOCALE = Locale("pt", "BR")
private val GOAL_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", GOAL_PT_BR_LOCALE)

private enum class GoalStatusFilter(val label: String) {
    ACTIVE("Ativas"),
    COMPLETED("Concluídas"),
    ALL("Todas")
}

private enum class GoalTypeFilter(val label: String) {
    ALL("Todas"),
    DEADLINES("Prazos"),
    AUTOMATIC("Automáticas"),
    MANUAL("Manuais")
}

private data class GoalCardPalette(val name: String, val value: Long, val foreground: Color)

private val goalCardPalettes = listOf(
    GoalCardPalette("Índigo", 0xFF6156D9L, accessibleOnColor(Color(0xFF6156D9L.toInt()))),
    GoalCardPalette("Sol", 0xFFFFD75EL, accessibleOnColor(Color(0xFFFFD75EL.toInt()))),
    GoalCardPalette("Coral", 0xFFE85D4AL, accessibleOnColor(Color(0xFFE85D4AL.toInt()))),
    GoalCardPalette("Oceano", 0xFF158FA6L, accessibleOnColor(Color(0xFF158FA6L.toInt()))),
    GoalCardPalette("Noite", 0xFF343744L, accessibleOnColor(Color(0xFF343744L.toInt()))),
    GoalCardPalette("Papel", 0xFFE8E5DEL, accessibleOnColor(Color(0xFFE8E5DEL.toInt())))
)

private fun resolveGoalPalette(colorValue: Long): GoalCardPalette {
    goalCardPalettes.firstOrNull { it.value == colorValue }?.let { return it }
    val bg = Color(colorValue.toInt())
    return GoalCardPalette("Personalizada", colorValue, accessibleOnColor(bg))
}

@Composable
fun GoalsDashboardPage(
    goals: List<Goal>,
    subjects: List<Subject>,
    sessions: List<StudySession>,
    questionLogs: List<QuestionLog>,
    books: List<Book>,
    readingLogs: List<ReadingLog>,
    flashcards: List<Flashcard>,
    cardReviews: List<CardReview>,
    tasks: List<StudyTask>,
    habitCompletions: List<HabitCompletion>,
    onSaveGoal: (Goal) -> Unit,
    onUpdateGoal: (Goal) -> Unit = onSaveGoal,
    onCompleteGoal: (Long, Boolean) -> Unit,
    onTogglePinned: (Goal) -> Unit = { onUpdateGoal(it.copy(pinned = !it.pinned)) },
    onDeleteGoal: (Goal) -> Unit
) {
    val context = LocalContext.current
    var statusFilter by rememberSaveable { mutableStateOf(GoalStatusFilter.ACTIVE) }
    var typeFilter by rememberSaveable { mutableStateOf(GoalTypeFilter.ALL) }
    var editorOpen by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<Goal?>(null) }
    var goalToDelete by remember { mutableStateOf<Goal?>(null) }
    var manualAdjustGoal by remember { mutableStateOf<Goal?>(null) }

    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }

    var snapshots by remember { mutableStateOf<List<GoalProgressSnapshot>>(emptyList()) }
    var snapshotsReady by remember { mutableStateOf(false) }
    LaunchedEffect(
        goals, sessions, questionLogs, books, readingLogs,
        flashcards, cardReviews, tasks, habitCompletions
    ) {
        snapshots = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            goals.map { goal ->
                GoalProgressEngine.compute(
                    goal = goal,
                    sessions = sessions,
                    questionLogs = questionLogs,
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
        GoalProgressWidget.cacheComputedSnapshots(context, snapshots)
        snapshotsReady = true
    }

    val activeCount = remember(snapshots) { snapshots.count { !it.goal.completed } }
    val onTrackCount = remember(snapshots) { snapshots.count { !it.goal.completed && it.isOnTrack } }
    val attentionCount = remember(snapshots) { snapshots.count { !it.goal.completed && !it.isOnTrack } }
    val completedCount = remember(snapshots) { snapshots.count { it.goal.completed } }

    val filteredSnapshots = remember(snapshots, statusFilter, typeFilter) {
        snapshots.filter { snap ->
            val matchesStatus = when (statusFilter) {
                GoalStatusFilter.ACTIVE -> !snap.goal.completed
                GoalStatusFilter.COMPLETED -> snap.goal.completed
                GoalStatusFilter.ALL -> true
            }
            val matchesType = when (typeFilter) {
                GoalTypeFilter.ALL -> true
                GoalTypeFilter.DEADLINES -> snap.metricType == GoalMetricType.COUNTDOWN
                GoalTypeFilter.AUTOMATIC -> snap.metricType != GoalMetricType.COUNTDOWN && snap.metricType != GoalMetricType.MANUAL
                GoalTypeFilter.MANUAL -> snap.metricType == GoalMetricType.MANUAL
            }
            matchesStatus && matchesType
        }.sortedWith(
            compareBy<GoalProgressSnapshot> { it.goal.completed }
                .thenByDescending { it.goal.pinned }
                .thenBy { it.goal.targetAt }
        )
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        StudyPageHeader(
            title = "Metas & Contagens",
            subtitle = "Prazos, marcos e metas automáticas do seu estudo",
            showGlobalSearch = true,
            actions = {
                FilledTonalButton(
                    onClick = { editingGoal = null; editorOpen = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Meta", maxLines = 1)
                }
            }
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            GoalStatusFilter.entries.forEachIndexed { idx, option ->
                val countLabel = when (option) {
                    GoalStatusFilter.ACTIVE -> " ($activeCount)"
                    GoalStatusFilter.COMPLETED -> " ($completedCount)"
                    GoalStatusFilter.ALL -> " (${snapshots.size})"
                }
                SegmentedButton(
                    selected = statusFilter == option,
                    onClick = { statusFilter = option },
                    shape = SegmentedButtonDefaults.itemShape(idx, GoalStatusFilter.entries.size)
                ) {
                    Text("${option.label}$countLabel", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GoalTypeFilter.entries.forEach { option ->
                FilterChip(
                    selected = typeFilter == option,
                    onClick = { typeFilter = option },
                    label = { Text(option.label) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            item(key = "goals_summary", contentType = "summary") {
                GoalsSummaryCard(
                    activeCount = activeCount,
                    onTrackCount = onTrackCount,
                    attentionCount = attentionCount,
                    completedCount = completedCount
                )
            }

            if (goals.isEmpty()) {
                item(key = "goals_empty", contentType = "empty") {
                    Card(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.HourglassTop, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
                            Text("Defina metas e contagens", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            Text(
                                "Acompanhe prazos de provas, horas de estudo, questões, livros ou flashcards automaticamente.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Button(onClick = { editingGoal = null; editorOpen = true }, modifier = Modifier.padding(top = 12.dp)) {
                                Icon(Icons.Default.Add, null)
                                Text(" Criar primeira meta")
                            }
                        }
                    }
                }
            } else if (snapshotsReady && filteredSnapshots.isEmpty()) {
                item(key = "goals_empty_filter", contentType = "empty_filter") {
                    Card(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nenhuma meta neste filtro", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Ajuste os filtros acima ou crie uma nova meta.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            if (statusFilter != GoalStatusFilter.ALL || typeFilter != GoalTypeFilter.ALL) {
                                TextButton(
                                    onClick = { statusFilter = GoalStatusFilter.ALL; typeFilter = GoalTypeFilter.ALL },
                                    modifier = Modifier.padding(top = 6.dp)
                                ) { Text("Mostrar todas") }
                            }
                        }
                    }
                }
            } else {
                item(key = "goals_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(4.dp))
                }
                items(
                    items = filteredSnapshots,
                    key = { it.goal.id },
                    contentType = { "goal_card" }
                ) { snapshot ->
                    RichGoalCard(
                        snapshot = snapshot,
                        subject = snapshot.goal.subjectId?.let { subjectsById[it] },
                        onTogglePin = { onTogglePinned(snapshot.goal) },
                        onEdit = { editingGoal = snapshot.goal; editorOpen = true },
                        onComplete = { onCompleteGoal(snapshot.goal.id, !snapshot.goal.completed) },
                        onDelete = { goalToDelete = snapshot.goal },
                        onUpdateGoal = onUpdateGoal,
                        onRequestManualAdjust = { manualAdjustGoal = snapshot.goal }
                    )
                }
            }
        }
    }

    if (editorOpen) {
        GoalEditorDialog(
            existing = editingGoal,
            subjects = subjects,
            onDismiss = { editorOpen = false },
            onSave = { savedGoal ->
                if (savedGoal.id == 0L) onSaveGoal(savedGoal) else onUpdateGoal(savedGoal)
                editorOpen = false
            }
        )
    }

    manualAdjustGoal?.let { targetGoal ->
        ManualProgressDialog(
            goal = targetGoal,
            onDismiss = { manualAdjustGoal = null },
            onApplyDelta = { delta ->
                val nextVal = (targetGoal.currentValue + delta).coerceAtLeast(0.0)
                onUpdateGoal(targetGoal.copy(currentValue = nextVal))
                manualAdjustGoal = null
            }
        )
    }

    goalToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { goalToDelete = null },
            title = { Text("Excluir meta?") },
            text = { Text("“${target.title}” será removida permanentemente.") },
            confirmButton = {
                TextButton(onClick = { onDeleteGoal(target); goalToDelete = null }) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { goalToDelete = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun GoalsSummaryCard(
    activeCount: Int,
    onTrackCount: Int,
    attentionCount: Int,
    completedCount: Int
) {
    val semantic = LocalStudySemanticColors.current
    StudyCard(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Flag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Resumo de Metas", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalSummaryStatPill("Ativas", activeCount, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    GoalSummaryStatPill("Em dia", onTrackCount, semantic.success, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalSummaryStatPill(
                        "Atenção",
                        attentionCount,
                        if (attentionCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        Modifier.weight(1f)
                    )
                    GoalSummaryStatPill("Concluídas", completedCount, MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GoalSummaryStatPill(label: String, value: Int, tint: Color, modifier: Modifier = Modifier) {
    val cardSurface = MaterialTheme.colorScheme.surface
    val readableTint = accessibleAccentOnSurface(tint, cardSurface)
    Surface(
        modifier = modifier,
        shape = studyControlShape(),
        color = tint.copy(alpha = 0.12f)
    ) {
        Column(Modifier.padding(vertical = 8.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = readableTint)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RichGoalCard(
    snapshot: GoalProgressSnapshot,
    subject: Subject?,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    onUpdateGoal: (Goal) -> Unit,
    onRequestManualAdjust: () -> Unit
) {
    val goal = snapshot.goal
    val palette = resolveGoalPalette(goal.color)
    val fg = palette.foreground
    val containerColor = Color(goal.color.toInt())
    var milestonesExpanded by remember(goal.id) { mutableStateOf(false) }

    val isQuantitative = snapshot.metricType != GoalMetricType.COUNTDOWN
    val paceBadgeText = when {
        goal.completed -> "Concluída"
        !isQuantitative -> null
        !snapshot.isOnTrack -> if (!snapshot.paceStatusLabel.isNullOrBlank()) "Atenção · ${snapshot.paceStatusLabel}" else "Atenção"
        snapshot.paceStatusLabel == "Meta atingida" || snapshot.paceStatusLabel == "Acurácia atingida" -> snapshot.paceStatusLabel
        snapshot.paceStatusLabel == "Sem questões ainda" -> "Sem questões ainda"
        else -> "No prazo"
    }

    val zone = remember { ZoneId.systemDefault() }
    val daysDelta = remember(goal.targetAt, zone) {
        val today = LocalDate.now(zone)
        val target = Instant.ofEpochMilli(goal.targetAt).atZone(zone).toLocalDate()
        ChronoUnit.DAYS.between(today, target).toInt()
    }
    val deadlineSubtext = when {
        daysDelta == 0 -> "Hoje"
        daysDelta == 1 -> "Amanhã"
        daysDelta > 1 -> "Faltam $daysDelta d"
        else -> "Há ${abs(daysDelta)} d"
    }
    val formattedTargetDate = remember(goal.targetAt, zone) {
        Instant.ofEpochMilli(goal.targetAt).atZone(zone).toLocalDate().format(GOAL_DATE_FORMATTER)
    }
    val cardShape = when (goal.preset) {
        "SWISS" -> RoundedCornerShape(8.dp)
        "AQUA" -> RoundedCornerShape(28.dp)
        "MINIMAL" -> RoundedCornerShape(16.dp)
        else -> RoundedCornerShape(22.dp)
    }
    val cardBorder = when (goal.preset) {
        "SWISS" -> BorderStroke(2.dp, fg.copy(alpha = 0.85f))
        "MINIMAL" -> BorderStroke(1.dp, fg.copy(alpha = 0.35f))
        "AQUA" -> BorderStroke(1.5.dp, Color.White.copy(alpha = 0.35f))
        else -> null
    }

    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = cardShape,
        border = cardBorder
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GoalCardBadge(
                    icon = metricIcon(snapshot.metricType),
                    text = snapshot.metricType.label,
                    fg = fg
                )
                if (subject != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = fg.copy(alpha = 0.16f)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(subject.color.toInt())))
                            Text(subject.name, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (goal.pinned) {
                    GoalCardBadge(icon = Icons.Default.PushPin, text = "Fixada na Home", fg = fg)
                }
                if (paceBadgeText != null) {
                    val semantic = LocalStudySemanticColors.current
                    val warn = !snapshot.isOnTrack && !goal.completed
                    val badgeBg = if (warn) semantic.warningContainer else fg.copy(alpha = 0.22f)
                    val badgeFg = if (warn) semantic.onWarningContainer else fg
                    Surface(shape = RoundedCornerShape(50), color = badgeBg) {
                        Text(
                            paceBadgeText,
                            color = badgeFg,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        goal.title,
                        color = fg,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (goal.completed) TextDecoration.LineThrough else null
                    )
                    if (!isQuantitative) {
                        Text(
                            snapshot.paceStatusLabel.orEmpty(),
                            color = fg.copy(alpha = 0.90f),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Text(
                            snapshot.displayTarget,
                            color = fg.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    } else {
                        val progressLabel = if (snapshot.target > 0.0) {
                            "${snapshot.displayCurrent} / ${snapshot.displayTarget}"
                        } else {
                            snapshot.displayCurrent
                        }
                        Text(
                            progressLabel,
                            color = fg.copy(alpha = 0.95f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        LinearProgressIndicator(
                            progress = { snapshot.progressFraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(50)),
                            color = fg,
                            trackColor = fg.copy(alpha = 0.24f)
                        )
                        val paceLine = buildList {
                            snapshot.requiredDailyPaceLabel?.let { label ->
                                if (snapshot.metricType == GoalMetricType.ACCURACY) add(label)
                                else add("Ritmo: $label")
                            }
                            add("$formattedTargetDate ($deadlineSubtext)")
                        }.joinToString(" · ")
                        Text(
                            paceLine,
                            color = fg.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                GoalVisualIndicator(snapshot = snapshot, fg = fg)
            }

            if (snapshot.metricType == GoalMetricType.MANUAL && !goal.completed) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Atualizar:", color = fg.copy(alpha = 0.88f), style = MaterialTheme.typography.labelMedium)
                    listOf(-1.0 to "-1", 1.0 to "+1", 5.0 to "+5").forEach { (delta, label) ->
                        Surface(
                            onClick = {
                                val nextVal = (goal.currentValue + delta).coerceAtLeast(0.0)
                                onUpdateGoal(goal.copy(currentValue = nextVal))
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = fg.copy(alpha = 0.18f)
                        ) {
                            Box(
                                modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Surface(
                        onClick = onRequestManualAdjust,
                        shape = RoundedCornerShape(10.dp),
                        color = fg.copy(alpha = 0.24f)
                    ) {
                        Box(
                            modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("+X", color = fg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (snapshot.milestones.isNotEmpty()) {
                val doneMilestones = snapshot.milestones.count { it.done }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = fg.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { milestonesExpanded = !milestonesExpanded }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Checklist, null, tint = fg, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Marcos ($doneMilestones/${snapshot.milestones.size})",
                                color = fg,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (milestonesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                if (milestonesExpanded) "Recolher marcos" else "Expandir marcos",
                                tint = fg
                            )
                        }
                        AnimatedVisibility(visible = milestonesExpanded) {
                            Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
                                snapshot.milestones.forEach { milestone ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable {
                                            val updatedJson = GoalMilestones.toggle(goal.milestonesJson, milestone.id)
                                            onUpdateGoal(goal.copy(milestonesJson = updatedJson))
                                        }.padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = milestone.done,
                                            onCheckedChange = {
                                                val updatedJson = GoalMilestones.toggle(goal.milestonesJson, milestone.id)
                                                onUpdateGoal(goal.copy(milestonesJson = updatedJson))
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = fg,
                                                checkmarkColor = Color(goal.color.toInt()),
                                                uncheckedColor = fg.copy(alpha = 0.72f)
                                            )
                                        )
                                        Text(
                                            milestone.title,
                                            color = fg.copy(alpha = if (milestone.done) 0.65f else 0.95f),
                                            style = MaterialTheme.typography.bodySmall,
                                            textDecoration = if (milestone.done) TextDecoration.LineThrough else null,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = fg.copy(alpha = 0.16f))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onComplete() }
                        .padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = goal.completed,
                        onCheckedChange = { onComplete() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = fg,
                            checkmarkColor = Color(goal.color.toInt()),
                            uncheckedColor = fg.copy(alpha = 0.82f)
                        )
                    )
                    Text(
                        if (goal.completed) "Concluída" else "Concluir",
                        color = fg,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (goal.pinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            if (goal.pinned) "Desafixar da Home" else "Fixar na Home",
                            tint = if (goal.pinned) fg else fg.copy(alpha = 0.82f)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Edit, "Editar meta", tint = fg.copy(alpha = 0.90f))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Delete, "Excluir meta", tint = fg.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalCardBadge(icon: ImageVector, text: String, fg: Color) {
    Surface(shape = RoundedCornerShape(50), color = fg.copy(alpha = 0.16f)) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(13.dp))
            Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun GoalVisualIndicator(snapshot: GoalProgressSnapshot, fg: Color) {
    val isCountdown = snapshot.metricType == GoalMetricType.COUNTDOWN
    val centerText = if (isCountdown) {
        snapshot.current.toLong().toString()
    } else {
        "${(snapshot.progressFraction * 100f).roundToInt()}%"
    }
    val formattedCenter = when (snapshot.goal.displayFormat) {
        "DECIMAL" -> if (isCountdown) centerText else String.format(GOAL_PT_BR_LOCALE, "%.1f%%", snapshot.progressFraction * 100f)
        "M_D" -> if (isCountdown) {
            val d = snapshot.current.toLong().coerceAtLeast(0L)
            "${d / 30}m ${d % 30}d"
        } else centerText
        "D_H_M" -> if (isCountdown) {
            val totalMin = ((snapshot.goal.targetAt - System.currentTimeMillis()).coerceAtLeast(0L)) / 60_000L
            val d = totalMin / (24 * 60)
            val h = (totalMin % (24 * 60)) / 60
            val m = totalMin % 60
            "${d}d ${h}h ${m}m"
        } else centerText
        else -> centerText
    }
    when (snapshot.goal.style) {
        "BAR" -> {
            Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formattedCenter, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(fg.copy(alpha = 0.22f))
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(snapshot.progressFraction.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(50))
                            .background(fg)
                    )
                    val startMs = snapshot.goal.startAt.takeIf { it > 0L } ?: snapshot.goal.createdAt
                    val expected = if (snapshot.goal.targetAt > startMs && startMs > 0L) {
                        ((System.currentTimeMillis() - startMs).toDouble() / (snapshot.goal.targetAt - startMs).toDouble()).toFloat().coerceIn(0.02f, 0.98f)
                    } else null
                    if (expected != null && !isCountdown) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .padding(start = (88f * expected).dp)
                                .width(2.dp)
                                .background(fg.copy(alpha = 0.85f))
                        )
                    }
                }
            }
        }
        "DOTS" -> {
            val totalDots = if (isCountdown && snapshot.current.toLong() in 1L..28L) {
                snapshot.current.toInt().coerceIn(7, 28)
            } else {
                21
            }
            val activeDots = if (isCountdown) {
                ((1f - snapshot.progressFraction) * totalDots).roundToInt().coerceIn(0, totalDots)
            } else {
                (snapshot.progressFraction * totalDots).roundToInt().coerceIn(0, totalDots)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formattedCenter, color = fg, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (rowStart in 0 until totalDots step 7) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in rowStart until minOf(rowStart + 7, totalDots)) {
                                Box(
                                    Modifier.size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (i < activeDots) fg else fg.copy(alpha = 0.26f))
                                )
                            }
                        }
                    }
                }
            }
        }
        "PLAIN" -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val icon = if (isCountdown && snapshot.goal.direction == "SINCE") Icons.Default.History else metricIcon(snapshot.metricType)
                Icon(icon, null, tint = fg, modifier = Modifier.size(32.dp))
                if (!isCountdown) {
                    Text(centerText, color = fg, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        else -> {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { snapshot.progressFraction },
                    modifier = Modifier.size(72.dp),
                    color = fg,
                    trackColor = fg.copy(alpha = 0.22f),
                    strokeWidth = 6.dp
                )
                Text(centerText, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun metricIcon(type: GoalMetricType): ImageVector = when (type) {
    GoalMetricType.COUNTDOWN -> Icons.Default.Event
    GoalMetricType.STUDY_HOURS -> Icons.Default.Timer
    GoalMetricType.QUESTIONS -> Icons.Default.Quiz
    GoalMetricType.ACCURACY -> Icons.Default.Percent
    GoalMetricType.PAGES -> Icons.Default.AutoStories
    GoalMetricType.FLASHCARDS -> Icons.Default.Style
    GoalMetricType.TASKS -> Icons.Default.CheckCircle
    GoalMetricType.HABITS -> Icons.Default.Spa
    GoalMetricType.MANUAL -> Icons.Default.Tune
}

private fun defaultTargetSuggestion(type: GoalMetricType): String = when (type) {
    GoalMetricType.COUNTDOWN -> ""
    GoalMetricType.STUDY_HOURS -> "40"
    GoalMetricType.QUESTIONS -> "300"
    GoalMetricType.ACCURACY -> "85"
    GoalMetricType.PAGES -> "200"
    GoalMetricType.FLASHCARDS -> "150"
    GoalMetricType.TASKS -> "20"
    GoalMetricType.HABITS -> "21"
    GoalMetricType.MANUAL -> "10"
}

private fun formatEditableNumber(value: Double): String {
    if (!value.isFinite() || value <= 0.0) return ""
    return if (abs(value - value.roundToLong()) < 0.005) {
        value.roundToLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

@Composable
private fun ManualProgressDialog(
    goal: Goal,
    onDismiss: () -> Unit,
    onApplyDelta: (Double) -> Unit
) {
    var amountText by remember { mutableStateOf("1") }
    val parsed = amountText.replace(',', '.').toDoubleOrNull()
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Atualizar progresso") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Quanto adicionar em “${goal.title}”?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Quantidade (${goal.metricUnit.ifBlank { GoalMetricType.MANUAL.defaultUnit }})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (parsed != null && parsed != 0.0) onApplyDelta(parsed) },
                enabled = parsed != null && parsed != 0.0
            ) { Text("Aplicar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun GoalEditorDialog(
    existing: Goal?,
    subjects: List<Subject>,
    onDismiss: () -> Unit,
    onSave: (Goal) -> Unit
) {
    val context = LocalContext.current
    val initialType = remember(existing?.id) {
        GoalMetricType.fromId(existing?.metricType ?: GoalMetricType.COUNTDOWN.id)
    }
    var title by remember(existing?.id) { mutableStateOf(existing?.title.orEmpty()) }
    var metricType by remember(existing?.id) { mutableStateOf(initialType) }
    var targetValueText by remember(existing?.id) {
        mutableStateOf(
            when {
                existing != null && existing.targetValue > 0.0 -> formatEditableNumber(existing.targetValue)
                existing != null -> ""
                else -> defaultTargetSuggestion(initialType)
            }
        )
    }
    var currentValueText by remember(existing?.id) {
        mutableStateOf(if (existing != null && existing.currentValue > 0.0) formatEditableNumber(existing.currentValue) else "0")
    }
    var metricUnit by remember(existing?.id) {
        mutableStateOf(existing?.metricUnit?.ifBlank { initialType.defaultUnit } ?: initialType.defaultUnit)
    }
    var subjectId by remember(existing?.id) { mutableStateOf(existing?.subjectId) }
    var targetAt by remember(existing?.id) {
        mutableLongStateOf(
            existing?.targetAt?.takeIf { it > 0L } ?: run {
                val zone = java.time.ZoneId.systemDefault()
                java.time.LocalDate.now(zone).plusDays(14).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            }
        )
    }
    var direction by remember(existing?.id) { mutableStateOf(existing?.direction ?: "COUNTDOWN") }
    var countdownUnit by remember(existing?.id) { mutableStateOf(existing?.unit ?: "DAYS") }
    var roundUp by remember(existing?.id) { mutableStateOf(existing?.roundUp ?: true) }
    var color by remember(existing?.id) { mutableLongStateOf(existing?.color ?: goalCardPalettes.first().value) }
    var style by remember(existing?.id) { mutableStateOf(existing?.style ?: "RING") }
    var preset by remember(existing?.id) { mutableStateOf(existing?.preset ?: "DEFAULT") }
    var displayFormat by remember(existing?.id) { mutableStateOf(existing?.displayFormat ?: "AUTO") }
    var pinned by remember(existing?.id) { mutableStateOf(existing?.pinned ?: false) }
    var milestones by remember(existing?.id) { mutableStateOf(GoalMilestones.parse(existing?.milestonesJson)) }
    var newMilestoneText by remember { mutableStateOf("") }

    var unitExpanded by remember { mutableStateOf(false) }
    var subjectExpanded by remember { mutableStateOf(false) }

    val parsedTarget = targetValueText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val parsedCurrent = (currentValueText.replace(',', '.').toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
    val canSave = title.trim().isNotEmpty() && (
        metricType == GoalMetricType.COUNTDOWN ||
            parsedTarget > 0.0 ||
            (metricType == GoalMetricType.MANUAL && milestones.isNotEmpty())
        )

    fun selectMetricType(nextType: GoalMetricType) {
        val oldDefaultUnit = metricType.defaultUnit
        val oldDefaultTarget = defaultTargetSuggestion(metricType)
        metricType = nextType
        if (metricUnit.isBlank() || metricUnit == oldDefaultUnit) {
            metricUnit = nextType.defaultUnit
        }
        if (nextType != GoalMetricType.COUNTDOWN && (targetValueText.isBlank() || targetValueText == oldDefaultTarget)) {
            targetValueText = defaultTargetSuggestion(nextType)
        }
    }

    fun appendMilestone() {
        val clean = newMilestoneText.trim()
        if (clean.isNotEmpty()) {
            milestones = milestones + GoalMilestone(id = UUID.randomUUID().toString(), title = clean, done = false)
            newMilestoneText = ""
        }
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nova meta ou contagem" else "Editar meta") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título da meta") },
                    placeholder = { Text("Ex.: 40h de Matemática ou Prova Final") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Tipo de acompanhamento", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GoalMetricType.entries.forEach { option ->
                        FilterChip(
                            selected = metricType == option,
                            onClick = { selectMetricType(option) },
                            leadingIcon = { Icon(metricIcon(option), null, modifier = Modifier.size(16.dp)) },
                            label = { Text(option.label) }
                        )
                    }
                }

                if (metricType != GoalMetricType.COUNTDOWN) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = targetValueText,
                            onValueChange = { targetValueText = it },
                            label = { Text(if (metricType == GoalMetricType.ACCURACY) "Alvo (%)" else "Valor alvo") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = metricUnit,
                            onValueChange = { metricUnit = it },
                            label = { Text("Unidade") },
                            singleLine = true,
                            modifier = Modifier.weight(0.8f)
                        )
                    }
                    if (metricType == GoalMetricType.MANUAL) {
                        OutlinedTextField(
                            value = currentValueText,
                            onValueChange = { currentValueText = it },
                            label = { Text("Valor atual") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (subjects.isNotEmpty()) {
                    val selectedSubjectName = subjectId?.let { id -> subjects.firstOrNull { it.id == id }?.name } ?: "Geral (todas as matérias)"
                    ExposedDropdownMenuBox(expanded = subjectExpanded, onExpandedChange = { subjectExpanded = !subjectExpanded }) {
                        OutlinedTextField(
                            value = selectedSubjectName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Matéria vinculada") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(subjectExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = subjectExpanded, onDismissRequest = { subjectExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Geral (todas as matérias)") },
                                onClick = { subjectId = null; subjectExpanded = false }
                            )
                            subjects.forEach { subj ->
                                DropdownMenuItem(
                                    text = { Text(subj.name) },
                                    leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(subj.color.toInt()))) },
                                    onClick = { subjectId = subj.id; subjectExpanded = false }
                                )
                            }
                        }
                    }
                }

                if (metricType == GoalMetricType.COUNTDOWN) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = direction == "COUNTDOWN",
                            onClick = { direction = "COUNTDOWN" },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) { Text("Contagem", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                        SegmentedButton(
                            selected = direction == "SINCE",
                            onClick = { direction = "SINCE" },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) { Text("Desde", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val zone = java.time.ZoneId.systemDefault()
                        val d0 = java.time.Instant.ofEpochMilli(targetAt).atZone(zone).toLocalDate()
                        DatePickerDialog(
                            context,
                            { _, y, m, d ->
                                targetAt = java.time.LocalDate.of(y, m + 1, d).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                            },
                            d0.year,
                            d0.monthValue - 1,
                            d0.dayOfMonth
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    val datePrefix = if (metricType == GoalMetricType.COUNTDOWN && direction == "SINCE") "Data inicial" else "Prazo"
                    val dialogDateText = remember(targetAt) {
                        Instant.ofEpochMilli(targetAt).atZone(ZoneId.systemDefault()).toLocalDate().format(GOAL_DATE_FORMATTER)
                    }
                    Text(
                        "  $datePrefix: $dialogDateText",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (metricType == GoalMetricType.COUNTDOWN) {
                    ExposedDropdownMenuBox(expanded = unitExpanded, onExpandedChange = { unitExpanded = !unitExpanded }) {
                        OutlinedTextField(
                            value = when (countdownUnit) {
                                "HOURS" -> "Horas"
                                "WEEKS" -> "Semanas"
                                "MONTHS" -> "Meses"
                                "YEARS" -> "Anos"
                                else -> "Dias"
                            },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Unidade exibida") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(unitExpanded) },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                            listOf("Dias" to "DAYS", "Horas" to "HOURS", "Semanas" to "WEEKS", "Meses" to "MONTHS", "Anos" to "YEARS").forEach { (label, key) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = { countdownUnit = key; unitExpanded = false })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { roundUp = !roundUp }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.SwapVert, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (roundUp) "Arredondar para cima" else "Arredondar para baixo", modifier = Modifier.weight(1f).padding(start = 10.dp))
                        Switch(checked = roundUp, onCheckedChange = { roundUp = it })
                    }
                }

                Text("Indicador", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("RING" to "Anel", "BAR" to "Barra", "DOTS" to "Dot Grid", "PLAIN" to "Simples").forEach { (key, label) ->
                        FilterChip(selected = style == key, onClick = { style = key }, label = { Text(label) })
                    }
                }

                Text("Formato e Preset (Pretty Progress)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("AUTO" to "Auto", "M_D" to "M/D", "D_H_M" to "D/H/M", "DECIMAL" to "64.5%").forEach { (k, lbl) ->
                        FilterChip(selected = displayFormat == k, onClick = { displayFormat = k }, label = { Text(lbl) })
                    }
                }
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("DEFAULT" to "Padrão", "SWISS" to "Swiss", "MINIMAL" to "Minimal", "AQUA" to "Aqua").forEach { (k, lbl) ->
                        FilterChip(selected = preset == k, onClick = { preset = k }, label = { Text(lbl) })
                    }
                }
                Text("Cor do cartão", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    goalCardPalettes.forEach { option ->
                        val swatchColor = Color(option.value.toInt())
                        Surface(
                            onClick = { color = option.value },
                            shape = CircleShape,
                            color = swatchColor,
                            modifier = Modifier.size(38.dp),
                            border = if (color == option.value) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null
                        ) {
                            if (color == option.value) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = option.name,
                                        tint = accessibleOnColor(swatchColor),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().clickable { pinned = !pinned }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PushPin, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Fixar na tela inicial (Hoje)", modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }

                HorizontalDivider()
                Text("Marcos / Submetas (${milestones.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                milestones.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = item.done,
                            onCheckedChange = { checked ->
                                milestones = milestones.map { if (it.id == item.id) it.copy(done = checked) else it }
                            }
                        )
                        Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { milestones = milestones.filterNot { it.id == item.id } }) {
                            Icon(Icons.Default.Close, "Remover marco", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newMilestoneText,
                        onValueChange = { newMilestoneText = it },
                        label = { Text("Novo marco") },
                        placeholder = { Text("Ex.: Ler cap. 1 a 3") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { appendMilestone() }),
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(onClick = { appendMilestone() }, enabled = newMilestoneText.isNotBlank()) {
                        Icon(Icons.Default.Add, "Adicionar marco")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sanitizedTarget = when (metricType) {
                        GoalMetricType.COUNTDOWN -> 0.0
                        GoalMetricType.ACCURACY -> parsedTarget.coerceIn(0.0, 100.0)
                        else -> parsedTarget.coerceAtLeast(0.0)
                    }
                    onSave(
                        Goal(
                            id = existing?.id ?: 0L,
                            title = title.trim(),
                            targetAt = targetAt,
                            completed = existing?.completed ?: false,
                            direction = if (metricType == GoalMetricType.COUNTDOWN) direction else "COUNTDOWN",
                            unit = if (metricType == GoalMetricType.COUNTDOWN) countdownUnit else "DAYS",
                            roundUp = roundUp,
                            color = color,
                            style = style,
                            createdAt = existing?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            metricType = metricType.id,
                            currentValue = when (metricType) { GoalMetricType.MANUAL -> parsedCurrent; GoalMetricType.COUNTDOWN -> 0.0; else -> existing?.currentValue ?: 0.0 },
                            targetValue = sanitizedTarget,
                            metricUnit = if (metricType == GoalMetricType.COUNTDOWN) "" else metricUnit.trim().ifBlank { metricType.defaultUnit },
                            subjectId = subjectId,
                            milestonesJson = GoalMilestones.serialize(milestones),
                            pinned = pinned,
                            startAt = existing?.startAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            bookId = existing?.bookId,
                            displayFormat = displayFormat,
                            preset = preset
                        )
                    )
                },
                enabled = canSave
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

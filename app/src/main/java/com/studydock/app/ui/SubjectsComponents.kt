@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.app.DatePickerDialog
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
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private val SUBJ_PT_BR_LOCALE: Locale = Locale("pt", "BR")
private val SUBJ_SHORT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", SUBJ_PT_BR_LOCALE)
private val SUBJ_FULL_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", SUBJ_PT_BR_LOCALE)

internal val subjectPalette = listOf(
    0xFF6156D9L,
    0xFF258A8AL,
    0xFFE07846L,
    0xFF3B82C4L,
    0xFFB64E78L,
    0xFF71864AL,
    0xFF8C63B5L,
    0xFF596273L
)

private enum class SubjectSortMode(val label: String) {
    WEIGHT("Peso / Ciclo"),
    PENDING("Pendentes hoje"),
    ALPHABETICAL("Alfabética")
}

/**
 * Painel principal de Matérias ("Centro de Comando das Disciplinas") com ciclo de estudos
 * proporcional por peso, filtro de arquivadas, ordenação, próximo passo inline e visão de ecossistema.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectsDashboardPage(
    subjects: List<Subject>,
    sessions: List<StudySession>,
    tasks: List<StudyTask> = emptyList(),
    flashcards: List<Flashcard> = emptyList(),
    mistakes: List<Mistake> = emptyList(),
    questionLogs: List<QuestionLog> = emptyList(),
    dailyGoalMinutes: Int = 180,
    onSaveSubject: (Subject) -> Unit,
    onDeleteSubject: (Subject) -> Unit,
    onStart: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    canStart: (Long) -> Boolean = { true },
    timerLocked: Boolean = false,
    onDeleteSubjectWithChildren: ((Subject, Boolean) -> Unit)? = null
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }

    var search by rememberSaveable { mutableStateOf("") }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var sortModeName by rememberSaveable { mutableStateOf(SubjectSortMode.WEIGHT.name) }
    val sortMode = SubjectSortMode.entries.firstOrNull { it.name == sortModeName } ?: SubjectSortMode.WEIGHT

    var editorOpen by remember { mutableStateOf(false) }
    var editingSubject by remember { mutableStateOf<Subject?>(null) }
    var presetParentSubject by remember { mutableStateOf<Subject?>(null) }
    var deleteTarget by remember { mutableStateOf<Subject?>(null) }

    var overviews by remember { mutableStateOf<List<SubjectEcosystemOverview>>(emptyList()) }
    LaunchedEffect(subjects, sessions, tasks, flashcards, mistakes, questionLogs, dailyGoalMinutes, now) {
        overviews = withContext(Dispatchers.Default) {
            SubjectEcosystemEngine.buildOverview(
                subjects = subjects,
                sessions = sessions,
                tasks = tasks,
                flashcards = flashcards,
                mistakes = mistakes,
                questionLogs = questionLogs,
                dailyGoalMinutes = dailyGoalMinutes,
                now = now
            )
        }
    }

    val activeOverviews = remember(overviews) { overviews.filter { !it.subject.archived } }
    val archivedCount = remember(overviews) { overviews.count { it.subject.archived } }
    val subjectsById = remember(subjects) { subjects.associateBy { it.id } }
    val overviewById = remember(overviews) { overviews.associateBy { it.subject.id } }

    val visibleOverviews = remember(overviews, search, showArchived, sortMode) {
        val query = search.trim()
        val matchingChildParents = if (query.isNotEmpty()) {
            overviews.mapNotNullTo(HashSet()) { ov ->
                if (ov.subject.name.contains(query, ignoreCase = true) ||
                    ov.subject.nextStep.orEmpty().contains(query, ignoreCase = true)
                ) {
                    ov.subject.parentId
                } else {
                    null
                }
            }
        } else {
            emptySet()
        }

        val filtered = overviews.filter { ov ->
            val archivedMatch = showArchived || !ov.subject.archived
            if (!archivedMatch) return@filter false
            if (query.isNotEmpty()) {
                val parentName = ov.subject.parentId?.let { overviewById[it]?.subject?.name }.orEmpty()
                ov.subject.name.contains(query, ignoreCase = true) ||
                    parentName.contains(query, ignoreCase = true) ||
                    ov.subject.nextStep.orEmpty().contains(query, ignoreCase = true) ||
                    ov.subject.id in matchingChildParents
            } else {
                // Sem busca ativa, mostramos cards principais (raízes) com suas submatérias dentro,
                // além de submatérias ativas cuja mãe esteja arquivada quando arquivadas estão ocultas.
                val parent = ov.subject.parentId?.let { overviewById[it]?.subject }
                parent == null || (!showArchived && parent.archived && !ov.subject.archived)
            }
        }
        when (sortMode) {
            SubjectSortMode.WEIGHT -> filtered.sortedWith(
                compareBy<SubjectEcosystemOverview> { it.subject.archived }
                    .thenByDescending { it.subject.weight.coerceAtLeast(1) }
                    .thenByDescending { it.recommendedMinutesPerDay }
                    .thenBy { it.subject.name.lowercase(SUBJ_PT_BR_LOCALE) }
            )
            SubjectSortMode.PENDING -> filtered.sortedWith(
                compareBy<SubjectEcosystemOverview> { it.subject.archived }
                    .thenByDescending { pendingUrgencyScore(it) }
                    .thenByDescending { it.subject.weight.coerceAtLeast(1) }
                    .thenBy { it.subject.name.lowercase(SUBJ_PT_BR_LOCALE) }
            )
            SubjectSortMode.ALPHABETICAL -> filtered.sortedWith(
                compareBy<SubjectEcosystemOverview> { it.subject.archived }
                    .thenBy { it.subject.name.lowercase(SUBJ_PT_BR_LOCALE) }
            )
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        StudyPageHeader(
            title = "Matérias",
            subtitle = if (timerLocked) "Finalize a sessão atual antes de iniciar outra matéria."
            else "Matérias, submatérias, ciclo por pesos e metas por disciplina.",
            showGlobalSearch = true,
            actions = {
                FilledTonalIconButton(
                    onClick = {
                        editingSubject = null
                        presetParentSubject = null
                        editorOpen = true
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, "Adicionar matéria")
                }
            }
        )

        if (subjects.isEmpty()) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Sua lista começa aqui", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cadastre suas disciplinas com peso no ciclo, meta diária, próximo passo e data de prova.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Button(
                        onClick = {
                            editingSubject = null
                            editorOpen = true
                        },
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Text(" Nova matéria")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "cycle_summary", contentType = "cycle_summary") {
                    StudyCycleSummaryCard(
                        activeOverviews = activeOverviews,
                        allOverviews = overviews,
                        dailyGoalMinutes = dailyGoalMinutes,
                        onOpenSubject = onOpen
                    )
                }

                item(key = "search_and_filters", contentType = "search_and_filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudyLocalSearchField(
                            query = search,
                            onQueryChange = { search = it },
                            placeholder = "Buscar matéria ou próximo passo"
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SubjectSortMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortModeName = mode.name },
                                    label = { Text(mode.label) }
                                )
                            }
                            FilterChip(
                                selected = showArchived,
                                onClick = { showArchived = !showArchived },
                                leadingIcon = {
                                    Icon(
                                        if (showArchived) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                label = {
                                    Text(if (archivedCount > 0) "Arquivadas ($archivedCount)" else "Arquivadas")
                                }
                            )
                        }
                    }
                }

                if (visibleOverviews.isEmpty()) {
                    item(key = "empty_filtered", contentType = "empty_filtered") {
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    when {
                                        search.isNotBlank() -> "Nenhuma matéria encontrada para \"$search\"."
                                        activeOverviews.isEmpty() && archivedCount > 0 && !showArchived ->
                                            "Todas as suas matérias estão arquivadas."
                                        else -> "Nenhuma matéria visível."
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (activeOverviews.isEmpty() && archivedCount > 0 && !showArchived) {
                                    OutlinedButton(onClick = { showArchived = true }) {
                                        Text("Mostrar arquivadas ($archivedCount)")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(
                        items = visibleOverviews,
                        key = { it.subject.id },
                        contentType = { "subject_card" }
                    ) { overview ->
                        val childOverviews = remember(overviews, overview.subject.id, showArchived) {
                            if (overview.subject.parentId == null) {
                                overviews.filter {
                                    it.subject.parentId == overview.subject.id &&
                                        (showArchived || !it.subject.archived)
                                }
                            } else {
                                emptyList()
                            }
                        }
                        val rollUp = remember(overviews, overview.subject.id) {
                            if (overview.subject.parentId == null &&
                                overviews.any { it.subject.parentId == overview.subject.id }
                            ) {
                                SubjectHierarchy.rollUpForParent(overview.subject.id, overviews)
                            } else {
                                null
                            }
                        }
                        val parentSubj = overview.subject.parentId?.let { subjectsById[it] }
                        SubjectDashboardCard(
                            overview = overview,
                            parentSubject = parentSubj,
                            childrenOverviews = childOverviews,
                            rollUp = rollUp,
                            canStart = canStart(overview.subject.id) && !overview.subject.archived,
                            canStartChild = { childId -> canStart(childId) },
                            onOpen = { onOpen(overview.subject.id) },
                            onOpenChild = { childId -> onOpen(childId) },
                            onStart = { onStart(overview.subject.id) },
                            onStartChild = { childId -> onStart(childId) },
                            onAddSubSubject = if (overview.subject.parentId == null) {
                                {
                                    editingSubject = null
                                    presetParentSubject = overview.subject
                                    editorOpen = true
                                }
                            } else null,
                            onEdit = {
                                editingSubject = overview.subject
                                presetParentSubject = null
                                editorOpen = true
                            },
                            onToggleArchive = {
                                onSaveSubject(overview.subject.copy(archived = !overview.subject.archived))
                            },
                            onSaveNextStep = { nextText ->
                                onSaveSubject(overview.subject.copy(nextStep = nextText))
                            },
                            onDelete = { deleteTarget = overview.subject }
                        )
                    }
                }
            }
        }
    }

    if (editorOpen) {
        SubjectEditorDialog(
            initial = editingSubject,
            defaultColor = presetParentSubject?.color ?: subjectPalette[subjects.size % subjectPalette.size],
            allSubjects = subjects,
            initialParentSubject = presetParentSubject,
            onDismiss = {
                editorOpen = false
                editingSubject = null
                presetParentSubject = null
            },
            onSave = { saved ->
                onSaveSubject(saved)
                editorOpen = false
                editingSubject = null
                presetParentSubject = null
            }
        )
    }

    deleteTarget?.let { target ->
        val childCount = remember(target.id, subjects) {
            SubjectHierarchy.childrenOf(target.id, subjects).size
        }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Excluir matéria?") },
            text = {
                Text(
                    if (childCount > 0) {
                        "\"${target.name}\" possui $childCount submatéria(s). Você pode mantê-las como matérias principais ou excluir tudo."
                    } else {
                        "As sessões existentes de \"${target.name}\" continuam no histórico, mas deixam de ficar ligadas a esta matéria."
                    }
                )
            },
            confirmButton = {
                if (childCount > 0 && onDeleteSubjectWithChildren != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onDeleteSubjectWithChildren(target, false)
                                deleteTarget = null
                            }
                        ) { Text("Manter submatérias") }
                        Button(
                            onClick = {
                                onDeleteSubjectWithChildren(target, true)
                                deleteTarget = null
                            }
                        ) { Text("Excluir tudo") }
                    }
                } else {
                    Button(
                        onClick = {
                            onDeleteSubject(target)
                            deleteTarget = null
                        }
                    ) { Text("Excluir") }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

private fun pendingUrgencyScore(overview: SubjectEcosystemOverview): Int {
    val targetSec = overview.recommendedMinutesPerDay.coerceAtLeast(0) * 60L
    val goalPending = if (targetSec > 0L && overview.todayFocusSeconds < targetSec) 2 else 0
    return (overview.overdueTasksCount * 5) +
        (overview.dueMistakesCount * 3) +
        overview.dueFlashcardsCount +
        overview.openTasksCount +
        goalPending
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyCycleSummaryCard(
    activeOverviews: List<SubjectEcosystemOverview>,
    allOverviews: List<SubjectEcosystemOverview>,
    dailyGoalMinutes: Int,
    onOpenSubject: (Long) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val todayTotalSeconds = allOverviews.sumOf { it.todayFocusSeconds }
    val totalFocusSeconds = allOverviews.sumOf { it.totalFocusSeconds }
    val subjectsTargetSum = activeOverviews.sumOf { it.subject.targetMinutesPerDay.coerceAtLeast(0) }
    val effectiveDailyGoalMin = if (subjectsTargetSum > 0) subjectsTargetSum else dailyGoalMinutes.coerceAtLeast(0)

    val totalOpenTasks = activeOverviews.sumOf { it.openTasksCount }
    val totalDueCards = activeOverviews.sumOf { it.dueFlashcardsCount }
    val totalDueMistakes = activeOverviews.sumOf { it.dueMistakesCount }

    val sortedCycle = remember(activeOverviews) {
        activeOverviews.sortedWith(
            compareByDescending<SubjectEcosystemOverview> { it.subject.weight.coerceAtLeast(1) }
                .thenByDescending { it.recommendedMinutesPerDay }
                .thenBy { it.subject.name.lowercase(Locale("pt", "BR")) }
        )
    }

    StudyCard(emphasized = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DonutLarge, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        "Ciclo de Estudos · Distribuição por Peso",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    val goalLabel = if (effectiveDailyGoalMin > 0) {
                        "Meta diária: ${formatMinutesAsHoursMinutes(effectiveDailyGoalMin)}" +
                            if (subjectsTargetSum > 0) " (soma das matérias)" else " (meta geral)"
                    } else {
                        "Sem meta diária definida"
                    }
                    Text(
                        "${activeOverviews.size} ativas · $goalLabel",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatCompactSubjectDuration(todayTotalSeconds),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "hoje · ${formatCompactSubjectDuration(totalFocusSeconds)} total",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (sortedCycle.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(50)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    sortedCycle.forEach { ov ->
                        Box(
                            modifier = Modifier
                                .weight(ov.cycleSharePercent.coerceAtLeast(1f))
                                .fillMaxHeight()
                                .background(Color(ov.subject.color.toInt()))
                        )
                    }
                }

                val shownItems = if (expanded || sortedCycle.size <= 4) sortedCycle else sortedCycle.take(4)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    shownItems.forEach { ov ->
                        val shareInt = ov.cycleSharePercent.roundToInt()
                        Surface(
                            onClick = { onOpenSubject(ov.subject.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(ov.subject.color.toInt()))
                                )
                                Text(
                                    ov.subject.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 110.dp)
                                )
                                Text(
                                    "P${ov.subject.weight} · $shareInt% (${ov.recommendedMinutesPerDay}m)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (sortedCycle.size > 4) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Text(
                            if (expanded) "Mostrar menos" else "Ver todas (${sortedCycle.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (totalOpenTasks > 0 || totalDueCards > 0 || totalDueMistakes > 0) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (totalOpenTasks > 0) {
                        CyclePill("$totalOpenTasks tarefas abertas")
                    }
                    if (totalDueCards > 0) {
                        CyclePill("$totalDueCards Anki devidos")
                    }
                    if (totalDueMistakes > 0) {
                        CyclePill("$totalDueMistakes erros p/ revisar")
                    }
                }
            }
        }
    }
}

@Composable
private fun CyclePill(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectDashboardCard(
    overview: SubjectEcosystemOverview,
    parentSubject: Subject? = null,
    childrenOverviews: List<SubjectEcosystemOverview> = emptyList(),
    rollUp: SubjectRollUp? = null,
    canStart: Boolean,
    canStartChild: (Long) -> Boolean = { false },
    onOpen: () -> Unit,
    onOpenChild: ((Long) -> Unit)? = null,
    onStart: () -> Unit,
    onStartChild: ((Long) -> Unit)? = null,
    onAddSubSubject: (() -> Unit)? = null,
    onEdit: () -> Unit,
    onToggleArchive: () -> Unit,
    onSaveNextStep: (String?) -> Unit,
    onDelete: () -> Unit
) {
    val subject = overview.subject
    val subjectColor = Color(subject.color.toInt())
    val barAccent = if (subject.archived) subjectColor.copy(alpha = 0.45f) else subjectColor
    val cardContainer = if (subject.archived) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surface
    }
    val semantic = LocalStudySemanticColors.current
    val accent = accessibleAccentOnSurface(
        if (subject.archived) subjectColor.copy(alpha = 0.72f) else subjectColor,
        cardContainer
    )
    val successAccent = accessibleAccentOnSurface(semantic.success, cardContainer)

    val targetMin = if (subject.targetMinutesPerDay > 0) subject.targetMinutesPerDay else overview.recommendedMinutesPerDay
    val todayLabel = formatMinutesBadge(overview.todayFocusSeconds)
    val dailyFraction = if (targetMin > 0) {
        (overview.todayFocusSeconds.toFloat() / (targetMin * 60f)).coerceIn(0f, 1f)
    } else 0f

    val examBadge = remember(subject.examDate, overview.daysUntilExam) {
        examCountdownInfo(subject.examDate, overview.daysUntilExam)
    }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = cardContainer),
        shape = studyCardShape()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(50))
                        .background(barAccent)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 10.dp, end = 6.dp)
                ) {
                    Text(
                        subject.name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (parentSubject != null) {
                            SubjectBadgePill(
                                text = "Submatéria de ${parentSubject.name}",
                                containerColor = accent.copy(alpha = 0.14f),
                                contentColor = accent
                            )
                        } else if (childrenOverviews.isNotEmpty()) {
                            SubjectBadgePill(
                                text = "${childrenOverviews.size} ${if (childrenOverviews.size == 1) "submatéria" else "submatérias"}",
                                containerColor = accent.copy(alpha = 0.14f),
                                contentColor = accent
                            )
                        }
                        if (subject.archived) {
                            SubjectBadgePill(
                                text = "Arquivada",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            SubjectBadgePill(
                                text = "Peso ${subject.weight} · ${overview.cycleSharePercent.roundToInt()}% ciclo",
                                containerColor = accent.copy(alpha = 0.14f),
                                contentColor = accent
                            )
                        }
                        if (examBadge != null) {
                            val (label, urgent) = examBadge
                            SubjectBadgePill(
                                text = label,
                                containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
                FilledTonalIconButton(
                    onClick = onStart,
                    enabled = canStart
                ) {
                    Icon(Icons.Default.PlayArrow, "Estudar ${subject.name}")
                }
            }

            // Barra de meta diária
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val goalProgressText = when {
                        subject.targetMinutesPerDay > 0 -> "$todayLabel/${subject.targetMinutesPerDay}m hoje"
                        overview.recommendedMinutesPerDay > 0 -> "$todayLabel/${overview.recommendedMinutesPerDay}m ciclo"
                        else -> "$todayLabel hoje"
                    }
                    Text(
                        text = goalProgressText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (targetMin > 0 && overview.todayFocusSeconds >= targetMin * 60L) {
                            successAccent
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    val lastText = remember(overview.lastStudiedAt) {
                        overview.lastStudiedAt?.let {
                            val formatted = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(SUBJ_SHORT_DATE_FORMATTER)
                            "Último: $formatted"
                        } ?: "Sem sessões"
                    }
                    Text(
                        text = "Total ${formatCompactSubjectDuration(overview.totalFocusSeconds)} · $lastText",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (targetMin > 0 || overview.todayFocusSeconds > 0L) {
                    LinearProgressIndicator(
                        progress = { if (targetMin > 0) dailyFraction else 1f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (targetMin > 0 && overview.todayFocusSeconds >= targetMin * 60L) {
                            successAccent
                        } else {
                            accent
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            // Próximo passo clicável / editável inline
            InlineNextStepField(
                subjectId = subject.id,
                nextStep = subject.nextStep,
                accentColor = accent,
                editable = true,
                onSave = onSaveNextStep
            )

            // Métricas integradas do ecossistema
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val taskLabel = if (overview.overdueTasksCount > 0) {
                    "${overview.openTasksCount} tarefas (${overview.overdueTasksCount} atr.)"
                } else {
                    "${overview.openTasksCount} ${if (overview.openTasksCount == 1) "tarefa" else "tarefas"}"
                }
                SubjectBadgePill(
                    text = taskLabel,
                    containerColor = if (overview.overdueTasksCount > 0) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (overview.overdueTasksCount > 0) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                val ankiLabel = if (overview.dueFlashcardsCount > 0) {
                    "${overview.dueFlashcardsCount} Anki devidos"
                } else {
                    "${overview.totalFlashcardsCount} Anki"
                }
                SubjectBadgePill(
                    text = ankiLabel,
                    containerColor = if (overview.dueFlashcardsCount > 0) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (overview.dueFlashcardsCount > 0) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                if (overview.dueMistakesCount > 0 || overview.unresolvedMistakesCount > 0) {
                    val mistakeLabel = if (overview.dueMistakesCount > 0) {
                        "${overview.dueMistakesCount} ${if (overview.dueMistakesCount == 1) "erro" else "erros"} p/ revisar"
                    } else {
                        "${overview.unresolvedMistakesCount} ${if (overview.unresolvedMistakesCount == 1) "erro aberto" else "erros abertos"}"
                    }
                    SubjectBadgePill(
                        text = mistakeLabel,
                        containerColor = if (overview.dueMistakesCount > 0) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (overview.dueMistakesCount > 0) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                val accuracyLabel = if (overview.questionAccuracyPercent != null) {
                    "${overview.questionAccuracyPercent}% acertos (${overview.questionsTotal}q)"
                } else {
                    "Sem questões"
                }
                SubjectBadgePill(
                    text = accuracyLabel,
                    containerColor = when {
                        overview.questionAccuracyPercent == null -> MaterialTheme.colorScheme.surfaceVariant
                        overview.questionAccuracyPercent >= 75 -> semantic.successContainer
                        overview.questionAccuracyPercent < 55 -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = when {
                        overview.questionAccuracyPercent == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        overview.questionAccuracyPercent >= 75 -> semantic.onSuccessContainer
                        overview.questionAccuracyPercent < 55 -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (childrenOverviews.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Submatérias (${childrenOverviews.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (rollUp != null && (rollUp.todayFocusSeconds > 0L || rollUp.openTasksCount > 0 || rollUp.questionsTotal > 0)) {
                                Text(
                                    text = buildString {
                                        append("Consolidado: ${formatCompactSubjectDuration(rollUp.todayFocusSeconds)} hoje")
                                        if (rollUp.openTasksCount > 0) append(" · ${rollUp.openTasksCount} tar.")
                                        rollUp.questionAccuracyPercent?.let { acc ->
                                            append(" · $acc% (${rollUp.questionsTotal}q)")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        childrenOverviews.forEach { childOv ->
                            val child = childOv.subject
                            val childColor = Color(child.color.toInt())
                            val childMeta = buildList {
                                if (childOv.todayFocusSeconds > 0L) add("${formatMinutesBadge(childOv.todayFocusSeconds)} hoje")
                                if (childOv.openTasksCount > 0) add("${childOv.openTasksCount} tar.")
                                if (childOv.dueFlashcardsCount > 0) add("${childOv.dueFlashcardsCount} Anki")
                                childOv.questionAccuracyPercent?.let { add("$it%") }
                            }.joinToString(" · ").ifEmpty {
                                child.nextStep?.trim().takeIf { !it.isNullOrEmpty() } ?: "Abrir submatéria"
                            }
                            Surface(
                                onClick = { onOpenChild?.invoke(child.id) },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(childColor)
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = child.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = childMeta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!child.archived && onStartChild != null && canStartChild(child.id)) {
                                        IconButton(
                                            onClick = { onStartChild(child.id) },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Iniciar ${child.name}",
                                                tint = accent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Botões diretos (Abrir detalhes / Editar / Arquivar / Excluir)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onOpen,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Text(" Detalhes", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                if (onAddSubSubject != null && !subject.archived) {
                    TextButton(
                        onClick = onAddSubSubject,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 40.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Text(" Submatéria", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                    Text(" Editar", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                TextButton(
                    onClick = onToggleArchive,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 40.dp)
                ) {
                    Icon(
                        if (subject.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                        null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        if (subject.archived) " Ativar" else " Arquivar",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Excluir ${subject.name}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SubjectBadgePill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
internal fun InlineNextStepField(
    subjectId: Long,
    nextStep: String?,
    accentColor: Color,
    editable: Boolean = true,
    placeholder: String = "Definir próximo passo...",
    onSave: (String?) -> Unit
) {
    var editing by remember(subjectId) { mutableStateOf(false) }
    var draft by remember(subjectId, nextStep) { mutableStateOf(nextStep.orEmpty()) }

    if (editing && editable) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Próximo passo") },
                placeholder = { Text("Ex.: Cap. 4 + 20 questões") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onSave(draft.trim().ifBlank { null })
                        editing = false
                    }
                ),
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(
                onClick = {
                    onSave(draft.trim().ifBlank { null })
                    editing = false
                }
            ) {
                Icon(Icons.Default.Check, "Salvar próximo passo")
            }
            IconButton(
                onClick = {
                    draft = nextStep.orEmpty()
                    editing = false
                }
            ) {
                Icon(Icons.Default.Close, "Cancelar edição")
            }
        }
    } else {
        val hasStep = !nextStep.isNullOrBlank()
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (editable) {
                        Modifier.clickable {
                            draft = nextStep.orEmpty()
                            editing = true
                        }
                    } else {
                        Modifier
                    }
                ),
            shape = RoundedCornerShape(10.dp),
            color = if (hasStep) accentColor.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    Icons.Default.FastForward,
                    contentDescription = null,
                    tint = if (hasStep) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (hasStep) "Próximo: ${nextStep!!.trim()}" else placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (hasStep) FontWeight.Medium else FontWeight.Normal,
                    color = if (hasStep) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (editable) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Editar próximo passo",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Dialog completo para criar ou editar uma Matéria com nome, cor, meta diária, peso no ciclo,
 * próximo passo, data da prova e estado de arquivamento.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubjectEditorDialog(
    initial: Subject?,
    defaultColor: Long = subjectPalette.first(),
    allSubjects: List<Subject> = emptyList(),
    initialParentSubject: Subject? = null,
    onDismiss: () -> Unit,
    onSave: (Subject) -> Unit
) {
    val context = LocalContext.current
    var name by remember(initial?.id, initialParentSubject?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember(initial?.id, initialParentSubject?.id) {
        mutableLongStateOf(initial?.color ?: initialParentSubject?.color ?: defaultColor)
    }
    var targetMinutesText by remember(initial?.id) {
        mutableStateOf((initial?.targetMinutesPerDay ?: 0).toString())
    }
    var weight by remember(initial?.id) { mutableIntStateOf((initial?.weight ?: 1).coerceIn(1, 5)) }
    var nextStep by remember(initial?.id) { mutableStateOf(initial?.nextStep.orEmpty()) }
    var examDate by remember(initial?.id) { mutableStateOf(initial?.examDate) }
    var archived by remember(initial?.id) { mutableStateOf(initial?.archived ?: false) }

    val hasExistingChildren = remember(initial?.id, allSubjects) {
        initial != null && initial.id != 0L && allSubjects.any { it.parentId == initial.id }
    }
    val parentCandidates = remember(initial?.id, allSubjects) {
        allSubjects.filter { s ->
            s.parentId == null && s.id != (initial?.id ?: 0L)
        }
    }
    var parentId by remember(initial?.id, initialParentSubject?.id) {
        mutableStateOf(
            if (hasExistingChildren) null
            else initial?.parentId ?: initialParentSubject?.id
        )
    }

    val selectedMinutes = targetMinutesText.toIntOrNull() ?: -1

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    initial != null -> "Editar matéria"
                    parentId != null -> "Nova submatéria"
                    else -> "Nova matéria"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (parentId != null) "Nome da submatéria" else "Nome da matéria") },
                    placeholder = {
                        Text(
                            if (parentId != null) "Ex.: Geometria Espacial, Álgebra Linear"
                            else "Ex.: Matemática, Cálculo II, Direito Constitucional"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (hasExistingChildren) {
                    Text(
                        "Esta matéria já possui submatérias e permanece como matéria principal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (parentCandidates.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Hierarquia (opcional)", style = MaterialTheme.typography.labelLarge)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = parentId == null,
                                onClick = { parentId = null },
                                label = { Text("Matéria principal") }
                            )
                            parentCandidates.forEach { candidate ->
                                FilterChip(
                                    selected = parentId == candidate.id,
                                    onClick = {
                                        parentId = candidate.id
                                        if (initial == null) {
                                            color = candidate.color
                                        }
                                    },
                                    leadingIcon = {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(Color(candidate.color.toInt()))
                                        )
                                    },
                                    label = { Text(candidate.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Cor", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        subjectPalette.forEach { option ->
                            val swatch = Color(option.toInt())
                            Surface(
                                onClick = { color = option },
                                shape = CircleShape,
                                color = swatch,
                                modifier = Modifier.size(38.dp),
                                border = if (color == option) {
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                                } else null
                            ) {
                                if (color == option) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Cor selecionada",
                                            tint = accessibleOnColor(swatch),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Meta diária (minutos)", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = targetMinutesText,
                        onValueChange = { input ->
                            targetMinutesText = input.filter(Char::isDigit).take(4)
                        },
                        label = { Text("Minutos por dia (0 = usar ciclo)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(0 to "0 (Ciclo)", 30 to "30m", 45 to "45m", 60 to "60m", 90 to "90m").forEach { (mins, label) ->
                            FilterChip(
                                selected = selectedMinutes == mins,
                                onClick = { targetMinutesText = mins.toString() },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Peso no ciclo (1–5)", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Matérias com peso maior recebem fatia proporcionalmente maior no ciclo diário.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (1..5).forEach { w ->
                            FilterChip(
                                selected = weight == w,
                                onClick = { weight = w },
                                label = { Text("Peso $w", maxLines = 1) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = nextStep,
                    onValueChange = { nextStep = it },
                    label = { Text("Próximo passo (opcional)") },
                    placeholder = { Text("Ex.: Ler cap. 3 e fazer 20 questões") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Data da prova (opcional)", style = MaterialTheme.typography.labelLarge)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val zone = java.time.ZoneId.systemDefault()
                                val d0 = examDate?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                                    ?: java.time.LocalDate.now(zone).plusDays(30)
                                DatePickerDialog(
                                    context,
                                    { _, y, m, d ->
                                        examDate = java.time.LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                                    },
                                    d0.year,
                                    d0.monthValue - 1,
                                    d0.dayOfMonth
                                ).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Event, null, modifier = Modifier.size(16.dp))
                            val examDateLabel = remember(examDate) {
                                examDate?.let {
                                    "  " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(SUBJ_FULL_DATE_FORMATTER)
                                } ?: "  Definir data da prova"
                            }
                            Text(
                                examDateLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (examDate != null) {
                            IconButton(onClick = { examDate = null }) {
                                Icon(Icons.Default.Clear, "Remover data da prova")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { archived = !archived }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Arquivar matéria", fontWeight = FontWeight.Medium)
                        Text(
                            "Remove do ciclo ativo sem perder histórico, notas ou cartões.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = archived, onCheckedChange = { archived = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotBlank()) {
                        val minutes = (targetMinutesText.toIntOrNull() ?: 0).coerceIn(0, 1440)
                        val base = initial ?: Subject(name = trimmed, color = color)
                        onSave(
                            base.copy(
                                name = trimmed,
                                color = color,
                                targetMinutesPerDay = minutes,
                                weight = weight.coerceIn(1, 5),
                                nextStep = nextStep.trim().ifBlank { null },
                                examDate = examDate,
                                archived = archived,
                                parentId = if (hasExistingChildren) null else parentId
                            )
                        )
                    }
                },
                enabled = name.isNotBlank()
            ) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

internal fun examCountdownInfo(examDate: Long?, daysUntilExam: Int?): Pair<String, Boolean>? {
    if (examDate == null || daysUntilExam == null) return null
    val label = when {
        daysUntilExam == 0 -> "Prova hoje!"
        daysUntilExam == 1 -> "Prova amanhã"
        daysUntilExam > 1 -> "Prova em ${daysUntilExam}d"
        else -> "Prova há ${-daysUntilExam}d"
    }
    val urgent = daysUntilExam in 0..7
    return label to urgent
}

internal fun formatMinutesBadge(seconds: Long): String = when {
    seconds <= 0L -> "0m"
    seconds < 60L -> "<1m"
    else -> "${seconds / 60L}m"
}

internal fun formatCompactSubjectDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    if (safe == 0L) return "0min"
    val minutesTotal = (safe + 59L) / 60L
    val hours = minutesTotal / 60L
    val minutes = minutesTotal % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes}min"
}

internal fun formatMinutesAsHoursMinutes(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val h = safe / 60
    val m = safe % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}min"
        h > 0 -> "${h}h"
        else -> "${m}min"
    }
}

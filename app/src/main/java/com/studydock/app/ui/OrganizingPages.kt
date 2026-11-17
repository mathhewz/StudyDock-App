@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.studydock.app.ui

import com.studydock.app.data.*

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.studydock.app.data.StudyTask
import com.studydock.app.data.Note
import com.studydock.app.data.Subject
import com.studydock.app.data.SecretStatus
import com.studydock.app.data.SecretStore
import com.studydock.app.data.StudyLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PT_BR_LOCALE = Locale("pt", "BR")
private val TASK_SHORT_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", PT_BR_LOCALE)
private val TASK_FULL_DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", PT_BR_LOCALE)
private val TASK_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", PT_BR_LOCALE)

private fun localDateFromEpoch(time: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(time).atZone(zone).toLocalDate()

private data class AssistantLoadedConfig(
    val providerMode: AiProviderMode,
    val geminiApiKey: String,
    val geminiModel: String,
    val openAiApiKey: String,
    val openAiBaseUrl: String,
    val openAiModel: String,
    val serviceUrl: String,
    val accessToken: String,
    val hasCorruptedKeystore: Boolean
)

@Composable private fun accentColor() = MaterialTheme.colorScheme.primary

@Composable
private fun HubSectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
fun OrganizationHub(
    onHabits: () -> Unit,
    onGoals: () -> Unit,
    onNotes: () -> Unit,
    onSubjects: () -> Unit,
    onTasks: () -> Unit,
    onBooks: () -> Unit,
    onFitness: () -> Unit,
    onDiary: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StudyPageHeader(
            title = "Organizar",
            subtitle = "Estudos, conhecimento e rotina pessoal",
            showGlobalSearch = false
        )
        Spacer(Modifier.height(4.dp))
        HubSectionTitle("Estudos & Conhecimento")
        HubCard("Matérias & Submatérias", "Ciclo por pesos, submatérias, próximo passo, edital e caderno de erros.", Icons.AutoMirrored.Filled.MenuBook, onSubjects)
        HubCard("Notas (Segundo Cérebro)", "Anotações Markdown, pastas, wikilinks e grafo visual.", Icons.Default.Description, onNotes)
        HubCard("Livros & Leitura", "Estante, leitor integrado, ritmo pág/h, destaques e metas.", Icons.Default.AutoStories, onBooks)

        HubSectionTitle("Execução & Metas")
        HubCard("Tarefas & Kanban", "Organize o que revisar por matéria, prazos e matriz.", Icons.Default.Checklist, onTasks)
        HubCard("Metas & Marcos", "Metas automáticas por horas, questões, páginas e prazos.", Icons.Default.Flag, onGoals)

        HubSectionTitle("Rotina & Bem-estar")
        HubCard("Hábitos", "Check-in diário, sequências, métricas e lembretes programados.", Icons.Default.CheckCircle, onHabits)
        HubCard("Diário", "Check-in de humor/energia, prompts e resumo do dia.", Icons.Default.EditNote, onDiary)
        HubCard("Treino e alimentação", "Planos de treino, descanso, séries e macros diários.", Icons.Default.FitnessCenter, onFitness)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun TasksPage(tasks: List<StudyTask>, subjects: List<Subject>, addTask: (Long?, String, Long) -> Unit, complete: (Long, Boolean) -> Unit, delete: (StudyTask) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    var selectedDay by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var createFor by remember { mutableStateOf<Long?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    val selectedLocalDate = remember(selectedDay, zone) { localDateFromEpoch(selectedDay, zone) }
    val selectedKey = remember(selectedLocalDate) {
        selectedLocalDate.year * 10000L + selectedLocalDate.monthValue * 100L + selectedLocalDate.dayOfMonth
    }
    val tasksByDateKey = remember(tasks, zone) { tasks.groupBy { taskDateKey(it.dueAt, zone) } }
    val selectedTasks = remember(tasksByDateKey, selectedKey) { tasksByDateKey[selectedKey].orEmpty() }
    val selectedTasksBySubject = remember(selectedTasks) { selectedTasks.groupBy { it.subjectId } }
    val weekStart = remember(selectedLocalDate) {
        selectedLocalDate.minusDays((selectedLocalDate.dayOfWeek.value - 1).toLong())
    }
    val groups = remember(subjects, selectedTasksBySubject) {
        buildList {
            subjects.forEach { subj ->
                if (selectedTasksBySubject.containsKey(subj.id)) {
                    add(subj.id to subj.name)
                }
            }
            if (selectedTasksBySubject.containsKey(null)) {
                add(null to "Geral")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        StudyPageHeader(
            title = "Tarefas",
            subtitle = "Organizadas por matéria",
            showGlobalSearch = true,
            actions = {
                IconButton(onClick = { createFor = null; showCreate = true }) {
                    Icon(Icons.Default.Add, "Nova tarefa", tint = accentColor())
                }
            }
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { offset ->
                val date = weekStart.plusDays(offset.toLong())
                val key = date.year * 10000L + date.monthValue * 100L + date.dayOfMonth
                val isSelected = key == selectedKey
                val dayTasks = tasksByDateKey[key].orEmpty()
                val dayNoonMillis = remember(date, zone) {
                    date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                }
                val dayShort = remember(date) {
                    date.format(TASK_SHORT_DAY_FORMATTER).replaceFirstChar { it.uppercase() }
                }
                Surface(
                    onClick = { selectedDay = dayNoonMillis },
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) accentColor() else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.widthIn(min = 56.dp)
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            dayShort,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            date.dayOfMonth.toString(),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            if (dayTasks.isEmpty()) "—" else "${dayTasks.count { it.completed }}/${dayTasks.size}",
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else accentColor(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
        val selectedDateHeader = remember(selectedLocalDate) {
            selectedLocalDate.format(TASK_FULL_DAY_FORMATTER).replaceFirstChar { it.uppercase() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                selectedDateHeader,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Text("${selectedTasks.count { it.completed }}/${selectedTasks.size}", color = accentColor(), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (selectedTasks.isEmpty()) {
                item(key = "tasks_empty", contentType = "empty") {
                    StudyCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Dia livre de tarefas", fontWeight = FontWeight.SemiBold)
                            Text("Adicione o que você quer estudar ou revisar.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = { createFor = null; showCreate = true }, modifier = Modifier.padding(top = 4.dp)) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(4.dp))
                                Text("Nova tarefa", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else {
                groups.forEach { (subjectId, name) ->
                    val groupTasks = selectedTasksBySubject[subjectId].orEmpty()
                    item(key = "grp_hdr_${subjectId ?: "none"}", contentType = "group_header") {
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            IconButton(onClick = { createFor = subjectId; showCreate = true }) { Icon(Icons.Default.Add, "Adicionar em $name", tint = accentColor()) }
                        }
                    }
                    items(groupTasks, key = { "task_${it.id}" }, contentType = { "task" }) { task ->
                        StudyCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = task.completed, onCheckedChange = { complete(task.id, it) })
                                Text(task.title, modifier = Modifier.weight(1f), fontWeight = if (task.completed) FontWeight.Normal else FontWeight.Medium, color = if (task.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                IconButton(onClick = { delete(task) }) { Icon(Icons.Default.Close, "Excluir tarefa", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showCreate) TaskCreateDialog(subjects, createFor, selectedDay, onDismiss = { showCreate = false }, onSave = { id, title, dueAt -> addTask(id, title, dueAt); showCreate = false })
}

@Composable
private fun TaskCreateDialog(subjects: List<Subject>, initialSubject: Long?, selectedDay: Long, onDismiss: () -> Unit, onSave: (Long?, String, Long) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var subjectId by remember { mutableStateOf(initialSubject) }
    var dueAt by remember(selectedDay) { mutableLongStateOf(selectedDay) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nova tarefa") }, text = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("O que estudar?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = subjects.firstOrNull { it.id == subjectId }?.name ?: "Geral",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Matéria") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Geral") }, onClick = { subjectId = null; expanded = false })
                    subjects.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { subjectId = s.id; expanded = false }) }
                }
            }
            OutlinedButton(onClick = {
                val zone = java.time.ZoneId.systemDefault()
                val d0 = java.time.Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
                DatePickerDialog(context, { _, y, m, d ->
                    dueAt = java.time.LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                }, d0.year, d0.monthValue - 1, d0.dayOfMonth).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarMonth, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                val dueDateText = remember(dueAt) { localDateFromEpoch(dueAt).format(TASK_DATE_FORMATTER) }
                Text(dueDateText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }, confirmButton = { Button(onClick = { if (title.isNotBlank()) onSave(subjectId, title.trim(), dueAt) }, enabled = title.isNotBlank()) { Text("Adicionar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

private fun taskDateKey(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    localDateFromEpoch(time, zone).let { it.year * 10000L + it.monthValue * 100L + it.dayOfMonth }

@Composable
fun MoreHub(
    onCards: () -> Unit,
    onPlayer: () -> Unit,
    onAssistant: () -> Unit,
    darkMode: Boolean,
    onDarkMode: (Boolean) -> Unit,
    onMathPractice: () -> Unit = {}
) {
    val context = LocalContext.current
    val themeConfig = LocalStudyThemeConfig.current
    val openThemeStudio = LocalThemeStudioOpener.current
    val isDarkActive = themeConfig.baseSurface.isDark
    val toggleDark: (Boolean) -> Unit = { nextDark ->
        StudyThemeStore.setDarkMode(context, nextDark)
        onDarkMode(nextDark)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StudyPageHeader(
            title = "Mais",
            subtitle = "Prática ativa, sons, IA e ajustes do sistema",
            showGlobalSearch = false
        )
        Spacer(Modifier.height(4.dp))

        HubSectionTitle("Prática Ativa & Inteligência")
        HubCard("Flashcards (Anki)", "Baralhos SM-2/FSRS, oclusão de imagem, modo cram e estatísticas.", Icons.Default.Style, onCards)
        HubCard("Arena de Exatas & Cálculo", "Álgebra, geometria, trigonometria, probabilidade, funções, sobrevivência e sprint.", Icons.Default.Calculate, onMathPractice)
        HubCard("Tutor IA (Aluno & Professor)", "Modo Aluno (Feynman), auditoria de Anki e geração de notas, canvas e cartões.", Icons.Default.AutoAwesome, onAssistant)

        HubSectionTitle("Sons & Concentração")
        HubCard("Central Sonora & Binaural", "32 camadas ambientes, ondas Alpha/Theta/Beta/Gamma/Delta, mixes e playlists.", Icons.Default.Headphones, onPlayer)

        HubSectionTitle("Aparência, Notificações & Dados")
        StudyCard(
            onClick = openThemeStudio,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            highlighted = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(themeConfig.baseSurface.previewBackground)
                        .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        "Personalizar Tema & Aparência",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${themeConfig.baseSurface.label} · ${themeConfig.cardStyle.label} · ${themeConfig.density.label}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
        StudyCard(
            onClick = { toggleDark(!isDarkActive) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isDarkActive) Icons.Default.DarkMode else Icons.Default.LightMode, null, tint = accentColor())
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text("Tema escuro rápido", fontWeight = FontWeight.SemiBold)
                    Text("Mais confortável em ambientes com pouca luz.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = isDarkActive, onCheckedChange = toggleDark)
            }
        }
        NotificationSettingsCard()
        BackupAndSettingsCard()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun AssistantPage(
    studyContext: String,
    subjects: List<Subject> = emptyList(),
    notes: List<Note> = emptyList(),
    canvasBoards: List<CanvasBoard> = emptyList(),
    flashcards: List<Flashcard> = emptyList(),
    mistakes: List<Mistake> = emptyList(),
    questionLogs: List<QuestionLog> = emptyList(),
    tutorState: TutorUiState = TutorUiState(),
    onTutorModeChange: (StudyTutorMode) -> Unit = {},
    onFeynmanLevelChange: (Int) -> Unit = {},
    onFocusTopicChange: (String) -> Unit = {},
    onDraftChange: (String) -> Unit = {},
    onSendPrompt: (
        provider: AiProviderMode,
        apiKeyOrToken: String,
        serviceUrl: String,
        modelName: String,
        prompt: String,
        studyContext: String
    ) -> Unit = { _, _, _, _, _, _ -> },
    onClearConversation: () -> Unit = {},
    onApplyAction: (actionKey: String, obj: GeneratedStudyObject, openNoteAfter: Boolean) -> Unit = { _, _, _ -> },
    onOpenSavedNote: (Long) -> Unit = {},
    onOpenSavedCanvas: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ioScope = rememberCoroutineScope()

    var providerMode by remember { mutableStateOf(AiProviderMode.GEMINI_DIRECT) }
    var geminiApiKey by remember { mutableStateOf("") }
    var geminiModel by remember { mutableStateOf("gemini-2.5-flash") }
    var openAiApiKey by remember { mutableStateOf("") }
    var openAiBaseUrl by remember { mutableStateOf("https://api.openai.com/v1") }
    var openAiModel by remember { mutableStateOf("gpt-4o-mini") }
    var serviceUrl by remember { mutableStateOf("") }
    var accessToken by remember { mutableStateOf("") }
    var showKeystoreRecoveryWarning by remember { mutableStateOf(false) }
    var secretSaveError by remember { mutableStateOf<String?>(null) }
    var secretsLoaded by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf(false) }

    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("studydock_assistant", android.content.Context.MODE_PRIVATE)
            val loadedMode = AiProviderMode.fromId(
                prefs.getString("provider_mode", null) ?: if (prefs.getString("service_url", "").orEmpty().isNotBlank()) "PROXY" else "GEMINI"
            )
            val geminiRead = SecretStore.readWithStatus(context, "assistant_gemini_key")
            val openAiRead = SecretStore.readWithStatus(context, "assistant_openai_key")
            val tokenRead = SecretStore.readWithStatus(context, "assistant_access_token")
            val corruptedKeys = buildList {
                if (geminiRead.status == SecretStatus.CORRUPTED_KEYSTORE) add("assistant_gemini_key")
                if (openAiRead.status == SecretStatus.CORRUPTED_KEYSTORE) add("assistant_openai_key")
                if (tokenRead.status == SecretStatus.CORRUPTED_KEYSTORE) add("assistant_access_token")
            }
            val hasCorruptedKeystore = corruptedKeys.isNotEmpty() || SecretStore.hasCorruptedSecrets(context)
            if (corruptedKeys.isNotEmpty()) {
                StudyLog.w(
                    "AssistantPage",
                    "Segredos do Tutor IA com falha no Keystore (${corruptedKeys.size}); limpando entradas para reconfiguração"
                )
                corruptedKeys.forEach { key ->
                    runCatching { SecretStore.remove(context, key) }
                        .onFailure { err ->
                            StudyLog.w("AssistantPage", "Falha ao limpar entrada corrompida '$key'", err)
                        }
                }
            } else if (hasCorruptedKeystore) {
                StudyLog.w("AssistantPage", "Segredo corrompido detectado no SecretStore após restauração")
            }
            AssistantLoadedConfig(
                providerMode = loadedMode,
                geminiApiKey = geminiRead.value.orEmpty(),
                geminiModel = prefs.getString("gemini_model", "gemini-2.5-flash").orEmpty(),
                openAiApiKey = openAiRead.value.orEmpty(),
                openAiBaseUrl = prefs.getString("openai_base_url", "https://api.openai.com/v1").orEmpty(),
                openAiModel = prefs.getString("openai_model", "gpt-4o-mini").orEmpty(),
                serviceUrl = prefs.getString("service_url", "").orEmpty(),
                accessToken = tokenRead.value.orEmpty(),
                hasCorruptedKeystore = hasCorruptedKeystore
            )
        }
        providerMode = loaded.providerMode
        geminiApiKey = loaded.geminiApiKey
        geminiModel = loaded.geminiModel
        openAiApiKey = loaded.openAiApiKey
        openAiBaseUrl = loaded.openAiBaseUrl
        openAiModel = loaded.openAiModel
        serviceUrl = loaded.serviceUrl
        accessToken = loaded.accessToken
        showKeystoreRecoveryWarning = loaded.hasCorruptedKeystore
        val loadedConfigured = when (loaded.providerMode) {
            AiProviderMode.GEMINI_DIRECT -> loaded.geminiApiKey.isNotBlank()
            AiProviderMode.OPENAI_COMPAT -> loaded.openAiApiKey.isNotBlank() && loaded.openAiBaseUrl.trim().startsWith("http")
            AiProviderMode.CUSTOM_PROXY -> loaded.serviceUrl.trim().startsWith("http") && loaded.accessToken.isNotBlank()
        }
        if (!secretsLoaded) {
            configuring = !loadedConfigured
            secretsLoaded = true
        }
    }

    val isConfigured = when (providerMode) {
        AiProviderMode.GEMINI_DIRECT -> geminiApiKey.isNotBlank()
        AiProviderMode.OPENAI_COMPAT -> openAiApiKey.isNotBlank() && openAiBaseUrl.trim().startsWith("http")
        AiProviderMode.CUSTOM_PROXY -> serviceUrl.trim().startsWith("http") && accessToken.isNotBlank()
    }

    val tutorMode = tutorState.tutorMode
    val feynmanLevel = tutorState.feynmanLevel
    val focusTopic = tutorState.focusTopic
    var showTopicPicker by remember { mutableStateOf(false) }

    val includeData = tutorState.includeData
    val draft = tutorState.draft
    val busy = tutorState.busy
    val messages = tutorState.messages
    val appliedActions = tutorState.appliedActions
    val savedNoteIds = tutorState.savedNoteIds
    val savedCanvasIds = tutorState.savedCanvasIds
    val existingNoteIds = remember(notes) { notes.mapTo(HashSet()) { it.id } }
    val existingCanvasIds = remember(canvasBoards) { canvasBoards.mapTo(HashSet()) { it.id } }

    // Precompute Anki & Error diagnostics for Professor Mode
    val leechCards = remember(flashcards) {
        flashcards.filter { !it.suspended && (it.lapses >= 2 || it.easeFactor < 1.9f) }
            .sortedWith(compareByDescending<Flashcard> { it.lapses }.thenBy { it.easeFactor })
    }
    val accuracyPercent = remember(questionLogs) {
        val total = questionLogs.sumOf { it.questionCount }
        val correct = questionLogs.sumOf { it.correctCount }
        if (total > 0) (correct * 100) / total else null
    }

    fun buildDetailedDiagnosticContext(): String = buildString {
        appendLine(studyContext)
        if (focusTopic.isNotBlank()) {
            appendLine("\n=== FOCO SELECIONADO PELO ESTUDANTE: $focusTopic ===")
            notes.firstOrNull { it.title.equals(focusTopic, ignoreCase = true) }?.let { matchedNote ->
                appendLine("Conteúdo da Nota '${matchedNote.title}':\n${matchedNote.markdown.take(2500)}")
            }
        }
        if (leechCards.isNotEmpty()) {
            appendLine("\n=== CARTÕES CRÍTICOS / SANGUESSUGAS NO ANKI (MAIS ERRADOS) ===")
            leechCards.take(12).forEach { card ->
                val subj = subjects.firstOrNull { it.id == card.subjectId }?.name ?: "Geral"
                appendLine("- [Matéria: $subj | Lapsos/Erros: ${card.lapses}x | Facilidade: ${(card.easeFactor * 100).toInt()}% | Intervalo: ${card.intervalDays}d] Frente: \"${card.front.take(180)}\" -> Verso: \"${card.back.take(180)}\"")
            }
        }
        if (mistakes.isNotEmpty()) {
            appendLine("\n=== CADERNO DE ERROS REGISTRADOS PELO ESTUDANTE ===")
            mistakes.take(10).forEach { m ->
                val subj = subjects.firstOrNull { it.id == m.subjectId }?.name ?: "Matéria"
                appendLine("- [$subj · ${m.topic}] Questão/Erro: ${m.question.take(160)} | Motivo: ${m.reason.take(120)} | Solução: ${m.solution.take(120)}")
            }
        }
        if (questionLogs.isNotEmpty()) {
            appendLine("\n=== DESEMPENHO EM QUESTÕES POR TÓPICO ===")
            questionLogs.take(12).forEach { q ->
                val subj = subjects.firstOrNull { it.id == q.subjectId }?.name ?: "Geral"
                val pct = if (q.questionCount > 0) (q.correctCount * 100) / q.questionCount else 0
                appendLine("- [$subj · ${q.topic}] ${q.correctCount}/${q.questionCount} acertos ($pct%)")
            }
        }
    }

    fun sendToTutor(userPrompt: String) {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.isEmpty() || busy) return
        val activeKey = when (providerMode) {
            AiProviderMode.GEMINI_DIRECT -> geminiApiKey
            AiProviderMode.OPENAI_COMPAT -> openAiApiKey
            AiProviderMode.CUSTOM_PROXY -> accessToken
        }
        val activeUrl = when (providerMode) {
            AiProviderMode.GEMINI_DIRECT -> ""
            AiProviderMode.OPENAI_COMPAT -> openAiBaseUrl
            AiProviderMode.CUSTOM_PROXY -> serviceUrl
        }
        val activeModel = when (providerMode) {
            AiProviderMode.GEMINI_DIRECT -> geminiModel
            AiProviderMode.OPENAI_COMPAT -> openAiModel
            AiProviderMode.CUSTOM_PROXY -> ""
        }
        onSendPrompt(
            providerMode,
            activeKey,
            activeUrl,
            activeModel,
            cleanPrompt,
            if (includeData) buildDetailedDiagnosticContext() else ""
        )
    }

    var quickPromptsExpanded by rememberSaveable { mutableStateOf(false) }
    val imeOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val chatListState = rememberLazyListState()
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) {
            val targetIdx = if (busy) messages.size else messages.lastIndex
            runCatching { chatListState.animateScrollToItem(targetIdx) }
        }
    }

    val providerBadge = when (providerMode) {
        AiProviderMode.GEMINI_DIRECT -> geminiModel.ifBlank { "gemini-2.5-flash" }
        AiProviderMode.OPENAI_COMPAT -> openAiModel.ifBlank { "gpt-4o-mini" }
        AiProviderMode.CUSTOM_PROXY -> "Proxy"
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Tutor IA",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(
                        onClick = { configuring = !configuring },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isConfigured) Color(0xFF10B981) else MaterialTheme.colorScheme.error)
                            )
                            Text(
                                providerBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 128.dp)
                            )
                        }
                    }
                }
            }
            if (messages.isNotEmpty() && !configuring) {
                IconButton(onClick = onClearConversation, enabled = !busy) {
                    Icon(Icons.Default.DeleteSweep, "Nova conversa", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { configuring = !configuring }) {
                Icon(
                    if (configuring) Icons.Default.Close else Icons.Default.Tune,
                    contentDescription = if (configuring) "Fechar configurações" else "Configurar IA",
                    tint = if (configuring) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (configuring) {
            StudyCard(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(StudyTokens.cardPadding())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = accentColor(), modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Conectar Inteligência Artificial", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Escolha como conectar seu Tutor IA. Suas chaves ficam criptografadas localmente no Android Keystore (SecretStore).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    if (showKeystoreRecoveryWarning) {
                        KeystoreRecoveryWarningBanner(
                            onDismiss = { showKeystoreRecoveryWarning = false }
                        )
                    }

                    // Provider selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        AiProviderMode.entries.forEach { mode ->
                            FilterChip(
                                selected = providerMode == mode,
                                onClick = { providerMode = mode },
                                label = { Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    when (providerMode) {
                        AiProviderMode.GEMINI_DIRECT -> {
                            Text(
                                "Dica: Você pode gerar uma chave gratuita no Google AI Studio (aistudio.google.com).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = geminiApiKey,
                                onValueChange = { geminiApiKey = it },
                                label = { Text("Gemini API Key (AIza...)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            OutlinedTextField(
                                value = geminiModel,
                                onValueChange = { geminiModel = it },
                                label = { Text("Modelo Gemini") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            ) {
                                listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash").forEach { preset ->
                                    AssistChip(
                                        onClick = { geminiModel = preset },
                                        label = { Text(preset, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    )
                                }
                            }
                        }

                        AiProviderMode.OPENAI_COMPAT -> {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AssistChip(
                                    onClick = {
                                        openAiBaseUrl = "https://api.openai.com/v1"
                                        openAiModel = "gpt-4o-mini"
                                    },
                                    label = { Text("OpenAI", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                                )
                                AssistChip(
                                    onClick = {
                                        openAiBaseUrl = "https://api.groq.com/openai/v1"
                                        openAiModel = "llama-3.3-70b-versatile"
                                    },
                                    label = { Text("Groq (Ultra-rápido)", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                                AssistChip(
                                    onClick = {
                                        openAiBaseUrl = "https://openrouter.ai/api/v1"
                                        openAiModel = "openai/gpt-4o-mini"
                                    },
                                    label = { Text("OpenRouter", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                                )
                            }
                            OutlinedTextField(
                                value = openAiApiKey,
                                onValueChange = { openAiApiKey = it },
                                label = { Text("API Key (sk-... ou gsk_...)") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            OutlinedTextField(
                                value = openAiBaseUrl,
                                onValueChange = { openAiBaseUrl = it },
                                label = { Text("URL Base da API") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            OutlinedTextField(
                                value = openAiModel,
                                onValueChange = { openAiModel = it },
                                label = { Text("Modelo (ex: gpt-4o-mini)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }

                        AiProviderMode.CUSTOM_PROXY -> {
                            Text(
                                "Conecta ao seu Cloudflare Worker privado (backend/worker.js).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = serviceUrl,
                                onValueChange = { serviceUrl = it },
                                label = { Text("Endereço HTTPS do serviço") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            )
                            OutlinedTextField(
                                value = accessToken,
                                onValueChange = { accessToken = it },
                                label = { Text("Token de acesso do serviço") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                        }
                    }

                    secretSaveError?.let { errorMsg ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        ) {
                            Text(
                                errorMsg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                    ) {
                        Button(
                            onClick = {
                                secretSaveError = null
                                val snapshotMode = providerMode
                                val snapshotGeminiKey = geminiApiKey
                                val snapshotGeminiModel = geminiModel.trim().ifBlank { "gemini-2.5-flash" }
                                val snapshotOpenAiKey = openAiApiKey
                                val snapshotOpenAiBase = openAiBaseUrl.trim()
                                val snapshotOpenAiModel = openAiModel.trim()
                                val snapshotServiceUrl = serviceUrl.trim()
                                val snapshotAccessToken = accessToken
                                ioScope.launch {
                                    val error = withContext(Dispatchers.IO) {
                                        try {
                                            context.getSharedPreferences("studydock_assistant", android.content.Context.MODE_PRIVATE)
                                                .edit()
                                                .putString("provider_mode", snapshotMode.id)
                                                .putString("gemini_model", snapshotGeminiModel)
                                                .putString("openai_base_url", snapshotOpenAiBase)
                                                .putString("openai_model", snapshotOpenAiModel)
                                                .putString("service_url", snapshotServiceUrl)
                                                .apply()
                                            persistOrClearSecret(context, "assistant_gemini_key", snapshotGeminiKey)
                                            persistOrClearSecret(context, "assistant_openai_key", snapshotOpenAiKey)
                                            persistOrClearSecret(context, "assistant_access_token", snapshotAccessToken)
                                            null
                                        } catch (e: Exception) {
                                            StudyLog.w("AssistantPage", "Falha ao salvar chaves no Keystore (${e::class.java.simpleName})", e)
                                            "Não foi possível salvar a chave no Keystore deste aparelho. Limpe as chaves e tente novamente."
                                        }
                                    }
                                    if (error == null) {
                                        showKeystoreRecoveryWarning = false
                                        configuring = false
                                    } else {
                                        secretSaveError = error
                                    }
                                }
                            },
                            enabled = isConfigured
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Salvar e Abrir Tutor", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (isConfigured) {
                            OutlinedButton(onClick = { configuring = false }) {
                                Text("Cancelar", maxLines = 1, softWrap = false)
                            }
                        }
                        if (showKeystoreRecoveryWarning || secretSaveError != null) {
                            TextButton(
                                onClick = {
                                    ioScope.launch(Dispatchers.IO) {
                                        SecretStore.DEFAULT_KEYS.forEach { key ->
                                            runCatching { SecretStore.remove(context, key) }
                                                .onFailure { err ->
                                                    StudyLog.w("AssistantPage", "Falha ao remover segredo '$key'", err)
                                                }
                                        }
                                    }
                                    geminiApiKey = ""
                                    openAiApiKey = ""
                                    accessToken = ""
                                    secretSaveError = null
                                    showKeystoreRecoveryWarning = false
                                }
                            ) {
                                Text("Limpar chaves salvas", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        } else {
            if (showKeystoreRecoveryWarning) {
                KeystoreRecoveryWarningBanner(
                    onConfigure = { configuring = true },
                    onDismiss = { showKeystoreRecoveryWarning = false },
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // 1. Clean Segmented Pill Mode Selector
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    StudyTutorMode.entries.forEach { mode ->
                        val selected = tutorMode == mode
                        val label = when (mode) {
                            StudyTutorMode.STUDENT_FEYNMAN -> "Aluno"
                            StudyTutorMode.PROFESSOR_CRITIC -> "Professor"
                            StudyTutorMode.AGENT_CREATOR -> "Criador"
                        }
                        Surface(
                            onClick = { onTutorModeChange(mode) },
                            shape = CircleShape,
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 2. Single-Line Minimalist Context Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistantMetaPill(
                    icon = Icons.Default.TrackChanges,
                    label = if (focusTopic.isBlank()) "Escolher foco" else focusTopic,
                    highlighted = focusTopic.isNotBlank(),
                    onClick = { showTopicPicker = true }
                )
                if (tutorMode == StudyTutorMode.STUDENT_FEYNMAN) {
                    AssistantMetaPill(
                        icon = Icons.Default.AutoAwesome,
                        label = "Feynman $feynmanLevel/5",
                        highlighted = feynmanLevel > 1,
                        onClick = { onFeynmanLevelChange(if (feynmanLevel >= 5) 1 else feynmanLevel + 1) }
                    )
                } else if (tutorMode == StudyTutorMode.PROFESSOR_CRITIC && (leechCards.isNotEmpty() || mistakes.isNotEmpty() || accuracyPercent != null)) {
                    val statLabel = buildString {
                        append("${leechCards.size} críticos")
                        append(" · ${mistakes.size} erros")
                        accuracyPercent?.let { append(" · $it%") }
                    }
                    AssistantMetaPill(
                        icon = Icons.Default.Analytics,
                        label = statLabel,
                        highlighted = leechCards.isNotEmpty()
                    )
                }
                if (messages.isNotEmpty()) {
                    AssistantMetaPill(
                        icon = if (quickPromptsExpanded) Icons.Default.ExpandLess else Icons.Default.Bolt,
                        label = "Atalhos",
                        highlighted = quickPromptsExpanded,
                        onClick = { quickPromptsExpanded = !quickPromptsExpanded }
                    )
                }
            }

            val starterPrompts = remember(tutorMode, feynmanLevel, focusTopic, subjects) {
                val target = focusTopic.ifBlank { subjects.firstOrNull()?.name ?: "meu tema de estudo" }
                when (tutorMode) {
                    StudyTutorMode.STUDENT_FEYNMAN -> listOf(
                        "Me faça a 1ª pergunta" to "Quero começar o Modo Aluno (Técnica de Feynman) sobre \"$target\" no Nível $feynmanLevel. Me faça a primeira pergunta para eu te explicar com minhas próprias palavras!",
                        "Subir nível (+1)" to "Suba a profundidade para o próximo nível! Me faça uma pergunta mais complexa (caso de borda ou pegadinha de prova) sobre \"$target\".",
                        "Mapear lacunas no Canvas" to "Resuma os pontos cegos desta conversa, crie um Canvas visual (ACTION:CANVAS) conectando os conceitos e gere Flashcards Anki (ACTION:FLASHCARD) corrigindo minhas lacunas.",
                        "Gerar Anki & Nota" to "Gere Flashcards Anki (ACTION:FLASHCARD) e uma Nota Obsidian (ACTION:NOTE) consolidando o que discutimos até agora."
                    )
                    StudyTutorMode.PROFESSOR_CRITIC -> listOf(
                        "Auditar erros no Anki" to "Analise meus cartões mais errados (Sanguessugas) no Anki e meu Caderno de Erros. Faça críticas construtivas diretas e gere versões reformuladas (ACTION:FLASHCARD).",
                        "Criar Canvas do tema" to "Crie um Canvas visual (ACTION:CANVAS) completo e bem estruturado sobre \"$target\" conectando conceitos centrais, mecanismos e armadilhas.",
                        "Crítica da minha rotina" to "Faça uma crítica construtiva completa da minha rotina de estudos (tempo por matéria, hábitos, metas e revisões pendentes) e gere Tarefas concretas (ACTION:TASK) ou Metas (ACTION:GOAL).",
                        "Criar Nota Obsidian" to "Crie uma Nota Obsidian (ACTION:NOTE) aprofundada sobre \"$target\", com resumo estruturado, fórmulas em LaTeX, [[wikilinks]] e #tags.",
                        "Arguição oral rigorosa" to "Escolha o tópico ou erro mais crítico dos meus registros sobre \"$target\" e me faça uma arguição oral rigorosa estilo banca."
                    )
                    StudyTutorMode.AGENT_CREATOR -> listOf(
                        "Criar Canvas visual" to "Crie um Canvas visual (ACTION:CANVAS) organizado sobre \"$target\" com 5 a 8 cards conectados por setas claras.",
                        "Nota Obsidian completa" to "Crie uma Nota completa no formato Segundo Cérebro (ACTION:NOTE) sobre \"$target\", com estrutura didática, fórmulas em LaTeX, conexões [[wikilinks]] e #tags.",
                        "5 Flashcards Anki" to "Crie 5 Flashcards Anki (ACTION:FLASHCARD) misturando perguntas atômicas e Cloze {{c1::...}} sobre os pontos mais cobrados de \"$target\".",
                        "Kit completo de estudo" to "Crie um Kit Completo sobre \"$target\": 1 Canvas visual (ACTION:CANVAS), 1 Nota Obsidian (ACTION:NOTE), 5 Flashcards Anki (ACTION:FLASHCARD) e 2 Tarefas práticas (ACTION:TASK)."
                    )
                }
            }

            AnimatedVisibility(visible = messages.isNotEmpty() && quickPromptsExpanded && !imeOpen) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    starterPrompts.forEach { (label, promptText) ->
                        SuggestionChip(
                            onClick = {
                                quickPromptsExpanded = false
                                sendToTutor(promptText)
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                        )
                    }
                }
            }

            // 3. Messages & Generated Action Cards
            LazyColumn(
                state = chatListState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            when (tutorMode) {
                                                StudyTutorMode.STUDENT_FEYNMAN -> Icons.Default.Psychology
                                                StudyTutorMode.PROFESSOR_CRITIC -> Icons.Default.School
                                                StudyTutorMode.AGENT_CREATOR -> Icons.Default.AutoAwesome
                                            },
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            tutorMode.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        tutorMode.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                "Sugestões rápidas",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 2.dp)
                            )
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                starterPrompts.forEach { (label, promptText) ->
                                    Surface(
                                        onClick = { sendToTutor(promptText) },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                    ) {
                                        Text(
                                            label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(messages.size, key = { index -> "$index-${messages[index].fromUser}-${messages[index].text.hashCode()}" }) { index ->
                    val message = messages[index]
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start
                    ) {
                        if (message.fromUser) {
                            Surface(
                                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.widthIn(max = 315.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    RichMarkdown(
                                        markdown = message.text,
                                        onToggleLine = {},
                                        onOpenLinked = {},
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        baseTextStyle = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            message.modeBadge?.ifBlank { "Tutor IA" } ?: "Tutor IA",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        IconButton(
                                            onClick = {
                                                clipboard.setText(AnnotatedString(message.text))
                                                Toast.makeText(context, "Resposta copiada", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.ContentCopy,
                                                contentDescription = "Copiar resposta",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                    RichMarkdown(
                                        markdown = message.text,
                                        onToggleLine = {},
                                        onOpenLinked = { rawRef ->
                                            val cleanTarget = NoteReferences.parse(rawRef).firstOrNull()?.noteTarget ?: rawRef
                                            val matched = notes.firstOrNull {
                                                it.trashedAt == null && it.title.equals(cleanTarget, ignoreCase = true)
                                            }
                                            if (matched != null) {
                                                onOpenSavedNote(matched.id)
                                            } else {
                                                Toast.makeText(context, "Nota \"$cleanTarget\" não encontrada no cofre.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        notes = if (message.text.contains("[[")) notes else emptyList(),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        baseTextStyle = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        // Interactive StudyDock Object Creation Cards when AI returns actions
                        if (message.actions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Column(
                                Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val unsavedIndices = message.actions.indices.filter { idx ->
                                    appliedActions["${index}_$idx"] != true
                                }
                                if (message.actions.size > 1 && unsavedIndices.isNotEmpty()) {
                                    FilledTonalButton(
                                        onClick = {
                                            unsavedIndices.forEach { actIdx ->
                                                val key = "${index}_$actIdx"
                                                onApplyAction(key, message.actions[actIdx], false)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.DoneAll, null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Salvar todos (${unsavedIndices.size})")
                                    }
                                }

                                message.actions.forEachIndexed { actIdx, obj ->
                                    val actionKey = "${index}_$actIdx"
                                    val saved = appliedActions[actionKey] == true
                                    val savedNoteId = savedNoteIds[actionKey]?.takeIf { existingNoteIds.contains(it) }
                                    val savedCanvasId = savedCanvasIds[actionKey]?.takeIf { existingCanvasIds.contains(it) }
                                    GeneratedObjectCard(
                                        obj = obj,
                                        saved = saved,
                                        savedNoteId = savedNoteId,
                                        savedCanvasId = savedCanvasId,
                                        onSave = { openAfter ->
                                            if (!saved) onApplyAction(actionKey, obj, openAfter)
                                        },
                                        onOpenSavedNote = onOpenSavedNote,
                                        onOpenSavedCanvas = onOpenSavedCanvas
                                    )
                                }
                            }
                        }
                    }
                }

                if (busy) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.padding(vertical = 2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    when (tutorMode) {
                                        StudyTutorMode.STUDENT_FEYNMAN -> "Avaliando sua explicação…"
                                        StudyTutorMode.PROFESSOR_CRITIC -> "Analisando dados e preparando resposta…"
                                        StudyTutorMode.AGENT_CREATOR -> "Gerando materiais para o StudyDock…"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // 4. Clean Floating Pill Composer
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    TextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        placeholder = {
                            Text(
                                when (tutorMode) {
                                    StudyTutorMode.STUDENT_FEYNMAN -> "Explique o tema ou responda…"
                                    StudyTutorMode.PROFESSOR_CRITIC -> "Pergunte, peça auditoria ou um Canvas…"
                                    StudyTutorMode.AGENT_CREATOR -> "Peça Canvas, notas, flashcards, metas…"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f),
                        maxLines = 5
                    )
                    FilledIconButton(
                        enabled = draft.isNotBlank() && !busy,
                        onClick = { sendToTutor(draft) },
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(bottom = 6.dp, end = 2.dp)
                            .size(40.dp)
                    ) {
                        Icon(Icons.Default.ArrowUpward, "Enviar", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }

    if (showTopicPicker) {
        var customTopic by remember { mutableStateOf(focusTopic) }
        AlertDialog(
            onDismissRequest = { showTopicPicker = false },
            title = { Text("Escolher Tema, Matéria ou Nota") },
            text = {
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = customTopic,
                        onValueChange = { customTopic = it },
                        label = { Text("Tema livre ou tópico específico") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (subjects.isNotEmpty()) {
                        Text("Suas Matérias", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            subjects.forEach { subj ->
                                FilterChip(
                                    selected = customTopic.equals(subj.name, ignoreCase = true),
                                    onClick = { customTopic = subj.name },
                                    label = { Text(subj.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                    if (notes.isNotEmpty()) {
                        Text("Notas do Segundo Cérebro (carrega o texto base)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        notes.take(10).forEach { note ->
                            val selectedNote = customTopic == note.title
                            Surface(
                                onClick = { customTopic = note.title },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedNote) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(
                                    "🧠 ${note.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (selectedNote) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onFocusTopicChange(customTopic.trim())
                    showTopicPicker = false
                }) { Text("Definir Foco") }
            },
            dismissButton = {
                TextButton(onClick = {
                    onFocusTopicChange("")
                    showTopicPicker = false
                }) { Text("Limpar") }
            }
        )
    }
}

private fun persistOrClearSecret(context: android.content.Context, key: String, rawValue: String) {
    val clean = rawValue.trim()
    if (clean.isEmpty()) {
        SecretStore.remove(context, key)
    } else {
        SecretStore.write(context, key, clean)
    }
}

@Composable
private fun KeystoreRecoveryWarningBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onConfigure: (() -> Unit)? = null
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        "Chave indisponível após restauração",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        "Chaves salvas em outro aparelho não puderam ser descriptografadas após restauração — informe sua API key novamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (onConfigure != null) {
                    TextButton(onClick = onConfigure) {
                        Text(
                            "Configurar",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        "Entendi",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMetaPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = bg,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp)
                )
            }
        }
    } else {
        Surface(
            shape = CircleShape,
            color = bg,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun GeneratedObjectCard(
    obj: GeneratedStudyObject,
    saved: Boolean,
    savedNoteId: Long? = null,
    savedCanvasId: Long? = null,
    onSave: (openNoteAfter: Boolean) -> Unit,
    onOpenSavedNote: (Long) -> Unit = {},
    onOpenSavedCanvas: (Long) -> Unit = {}
) {
    val contentPrimary = if (saved) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    val contentSecondary = if (saved) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val accentTint = if (saved) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
    StudyCard(
        Modifier.fillMaxWidth(),
        containerColorOverride = if (saved) MaterialTheme.colorScheme.primaryContainer else null
    ) {
        Column(Modifier.padding(12.dp)) {
            when (obj) {
                is GeneratedStudyObject.FlashcardObj -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Style, null, tint = accentTint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Flashcard Anki · ${obj.deckName}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = contentPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (!obj.subjectName.isNullOrBlank()) {
                            Text(obj.subjectName, style = MaterialTheme.typography.labelSmall, color = accentTint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("Frente: ${obj.front}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = contentPrimary, modifier = Modifier.padding(top = 4.dp))
                    Text("Verso: ${obj.back}", style = MaterialTheme.typography.bodySmall, color = contentSecondary, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { if (!saved) onSave(false) },
                            enabled = !saved,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (saved) "Salvo no Anki" else "Adicionar ao Anki", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                is GeneratedStudyObject.NoteObj -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Hub, null, tint = accentTint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nota Obsidian · ${obj.folder}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = contentPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text(obj.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = contentPrimary, modifier = Modifier.padding(top = 4.dp))
                    Text(obj.markdown.take(220) + if (obj.markdown.length > 220) "…" else "", style = MaterialTheme.typography.bodySmall, color = contentSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    if (obj.tags.isNotEmpty()) {
                        Text(obj.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = accentTint, modifier = Modifier.padding(top = 4.dp))
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (saved && savedNoteId != null) onOpenSavedNote(savedNoteId)
                                else if (!saved) onSave(true)
                            },
                            enabled = !saved || savedNoteId != null,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (saved && savedNoteId != null) "Abrir Nota" else "Salvar e Abrir",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = { if (!saved) onSave(false) },
                            enabled = !saved,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (saved) "Nota Salva" else "Salvar no Cofre",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                is GeneratedStudyObject.CanvasObj -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dashboard, null, tint = accentTint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Canvas · ${obj.layout}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${obj.nodes.size} cards · ${obj.edges.size} links",
                            style = MaterialTheme.typography.labelSmall,
                            color = accentTint,
                            maxLines = 1
                        )
                    }
                    Text(
                        obj.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentPrimary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    if (obj.summary.isNotBlank()) {
                        Text(
                            obj.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        obj.nodes.take(6).forEach { node ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    node.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (saved && savedCanvasId != null) onOpenSavedCanvas(savedCanvasId)
                                else if (!saved) onSave(true)
                            },
                            enabled = !saved || savedCanvasId != null,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (saved && savedCanvasId != null) "Abrir Canvas" else "Salvar e Abrir",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = { if (!saved) onSave(false) },
                            enabled = !saved,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (saved) "Canvas Salvo" else "Salvar no Canvas",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                is GeneratedStudyObject.TaskObj -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Checklist, null, tint = accentTint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tarefa · Prazo +${obj.dueInDays}d (P${obj.priority})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = contentPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (!obj.subjectName.isNullOrBlank()) {
                            Text(obj.subjectName, style = MaterialTheme.typography.labelSmall, color = accentTint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(obj.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = contentPrimary, modifier = Modifier.padding(top = 4.dp))
                    if (obj.details.isNotBlank()) {
                        Text(obj.details, style = MaterialTheme.typography.labelSmall, color = contentSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { if (!saved) onSave(false) },
                            enabled = !saved,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (saved) "Tarefa Criada" else "Criar Tarefa", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                is GeneratedStudyObject.GoalObj -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Flag, null, tint = accentTint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        val targetLabel = if (obj.metricType != "COUNTDOWN" && obj.targetValue > 0.0) {
                            val num = if (obj.targetValue % 1.0 == 0.0) obj.targetValue.toInt().toString() else String.format(Locale("pt", "BR"), "%.1f", obj.targetValue)
                            " · Alvo: $num ${obj.metricUnit}".trimEnd()
                        } else ""
                        Text("Meta · ${obj.targetInDays} dias$targetLabel", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = contentPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (!obj.subjectName.isNullOrBlank()) {
                            Text(obj.subjectName, style = MaterialTheme.typography.labelSmall, color = accentTint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text(obj.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = contentPrimary, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Button(
                            onClick = { if (!saved) onSave(false) },
                            enabled = !saved,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(if (saved) Icons.Default.Check else Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (saved) "Meta Criada" else "Criar Meta", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HubCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    StudyCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(StudyTokens.cardPadding()), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = accentColor(), modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun daysUntil(timestamp: Long): Long {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val target = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(today, target)
}

private fun isToday(timestamp: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() == LocalDate.now(zone)
}

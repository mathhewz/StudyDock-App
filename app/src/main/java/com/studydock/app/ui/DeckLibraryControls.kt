@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.studydock.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.CardDeck
import com.studydock.app.data.DeckTree
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val STEPS_SPLIT_REGEX = Regex("[\\s,;]+")

@Composable
internal fun DeckLibraryControls(
    tree: DeckTree,
    selectedId: Long,
    choose: (Long) -> Unit,
    save: suspend (CardDeck) -> Long,
    dissolve: suspend (Long) -> Unit,
    reviews: List<com.studydock.app.data.CardReview> = emptyList()
) {
    val selected = tree.ordered.find { it.id == selectedId }
    var showScheduling by rememberSaveable { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var action by rememberSaveable(selectedId) { mutableStateOf<String?>(null) }
    val controlShape = studyControlShape()

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth(),
                shape = controlShape
            ) {
                Text(
                    if (selectedId == -1L) "Todos os baralhos"
                    else if (selectedId == 0L) "Sem baralho"
                    else tree.label(selectedId),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(picking, { picking = false }) {
                DropdownMenuItem(text = { Text("Todos os baralhos") }, onClick = { choose(-1); picking = false })
                DropdownMenuItem(text = { Text("Sem baralho") }, onClick = { choose(0); picking = false })
                tree.ordered.forEach { deck ->
                    DropdownMenuItem(
                        text = { Text(tree.label(deck.id), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { choose(deck.id); picking = false }
                    )
                }
            }
        }
        if (selected == null) {
            TextButton({ action = "create" }, shape = controlShape) {
                Text("Criar baralho", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            IconButton(onClick = { action = "edit" }) {
                Icon(Icons.Default.Tune, "Configurar baralho")
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Opções do baralho") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Como funcionam as revisões") }, onClick = { menu = false; showScheduling = true })
                    DropdownMenuItem(text = { Text("Configurar baralho (Opções Pro)") }, onClick = { menu = false; action = "edit" })
                    DropdownMenuItem(text = { Text("Criar subbaralho") }, onClick = { menu = false; action = "create" })
                    DropdownMenuItem(text = { Text("Excluir baralho") }, onClick = { menu = false; action = "delete" })
                }
            }
        }
    }

    if (showScheduling) SchedulingInfoDialog { showScheduling = false }

    val editing = action == "edit"
    if (action == "create" || (editing && selected != null)) {
        val initial = if (editing) selected!! else CardDeck(name = "", parentId = selected?.id)
        DeckOptionsProDialog(
            tree = tree,
            initial = initial,
            editing = editing,
            reviews = reviews,
            onDismiss = { action = null },
            onSave = { updated ->
                val savedId = save(updated)
                action = null
                choose(savedId)
                savedId
            },
            onRequestDelete = if (editing) {
                { action = "delete" }
            } else null
        )
    }

    if (action == "delete" && selected != null) {
        DeleteDeckConfirmDialog(
            tree = tree,
            deck = selected,
            onDismiss = { action = null },
            onConfirmDelete = {
                dissolve(selected.id)
                action = null
                choose(selected.parentId ?: -1L)
            }
        )
    }
}

@Composable
internal fun DeckOptionsProDialog(
    tree: DeckTree,
    initial: CardDeck,
    editing: Boolean,
    reviews: List<com.studydock.app.data.CardReview> = emptyList(),
    onDismiss: () -> Unit,
    onSave: suspend (CardDeck) -> Long,
    onRequestDelete: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val controlShape = studyControlShape()
    val scheme = MaterialTheme.colorScheme

    var name by rememberSaveable(initial.id, editing) { mutableStateOf(initial.name) }
    var parent by rememberSaveable(initial.id, editing) { mutableStateOf(initial.parentId) }
    var parentsMenu by remember { mutableStateOf(false) }

    // Tab 0: Agendador, Tab 1: Limites Diários, Tab 2: Cartas & Sanguessugas
    var selectedTab by rememberSaveable(initial.id, editing) { mutableIntStateOf(0) }

    var schedulerMode by rememberSaveable(initial.id, editing) {
        mutableStateOf(if (initial.schedulerMode.equals("FSRS", ignoreCase = true)) "FSRS" else "SM2")
    }
    var desiredRetention by rememberSaveable(initial.id, editing) {
        mutableFloatStateOf(initial.desiredRetention.toFloat().coerceIn(0.75f, 0.97f))
    }
    var learningSteps by rememberSaveable(initial.id, editing) { mutableStateOf(initial.learningSteps.ifBlank { "1 10" }) }
    var relearningSteps by rememberSaveable(initial.id, editing) { mutableStateOf(initial.relearningSteps.ifBlank { "10" }) }
    var graduatingInterval by rememberSaveable(initial.id, editing) { mutableStateOf(initial.graduatingInterval.coerceAtLeast(1).toString()) }
    var easyInterval by rememberSaveable(initial.id, editing) { mutableStateOf(initial.easyInterval.coerceAtLeast(1).toString()) }

    var newLimit by rememberSaveable(initial.id, editing) { mutableStateOf(initial.newLimit?.toString().orEmpty()) }
    var reviewLimit by rememberSaveable(initial.id, editing) { mutableStateOf(initial.reviewLimit?.toString().orEmpty()) }
    var goal by rememberSaveable(initial.id, editing) { mutableStateOf(initial.dailyGoal?.toString().orEmpty()) }
    var dayStartHour by rememberSaveable(initial.id, editing) { mutableStateOf(initial.dayStartHour.coerceIn(0, 23).toString()) }
    var filterQuery by rememberSaveable(initial.id, editing) { mutableStateOf(initial.filterQuery) }

    var burySiblings by rememberSaveable(initial.id, editing) { mutableStateOf(initial.burySiblings) }
    var leechThreshold by rememberSaveable(initial.id, editing) { mutableStateOf(initial.leechThreshold.coerceAtLeast(1).toString()) }
    var leechSuspend by rememberSaveable(initial.id, editing) { mutableStateOf(initial.leechSuspend) }
    var fsrsWeights by rememberSaveable(initial.id, editing) { mutableStateOf(initial.fsrsWeights) }
    var presetName by rememberSaveable(initial.id, editing) { mutableStateOf(initial.presetName) }
    var easyDaysMask by rememberSaveable(initial.id, editing) { mutableIntStateOf(initial.easyDaysMask) }
    var displayOrder by rememberSaveable(initial.id, editing) { mutableStateOf(initial.displayOrder.ifBlank { "MIX" }) }
    var autoAdvanceSec by rememberSaveable(initial.id, editing) { mutableStateOf(if (initial.autoAdvanceSec > 0) initial.autoAdvanceSec.toString() else "") }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun parseStepsValid(raw: String): Boolean {
        val tokens = raw.trim().split(STEPS_SPLIT_REGEX).filter { it.isNotBlank() }
        return tokens.isNotEmpty() && tokens.all { token ->
            val v = token.toIntOrNull()
            v != null && v in 1..43_200
        }
    }

    fun normalizeSteps(raw: String): String =
        raw.trim().split(STEPS_SPLIT_REGEX).filter { it.isNotBlank() }.joinToString(" ")

    val nameValid = name.isNotBlank()
    val learningValid = parseStepsValid(learningSteps)
    val relearningValid = parseStepsValid(relearningSteps)
    val gradInt = graduatingInterval.toIntOrNull()
    val easyInt = easyInterval.toIntOrNull()
    val gradValid = gradInt != null && gradInt in 1..3650
    val easyValid = easyInt != null && easyInt in 1..3650 && (gradInt == null || easyInt >= gradInt)

    val newLimitValid = newLimit.isBlank() || (newLimit.toIntOrNull()?.let { it >= 0 } == true)
    val reviewLimitValid = reviewLimit.isBlank() || (reviewLimit.toIntOrNull()?.let { it >= 0 } == true)
    val goalValid = goal.isBlank() || ((goal.toIntOrNull() ?: 0) > 0)
    val dayStartInt = dayStartHour.toIntOrNull()
    val dayStartValid = dayStartInt != null && dayStartInt in 0..23

    val leechInt = leechThreshold.toIntOrNull()
    val leechValid = leechInt != null && leechInt in 1..999

    val schedulerValid = learningValid && relearningValid && gradValid && easyValid
    val limitsValid = newLimitValid && reviewLimitValid && goalValid && dayStartValid
    val cardsValid = leechValid
    val allValid = nameValid && schedulerValid && limitsValid && cardsValid

    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
                shape = studyCardShape(isHero = true),
                color = scheme.surface,
                contentColor = scheme.onSurface,
                tonalElevation = 6.dp
            ) {
                Column(
                    Modifier.padding(StudyTokens.cardPadding()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (editing) "Opções do baralho" else "Criar baralho",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (initial.isFiltered) {
                                Text(
                                    "Baralho Filtrado (Query DSL)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.primary
                                )
                            }
                        }
                        if (editing && onRequestDelete != null) {
                            TextButton(
                                onClick = onRequestDelete,
                                enabled = !busy
                            ) {
                                Text("Excluir", color = scheme.error)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nome do baralho") },
                        singleLine = true,
                        isError = !nameValid,
                        shape = controlShape
                    )

                    if (!initial.isFiltered) {
                        Box {
                            TextButton({ parentsMenu = true }) {
                                Text(
                                    "Dentro de · ${parent?.let(tree::label) ?: "Coleção (raiz)"}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            DropdownMenu(parentsMenu, { parentsMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Coleção (raiz)") },
                                    onClick = { parent = null; parentsMenu = false }
                                )
                                tree.ordered
                                    .filterNot { editing && tree.contains(initial.id, it.id) }
                                    .forEach { item ->
                                        DropdownMenuItem(
                                            text = { Text(tree.label(item.id), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                            onClick = { parent = item.id; parentsMenu = false }
                                        )
                                    }
                            }
                        }
                    }

                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Text(
                                    if (schedulerValid) "Agendador" else "Agendador •",
                                    color = if (!schedulerValid && selectedTab != 0) scheme.error else LocalContentColor.current,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Text(
                                    if (limitsValid) "Limites" else "Limites •",
                                    color = if (!limitsValid && selectedTab != 1) scheme.error else LocalContentColor.current,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = {
                                Text(
                                    if (cardsValid) "Sanguessugas" else "Sanguessugas •",
                                    color = if (!cardsValid && selectedTab != 2) scheme.error else LocalContentColor.current,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }

                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (selectedTab) {
                            0 -> {
                                Text("Algoritmo de repetição", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    FilterChip(
                                        selected = schedulerMode == "FSRS",
                                        onClick = { schedulerMode = "FSRS" },
                                        label = { Text("FSRS (Recomendado Pro)") }
                                    )
                                    FilterChip(
                                        selected = schedulerMode == "SM2",
                                        onClick = { schedulerMode = "SM2" },
                                        label = { Text("SM-2 Clássico") }
                                    )
                                }
                                Text(
                                    if (schedulerMode == "FSRS") {
                                        "FSRS modela estabilidade e dificuldade da memória para agendar revisões com menos carga para a retenção escolhida."
                                    } else {
                                        "SM-2 usa fator de facilidade por carta e progressão clássica de intervalos."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )

                                if (schedulerMode == "FSRS") {
                                    val retentionPct = (desiredRetention * 100f).roundToInt().coerceIn(75, 97)
                                    Surface(
                                        shape = controlShape,
                                        color = scheme.surfaceContainerLow,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Retenção desejada", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                                Text("$retentionPct%", style = MaterialTheme.typography.labelLarge, color = scheme.primary, fontWeight = FontWeight.Bold)
                                            }
                                            Slider(
                                                value = desiredRetention.coerceIn(0.75f, 0.97f),
                                                onValueChange = { desiredRetention = it.coerceIn(0.75f, 0.97f) },
                                                valueRange = 0.75f..0.97f,
                                                steps = 21
                                            )
                                            Text(
                                                "Padrão Anki: 90%. Acima de 93% aumenta significativamente as revisões diárias.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = scheme.onSurfaceVariant
                                            )
                                            var calibrationNote by remember { mutableStateOf<String?>(null) }
                                            var suggestedRetention by remember { mutableStateOf<Float?>(null) }
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        val res = com.studydock.app.data.FsrsOptimizer.calibrate(
                                                            reviews = reviews,
                                                            currentDesiredRetention = desiredRetention.toDouble(),
                                                            deckId = initial.id.takeIf { it > 0L }
                                                        )
                                                        if (res.sampleCount >= 10) {
                                                            fsrsWeights = com.studydock.app.data.FsrsOptimizer.serializeWeights(res.optimizedWeights)
                                                            suggestedRetention = res.recommendedRetention.toFloat().coerceIn(0.75f, 0.97f)
                                                        }
                                                        calibrationNote = res.summary
                                                    }
                                                ) {
                                                    Text("Calibrar com meu histórico")
                                                }
                                                suggestedRetention?.let { rec ->
                                                    val recPct = (rec * 100f).roundToInt()
                                                    if (recPct != retentionPct) {
                                                        TextButton(onClick = { desiredRetention = rec }) {
                                                            Text("Usar $recPct% sugerido")
                                                        }
                                                    }
                                                }
                                            }
                                            calibrationNote?.let { note ->
                                                Text(
                                                    text = note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = scheme.primary
                                                )
                                            }
                                            OutlinedTextField(
                                                value = fsrsWeights,
                                                onValueChange = { fsrsWeights = it },
                                                label = { Text("Pesos FSRS-5 (w0..w18)") },
                                                placeholder = { Text("Padrão FSRS-5 (19 pesos)") },
                                                singleLine = true,
                                                shape = controlShape,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            OutlinedTextField(
                                                value = presetName,
                                                onValueChange = { presetName = it },
                                                label = { Text("Preset compartilhado (opcional)") },
                                                singleLine = true,
                                                shape = controlShape,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                OutlinedTextField(
                                    value = learningSteps,
                                    onValueChange = { learningSteps = it },
                                    label = { Text("Passos de aprendizagem (min)") },
                                    placeholder = { Text("1 10") },
                                    supportingText = {
                                        Text(
                                            if (learningValid) "Minutos separados por espaço (ex: 1 10 ou 1 10 60)."
                                            else "Informe inteiros positivos de 1 a 43200 separados por espaço."
                                        )
                                    },
                                    isError = !learningValid,
                                    singleLine = true,
                                    shape = controlShape,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = relearningSteps,
                                    onValueChange = { relearningSteps = it },
                                    label = { Text("Passos de reaprendizagem após erro (min)") },
                                    placeholder = { Text("10") },
                                    supportingText = {
                                        Text(
                                            if (relearningValid) "Minutos usados quando você erra um cartão em revisão (ex: 10)."
                                            else "Informe inteiros positivos de 1 a 43200 separados por espaço."
                                        )
                                    },
                                    isError = !relearningValid,
                                    singleLine = true,
                                    shape = controlShape,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    if (maxWidth >= 260.dp * fontScale) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DeckNumber(
                                                label = "Graduação (dias)",
                                                value = graduatingInterval,
                                                modifier = Modifier.weight(1f),
                                                isError = !gradValid
                                            ) { graduatingInterval = it }
                                            DeckNumber(
                                                label = "Intervalo Fácil (dias)",
                                                value = easyInterval,
                                                modifier = Modifier.weight(1f),
                                                isError = !easyValid
                                            ) { easyInterval = it }
                                        }
                                    } else {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DeckNumber(
                                                label = "Intervalo de graduação (dias)",
                                                value = graduatingInterval,
                                                modifier = Modifier.fillMaxWidth(),
                                                isError = !gradValid
                                            ) { graduatingInterval = it }
                                            DeckNumber(
                                                label = "Intervalo Fácil (dias)",
                                                value = easyInterval,
                                                modifier = Modifier.fillMaxWidth(),
                                                isError = !easyValid
                                            ) { easyInterval = it }
                                        }
                                    }
                                }
                                if (!gradValid || !easyValid) {
                                    Text(
                                        "Graduação deve ser >= 1 dia e Intervalo Fácil >= Graduação.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.error
                                    )
                                }
                            }

                            1 -> {
                                Text("Limites diários", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Os subbaralhos compartilham os limites do pai. Deixe em branco para estudar sem limite.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    if (maxWidth >= 260.dp * fontScale) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DeckNumber("Novos / dia", newLimit, Modifier.weight(1f), !newLimitValid) { newLimit = it }
                                            DeckNumber("Revisões / dia", reviewLimit, Modifier.weight(1f), !reviewLimitValid) { reviewLimit = it }
                                        }
                                    } else {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            DeckNumber("Novos / dia", newLimit, Modifier.fillMaxWidth(), !newLimitValid) { newLimit = it }
                                            DeckNumber("Revisões / dia", reviewLimit, Modifier.fillMaxWidth(), !reviewLimitValid) { reviewLimit = it }
                                        }
                                    }
                                }
                                DeckNumber("Meta diária de cartões (opcional)", goal, Modifier.fillMaxWidth(), !goalValid) { goal = it }

                                HorizontalDivider()
                                Text("Virada do dia de estudo (Rollover)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                DeckNumber(
                                    label = "Hora de início do dia (0 a 23h)",
                                    value = dayStartHour,
                                    modifier = Modifier.fillMaxWidth(),
                                    isError = !dayStartValid
                                ) { dayStartHour = it }
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(0 to "00:00", 4 to "04:00 (Anki)", 6 to "06:00").forEach { (h, label) ->
                                        FilterChip(
                                            selected = dayStartHour.toIntOrNull() == h,
                                            onClick = { dayStartHour = h.toString() },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                                Text(
                                    "Define quando o StudyDock considera que começou um novo dia de limites e cartas enterradas (padrão Anki: 4h da manhã).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                                HorizontalDivider()
                                Text("Ordem de exibição & Dias fáceis", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        "MIX" to "Misturado",
                                        "NEW_FIRST" to "Novos primeiro",
                                        "REVIEW_FIRST" to "Revisões primeiro"
                                    ).forEach { (ordKey, ordLabel) ->
                                        FilterChip(
                                            selected = displayOrder.equals(ordKey, ignoreCase = true),
                                            onClick = { displayOrder = ordKey },
                                            label = { Text(ordLabel) }
                                        )
                                    }
                                }
                                Text(
                                    "Dias com carga reduzida (Easy Days):",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant
                                )
                                FlowRow(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom").forEachIndexed { bit, dayName ->
                                        val active = (easyDaysMask and (1 shl bit)) != 0
                                        FilterChip(
                                            selected = active,
                                            onClick = { easyDaysMask = easyDaysMask xor (1 shl bit) },
                                            label = { Text(dayName) }
                                        )
                                    }
                                }
                                DeckNumber(
                                    label = "Auto-avançar pergunta (seg, 0 = desligado)",
                                    value = autoAdvanceSec,
                                    modifier = Modifier.fillMaxWidth()
                                ) { autoAdvanceSec = it }
                                if (initial.isFiltered) {
                                    HorizontalDivider()
                                    OutlinedTextField(
                                        value = filterQuery,
                                        onValueChange = { filterQuery = it },
                                        label = { Text("Query DSL do baralho filtrado") },
                                        singleLine = true,
                                        shape = controlShape,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            2 -> {
                                Text("Cartas irmãs", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Surface(
                                    shape = controlShape,
                                    color = scheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text("Ocultar irmãos até amanhã", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                            Text(
                                                "Enterra automaticamente variantes irmãs (Cloze, Invertidos, Oclusão) após revisar uma delas no dia.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = scheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(checked = burySiblings, onCheckedChange = { burySiblings = it })
                                    }
                                }

                                HorizontalDivider()
                                Text("Sanguessugas (Leeches)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                DeckNumber(
                                    label = "Limiar de erros (lapses) para sanguessuga",
                                    value = leechThreshold,
                                    modifier = Modifier.fillMaxWidth(),
                                    isError = !leechValid
                                ) { leechThreshold = it }
                                Text(
                                    if (leechValid) "Cartões que atingirem esse número de lapsos serão marcados para revisão cuidadosa."
                                    else "Informe um número entre 1 e 999.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (leechValid) scheme.onSurfaceVariant else scheme.error
                                )

                                Text("Ação ao atingir o limiar", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        onClick = { leechSuspend = false },
                                        shape = controlShape,
                                        color = if (!leechSuspend) scheme.primaryContainer else scheme.surfaceContainerLow,
                                        contentColor = if (!leechSuspend) scheme.onPrimaryContainer else scheme.onSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = !leechSuspend, onClick = { leechSuspend = false })
                                            Column {
                                                Text("Apenas marcar com tag 'sanguessuga'", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                                Text("Mantém o cartão ativo nas revisões.", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    Surface(
                                        onClick = { leechSuspend = true },
                                        shape = controlShape,
                                        color = if (leechSuspend) scheme.primaryContainer else scheme.surfaceContainerLow,
                                        contentColor = if (leechSuspend) scheme.onPrimaryContainer else scheme.onSurface,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = leechSuspend, onClick = { leechSuspend = true })
                                            Column {
                                                Text("Marcar tag + Suspender automaticamente", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                                Text("Pausa o cartão até que você o reformule.", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                        }
                    }

                    HorizontalDivider()
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = allValid && !busy,
                            shape = controlShape,
                            onClick = {
                                if (busy) return@Button
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        val cleanRetention = ((desiredRetention * 100f).roundToInt().coerceIn(75, 97)) / 100.0
                                        onSave(
                                            initial.copy(
                                                name = name.trim(),
                                                parentId = if (initial.isFiltered) initial.parentId else parent,
                                                newLimit = newLimit.toIntOrNull(),
                                                reviewLimit = reviewLimit.toIntOrNull(),
                                                dailyGoal = goal.toIntOrNull(),
                                                schedulerMode = schedulerMode,
                                                learningSteps = normalizeSteps(learningSteps),
                                                relearningSteps = normalizeSteps(relearningSteps),
                                                graduatingInterval = (gradInt ?: 1).coerceAtLeast(1),
                                                easyInterval = (easyInt ?: 4).coerceAtLeast(1),
                                                desiredRetention = cleanRetention,
                                                burySiblings = burySiblings,
                                                leechThreshold = (leechInt ?: 8).coerceAtLeast(1),
                                                leechSuspend = leechSuspend,
                                                dayStartHour = (dayStartInt ?: 4).coerceIn(0, 23),
                                                filterQuery = if (initial.isFiltered) filterQuery.trim() else initial.filterQuery,
                                                fsrsWeights = fsrsWeights.trim(),
                                                presetName = presetName.trim(),
                                                easyDaysMask = easyDaysMask,
                                                displayOrder = displayOrder,
                                                autoAdvanceSec = autoAdvanceSec.toIntOrNull()?.coerceIn(0, 300) ?: 0
                                            )
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.localizedMessage ?: "Não foi possível salvar o baralho."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) {
                            Text(if (busy) "Salvando…" else "Salvar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeleteDeckConfirmDialog(
    tree: DeckTree,
    deck: CardDeck,
    onDismiss: () -> Unit,
    onConfirmDelete: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.imePadding(),
        title = { Text("Excluir ${deck.name}?", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val target = deck.parentId?.let(tree::label)
                Text(
                    if (target == null) "Os cartões vão para Sem baralho e os subbaralhos ficam na coleção. O histórico é mantido."
                    else "Os cartões e os subbaralhos vão para $target. O histórico é mantido."
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (busy) return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            onConfirmDelete()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Não foi possível excluir o baralho."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text(if (busy) "Excluindo…" else "Excluir", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun DeckNumber(
    label: String,
    value: String,
    modifier: Modifier,
    isError: Boolean = false,
    update: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { entry -> if (entry.all(Char::isDigit)) update(entry) },
        modifier = modifier,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        isError = isError,
        shape = studyControlShape(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

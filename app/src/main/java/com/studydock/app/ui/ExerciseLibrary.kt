@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.content.SharedPreferences
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.WorkoutLog
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Movement(val name: String, val sets: Int = 3, val reps: Int = 10, val rest: Int = 60)
private data class Plan(val name: String, val moves: List<Movement>)
private val examples = listOf(
    Plan("Treino de perna", listOf(Movement("Agachamento"), Movement("Afundo"), Movement("Panturrilha"))),
    Plan("Treino de ombro", listOf(Movement("Desenvolvimento"), Movement("Elevação lateral")))
)
private val recordedSet = Regex("""(.+) · (\d+) rep · (\d+)s""")
private val shortDate = DateTimeFormatter.ofPattern("dd/MM")
private val fullDate = DateTimeFormatter.ofPattern("EEE, dd/MM/yyyy", Locale("pt", "BR"))

private fun encode(plan: Plan): String {
    val moves = JSONArray()
    plan.moves.forEach { moves.put(JSONArray(listOf(it.name, it.sets, it.reps, it.rest))) }
    return JSONObject().put("name", plan.name).put("moves", moves).toString()
}

private fun decode(raw: String): Plan {
    val json = JSONObject(raw)
    val moves = json.getJSONArray("moves")
    return Plan(json.getString("name"), List(moves.length()) { index ->
        val row = moves.getJSONArray(index)
        Movement(row.getString(0), row.getInt(1), row.getInt(2), row.getInt(3))
    })
}

internal fun validateTrainingPlans(raw: String) {
    val array = JSONArray(raw)
    repeat(array.length()) { index ->
        val plan = decode(array.getString(index))
        require(plan.name.isNotBlank() && plan.moves.isNotEmpty() && plan.moves.all { it.name.isNotBlank() && it.sets > 0 && it.reps > 0 && it.rest >= 0 }) { "Plano de treino inválido." }
    }
}

private fun loadPlans(prefs: SharedPreferences): List<Plan> {
    val raw = prefs.getString("training_plans", null) ?: return examples
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) { decode(array.getString(it)) }
    }.getOrDefault(examples)
}

internal fun exerciseNames(prefs: SharedPreferences, workouts: List<WorkoutLog>): List<String> {
    val planned = loadPlans(prefs).flatMap { plan -> plan.moves.map { it.name } }
    val logged = workouts.flatMap { workout -> workout.exercises.lines().mapNotNull { recordedSet.matchEntire(it.trim())?.groupValues?.get(1) } }
    return (listOf("Flexões") + planned + logged).distinctBy { it.lowercase(Locale.ROOT) }
}

@Composable
internal fun PreviousWorkout(name: String, workouts: List<WorkoutLog>) {
    val previous = remember(name, workouts) {
        workouts.sortedByDescending { it.startedAt }.firstNotNullOfOrNull { log ->
            val reps = log.exercises.lines().mapNotNull { line ->
                recordedSet.matchEntire(line.trim())?.takeIf { it.groupValues[1].equals(name, true) }?.groupValues?.get(2)?.toIntOrNull()
            }
            if (reps.isEmpty()) null else {
                val date = Instant.ofEpochMilli(log.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(shortDate)
                val series = reps.joinToString(" + ")
                "Último treino ($date) · " + (if (reps.size > 1) "$series = ${reps.sum()}" else series) + " rep"
            }
        }
    }
    if (previous != null) Text(previous, style = MaterialTheme.typography.bodyMedium)
}

private data class LoggedSet(val name: String, val reps: Int, val date: LocalDate)

@Composable
internal fun ExerciseLibrary(plansTab: Boolean, workouts: List<WorkoutLog>, prefs: SharedPreferences,
    onSave: (String, String, Int, String) -> Unit, onDelete: (WorkoutLog) -> Unit, onTimer: (String) -> Unit) {
    var plans by remember { mutableStateOf(loadPlans(prefs)) }
    var editor by rememberSaveable { mutableStateOf<Int?>(null) }
    var session by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    fun store(next: List<Plan>) {
        prefs.edit().putString("training_plans", JSONArray(next.map(::encode)).toString()).apply()
        plans = next
    }
    val sets = remember(workouts) { workouts.flatMap { log ->
        val date = Instant.ofEpochMilli(log.startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        log.exercises.lines().mapNotNull { line -> recordedSet.matchEntire(line.trim())?.let {
            val reps = it.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            LoggedSet(it.groupValues[1].trim(), reps, date)
        } }
    } }
    val cardPad = StudyTokens.cardPadding()
    val itemSpace = StudyTokens.itemSpacing()
    if (plansTab) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Meus planos", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FilledTonalButton({ editor = -1 }) { Text("Novo plano", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        plans.forEachIndexed { index, plan ->
            StudyCard(Modifier.fillMaxWidth().padding(top = itemSpace)) {
                Column(Modifier.padding(cardPad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    plan.moves.forEach { Text("${it.name} · ${it.sets} × ${it.reps} · ${it.rest}s") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button({ session = encode(plan) }) { Text("Iniciar treino", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        TextButton({ editor = index }) { Text("Editar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    } else {
        val exercise = selected
        if (exercise == null) {
            val names = (listOf("Flexões") + plans.flatMap { it.moves.map(Movement::name) } + sets.map { it.name })
                .distinctBy { it.lowercase(Locale.ROOT) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Meus exercícios", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilledTonalButton({ adding = true }) { Text("Outro exercício", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            names.forEach { name ->
                val latest = sets.filter { it.name.equals(name, true) }.maxOfOrNull { it.date }
                StudyCard(onClick = { selected = name }, modifier = Modifier.fillMaxWidth().padding(top = itemSpace)) {
                    Column(Modifier.padding(cardPad)) {
                        Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            latest?.let { "Último registro · ${it.format(fullDate)}" } ?: "Registrar primeira série",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            var history by rememberSaveable { mutableStateOf(false) }
            TextButton({ history = !history }) { Text(if (history) "Ocultar registros" else "Ver registros de treino", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (history) workouts.forEach { log ->
                Text(log.title + " · " + Instant.ofEpochMilli(log.startedAt).atZone(ZoneId.systemDefault()).toLocalDate().format(fullDate), fontWeight = FontWeight.SemiBold)
                Text(log.exercises)
                if (log.notes.isNotBlank()) Text(log.notes)
                TextButton({ onDelete(log) }) { Text("Excluir registro", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        } else {
            BackHandler { selected = null }
            TextButton({ selected = null }) { Text("‹ Todos os exercícios", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(exercise, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedButton({ onTimer(exercise) }) { Text("Abrir timer de treino", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val daily = sets.filter { it.name.equals(exercise, true) }.groupBy { it.date }.toSortedMap()
            Button({ session = encode(Plan(exercise, listOf(Movement(exercise, sets = 1)))) }, Modifier.padding(vertical = 10.dp)) {
                Text("Registrar séries", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Repetições por dia", style = MaterialTheme.typography.titleMedium)
            if (daily.isEmpty()) Text("Registre uma série para começar o gráfico.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else {
                RepetitionCurve(daily.mapValues { entry -> entry.value.sumOf { it.reps } })
                daily.entries.reversed().forEach { (date, rows) ->
                    ListItem(headlineContent = { Text(date.format(fullDate)) },
                        supportingContent = { Text("Séries · " + rows.joinToString(" + ") { it.reps.toString() }) },
                        trailingContent = { Text("${rows.sumOf { it.reps }} rep") })
                }
            }
        }
    }
    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Outro exercício") },
            text = {
                Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button({ selected = name.trim(); adding = false }, enabled = name.isNotBlank()) { Text("Abrir exercício", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { TextButton({ adding = false }) { Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        )
    }
    editor?.let { index -> PlanEditor(plans.getOrNull(index), { editor = null }, { plan ->
        store(plans.toMutableList().apply { if (index < 0) add(plan) else set(index, plan) }); editor = null
    }, { store(plans.filterIndexed { i, _ -> i != index }); editor = null }) }
    session?.let { raw -> TrainingSession(remember(raw) { decode(raw) }, workouts, onDismiss = { session = null }, onSave = onSave) }
}

@Composable
private fun RepetitionCurve(days: Map<LocalDate, Int>) {
    val dates = days.keys.sorted()
    val first = dates.first().toEpochDay()
    val span = (dates.last().toEpochDay() - first).coerceAtLeast(1)
    val peak = days.values.max().coerceAtLeast(1)
    val ink = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Column(Modifier.height(142.dp).padding(end = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(peak.toString(), style = MaterialTheme.typography.labelSmall)
            Text("0", style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(142.dp).padding(5.dp)) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y))
                }
                val points = dates.map { date ->
                    Offset(size.width * (date.toEpochDay() - first).toFloat() / span,
                        size.height * (1f - days.getValue(date).toFloat() / peak))
                }
                points.zipWithNext().forEach { (a, b) -> drawLine(ink, a, b, 2.dp.toPx()) }
                points.forEach { drawCircle(ink, 4.dp.toPx(), it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(dates.first().format(shortDate), style = MaterialTheme.typography.labelSmall)
                if (dates.size > 1) Text(dates.last().format(shortDate), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PlanEditor(initial: Plan?, close: () -> Unit, save: (Plan) -> Unit, delete: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var draft by rememberSaveable { mutableStateOf(encode(initial ?: Plan("", emptyList()))) }
    val moves = remember(draft) { decode(draft).moves }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(if (initial == null) "Novo plano" else "Editar plano") },
        text = { Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Nome do plano") }, modifier = Modifier.fillMaxWidth())
            moves.forEachIndexed { index, move ->
                OutlinedButton({ editing = index }, Modifier.fillMaxWidth()) {
                    Text("${move.name} · ${move.sets} × ${move.reps} · ${move.rest}s", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton({ editing = -1 }) { Text("+ Adicionar exercício", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (initial != null) TextButton({ confirmDelete = true }) { Text("Excluir plano", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } }, confirmButton = { Button({ save(Plan(title.trim(), moves)) }, enabled = title.isNotBlank() && moves.isNotEmpty()) { Text("Salvar plano", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(close) { Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Excluir este plano?") },
        confirmButton = { TextButton(delete) { Text("Excluir") } }, dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
    editing?.let { index ->
        val old = moves.getOrNull(index) ?: Movement("")
        var name by rememberSaveable(index) { mutableStateOf(old.name) }
        var sets by rememberSaveable(index) { mutableStateOf(old.sets.toString()) }
        var reps by rememberSaveable(index) { mutableStateOf(old.reps.toString()) }
        var rest by rememberSaveable(index) { mutableStateOf(old.rest.toString()) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Exercício do plano") },
            text = { Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Exercício") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TrainingNumber("Séries", sets) { sets = it }
                TrainingNumber("Repetições por série", reps) { reps = it }
                TrainingNumber("Descanso em segundos", rest) { rest = it }
                if (index >= 0) TextButton({ draft = encode(Plan("", moves.filterIndexed { i, _ -> i != index })); editing = null }) { Text("Remover do plano") }
            } }, confirmButton = { Button(onClick = {
                val move = Movement(name.trim(), sets.toInt(), reps.toInt(), rest.toInt())
                val updated = moves.toMutableList().apply { if (index < 0) add(move) else set(index, move) }
                draft = encode(Plan("", updated)); editing = null
            }, enabled = name.isNotBlank() && (sets.toIntOrNull() ?: 0) > 0 && (reps.toIntOrNull() ?: 0) > 0 && rest.isNotBlank()) { Text("Confirmar", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            dismissButton = { TextButton({ editing = null }) { Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    }
}

@Composable
private fun TrainingNumber(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), change: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { change(it.filter(Char::isDigit).take(4)) },
        singleLine = true,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun TrainingSession(plan: Plan, workouts: List<WorkoutLog>, onDismiss: () -> Unit, onSave: (String, String, Int, String) -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val move = plan.moves[index]
    var completed by rememberSaveable { mutableIntStateOf(0) }
    var lines by rememberSaveable { mutableStateOf("") }
    var sessionNotes by rememberSaveable { mutableStateOf("") }
    var reps by rememberSaveable(index) { mutableStateOf(move.reps.toString()) }
    var rest by rememberSaveable(index) { mutableStateOf(move.rest.toString()) }
    val start = rememberSaveable { SystemClock.elapsedRealtime() }
    var until by rememberSaveable { mutableLongStateOf(0) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val remaining = ((until - now + 999) / 1000).coerceAtLeast(0)
    LaunchedEffect(until) { do { now = SystemClock.elapsedRealtime(); if (now < until) delay(200) } while (now < until) }
    val close = { if (lines.isEmpty()) onDismiss() else discard = true }
    val inPlateau = remember(move.name, workouts) {
        BarbellPlateCalculator.isExerciseInPlateau(move.name, workouts)
    }
    AlertDialog(onDismissRequest = close, title = { Text(plan.name) },
        text = { Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (plan.moves.size > 1) Text("Exercício ${index + 1} de ${plan.moves.size}")
            Text(move.name, style = MaterialTheme.typography.headlineSmall)
            Text("$completed de ${move.sets} séries registradas")
            PreviousWorkout(move.name, workouts)
            if (inPlateau) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "📈 Platô detectado nas últimas 3 sessões de ${move.name}. Sugestão: Deload (-10% carga) ou progressão dupla.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            var weightKg by rememberSaveable(index) { mutableStateOf("") }
            var rpe by rememberSaveable(index) { mutableStateOf("") }
            var setType by rememberSaveable(index) { mutableStateOf("N") }
            TrainingNumber("Repetições feitas", reps) { reps = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrainingNumber("Carga (kg)", weightKg, Modifier.weight(1f)) { weightKg = it }
                TrainingNumber("RPE (6-10)", rpe, Modifier.weight(1f)) { rpe = it }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("W" to "Aquec.", "N" to "Normal", "D" to "Drop", "F" to "Falha").forEach { (k, lbl) ->
                    FilterChip(selected = setType == k, onClick = { setType = k }, label = { Text(lbl) })
                }
            }
            val wVal = weightKg.toDoubleOrNull() ?: 0.0
            val rVal = reps.toIntOrNull() ?: 0
            if (wVal > 0.0 && rVal > 0) {
                val oneRm = wVal * (1.0 + rVal / 30.0)
                val plates = BarbellPlateCalculator.platesPerSide(wVal)
                val platesStr = if (plates.isEmpty()) "" else " · Anilhas/lado: ${plates.joinToString("+")}kg"
                Text(String.format(Locale.getDefault(), "1RM estimado (Epley): %.1f kg%s", oneRm, platesStr), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            TrainingNumber("Descanso em segundos", rest) { rest = it }
            Button(onClick = {
                val line = "${move.name} · ${reps.toInt()} rep · ${rest.toInt()}s"
                lines = if (lines.isEmpty()) line else lines + "\n" + line
                val metaParts = buildList {
                    if (setType != "N") add("[$setType]")
                    if (wVal > 0.0) add("${weightKg}kg")
                    if (rpe.isNotBlank()) add("RPE $rpe")
                }
                if (metaParts.isNotEmpty()) {
                    val noteEntry = "${move.name} (#${completed + 1}): ${metaParts.joinToString(" · ")}"
                    sessionNotes = if (sessionNotes.isEmpty()) noteEntry else "$sessionNotes | $noteEntry"
                }
                completed++
                now = SystemClock.elapsedRealtime(); until = now + rest.toInt() * 1000L
            }, enabled = (reps.toIntOrNull() ?: 0) > 0 && rest.isNotBlank()) { Text("Registrar série", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (remaining > 0) Text("Descanso · ${remaining}s", color = MaterialTheme.colorScheme.primary)
            if (index < plan.moves.lastIndex) OutlinedButton({ index++; completed = 0; until = 0L }) {
                Text("Próximo · " + plan.moves[index + 1].name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } }, confirmButton = { Button(onClick = {
            onSave(plan.name, lines, ((SystemClock.elapsedRealtime() - start) / 60000).toInt(), sessionNotes)
            onDismiss()
        }, enabled = lines.isNotEmpty()) { Text("Salvar treino", maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        dismissButton = { TextButton(close) { Text("Sair", maxLines = 1, overflow = TextOverflow.Ellipsis) } })
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Descartar as séries desta sessão?") },
        confirmButton = { TextButton(onDismiss) { Text("Descartar") } }, dismissButton = { TextButton({ discard = false }) { Text("Continuar treino") } })
}

object BarbellPlateCalculator {
    private val standardPlates = doubleArrayOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    /** Retorna lista de anilhas por lado para atingir [targetTotalKg] com barra olímpica de [barKg]. */
    fun platesPerSide(targetTotalKg: Double, barKg: Double = 20.0): List<Double> {
        var remPerSide = ((targetTotalKg - barKg) / 2.0).coerceAtLeast(0.0)
        if (remPerSide <= 0.0) return emptyList()
        val result = mutableListOf<Double>()
        for (plate in standardPlates) {
            while (remPerSide + 1e-6 >= plate && result.size < 16) {
                result.add(plate)
                remPerSide -= plate
            }
        }
        return result
    }

    /** Detecta platô se nas últimas 3 sessões de [exerciseName] o número máximo de repetições não subiu. */
    fun isExerciseInPlateau(exerciseName: String, workouts: List<WorkoutLog>): Boolean {
        val targetLower = exerciseName.trim().lowercase(Locale.ROOT)
        val sessionMaxReps = workouts.mapNotNull { w ->
            val repsInWorkout = w.exercises.lines().mapNotNull { line ->
                val m = recordedSet.matchEntire(line.trim())
                if (m != null && m.groupValues[1].trim().lowercase(Locale.ROOT) == targetLower) {
                    m.groupValues[2].toIntOrNull()
                } else null
            }
            repsInWorkout.maxOrNull()
        }
        if (sessionMaxReps.size < 3) return false
        val recent3 = sessionMaxReps.take(3)
        // recent3[0] is most recent; plateau if recent3[0] <= recent3[1] && recent3[1] <= recent3[2]
        return recent3[0] <= recent3[1] && recent3[1] <= recent3[2]
    }
}

@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package com.studydock.app.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studydock.app.data.MealLog
import com.studydock.app.data.WorkoutLog
import java.util.Calendar
import java.util.Locale

private data class NutritionTargets(val calories: Int, val protein: Int, val carbs: Int, val fat: Int)

@Composable
fun FitnessPage(
    workouts: List<WorkoutLog>,
    meals: List<MealLog>,
    onAddWorkout: (String, String, Int, String) -> Unit,
    onDeleteWorkout: (WorkoutLog) -> Unit,
    onAddMeal: (String, String, Int, Double, Double, Double) -> Unit,
    onDeleteMeal: (MealLog) -> Unit,
    onTimer: (String) -> Unit
) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var showMealDialog by remember { mutableStateOf(false) }
    var showTargetsDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("studydock_fitness", Context.MODE_PRIVATE) }
    var targets by remember { mutableStateOf(loadTargets(prefs)) }
    val sectionSpace = StudyTokens.sectionSpacing()

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
        StudyPageHeader(
            title = "Treino e alimentação",
            showGlobalSearch = true
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = sectionSpace)) {
            listOf("Exercícios", "Planos", "Alimentação").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = tab == index,
                    onClick = { tab = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 3),
                    icon = {}
                ) {
                    Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (tab < 2) {
            ExerciseLibrary(tab == 1, workouts, prefs, onAddWorkout, onDeleteWorkout, onTimer)
        } else {
            NutritionTab(meals, targets, onAdd = { showMealDialog = true }, onDelete = onDeleteMeal, onTargets = { showTargetsDialog = true })
        }
    }
    if (showMealDialog) MealDialog(onDismiss = { showMealDialog = false }, onSave = { type, foods, calories, protein, carbs, fat -> onAddMeal(type, foods, calories, protein, carbs, fat); showMealDialog = false })
    if (showTargetsDialog) TargetsDialog(targets, onDismiss = { showTargetsDialog = false }, onSave = { updated ->
        targets = updated
        prefs.edit().putInt("target_calories", updated.calories).putInt("target_protein", updated.protein).putInt("target_carbs", updated.carbs).putInt("target_fat", updated.fat).apply()
        showTargetsDialog = false
    })
}

@Composable
private fun NutritionTab(meals: List<MealLog>, targets: NutritionTargets, onAdd: () -> Unit, onDelete: (MealLog) -> Unit, onTargets: () -> Unit) {
    val semantic = LocalStudySemanticColors.current
    val cardPad = StudyTokens.cardPadding()
    val sectionSpace = StudyTokens.sectionSpacing()
    val today = meals.filter { isSameDay(it.loggedAt, System.currentTimeMillis()) }
    val calories = today.sumOf { it.calories }
    val protein = today.sumOf { it.proteinGrams }.toInt()
    val carbs = today.sumOf { it.carbsGrams }.toInt()
    val fat = today.sumOf { it.fatGrams }.toInt()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Hoje", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Registre refeições e veja os totais do dia.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onTargets) { Icon(Icons.Default.Settings, "Definir metas diárias", tint = MaterialTheme.colorScheme.primary) }
        FilledTonalButton(onClick = onAdd) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Adicionar", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    StudyCard(modifier = Modifier.fillMaxWidth().padding(top = sectionSpace), emphasized = true, isHero = true) {
        Column(Modifier.padding(cardPad)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$calories", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (targets.calories > 0) " / ${targets.calories} kcal" else " kcal", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f), modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
            }
            if (targets.calories > 0) LinearProgressIndicator(progress = { (calories.toFloat() / targets.calories).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f))
            Text(
                when {
                    targets.calories == 0 -> "Toque na engrenagem para definir suas metas"
                    calories > targets.calories -> "${calories - targets.calories} kcal acima da meta definida"
                    else -> "${targets.calories - calories} kcal restantes para sua meta"
                },
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MacroProgress("Proteína", protein, targets.protein, "g", semantic.success)
        MacroProgress("Carboidratos", carbs, targets.carbs, "g", semantic.warning)
        MacroProgress("Gorduras", fat, targets.fat, "g", semantic.info)
    }
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Refeições de hoje", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onAdd) { Text("+ Adicionar", maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    if (today.isEmpty()) EmptyFitnessCard("Nenhuma refeição registrada", "Você pode adicionar uma descrição e preencher só os valores que quiser acompanhar.", Icons.Default.Restaurant)
    today.sortedByDescending { it.loggedAt }.forEach { meal ->
        StudyCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 5.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(meal.mealType, fontWeight = FontWeight.SemiBold)
                    if (meal.foods.isNotBlank()) Text(meal.foods, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                    Text("${meal.calories} kcal · P ${meal.proteinGrams.pretty()} g · C ${meal.carbsGrams.pretty()} g · G ${meal.fatGrams.pretty()} g", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
                }
                Text(StudyDateFormats.formatEpoch(meal.loggedAt, StudyDateFormats.TIME_HH_MM_PT), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { onDelete(meal) }) { Icon(Icons.Default.DeleteOutline, "Excluir refeição", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    Text("Últimos 7 dias", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
    val weekStart = java.time.LocalDate.now().minusDays(6).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val weekMeals = meals.filter { it.loggedAt >= weekStart }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FitnessMetric("Dias registrados", weekMeals.map { dayKey(it.loggedAt) }.distinct().size.toString(), Modifier.weight(1f))
        FitnessMetric("Média kcal/dia", if (weekMeals.isEmpty()) "—" else "${weekMeals.sumOf { it.calories } / weekMeals.map { dayKey(it.loggedAt) }.distinct().size}", Modifier.weight(1f))
    }
}

@Composable
private fun MacroProgress(label: String, value: Int, target: Int, unit: String, color: Color) {
    StudyCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (target > 0) "$value / $target $unit" else "$value $unit · sem meta", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (target > 0) LinearProgressIndicator(progress = { (value.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(CircleShape), color = color, trackColor = color.copy(alpha = 0.22f))
        }
    }
}

@Composable
private fun MealDialog(onDismiss: () -> Unit, onSave: (String, String, Int, Double, Double, Double) -> Unit) {
    var type by remember { mutableStateOf("Almoço") }
    var foods by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    val mealTypes = listOf("Café da manhã", "Almoço", "Jantar", "Lanche")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar refeição") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    mealTypes.forEach { label ->
                        FilterChip(
                            selected = type == label,
                            onClick = { type = label },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                OutlinedTextField(foods, { foods = it }, label = { Text("O que você comeu?") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(calories, { calories = it.filter(Char::isDigit) }, label = { Text("Calorias · opcional") }, suffix = { Text("kcal") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NutritionNumberField("Proteína", protein, { protein = it }, Modifier.weight(1f))
                    NutritionNumberField("Carbos", carbs, { carbs = it }, Modifier.weight(1f))
                }
                NutritionNumberField("Gordura", fat, { fat = it }, Modifier.fillMaxWidth())
                Text("Valores nutricionais são opcionais; você pode registrar só a descrição.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(type, foods.trim(), calories.toIntOrNull() ?: 0, protein.decimalValue(), carbs.decimalValue(), fat.decimalValue()) },
                enabled = foods.isNotBlank() || calories.isNotBlank()
            ) { Text("Salvar refeição", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    )
}

@Composable
private fun NutritionNumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = { Text("g", style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun TargetsDialog(targets: NutritionTargets, onDismiss: () -> Unit, onSave: (NutritionTargets) -> Unit) {
    var calories by remember { mutableStateOf(targets.calories.takeIf { it > 0 }?.toString() ?: "") }
    var protein by remember { mutableStateOf(targets.protein.takeIf { it > 0 }?.toString() ?: "") }
    var carbs by remember { mutableStateOf(targets.carbs.takeIf { it > 0 }?.toString() ?: "") }
    var fat by remember { mutableStateOf(targets.fat.takeIf { it > 0 }?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Suas metas diárias") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Defina os valores que você quer acompanhar. Eles são opcionais e podem ser alterados depois.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TargetNumberField("Calorias (kcal)", calories, { calories = it })
                TargetNumberField("Proteína (g)", protein, { protein = it })
                TargetNumberField("Carboidratos (g)", carbs, { carbs = it })
                TargetNumberField("Gorduras (g)", fat, { fat = it })
            }
        },
        confirmButton = {
            Button(onClick = { onSave(NutritionTargets(calories.toIntOrNull() ?: 0, protein.toIntOrNull() ?: 0, carbs.toIntOrNull() ?: 0, fat.toIntOrNull() ?: 0)) }) {
                Text("Salvar metas", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    )
}

@Composable
private fun TargetNumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit)) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FitnessMetric(title: String, value: String, modifier: Modifier) {
    StudyCard(modifier) {
        Column(Modifier.padding(11.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyFitnessCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    StudyCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun loadTargets(prefs: android.content.SharedPreferences) = NutritionTargets(prefs.getInt("target_calories", 0), prefs.getInt("target_protein", 0), prefs.getInt("target_carbs", 0), prefs.getInt("target_fat", 0))
private fun isSameDay(a: Long, b: Long): Boolean = dayKey(a) == dayKey(b)
private fun dayKey(time: Long): Int = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { it.year * 10000 + it.monthValue * 100 + it.dayOfMonth }
private fun dateLabel(time: Long): String = StudyDateFormats.formatEpoch(time, StudyDateFormats.WEEKDAY_D_MMM_TIME_PT).replaceFirstChar { it.uppercase() }
private fun String.decimalValue(): Double = replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0
private fun Double.pretty(): String = if (this % 1.0 == 0.0) toInt().toString() else String.format(Locale("pt", "BR"), "%.1f", this)

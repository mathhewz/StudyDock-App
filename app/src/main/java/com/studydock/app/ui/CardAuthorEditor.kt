@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.studydock.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studydock.app.data.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TEMPLATE_PREFS = "studydock_card_templates"
private const val KEY_CUSTOM_TEMPLATES = "custom_templates_json"
private const val KEY_HIDDEN_TEMPLATES = "hidden_template_names_json"

private data class LocalTemplateStoreState(
    val overrides: List<CardTemplate>,
    val hiddenNames: Set<String>
)

private fun loadLocalTemplateStore(context: Context): LocalTemplateStoreState {
    val prefs = context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
    val overrides = runCatching {
        val raw = prefs.getString(KEY_CUSTOM_TEMPLATES, null).orEmpty()
        if (raw.isBlank()) emptyList()
        else {
            val arr = JSONArray(raw)
            List(arr.length()) { idx ->
                val obj = arr.getJSONObject(idx)
                CardTemplate(
                    name = obj.optString("name"),
                    front = obj.optString("front"),
                    back = obj.optString("back")
                )
            }.filter { it.name.isNotBlank() }
        }
    }.getOrDefault(emptyList())
    val hidden = runCatching {
        val raw = prefs.getString(KEY_HIDDEN_TEMPLATES, null).orEmpty()
        if (raw.isBlank()) emptySet()
        else {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
    }.getOrDefault(emptySet())
    return LocalTemplateStoreState(overrides, hidden)
}

private fun saveLocalTemplateStore(context: Context, state: LocalTemplateStoreState) {
    val prefs = context.getSharedPreferences(TEMPLATE_PREFS, Context.MODE_PRIVATE)
    val arr = JSONArray()
    state.overrides.forEach { t ->
        arr.put(JSONObject().put("name", t.name).put("front", t.front).put("back", t.back))
    }
    val hiddenArr = JSONArray()
    state.hiddenNames.forEach { hiddenArr.put(it) }
    prefs.edit()
        .putString(KEY_CUSTOM_TEMPLATES, arr.toString())
        .putString(KEY_HIDDEN_TEMPLATES, hiddenArr.toString())
        .apply()
}

private val CARD_TAG_SPLIT_REGEX = Regex("[\\s,;]+")

private fun parseCardTags(raw: String): List<String> =
    raw.split(CARD_TAG_SPLIT_REGEX)
        .map { it.trim().removePrefix("#") }
        .filter { it.isNotBlank() }
        .distinct()

private fun canonicalAuthorFormat(rawFormat: String?, occlusion: String?): String {
    val normalized = rawFormat?.trim().orEmpty()
    return when {
        normalized.equals("occlusion", ignoreCase = true) -> "occlusion"
        normalized.equals("CLOZE", ignoreCase = true) && !occlusion.isNullOrBlank() -> "occlusion"
        normalized.equals("REVERSE", ignoreCase = true) -> "REVERSE"
        normalized.equals("CLOZE", ignoreCase = true) -> "CLOZE"
        normalized.equals("CUSTOM", ignoreCase = true) -> "CUSTOM"
        else -> "BASIC"
    }
}

@Composable
internal fun CardAuthorEditor(
    card: Flashcard?,
    deck: Long,
    subject: Long,
    decks: List<Pair<Long, String>>,
    subjects: List<Pair<Long, String>>,
    templates: List<CardTemplate>,
    load: suspend (Long) -> CardAuthorNote?,
    loadImage: suspend (Long) -> ByteArray?,
    save: suspend (Long?, CardAuthorNote?, CardAuthorNote, Long?, Long?, CardImage?) -> Unit,
    onDismiss: () -> Unit,
    existingTags: List<String> = emptyList(),
    onSaveTemplate: (suspend (CardTemplate) -> Unit)? = null,
    onDeleteTemplate: (suspend (CardTemplate) -> Unit)? = null
) {
    var source by remember { mutableStateOf<CardAuthorNote?>(null) }
    var loaded by remember { mutableStateOf(card?.authorNoteId == null) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(card?.authorNoteId) {
        val id = card?.authorNoteId ?: return@LaunchedEffect
        try {
            source = requireNotNull(load(id)) { "A nota deste cartão não está disponível." }
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = e.localizedMessage
        }
    }
    if (!loaded) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(problem ?: "Carregando nota…") },
            confirmButton = { TextButton(onDismiss) { Text("Fechar") } }
        )
        return
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val promptFocus = remember { FocusRequester() }
    val answerFocus = remember { FocusRequester() }
    val controlShape = studyControlShape()
    val scheme = MaterialTheme.colorScheme

    var format by rememberSaveable {
        mutableStateOf(canonicalAuthorFormat(source?.format, source?.occlusion))
    }
    var prompt by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(source?.prompt ?: card?.front.orEmpty()))
    }
    var answer by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(source?.answer ?: card?.back.orEmpty()))
    }
    var name by rememberSaveable { mutableStateOf(source?.template.orEmpty()) }
    var fieldText by rememberSaveable { mutableStateOf(source?.fields ?: "{}") }
    var tagsList by rememberSaveable {
        mutableStateOf(parseCardTags(source?.tags ?: card?.tags.orEmpty()))
    }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var typeAnswer by rememberSaveable { mutableStateOf(source?.typeAnswer ?: false) }

    var deckId by rememberSaveable { mutableLongStateOf(card?.let { it.deckId ?: 0L } ?: deck.coerceAtLeast(0)) }
    var subjectId by rememberSaveable { mutableLongStateOf(card?.let { it.subjectId ?: 0L } ?: subject.coerceAtLeast(0)) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var imageKey by rememberSaveable { mutableStateOf(source?.imageKey) }
    var occlusion by rememberSaveable { mutableStateOf(source?.occlusion) }
    var draftPath by rememberSaveable { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var showOcclusionEditor by remember { mutableStateOf(false) }

    var localTemplateStore by remember { mutableStateOf(loadLocalTemplateStore(context)) }
    var templateEditorTarget by remember { mutableStateOf<CardTemplate?>(null) }
    var isCreatingNewTemplate by remember { mutableStateOf(false) }
    var deletingTemplate by remember { mutableStateOf<CardTemplate?>(null) }

    val availableTemplates = remember(templates, localTemplateStore) {
        val byName = linkedMapOf<String, CardTemplate>()
        templates.forEach { t ->
            if (t.name !in localTemplateStore.hiddenNames) {
                byName[t.name.lowercase()] = t
            }
        }
        localTemplateStore.overrides.forEach { t ->
            if (t.name !in localTemplateStore.hiddenNames) {
                byName[t.name.lowercase()] = t
            }
        }
        byName.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            preparing = true
            try {
                val (key, draft) = withContext(Dispatchers.IO) {
                    val image = CardPictures.fromDocument(context.contentResolver, uri)
                    val draft = File.createTempFile("card-image-", ".webp", context.cacheDir)
                    draft.writeBytes(image.bytes)
                    image.key to draft
                }
                draftPath?.let { File(it).delete() }
                draftPath = draft.path
                imageKey = key
                occlusion = null
                problem = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = "Não foi possível preparar essa imagem. Escolha outra."
            } finally {
                preparing = false
            }
        }
    }

    fun clearDraft() {
        draftPath?.let { File(it).delete() }
        draftPath = null
    }

    suspend fun loadCurrentImageBytes(): ByteArray? {
        val path = draftPath
        return if (path != null) {
            withContext(Dispatchers.IO) { File(path).takeIf { it.isFile }?.readBytes() }
        } else {
            source?.id?.let { loadImage(it) }
        }
    }

    val custom = format == "CUSTOM"
    val isOcclusion = format.equals("occlusion", ignoreCase = true)
    val roomTemplate = templates.find { it.name.equals(name.trim(), ignoreCase = true) }
    val conflict = custom && onSaveTemplate == null && roomTemplate != null &&
        (roomTemplate.front != prompt.text || roomTemplate.back != answer.text) &&
        source?.let { it.template == name.trim() && it.prompt == prompt.text && it.answer == answer.text } != true

    val sourceDraft = remember(format, prompt.text, answer.text, name, fieldText, imageKey, occlusion, tagsList, typeAnswer) {
        val fields = JSONObject()
        if (custom) {
            val all = runCatching { JSONObject(fieldText) }.getOrDefault(JSONObject())
            templateFields(prompt.text, answer.text).forEach { fields.put(it, all.optString(it)) }
        }
        CardAuthorNote(
            format = format,
            prompt = prompt.text.trim(),
            answer = answer.text.trim(),
            template = if (custom) name.trim() else "",
            fields = fields.toString(),
            imageKey = imageKey,
            occlusion = occlusion?.takeIf { (isOcclusion || format == "CLOZE") && imageKey != null },
            tags = tagsList.joinToString(" "),
            typeAnswer = typeAnswer
        )
    }

    val variants = remember(sourceDraft) { sourceDraft.variants() }
    val labels = linkedMapOf(
        "BASIC" to "Básico",
        "REVERSE" to "Básico (e invertido)",
        "CLOZE" to "Omissão de palavras (Cloze)",
        "occlusion" to "Oclusão de imagem",
        "CUSTOM" to "Modelo personalizado"
    )
    val dismiss = { if (!saving && !preparing) { clearDraft(); onDismiss() } }

    val activeBoxes = remember(occlusion, isOcclusion, format) {
        if (isOcclusion || format == "CLOZE") parseOcclusionBoxes(occlusion) else emptyList()
    }

    fun addTagsFromRaw(raw: String) {
        val parsed = parseCardTags(raw)
        if (parsed.isNotEmpty()) {
            tagsList = (tagsList + parsed).distinct()
        }
        tagInput = ""
    }

    CardEditorDialog(
        onDismissRequest = dismiss,
        title = { Text(if (card == null) "Criar cartões" else "Editar conteúdo") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val canChangeFormat = (source == null && card == null) || isOcclusion
                if (source == null && card == null) {
                    ChoiceMenu(
                        label = "Modelo",
                        selected = labels[format] ?: format,
                        items = labels.entries.map { it.key to it.value }
                    ) { chosen ->
                        format = chosen
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            labels[format] ?: labels[format.lowercase()] ?: format,
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary
                        )
                        if (canChangeFormat && format != "occlusion" && imageKey != null) {
                            TextButton(onClick = { format = "occlusion" }) {
                                Text("Usar como Oclusão")
                            }
                        }
                    }
                }

                if (custom) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (availableTemplates.isNotEmpty()) {
                                ChoiceMenu(
                                    label = "Modelo",
                                    selected = name.ifBlank { "Escolher salvo" },
                                    items = availableTemplates.map { it.name to it.name }
                                ) { selected ->
                                    val saved = availableTemplates.first { it.name == selected }
                                    name = saved.name
                                    prompt = TextFieldValue(saved.front)
                                    answer = TextFieldValue(saved.back)
                                }
                            } else {
                                Text(
                                    "Nenhum modelo salvo ainda",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                isCreatingNewTemplate = true
                                templateEditorTarget = CardTemplate(
                                    name = "",
                                    front = "{{Termo}}",
                                    back = "{{Definição}}"
                                )
                            }
                        ) {
                            Icon(Icons.Default.AddCircleOutline, "Novo modelo personalizado")
                        }
                        val currentSaved = availableTemplates.find { it.name.equals(name.trim(), ignoreCase = true) }
                        if (currentSaved != null) {
                            IconButton(
                                onClick = {
                                    isCreatingNewTemplate = false
                                    templateEditorTarget = currentSaved.copy(
                                        front = prompt.text.ifBlank { currentSaved.front },
                                        back = answer.text.ifBlank { currentSaved.back }
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Edit, "Editar modelo")
                            }
                            IconButton(
                                onClick = { deletingTemplate = currentSaved }
                            ) {
                                Icon(Icons.Default.DeleteOutline, "Excluir modelo", tint = scheme.error)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome do modelo") },
                        singleLine = true,
                        shape = controlShape,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Use {{Termo}}, {{#Extra}}...{{/Extra}} ou {{type:Resposta}} na frente e no verso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = {
                        Text(
                            when {
                                isOcclusion -> "Título / contexto da imagem (sem respostas)"
                                format == "CLOZE" -> "Texto com lacunas ({{c1::...}})"
                                custom -> "Modelo da frente"
                                else -> "Pergunta"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(promptFocus),
                    shape = controlShape
                )
                FormatControls(
                    value = prompt,
                    change = { prompt = it },
                    focus = promptFocus,
                    showClozeButton = format == "CLOZE" || (source == null && card == null && format == "BASIC"),
                    onClozeInserted = {
                        if (format == "BASIC" && source == null && card == null) {
                            format = "CLOZE"
                        }
                    }
                )

                if (format == "CLOZE") {
                    Text(
                        "Selecione a parte que quer ocultar e toque em \"[...] Cloze\" ou use Ocultar seleção.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    TextButton(
                        enabled = !prompt.selection.collapsed,
                        onClick = {
                            val start = prompt.selection.min
                            val end = prompt.selection.max
                            val used = clozeNumbers(prompt.text).toSet()
                            val number = generateSequence(1) { it + 1 }.first { it.toString() !in used }
                            val mark = "{{c$number::" + prompt.text.substring(start, end) + "}}"
                            prompt = TextFieldValue(
                                prompt.text.replaceRange(start, end, mark),
                                TextRange(start + mark.length)
                            )
                        }
                    ) {
                        Text("Ocultar seleção")
                    }
                }

                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(answerFocus),
                    shape = controlShape,
                    label = {
                        Text(
                            when {
                                isOcclusion || format == "CLOZE" -> "Explicação / nota extra (opcional)"
                                custom -> "Modelo do verso"
                                else -> "Resposta"
                            }
                        )
                    }
                )
                FormatControls(
                    value = answer,
                    change = { answer = it },
                    focus = answerFocus,
                    showClozeButton = false
                )

                if (custom) {
                    val fields = remember(fieldText) {
                        runCatching { JSONObject(fieldText) }.getOrDefault(JSONObject())
                    }
                    templateFields(prompt.text, answer.text).forEach { field ->
                        OutlinedTextField(
                            value = fields.optString(field),
                            onValueChange = { value ->
                                val base = runCatching { JSONObject(fieldText) }.getOrDefault(JSONObject())
                                fieldText = base.put(field, value).toString()
                            },
                            label = { Text(field) },
                            shape = controlShape,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (conflict) {
                        Text(
                            "Use outro nome para salvar esta variação do modelo.",
                            color = scheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Image & Occlusion Section
                Surface(
                    shape = controlShape,
                    color = scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                enabled = !saving && !preparing,
                                onClick = { picker.launch(arrayOf("image/*")) }
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (preparing) "Preparando imagem…"
                                    else if (imageKey != null) "Trocar imagem"
                                    else if (isOcclusion) "Selecionar imagem para oclusão"
                                    else "Anexar imagem / Oclusão",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (imageKey != null) {
                                FilledTonalButton(
                                    enabled = !saving && !preparing,
                                    onClick = { showOcclusionEditor = true },
                                    shape = controlShape
                                ) {
                                    Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (activeBoxes.isNotEmpty()) "Editar Oclusão (${activeBoxes.size})" else "Oclusão de Imagem",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    enabled = !saving && !preparing,
                                    onClick = {
                                        clearDraft()
                                        imageKey = null
                                        occlusion = null
                                    }
                                ) {
                                    Text("Remover", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        if (isOcclusion && imageKey == null) {
                            Text(
                                "Escolha uma imagem acima e desenhe as caixas de oclusão. As respostas ficam ocultas na frente sem vazar rótulos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        } else if (isOcclusion && activeBoxes.isEmpty()) {
                            Text(
                                "Toque em \"Oclusão de Imagem\" para desenhar ao menos 1 região ocultada.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.error
                            )
                        }

                        imageKey?.let { key ->
                            if (activeBoxes.isNotEmpty()) {
                                val hideAll = activeBoxes.firstOrNull()?.hideAll ?: true
                                AnswerPicture(
                                    identity = key,
                                    occlusionBoxes = activeBoxes,
                                    activeVariantKey = null,
                                    targetBoxId = null,
                                    revealed = false,
                                    hideAll = hideAll
                                ) {
                                    loadCurrentImageBytes()
                                }
                                Text(
                                    "${activeBoxes.size} região(ões) · ${if (hideAll) "Ocultar todas, adivinhar uma" else "Ocultar uma, adivinhar uma"} · sem spoiler no texto",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            } else {
                                AnswerPicture(key) { loadCurrentImageBytes() }
                            }
                        }
                    }
                }

                // Type-in-Answer Toggle
                Surface(
                    shape = controlShape,
                    color = scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Keyboard, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Digitar resposta ({{type:...}})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                "Solicita digitação na revisão e destaca acertos/erros letra por letra.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = typeAnswer, onCheckedChange = { typeAnswer = it })
                    }
                }

                // Tags Selector & Suggestions
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tags", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    if (tagsList.isNotEmpty()) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tagsList.forEach { tag ->
                                InputChip(
                                    selected = true,
                                    onClick = { tagsList = tagsList - tag },
                                    label = { Text("#$tag", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, "Remover tag $tag", Modifier.size(16.dp))
                                    }
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { updated ->
                                if (updated.endsWith(" ") || updated.endsWith(",") || updated.endsWith("\n")) {
                                    addTagsFromRaw(updated)
                                } else {
                                    tagInput = updated
                                }
                            },
                            label = { Text("Adicionar tag (ex: anatomia, mat/calc)") },
                            singleLine = true,
                            shape = controlShape,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalIconButton(
                            enabled = tagInput.trim().isNotEmpty(),
                            onClick = { addTagsFromRaw(tagInput) }
                        ) {
                            Icon(Icons.Default.Add, "Adicionar tag")
                        }
                    }
                    val suggestions = remember(existingTags, tagsList, tagInput) {
                        val filter = tagInput.trim().removePrefix("#")
                        existingTags
                            .asSequence()
                            .map { it.trim().removePrefix("#") }
                            .filter { it.isNotBlank() && it !in tagsList }
                            .filter { filter.isBlank() || it.contains(filter, ignoreCase = true) }
                            .take(12)
                            .toList()
                    }
                    if (suggestions.isNotEmpty()) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            suggestions.forEach { suggestion ->
                                AssistChip(
                                    onClick = {
                                        tagsList = (tagsList + suggestion).distinct()
                                        tagInput = ""
                                    },
                                    label = { Text("#$suggestion", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }
                }

                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ChoiceMenu("Baralho", decks.find { it.first == deckId }?.second ?: "Sem baralho", decks) { deckId = it }
                    ChoiceMenu("Matéria", subjects.find { it.first == subjectId }?.second ?: "Geral", subjects) { subjectId = it }
                }

                if (source?.format == "REVERSE" || source?.format == "CLOZE" || source?.format.equals("occlusion", ignoreCase = true)) {
                    Text(
                        "O conteúdo é atualizado nos cartões desta nota. Uma variante removida permanece como cartão independente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }

                if (containsMathSyntax(prompt.text) || containsMathSyntax(answer.text)) {
                    Surface(
                        shape = controlShape,
                        color = scheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Prévia instantânea (LaTeX)", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                            if (containsMathSyntax(prompt.text)) {
                                RichInlineMathText(
                                    text = prompt.text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            if (containsMathSyntax(answer.text)) {
                                if (containsMathSyntax(prompt.text)) HorizontalDivider()
                                RichInlineMathText(
                                    text = answer.text,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (variants.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton({ preview = !preview }) {
                            Icon(
                                if (preview) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null,
                                Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (preview) "Ocultar prévia" else "Pré-visualizar (${variants.size})")
                        }
                        if (typeAnswer) {
                            Text(
                                "⌨️ Digitar resposta",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.primary
                            )
                        }
                    }
                    if (preview) {
                        variants.values.forEach { (front, back) ->
                            StudyCard(
                                modifier = Modifier.fillMaxWidth(),
                                containerColorOverride = scheme.surfaceContainerHighest
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(StudyTokens.cardPadding()),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("Frente", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                                    CardContent(front, style = MaterialTheme.typography.titleMedium)
                                    HorizontalDivider()
                                    Text("Verso", style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                                    CardContent(back)
                                }
                            }
                            TextButton({ context.openAnkiCard(front, back) }) {
                                Text(if (imageKey == null) "Enviar ao AnkiDroid" else "Enviar texto ao AnkiDroid")
                            }
                        }
                    } else if (variants.size == 1) {
                        TextButton({
                            val (front, back) = variants.values.first()
                            context.openAnkiCard(front, back)
                        }) {
                            Text(if (imageKey == null) "Criar no AnkiDroid" else "Enviar texto ao AnkiDroid")
                        }
                    }
                }
                problem?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && !preparing && variants.isNotEmpty() && !conflict && (!custom || name.isNotBlank()) && (!isOcclusion || activeBoxes.isNotEmpty()),
                shape = controlShape,
                onClick = {
                    if (tagInput.trim().isNotEmpty()) addTagsFromRaw(tagInput)
                    saving = true
                    scope.launch {
                        try {
                            val image = draftPath?.let { path ->
                                withContext(Dispatchers.IO) {
                                    val draft = File(path)
                                    require(draft.isFile) { "Escolha a imagem novamente." }
                                    CardImage(requireNotNull(sourceDraft.imageKey), draft.readBytes())
                                }
                            }
                            val finalNote = sourceDraft.copy(
                                tags = (tagsList + parseCardTags(tagInput)).distinct().joinToString(" ")
                            )
                            save(card?.id, source, finalNote, deckId.takeIf { it > 0 }, subjectId.takeIf { it > 0 }, image)
                            clearDraft()
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            problem = e.localizedMessage ?: "Não foi possível salvar."
                        } finally {
                            saving = false
                        }
                    }
                }
            ) {
                Text(if (saving) "Salvando…" else if (card == null && variants.size > 1) "Criar ${variants.size} cartões" else "Salvar")
            }
        },
        dismissButton = {
            TextButton(dismiss, enabled = !saving && !preparing) { Text("Cancelar") }
        }
    )

    if (showOcclusionEditor && imageKey != null) {
        val initialBoxes = remember(occlusion) { parseOcclusionBoxes(occlusion) }
        ImageOcclusionEditorDialog(
            initialBoxes = initialBoxes,
            loadCurrentBytes = { loadCurrentImageBytes() },
            onDismiss = { showOcclusionEditor = false },
            onApplyOcclusion = { boxes ->
                occlusion = encodeOcclusionBoxes(boxes)
                format = "occlusion"
                // Never expose box labels or {{cN::...}} spoilers in prompt!
                val current = prompt.text.trim()
                val hasLegacySpoiler = current.contains("{{c") || current.startsWith("🖼️ Identifique as regiões ocultas")
                if (current.isEmpty() || hasLegacySpoiler) {
                    prompt = TextFieldValue("Identifique a região destacada na imagem.")
                }
                showOcclusionEditor = false
            }
        )
    }

    templateEditorTarget?.let { target ->
        CustomTemplateEditorDialog(
            initial = target,
            isNew = isCreatingNewTemplate,
            onDismiss = { templateEditorTarget = null },
            onSave = { updated ->
                val clean = updated.copy(name = updated.name.trim())
                if (onSaveTemplate != null) {
                    onSaveTemplate(clean)
                }
                val newOverrides = localTemplateStore.overrides.filterNot {
                    it.name.equals(clean.name, ignoreCase = true) ||
                        (!isCreatingNewTemplate && it.name.equals(target.name, ignoreCase = true))
                } + clean
                val newHidden = localTemplateStore.hiddenNames - clean.name
                val nextStore = LocalTemplateStoreState(newOverrides, newHidden)
                saveLocalTemplateStore(context, nextStore)
                localTemplateStore = nextStore
                name = clean.name
                prompt = TextFieldValue(clean.front)
                answer = TextFieldValue(clean.back)
                templateEditorTarget = null
            }
        )
    }

    deletingTemplate?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { deletingTemplate = null },
            title = { Text("Excluir modelo \"${toDelete.name}\"?") },
            text = { Text("Cartões já criados com este modelo não serão apagados.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            onDeleteTemplate?.invoke(toDelete)
                            val nextStore = LocalTemplateStoreState(
                                overrides = localTemplateStore.overrides.filterNot { it.name.equals(toDelete.name, ignoreCase = true) },
                                hiddenNames = localTemplateStore.hiddenNames + toDelete.name
                            )
                            saveLocalTemplateStore(context, nextStore)
                            localTemplateStore = nextStore
                            if (name.equals(toDelete.name, ignoreCase = true)) {
                                name = ""
                            }
                            deletingTemplate = null
                        }
                    }
                ) {
                    Text("Excluir", color = scheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingTemplate = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun CustomTemplateEditorDialog(
    initial: CardTemplate,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: suspend (CardTemplate) -> Unit
) {
    val scope = rememberCoroutineScope()
    val controlShape = studyControlShape()
    var tmplName by remember { mutableStateOf(initial.name) }
    var front by remember { mutableStateOf(TextFieldValue(initial.front)) }
    var back by remember { mutableStateOf(TextFieldValue(initial.back)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val valid = tmplName.trim().isNotEmpty() &&
        templateFields(front.text, back.text).isNotEmpty() &&
        front.text.isNotBlank() && back.text.isNotBlank()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.imePadding(),
        title = { Text(if (isNew) "Novo modelo personalizado" else "Editar modelo") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = tmplName,
                    onValueChange = { tmplName = it },
                    label = { Text("Nome do modelo") },
                    singleLine = true,
                    shape = controlShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = front,
                    onValueChange = { front = it },
                    label = { Text("Template da frente") },
                    shape = controlShape,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = back,
                    onValueChange = { back = it },
                    label = { Text("Template do verso") },
                    shape = controlShape,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Campos detectados: ${templateFields(front.text, back.text).joinToString(", ").ifBlank { "Nenhum ({{Campo}})" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("{{Termo}}", "{{Definição}}", "{{#Extra}}{{Extra}}{{/Extra}}", "{{type:Resposta}}").forEach { snippet ->
                        AssistChip(
                            onClick = {
                                val start = back.selection.min.coerceIn(0, back.text.length)
                                val end = back.selection.max.coerceIn(start, back.text.length)
                                back = TextFieldValue(
                                    back.text.replaceRange(start, end, snippet),
                                    TextRange(start + snippet.length)
                                )
                            },
                            label = { Text(snippet, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                enabled = valid && !busy,
                shape = controlShape,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            onSave(CardTemplate(tmplName.trim(), front.text, back.text))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Falha ao salvar modelo."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) {
                Text(if (busy) "Salvando…" else "Salvar modelo")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

private enum class OcclusionHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

private sealed class OcclusionDragGesture {
    data object PanCanvas : OcclusionDragGesture()
    data class DrawNew(val startNorm: Offset) : OcclusionDragGesture()
    data class MoveBox(val boxId: Int, val startNorm: Offset, val origBox: OcclusionBox) : OcclusionDragGesture()
    data class ResizeBox(val boxId: Int, val handle: OcclusionHandle, val origBox: OcclusionBox) : OcclusionDragGesture()
}

@Composable
private fun ImageOcclusionEditorDialog(
    initialBoxes: List<OcclusionBox>,
    loadCurrentBytes: suspend () -> ByteArray?,
    onDismiss: () -> Unit,
    onApplyOcclusion: (List<OcclusionBox>) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = null
        }
    }
    val normalizedInitial = remember(initialBoxes) {
        initialBoxes.mapIndexed { idx, box ->
            if (box.id > 0) box else box.copy(id = idx + 1)
        }
    }
    val boxes = remember { mutableStateListOf<OcclusionBox>().apply { addAll(normalizedInitial) } }
    var hideAll by rememberSaveable { mutableStateOf(normalizedInitial.firstOrNull()?.hideAll ?: true) }
    var selectedBoxId by remember { mutableStateOf<Int?>(normalizedInitial.firstOrNull()?.id) }
    var panMode by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    var draftRectNorm by remember { mutableStateOf<OcclusionBox?>(null) }

    LaunchedEffect(Unit) {
        bitmap = withContext(Dispatchers.IO) {
            loadCurrentBytes()?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while ((bounds.outWidth / sample) > 2048 || (bounds.outHeight / sample) > 2048) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        }
    }

    fun nextMonotonicId(): Int {
        val maxId = boxes.mapIndexed { idx, b -> b.id.takeIf { it > 0 } ?: (idx + 1) }.maxOrNull() ?: 0
        return maxId + 1
    }

    val bmp = bitmap
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalStudySemanticColors.current
    val maskFill = scheme.error
    val maskOnColor = scheme.onError
    val maskOuterStroke = if (contrastRatio(maskFill, scheme.onSurface) >= 2.5) scheme.onSurface else scheme.surface
    val selectedStroke = semantic.info
    val dragFill = semantic.warning.copy(alpha = 0.55f)
    val dragStroke = if (contrastRatio(semantic.warning, scheme.onSurface) >= 2.5) scheme.onSurface else semantic.onWarning
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(12.dp),
            shape = studyCardShape(isHero = true),
            color = scheme.surface,
            contentColor = scheme.onSurface,
            tonalElevation = 6.dp
        ) {
            if (bmp == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize().padding(StudyTokens.cardPadding())) {
                    val compactHeight = maxHeight < 460.dp || isImeVisible
                    val canvasHeight = if (compactHeight) {
                        (maxHeight * 0.30f).coerceIn(110.dp, 160.dp)
                    } else {
                        (maxHeight * 0.42f).coerceIn(190.dp, 340.dp)
                    }

                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(StudyTokens.itemSpacing())) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Oclusão de Imagem",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!compactHeight) {
                                    Text(
                                        "Arraste para criar, toque para selecionar, puxe os cantos para redimensionar ou use 2 dedos para zoom.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (boxes.isNotEmpty()) {
                                TextButton(onClick = { boxes.clear(); selectedBoxId = null }) {
                                    Text("Limpar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        // Mode + HideAll + Zoom toolbar
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilterChip(
                                selected = hideAll,
                                onClick = {
                                    hideAll = true
                                    for (i in boxes.indices) boxes[i] = boxes[i].copy(hideAll = true)
                                },
                                label = { Text("Ocultar todas, adivinhar uma", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                            FilterChip(
                                selected = !hideAll,
                                onClick = {
                                    hideAll = false
                                    for (i in boxes.indices) boxes[i] = boxes[i].copy(hideAll = false)
                                },
                                label = { Text("Ocultar uma, adivinhar uma", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = !panMode,
                                onClick = { panMode = false },
                                leadingIcon = { Icon(Icons.Default.CropFree, null, Modifier.size(16.dp)) },
                                label = { Text("Desenhar / Editar", maxLines = 1) }
                            )
                            FilterChip(
                                selected = panMode,
                                onClick = { panMode = true },
                                leadingIcon = { Icon(Icons.Default.PanTool, null, Modifier.size(16.dp)) },
                                label = { Text("Mover imagem", maxLines = 1) }
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    zoom = (zoom / 1.4f).coerceAtLeast(1f)
                                    if (zoom <= 1.01f) { zoom = 1f; panOffset = Offset.Zero }
                                },
                                enabled = zoom > 1f,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.ZoomOut, "Reduzir zoom", Modifier.size(18.dp))
                            }
                            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                            IconButton(
                                onClick = { zoom = (zoom * 1.4f).coerceAtMost(4f) },
                                enabled = zoom < 4f,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.ZoomIn, "Aumentar zoom", Modifier.size(18.dp))
                            }
                        }

                        // Real ContentScale.Fit Canvas with exact natural aspect ratio bounds + zoom/pan/resize/move
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .height(canvasHeight)
                                .clip(studyCardShape())
                                .background(scheme.surfaceContainerLow)
                                .border(1.dp, scheme.outlineVariant, studyCardShape())
                                .clipToBounds()
                        ) {
                            val cw = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
                            val ch = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
                            val bmpW = bmp.width.toFloat().coerceAtLeast(1f)
                            val bmpH = bmp.height.toFloat().coerceAtLeast(1f)
                            val scaleFit = min(cw / bmpW, ch / bmpH)
                            val fittedW = bmpW * scaleFit
                            val fittedH = bmpH * scaleFit
                            val baseLeft = (cw - fittedW) / 2f
                            val baseTop = (ch - fittedH) / 2f
                            val center = Offset(cw / 2f, ch / 2f)
                            val handleHitPx = with(density) { 22.dp.toPx() }
                            val edgeSnapMargin = with(density) { 14.dp.toPx() }

                            fun screenToNorm(screen: Offset, curZoom: Float, curPan: Offset): Offset {
                                val p0 = (screen - center - curPan) / curZoom + center
                                val nx = ((p0.x - baseLeft) / fittedW).coerceIn(0f, 1f)
                                val ny = ((p0.y - baseTop) / fittedH).coerceIn(0f, 1f)
                                return Offset(nx, ny)
                            }

                            fun isInsideFittedImage(screen: Offset, curZoom: Float, curPan: Offset): Boolean {
                                val p0 = (screen - center - curPan) / curZoom + center
                                return p0.x in (baseLeft - edgeSnapMargin)..(baseLeft + fittedW + edgeSnapMargin) &&
                                    p0.y in (baseTop - edgeSnapMargin)..(baseTop + fittedH + edgeSnapMargin)
                            }

                            fun normToScreen(nx: Float, ny: Float, curZoom: Float, curPan: Offset): Offset {
                                val bx = baseLeft + nx * fittedW
                                val by = baseTop + ny * fittedH
                                return Offset(
                                    (bx - center.x) * curZoom + center.x + curPan.x,
                                    (by - center.y) * curZoom + center.y + curPan.y
                                )
                            }

                            fun clampPan(rawPan: Offset, curZoom: Float): Offset {
                                if (curZoom <= 1.01f) return Offset.Zero
                                val limitX = ((fittedW * curZoom - cw) / 2f).coerceAtLeast(0f) + cw * 0.2f
                                val limitY = ((fittedH * curZoom - ch) / 2f).coerceAtLeast(0f) + ch * 0.2f
                                return Offset(rawPan.x.coerceIn(-limitX, limitX), rawPan.y.coerceIn(-limitY, limitY))
                            }

                            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
                            val labelTypeface = remember { Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
                            val cachedLabelPaint = remember(maskOnColor, labelTypeface) {
                                AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                                    color = maskOnColor.toArgb()
                                    typeface = labelTypeface
                                    textAlign = AndroidPaint.Align.CENTER
                                }
                            }

                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Imagem base para oclusão",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = zoom
                                        scaleY = zoom
                                        translationX = panOffset.x
                                        translationY = panOffset.y
                                    },
                                contentScale = ContentScale.Fit
                            )

                            val latestPanMode by rememberUpdatedState(panMode)
                            val latestSelectedId by rememberUpdatedState(selectedBoxId)

                            Canvas(
                                Modifier
                                    .fillMaxSize()
                                    .pointerInput(bmpW, bmpH, cw, ch) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            val startPos = down.position
                                            val startNorm = screenToNorm(startPos, zoom, panOffset)

                                            var gesture: OcclusionDragGesture? = if (latestPanMode) {
                                                OcclusionDragGesture.PanCanvas
                                            } else {
                                                // 1) Check corner resize handles of selected box first
                                                val selBox = boxes.firstOrNull { it.id == latestSelectedId }
                                                val hitHandle = if (selBox != null) {
                                                    val corners = listOf(
                                                        OcclusionHandle.TOP_LEFT to normToScreen(selBox.left, selBox.top, zoom, panOffset),
                                                        OcclusionHandle.TOP_RIGHT to normToScreen(selBox.right, selBox.top, zoom, panOffset),
                                                        OcclusionHandle.BOTTOM_LEFT to normToScreen(selBox.left, selBox.bottom, zoom, panOffset),
                                                        OcclusionHandle.BOTTOM_RIGHT to normToScreen(selBox.right, selBox.bottom, zoom, panOffset)
                                                    )
                                                    corners.minByOrNull { (_, pt) ->
                                                        hypot(pt.x - startPos.x, pt.y - startPos.y)
                                                    }?.takeIf { (_, pt) ->
                                                        hypot(pt.x - startPos.x, pt.y - startPos.y) <= handleHitPx
                                                    }?.first
                                                } else null

                                                if (selBox != null && hitHandle != null) {
                                                    OcclusionDragGesture.ResizeBox(selBox.id, hitHandle, selBox)
                                                } else {
                                                    // 2) Check hit inside existing box (prioritize selected, else topmost)
                                                    val hitBox = boxes.firstOrNull { b ->
                                                        b.id == latestSelectedId && startNorm.x in b.left..b.right && startNorm.y in b.top..b.bottom
                                                    } ?: boxes.lastOrNull { b ->
                                                        startNorm.x in b.left..b.right && startNorm.y in b.top..b.bottom
                                                    }
                                                    if (hitBox != null) {
                                                        selectedBoxId = hitBox.id
                                                        OcclusionDragGesture.MoveBox(hitBox.id, startNorm, hitBox)
                                                    } else if (isInsideFittedImage(startPos, zoom, panOffset)) {
                                                        OcclusionDragGesture.DrawNew(startNorm)
                                                    } else {
                                                        OcclusionDragGesture.PanCanvas
                                                    }
                                                }
                                            }

                                            var moved = false
                                            var multiTouchUsed = false

                                            while (true) {
                                                val event = awaitPointerEvent()
                                                val pressed = event.changes.filter { it.pressed }
                                                if (pressed.isEmpty()) break

                                                if (pressed.size >= 2) {
                                                    multiTouchUsed = true
                                                    draftRectNorm = null
                                                    val zoomChange = event.calculateZoom()
                                                    val panChange = event.calculatePan()
                                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                                        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
                                                        zoom = nextZoom
                                                        panOffset = clampPan(panOffset + panChange, nextZoom)
                                                    }
                                                    event.changes.forEach { it.consume() }
                                                    continue
                                                }

                                                if (multiTouchUsed) {
                                                    event.changes.forEach { it.consume() }
                                                    continue
                                                }

                                                val change = pressed.first()
                                                val deltaScreen = change.position - change.previousPosition
                                                if (hypot(change.position.x - startPos.x, change.position.y - startPos.y) > 4f) {
                                                    moved = true
                                                }

                                                when (val active = gesture) {
                                                    is OcclusionDragGesture.PanCanvas -> {
                                                        panOffset = clampPan(panOffset + deltaScreen, zoom)
                                                    }
                                                    is OcclusionDragGesture.MoveBox -> {
                                                        val curNorm = screenToNorm(change.position, zoom, panOffset)
                                                        val dx = curNorm.x - active.startNorm.x
                                                        val dy = curNorm.y - active.startNorm.y
                                                        val idx = boxes.indexOfFirst { it.id == active.boxId }
                                                        if (idx >= 0) {
                                                            val w = active.origBox.right - active.origBox.left
                                                            val h = active.origBox.bottom - active.origBox.top
                                                            val nl = (active.origBox.left + dx).coerceIn(0f, (1f - w).coerceAtLeast(0f))
                                                            val nt = (active.origBox.top + dy).coerceIn(0f, (1f - h).coerceAtLeast(0f))
                                                            boxes[idx] = active.origBox.copy(left = nl, top = nt, right = nl + w, bottom = nt + h)
                                                        }
                                                    }
                                                    is OcclusionDragGesture.ResizeBox -> {
                                                        val curNorm = screenToNorm(change.position, zoom, panOffset)
                                                        val idx = boxes.indexOfFirst { it.id == active.boxId }
                                                        if (idx >= 0) {
                                                            val minSpan = 0.025f
                                                            val o = active.origBox
                                                            val updated = when (active.handle) {
                                                                OcclusionHandle.TOP_LEFT -> o.copy(
                                                                    left = curNorm.x.coerceIn(0f, o.right - minSpan),
                                                                    top = curNorm.y.coerceIn(0f, o.bottom - minSpan)
                                                                )
                                                                OcclusionHandle.TOP_RIGHT -> o.copy(
                                                                    right = curNorm.x.coerceIn(o.left + minSpan, 1f),
                                                                    top = curNorm.y.coerceIn(0f, o.bottom - minSpan)
                                                                )
                                                                OcclusionHandle.BOTTOM_LEFT -> o.copy(
                                                                    left = curNorm.x.coerceIn(0f, o.right - minSpan),
                                                                    bottom = curNorm.y.coerceIn(o.top + minSpan, 1f)
                                                                )
                                                                OcclusionHandle.BOTTOM_RIGHT -> o.copy(
                                                                    right = curNorm.x.coerceIn(o.left + minSpan, 1f),
                                                                    bottom = curNorm.y.coerceIn(o.top + minSpan, 1f)
                                                                )
                                                            }
                                                            boxes[idx] = updated
                                                        }
                                                    }
                                                    is OcclusionDragGesture.DrawNew -> {
                                                        val curNorm = screenToNorm(change.position, zoom, panOffset)
                                                        val l = min(active.startNorm.x, curNorm.x)
                                                        val t = min(active.startNorm.y, curNorm.y)
                                                        val r = max(active.startNorm.x, curNorm.x)
                                                        val b = max(active.startNorm.y, curNorm.y)
                                                        draftRectNorm = OcclusionBox(l, t, r, b, label = "", id = 0, hideAll = hideAll)
                                                    }
                                                    null -> Unit
                                                }
                                                change.consume()
                                            }

                                            if (!multiTouchUsed) {
                                                val draft = draftRectNorm
                                                if (gesture is OcclusionDragGesture.DrawNew) {
                                                    if (moved && draft != null && (draft.right - draft.left) >= 0.025f && (draft.bottom - draft.top) >= 0.025f) {
                                                        val nextId = nextMonotonicId()
                                                        boxes.add(
                                                            draft.copy(
                                                                id = nextId,
                                                                label = "Região $nextId",
                                                                hideAll = hideAll
                                                            )
                                                        )
                                                        selectedBoxId = nextId
                                                    } else if (!moved) {
                                                        selectedBoxId = null
                                                    }
                                                }
                                            }
                                            draftRectNorm = null
                                            gesture = null
                                        }
                                    }
                            ) {
                                val baseLabelPx = 13.sp.toPx()
                                val minLabelPx = 10.sp.toPx()
                                val outerStrokePx = 2.dp.toPx()
                                val selectedStrokePx = 2.6.dp.toPx()
                                val handleRadiusPx = 6.dp.toPx()
                                val handleStrokePx = 2.dp.toPx()

                                boxes.forEachIndexed { idx, box ->
                                    val boxId = box.id.takeIf { it > 0 } ?: (idx + 1)
                                    val tl = normToScreen(box.left, box.top, zoom, panOffset)
                                    val br = normToScreen(box.right, box.bottom, zoom, panOffset)
                                    val w = (br.x - tl.x).coerceAtLeast(1f)
                                    val h = (br.y - tl.y).coerceAtLeast(1f)
                                    val isSelected = box.id == selectedBoxId

                                    drawRect(
                                        color = if (isSelected) maskFill.copy(alpha = 0.88f) else maskFill,
                                        topLeft = tl,
                                        size = Size(w, h)
                                    )
                                    drawRect(
                                        color = if (isSelected) selectedStroke else maskOuterStroke,
                                        topLeft = tl,
                                        size = Size(w, h),
                                        style = Stroke(width = if (isSelected) selectedStrokePx else outerStrokePx)
                                    )
                                    cachedLabelPaint.textSize = baseLabelPx.coerceAtMost((h * 0.65f).coerceAtLeast(minLabelPx))
                                    val textY = tl.y + h / 2f - (cachedLabelPaint.descent() + cachedLabelPaint.ascent()) / 2f
                                    drawContext.canvas.nativeCanvas.drawText(
                                        "#$boxId",
                                        tl.x + w / 2f,
                                        textY,
                                        cachedLabelPaint
                                    )

                                    if (isSelected) {
                                        val tr = Offset(br.x, tl.y)
                                        val bl = Offset(tl.x, br.y)
                                        drawCircle(Color.White, radius = handleRadiusPx, center = tl)
                                        drawCircle(selectedStroke, radius = handleRadiusPx, center = tl, style = Stroke(width = handleStrokePx))
                                        drawCircle(Color.White, radius = handleRadiusPx, center = tr)
                                        drawCircle(selectedStroke, radius = handleRadiusPx, center = tr, style = Stroke(width = handleStrokePx))
                                        drawCircle(Color.White, radius = handleRadiusPx, center = bl)
                                        drawCircle(selectedStroke, radius = handleRadiusPx, center = bl, style = Stroke(width = handleStrokePx))
                                        drawCircle(Color.White, radius = handleRadiusPx, center = br)
                                        drawCircle(selectedStroke, radius = handleRadiusPx, center = br, style = Stroke(width = handleStrokePx))
                                    }
                                }

                                draftRectNorm?.let { draft ->
                                    val tl = normToScreen(draft.left, draft.top, zoom, panOffset)
                                    val br = normToScreen(draft.right, draft.bottom, zoom, panOffset)
                                    val rw = (br.x - tl.x).coerceAtLeast(1f)
                                    val bh = (br.y - tl.y).coerceAtLeast(1f)
                                    drawRect(color = dragFill, topLeft = tl, size = Size(rw, bh))
                                    drawRect(color = dragStroke, topLeft = tl, size = Size(rw, bh), style = Stroke(width = outerStrokePx))
                                }
                            }
                        }

                        Text(
                            "Regiões ocultadas (${boxes.size}) · toque para destacar ou editar resposta",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (boxes.isEmpty()) {
                                Text(
                                    "Nenhuma caixa desenhada ainda. Arraste sobre a imagem acima para criar a caixa #1.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant
                                )
                            }
                            boxes.forEachIndexed { idx, box ->
                                val boxId = box.id.takeIf { it > 0 } ?: (idx + 1)
                                val isSelected = box.id == selectedBoxId
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(studyControlShape())
                                        .background(if (isSelected) scheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
                                        .clickable { selectedBoxId = box.id }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = studyControlShape(),
                                        color = if (isSelected) selectedStroke else maskFill,
                                        contentColor = maskOnColor
                                    ) {
                                        Text(
                                            "#$boxId",
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                    OutlinedTextField(
                                        value = box.label,
                                        onValueChange = { updated ->
                                            selectedBoxId = box.id
                                            boxes[idx] = box.copy(label = updated)
                                        },
                                        label = { Text("Resposta oculta em #$boxId", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        singleLine = true,
                                        shape = studyControlShape(),
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        val removedId = box.id
                                        boxes.removeAt(idx)
                                        if (selectedBoxId == removedId) {
                                            selectedBoxId = boxes.firstOrNull()?.id
                                        }
                                    }) {
                                        Icon(Icons.Default.DeleteOutline, "Remover caixa #$boxId")
                                    }
                                }
                            }
                        }

                        HorizontalDivider()
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Cancelar", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Button(
                                enabled = boxes.isNotEmpty(),
                                onClick = {
                                    val finalized = boxes.mapIndexed { idx, b ->
                                        val stableId = b.id.takeIf { it > 0 } ?: (idx + 1)
                                        b.copy(id = stableId, hideAll = hideAll)
                                    }
                                    onApplyOcclusion(finalized)
                                },
                                shape = studyControlShape()
                            ) {
                                Text(
                                    "Aplicar Oclusão (${boxes.size} ${if (boxes.size == 1) "cartão" else "cartões"})",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Renders occlusion masks onto [sourceBytes] in memory without altering stored image bytes.
 * When [activeIndex] identifies a valid box, that target box is highlighted in red while other
 * boxes remain neutral gray when [hideAll] is true (or hidden completely when [hideAll] is false).
 */
internal fun renderOccludedBytes(
    sourceBytes: ByteArray,
    boxes: List<OcclusionBox>,
    activeIndex: Int? = null,
    hideAll: Boolean = true
): ByteArray {
    if (boxes.isEmpty()) return sourceBytes
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return sourceBytes
    var sample = 1
    while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
        sample *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, decodeOpts) ?: return sourceBytes
    var working: Bitmap? = null
    try {
        working = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: return sourceBytes
        val canvas = AndroidCanvas(working)
        val w = working.width.toFloat()
        val h = working.height.toFloat()

        val activeFillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(225, 29, 72)
            style = AndroidPaint.Style.FILL
        }
        val secondaryFillPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(71, 85, 105)
            style = AndroidPaint.Style.FILL
        }
        val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = AndroidPaint.Style.STROKE
            strokeWidth = max(2f, min(w, h) * 0.004f)
        }
        val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = AndroidPaint.Align.CENTER
        }

        val hasValidActive = activeIndex != null && activeIndex in boxes.indices
        val effectiveHideAll = hideAll && (boxes.firstOrNull()?.hideAll != false)
        boxes.forEachIndexed { idx, box ->
            if (hasValidActive && !effectiveHideAll && idx != activeIndex) return@forEachIndexed
            val boxId = box.id.takeIf { it > 0 } ?: (idx + 1)
            val rect = RectF(box.left * w, box.top * h, box.right * w, box.bottom * h)
            val fill = if (!hasValidActive || idx == activeIndex) activeFillPaint else secondaryFillPaint
            canvas.drawRoundRect(rect, 6f, 6f, fill)
            canvas.drawRoundRect(rect, 6f, 6f, strokePaint)
            textPaint.textSize = (rect.height() * 0.55f).coerceIn(18f, 44f)
            val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("#$boxId", rect.centerX(), textY, textPaint)
        }

        val out = ByteArrayOutputStream()
        working.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    } finally {
        working?.recycle()
        decoded.recycle()
    }
}

@Composable
private fun <T> ChoiceMenu(label: String, selected: String, items: List<Pair<T, String>>, onChoose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, shape = studyControlShape()) {
            Text("$label · $selected", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, { expanded = false }) {
            items.forEach { (key, title) ->
                DropdownMenuItem(
                    text = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onChoose(key) }
                )
            }
        }
    }
}

@Composable
private fun FormatControls(
    value: TextFieldValue,
    change: (TextFieldValue) -> Unit,
    focus: FocusRequester,
    showClozeButton: Boolean = false,
    onClozeInserted: () -> Unit = {}
) {
    val keyboard = LocalSoftwareKeyboardController.current
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf("Negrito" to "**", "Itálico" to "*", "Código" to "`").forEach { (label, marker) ->
            TextButton(
                onClick = {
                    val start = value.selection.min
                    val end = value.selection.max
                    val selected = value.text.substring(start, end)
                    val formatted = marker + selected + marker
                    change(
                        TextFieldValue(
                            value.text.replaceRange(start, end, formatted),
                            TextRange(start + marker.length, end + marker.length)
                        )
                    )
                    focus.requestFocus()
                    keyboard?.show()
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (showClozeButton) {
            TextButton(
                onClick = {
                    val start = value.selection.min
                    val end = value.selection.max
                    val selected = value.text.substring(start, end)
                    val used = clozeNumbers(value.text).toSet()
                    val number = generateSequence(1) { it + 1 }.first { it.toString() !in used }
                    val prefix = "{{c$number::"
                    val formatted = "$prefix$selected}}"
                    val cursor = if (selected.isEmpty()) start + prefix.length else start + formatted.length
                    change(
                        TextFieldValue(
                            value.text.replaceRange(start, end, formatted),
                            TextRange(cursor)
                        )
                    )
                    onClozeInserted()
                    focus.requestFocus()
                    keyboard?.show()
                },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text("[...] Cloze", style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(
            onClick = {
                val start = value.selection.min
                val end = value.selection.max
                val selected = value.text.substring(start, end)
                val formatted = when {
                    selected.contains('\n') -> "\$\$\n$selected\n\$\$"
                    selected.isNotEmpty() -> "\$$selected\$"
                    else -> "\$\$  \$\$"
                }
                val cursor = if (selected.isEmpty()) TextRange(start + 3) else TextRange(start + formatted.length)
                change(TextFieldValue(value.text.replaceRange(start, end, formatted), cursor))
                focus.requestFocus()
                keyboard?.show()
            },
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text("\$\$ LaTeX \$\$", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CardEditorDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                Modifier.widthIn(max = 840.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = studyCardShape(isHero = true),
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(StudyTokens.cardPadding()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { text() }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        dismissButton()
                        Spacer(Modifier.width(8.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}

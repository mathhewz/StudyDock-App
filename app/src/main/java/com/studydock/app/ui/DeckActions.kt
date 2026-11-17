package com.studydock.app.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.studydock.app.data.CardDeck
import com.studydock.app.data.DeckFile
import com.studydock.app.data.StudyDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private data class CsvPendingImport(
    val defaultDeckName: String,
    val content: String,
    val lineCount: Int
)

private data class ApkgImportReport(
    val fileName: String,
    val importedDeckId: Long,
    val addedCardsEstimate: Int,
    val warnings: List<String>
)

@Composable
internal fun DeckActions(
    deck: CardDeck?,
    exportDeck: suspend (Long, String) -> DeckFile,
    importDeck: suspend (DeckFile) -> Long,
    selectDeck: (Long) -> Unit,
    importApkg: (suspend (Uri) -> Pair<Long, List<String>>)? = null,
    importCsv: (suspend (String, String) -> Long)? = null,
    exportCsv: (suspend (Long, String) -> String)? = null,
    cardsCount: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var showExternalMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var csvPending by remember { mutableStateOf<CsvPendingImport?>(null) }
    var csvDeckName by remember { mutableStateOf("") }
    var apkgReport by remember { mutableStateOf<ApkgImportReport?>(null) }
    val currentCardsCount by rememberUpdatedState(cardsCount)

    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.localizedMessage ?: "Não foi possível concluir a operação.",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                busy = false
            }
        }
    }

    fun resolveDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) else null
            } else null
        }
    }.getOrNull()

    val apkgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val importer = importApkg ?: return@rememberLauncherForActivityResult
        if (uri != null) perform {
            val beforeCount = withContext(Dispatchers.IO) {
                runCatching { StudyDatabase.get(context).studyDao().listFlashcardsForQueryValidation().size }
                    .getOrDefault(currentCardsCount)
            }
            val displayName = withContext(Dispatchers.IO) { resolveDisplayName(uri) } ?: "pacote.apkg"
            val (importedDeckId, warnings) = importer(uri)
            val afterCount = withContext(Dispatchers.IO) {
                runCatching { StudyDatabase.get(context).studyDao().listFlashcardsForQueryValidation().size }
                    .getOrDefault(currentCardsCount)
            }
            if (importedDeckId > 0L) selectDeck(importedDeckId)
            val delta = (afterCount - beforeCount).coerceAtLeast((currentCardsCount - beforeCount).coerceAtLeast(0))
            if (warnings.isNotEmpty()) {
                apkgReport = ApkgImportReport(
                    fileName = displayName,
                    importedDeckId = importedDeckId,
                    addedCardsEstimate = delta,
                    warnings = warnings
                )
            } else {
                val msg = if (delta > 0) {
                    "Pacote .apkg importado ($delta ${if (delta == 1) "cartão" else "cartões"})."
                } else {
                    "Pacote .apkg importado com sucesso."
                }
                Toast.makeText(
                    context,
                    msg,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && importCsv != null) perform {
            val (name, text, lines) = withContext(Dispatchers.IO) {
                val displayName = resolveDisplayName(uri).orEmpty()
                val baseName = displayName
                    .substringBeforeLast('.')
                    .trim()
                    .ifBlank { "Baralho CSV" }
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                    "Não foi possível abrir o arquivo selecionado."
                }
                val sb = StringBuilder()
                val buffer = CharArray(8192)
                input.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        require(sb.length + read <= 16 * 1024 * 1024) { "Planilha grande demais para importar." }
                        sb.append(buffer, 0, read)
                    }
                }
                val content = sb.toString()
                val nonBlankLines = content.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
                Triple(baseName, content, nonBlankLines)
            }
            csvDeckName = name
            csvPending = CsvPendingImport(name, text, lines)
        }
    }

    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val file = withContext(Dispatchers.IO) {
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                    "Não foi possível abrir o arquivo selecionado."
                }
                input.bufferedReader(Charsets.UTF_8).use(DeckFile::read)
            }
            selectDeck(importDeck(file))
            Toast.makeText(context, "Baralho importado: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    val ankiDroidPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.openAnkiPackage(uri)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            IconButton(
                onClick = {
                    showExternalMenu = false
                    menu = true
                },
                enabled = !busy
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.ImportExport, "Importar ou exportar baralhos")
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Importar pacote Anki (.apkg)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.FolderZip, null, Modifier.size(20.dp)) },
                    enabled = importApkg != null,
                    onClick = {
                        menu = false
                        apkgPicker.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Importar planilha (.csv / .tsv)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.TableChart, null, Modifier.size(20.dp)) },
                    enabled = importCsv != null,
                    onClick = {
                        menu = false
                        csvPicker.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text("Importar baralho JSON (.json)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.UploadFile, null, Modifier.size(20.dp)) },
                    onClick = {
                        menu = false
                        jsonPicker.launch(arrayOf("*/*"))
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("Exportar .csv / .tsv", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (deck != null) {
                                Text(
                                    "${deck.name} · $cardsCount ${if (cardsCount == 1) "cartão" else "cartões"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.Download, null, Modifier.size(20.dp)) },
                    enabled = deck != null && exportCsv != null,
                    onClick = {
                        menu = false
                        val exporter = exportCsv ?: return@DropdownMenuItem
                        if (deck != null) perform {
                            val uri = withContext(Dispatchers.IO) {
                                val csvData = exporter(deck.id, deck.name)
                                val sharedRoot = File(context.cacheDir, "shared_decks").apply { mkdirs() }
                                val staleCutoff = System.currentTimeMillis() - 86_400_000L
                                sharedRoot.listFiles()?.forEach { oldDir ->
                                    if (oldDir.lastModified() < staleCutoff) oldDir.deleteRecursively()
                                }
                                val folder = File(sharedRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                                val file = File(folder, "baralho.csv")
                                file.writeText(csvData, Charsets.UTF_8)
                                FileProvider.getUriForFile(context, "${context.packageName}.decks", file)
                            }
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/csv"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, "${deck.name}.csv")
                                clipData = ClipData.newRawUri(deck.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Exportar ${deck.name} (.csv)"))
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text("Exportar JSON completo (mídia e oclusão)", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Share, null, Modifier.size(20.dp)) },
                    enabled = deck != null,
                    onClick = {
                        menu = false
                        if (deck != null) perform {
                            val uri = withContext(Dispatchers.IO) {
                                val data = exportDeck(deck.id, deck.name).encode()
                                val sharedRoot = File(context.cacheDir, "shared_decks").apply { mkdirs() }
                                val staleCutoff = System.currentTimeMillis() - 86_400_000L
                                sharedRoot.listFiles()?.forEach { oldDir ->
                                    if (oldDir.lastModified() < staleCutoff) oldDir.deleteRecursively()
                                }
                                val folder = File(sharedRoot, UUID.randomUUID().toString()).apply { mkdirs() }
                                val file = File(folder, "baralho.json")
                                file.writeText(data, Charsets.UTF_8)
                                FileProvider.getUriForFile(context, "${context.packageName}.decks", file)
                            }
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri(deck.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Compartilhar ${deck.name} (.json)"))
                        }
                    }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(
                            "AnkiDroid & AnkiWeb",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        Icon(
                            if (showExternalMenu) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            Modifier.size(18.dp)
                        )
                    },
                    onClick = { showExternalMenu = !showExternalMenu }
                )
                if (showExternalMenu) {
                    DropdownMenuItem(
                        text = { Text("  Abrir AnkiDroid") },
                        onClick = { menu = false; context.openAnkiDecks() }
                    )
                    DropdownMenuItem(
                        text = { Text("  Abrir .apkg no AnkiDroid") },
                        onClick = { menu = false; ankiDroidPicker.launch(arrayOf("*/*")) }
                    )
                    DropdownMenuItem(
                        text = { Text("  Explorar baralhos no AnkiWeb") },
                        onClick = {
                            menu = false
                            perform {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ankiweb.net/shared/decks")))
                            }
                        }
                    )
                }
            }
        }
    }

    csvPending?.let { pending ->
        val validName = csvDeckName.trim().isNotEmpty()
        AlertDialog(
            onDismissRequest = { if (!busy) csvPending = null },
            modifier = Modifier.imePadding(),
            title = { Text("Importar planilha CSV / TSV") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "${pending.lineCount} linha(s) de dados encontradas. Separadores TAB, ponto e vírgula ou vírgula são detectados automaticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = csvDeckName,
                        onValueChange = { csvDeckName = it },
                        label = { Text("Nome do baralho de destino") },
                        singleLine = true,
                        shape = studyControlShape(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = validName && !busy && importCsv != null,
                    onClick = {
                        val importer = importCsv ?: return@Button
                        val name = csvDeckName.trim()
                        val content = pending.content
                        csvPending = null
                        perform {
                            val newDeckId = importer(name, content)
                            if (newDeckId > 0L) selectDeck(newDeckId)
                            Toast.makeText(context, "Planilha importada para \"$name\".", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = studyControlShape()
                ) {
                    Text("Importar")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { csvPending = null }) { Text("Cancelar") }
            }
        )
    }

    apkgReport?.let { report ->
        AlertDialog(
            onDismissRequest = { apkgReport = null },
            title = { Text("Importação .apkg concluída", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Arquivo: ${report.fileName}", style = MaterialTheme.typography.bodyMedium)
                    if (report.addedCardsEstimate > 0) {
                        Text(
                            "${report.addedCardsEstimate} ${if (report.addedCardsEstimate == 1) "cartão importado" else "cartões importados"}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (report.warnings.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            "Avisos da importação:",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        report.warnings.forEach { warn ->
                            Text("• $warn", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { apkgReport = null }) { Text("OK") }
            }
        )
    }
}

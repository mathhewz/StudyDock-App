package com.studydock.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

private const val ANKI_PACKAGE = "com.ichi2.anki"

internal fun Context.openAnkiCard(front: String, back: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        setPackage(ANKI_PACKAGE)
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, front.trim())
        putExtra(Intent.EXTRA_TEXT, back.trim())
    }
    launchAnki(intent)
}

internal fun Context.openAnkiPackage(uri: android.net.Uri) {
    launchAnki(Intent(Intent.ACTION_VIEW).apply {
        setPackage(ANKI_PACKAGE)
        setDataAndType(uri, "application/apkg")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })
}

internal fun Context.openAnkiDecks() {
    launchAnki(packageManager.getLaunchIntentForPackage(ANKI_PACKAGE))
}

private fun Context.launchAnki(intent: Intent?) {
    try {
        if (intent == null) throw ActivityNotFoundException()
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Instale ou ative o AnkiDroid para abrir sua coleção.", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(this, "O Android bloqueou a abertura do AnkiDroid.", Toast.LENGTH_LONG).show()
    }
}

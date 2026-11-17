package com.studydock.app.data

import android.util.Log

/**
 * Lightweight, JVM-test-safe logging helper for StudyDock.
 *
 * Security rule: never pass API keys, bearer tokens, passwords, or private
 * note/journal contents into log messages.
 */
object StudyLog {
    private const val TAG_PREFIX = "StudyDock:"

    private enum class Level { DEBUG, INFO, WARN, ERROR }

    fun d(tag: String, message: String, throwable: Throwable? = null) =
        emit(Level.DEBUG, tag, message, throwable)

    fun i(tag: String, message: String, throwable: Throwable? = null) =
        emit(Level.INFO, tag, message, throwable)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        emit(Level.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        emit(Level.ERROR, tag, message, throwable)

    private fun emit(level: Level, tag: String, message: String, throwable: Throwable?) {
        val formattedTag = formatTag(tag)
        runCatching {
            when (level) {
                Level.DEBUG -> if (throwable != null) Log.d(formattedTag, message, throwable) else Log.d(formattedTag, message)
                Level.INFO -> if (throwable != null) Log.i(formattedTag, message, throwable) else Log.i(formattedTag, message)
                Level.WARN -> if (throwable != null) Log.w(formattedTag, message, throwable) else Log.w(formattedTag, message)
                Level.ERROR -> if (throwable != null) Log.e(formattedTag, message, throwable) else Log.e(formattedTag, message)
            }
        }
    }

    private fun formatTag(tag: String): String {
        val clean = tag.trim().ifEmpty { "App" }
        return if (clean.startsWith(TAG_PREFIX)) clean else "$TAG_PREFIX$clean"
    }
}

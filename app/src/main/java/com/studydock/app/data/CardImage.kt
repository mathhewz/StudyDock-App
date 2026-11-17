package com.studydock.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

@Entity(tableName = "card_images")
data class CardImage(@PrimaryKey val key: String, val bytes: ByteArray)

internal object CardPictures {
    const val MAX_BYTES = 384 * 1024
    const val MAX_EDGE = 1280

    fun key(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun validate(image: CardImage) {
        require(image.bytes.size in 1..MAX_BYTES && key(image.bytes) == image.key) { "Imagem do cartão inválida." }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        require(options.outWidth in 1..MAX_EDGE && options.outHeight in 1..MAX_EDGE &&
            options.outMimeType in setOf("image/webp", "image/png", "image/jpeg")) { "Formato de imagem inválido." }
        requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)) { "Imagem ilegível." }.recycle()
    }

    fun fromDocument(resolver: ContentResolver, uri: Uri): CardImage {
        var bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val (width, height) = scaledSize(info.size.width, info.size.height, MAX_EDGE)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(width, height)
            }
        } else {
            fun stream() = requireNotNull(resolver.openInputStream(uri)) { "Imagem indisponível." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            stream().use { BitmapFactory.decodeStream(it, null, bounds) }
            val (width, height) = scaledSize(bounds.outWidth, bounds.outHeight, MAX_EDGE)
            val options = BitmapFactory.Options().apply {
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 2 * MAX_EDGE)
                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
            }
            val decoded = requireNotNull(stream().use { BitmapFactory.decodeStream(it, null, options) }) { "Imagem ilegível." }
            val orientation = stream().use { input ->
                runCatching { ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            }
            val matrix = Matrix().apply {
                // EXIF 2, 4, 5 and 7 are mirrored; rotation is applied after the flip.
                if (orientation in setOf(2, 4, 5, 7)) postScale(-1f, 1f)
                postRotate(when (orientation) { 3, 4 -> 180f; 5, 8 -> 270f; 6, 7 -> 90f; else -> 0f })
            }
            val small = Bitmap.createScaledBitmap(decoded, width, height, true)
            if (small !== decoded) decoded.recycle()
            Bitmap.createBitmap(small, 0, 0, width, height, matrix, true).also { if (it !== small) small.recycle() }
        }
        try {
            while (true) {
                val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                for (quality in intArrayOf(85, 68, 50, 35)) {
                    val output = ByteArrayOutputStream()
                    check(bitmap.compress(format, quality, output)) { "Falha ao preparar a imagem." }
                    if (output.size() <= MAX_BYTES) {
                        val bytes = output.toByteArray()
                        return CardImage(key(bytes), bytes)
                    }
                }
                val (width, height) = scaledSize(bitmap.width, bitmap.height, maxOf(bitmap.width, bitmap.height) * 3 / 4)
                check(width < bitmap.width || height < bitmap.height) { "Imagem grande demais." }
                val smaller = Bitmap.createScaledBitmap(bitmap, width, height, true)
                bitmap.recycle()
                bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }

    private fun scaledSize(width: Int, height: Int, edge: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Dimensões de imagem inválidas." }
        val scale = minOf(1.0, edge.toDouble() / maxOf(width, height))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }
}

internal val CARD_IMAGE_MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE card_images (`key` TEXT NOT NULL PRIMARY KEY, bytes BLOB NOT NULL)")
        db.execSQL("ALTER TABLE card_author_notes ADD COLUMN imageKey TEXT REFERENCES card_images(`key`)")
        db.execSQL("CREATE INDEX index_card_author_notes_imageKey ON card_author_notes(imageKey)")
    }
}

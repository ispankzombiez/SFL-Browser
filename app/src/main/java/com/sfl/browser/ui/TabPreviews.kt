package com.sfl.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.AtomicFile
import android.util.Log
import android.webkit.WebView
import com.sfl.browser.data.HomeTab
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt

internal object TabPreviews {
    fun save(view: WebView, tab: HomeTab) {
        if (view.width <= 0 || view.height <= 0 || view.url.isNullOrBlank() ||
            view.url == "about:blank" || view.progress < 100) return
        val scale = minOf(240f / view.width, 240f / view.height)
        val bitmap = Bitmap.createBitmap(
            (view.width * scale).roundToInt().coerceAtLeast(1),
            (view.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        )
        try {
            view.draw(Canvas(bitmap).apply { scale(scale, scale) })
            val file = file(view.context, tab)
            val output = file.startWrite()
            try {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
                    throw IOException("Could not encode tab preview")
                }
                file.finishWrite(output)
            } catch (error: IOException) {
                file.failWrite(output)
                throw error
            }
        } catch (error: IOException) {
            Log.w("TabPreviews", "Unable to save tab preview", error)
        } finally {
            bitmap.recycle()
        }
    }

    fun load(context: Context, tab: HomeTab): Bitmap? =
        try {
            file(context, tab).openRead().use { BitmapFactory.decodeStream(it) }
        } catch (_: FileNotFoundException) {
            null // Not every configured tab has been opened.
        } catch (error: IOException) {
            Log.w("TabPreviews", "Unable to read tab preview", error)
            null
        }

    fun remove(context: Context, tab: HomeTab) = file(context, tab).delete()

    private fun file(context: Context, tab: HomeTab): AtomicFile {
        val key = UUID.nameUUIDFromBytes("${tab.id}\u0000${tab.url}".toByteArray(Charsets.UTF_8))
        return AtomicFile(File(context.cacheDir, "tab-preview-$key.jpg"))
    }
}

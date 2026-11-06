package com.pgratz.multiviewpdf.pdf

import android.content.Context
import com.pgratz.multiviewpdf.model.AppPrefs
import com.pgratz.multiviewpdf.model.FigureDescriptions
import com.pgratz.multiviewpdf.model.ViewLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** Small JSON files under filesDir: app settings plus one view-layout file per document. */
class PrefsStore(context: Context) {
    private val dir = File(context.filesDir, "prefs").apply { mkdirs() }
    private val docDir = File(dir, "docs").apply { mkdirs() }
    private val figDir = File(dir, "figures").apply { mkdirs() }
    private val appFile = File(dir, "app.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun loadApp(): AppPrefs = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<AppPrefs>(appFile.readText()) }.getOrDefault(AppPrefs())
    }

    suspend fun saveApp(p: AppPrefs) = withContext(Dispatchers.IO) {
        writeAtomically(appFile, json.encodeToString(AppPrefs.serializer(), p))
    }

    suspend fun loadLayout(fingerprint: String): ViewLayout? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<ViewLayout>(File(docDir, "$fingerprint.json").readText()) }
            .getOrNull()
    }

    suspend fun saveLayout(fingerprint: String, layout: ViewLayout) = withContext(Dispatchers.IO) {
        writeAtomically(File(docDir, "$fingerprint.json"), json.encodeToString(ViewLayout.serializer(), layout))
    }

    /** Cached figure descriptions for one document (generated once, kept forever). */
    suspend fun loadFigureDescriptions(fingerprint: String): FigureDescriptions = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<FigureDescriptions>(File(figDir, "$fingerprint.json").readText()) }
            .getOrDefault(FigureDescriptions())
    }

    suspend fun saveFigureDescriptions(fingerprint: String, d: FigureDescriptions) = withContext(Dispatchers.IO) {
        writeAtomically(File(figDir, "$fingerprint.json"), json.encodeToString(FigureDescriptions.serializer(), d))
    }

    suspend fun deleteFigureDescriptions(fingerprint: String) = withContext(Dispatchers.IO) {
        File(figDir, "$fingerprint.json").delete()
    }

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }
}

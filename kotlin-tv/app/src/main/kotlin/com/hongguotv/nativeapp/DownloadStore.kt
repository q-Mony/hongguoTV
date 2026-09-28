// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class DownloadedEpisode(
    val series: Series,
    val episodeId: String,
    val episodeIndex: Int,
    val file: File,
    val quality: String,
    val savedAt: Long,
    val size: Long
) {
    val cardId get() = "download:${series.id}:$episodeIndex"
}

class DownloadStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("native-downloads-v1", Context.MODE_PRIVATE)
    val directory: File = File(app.filesDir, "downloads-v1").apply { mkdirs() }

    @Synchronized fun all(): List<DownloadedEpisode> = decode()
        .filter { it.file.exists() && it.file.length() > 0 }
        .sortedWith(compareByDescending<DownloadedEpisode> { it.savedAt }.thenBy { it.episodeIndex })

    @Synchronized fun series(): List<Series> = all().groupBy { it.series.id }.values.map { rows ->
        val first = rows.maxBy { it.savedAt }
        first.series.copy(badge = "已下载 ${rows.size} 集")
    }

    @Synchronized fun episodes(seriesId: String): List<DownloadedEpisode> =
        all().filter { it.series.id == seriesId }.sortedBy { it.episodeIndex }

    @Synchronized fun find(seriesId: String, episodeId: String): DownloadedEpisode? =
        all().firstOrNull { it.series.id == seriesId && it.episodeId == episodeId }

    @Synchronized fun byCardId(cardId: String): DownloadedEpisode? =
        all().firstOrNull { it.cardId == cardId }

    @Synchronized fun put(item: DownloadedEpisode) {
        val rows = all().filterNot { it.series.id == item.series.id && it.episodeId == item.episodeId } + item
        save(rows)
    }

    fun targetFile(seriesId: String, episodeId: String): File {
        val dir = File(directory, seriesId).apply { mkdirs() }
        return File(dir, "$episodeId.mp4")
    }

    private fun decode(): List<DownloadedEpisode> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val file = File(row.optString("file"))
            DownloadedEpisode(
                Series.fromJson(row.getJSONObject("series")),
                row.optString("episodeId"),
                row.optInt("episodeIndex"),
                file,
                row.optString("quality"),
                row.optLong("savedAt"),
                row.optLong("size")
            )
        }
    }.getOrDefault(emptyList())

    private fun save(rows: List<DownloadedEpisode>) {
        val array = JSONArray()
        rows.forEach { item ->
            array.put(JSONObject()
                .put("series", item.series.toJson())
                .put("episodeId", item.episodeId)
                .put("episodeIndex", item.episodeIndex)
                .put("file", item.file.absolutePath)
                .put("quality", item.quality)
                .put("savedAt", item.savedAt)
                .put("size", item.size))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}

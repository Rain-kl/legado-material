package io.legado.app.utils.moderation.cache

import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import com.google.gson.JsonParser

data class TocModerationCacheItem(
    val chapterIndex: Int,
    val chapterTitle: String,
    val score: Double,
    val flaggedLinesCount: Int
)

data class TocModerationCachePayload(
    val checkedChapters: Int = 0,
    val skippedChapters: Int = 0,
    val flaggedItems: List<TocModerationCacheItem>? = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

object TocModerationCacheStore {
    private const val CACHE_NAME = "toc_moderation_cache"

    private val cache by lazy {
        ACache.get(
            cacheName = CACHE_NAME,
            cacheDir = false
        )
    }

    fun buildCacheKey(bookName: String, author: String): String {
        return MD5Utils.md5Encode("$bookName#$author")
    }

    fun get(bookName: String, author: String): TocModerationCachePayload? {
        val key = buildCacheKey(bookName, author)
        val raw = cache.getAsString(key) ?: return null
        return parsePayload(raw)
    }

    fun put(bookName: String, author: String, payload: TocModerationCachePayload) {
        val key = buildCacheKey(bookName, author)
        cache.put(key, GSON.toJson(payload))
    }

    fun remove(bookName: String, author: String) {
        val key = buildCacheKey(bookName, author)
        cache.remove(key)
    }

    private fun parsePayload(raw: String): TocModerationCachePayload? {
        return kotlin.runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            val checkedChapters = root.get("checkedChapters")?.asInt ?: 0
            val skippedChapters = root.get("skippedChapters")?.asInt ?: 0
            val updatedAt = root.get("updatedAt")?.asLong ?: System.currentTimeMillis()
            val flaggedItems = root.getAsJsonArray("flaggedItems")?.mapNotNull { item ->
                kotlin.runCatching {
                    val obj = item.asJsonObject
                    TocModerationCacheItem(
                        chapterIndex = obj.get("chapterIndex")?.asInt ?: return@runCatching null,
                        chapterTitle = obj.get("chapterTitle")?.asString.orEmpty(),
                        score = obj.get("score")?.asDouble ?: 0.0,
                        flaggedLinesCount = obj.get("flaggedLinesCount")?.asInt ?: 0
                    )
                }.getOrNull()
            }.orEmpty()
            TocModerationCachePayload(
                checkedChapters = checkedChapters,
                skippedChapters = skippedChapters,
                flaggedItems = flaggedItems,
                updatedAt = updatedAt
            )
        }.getOrNull()
    }
}

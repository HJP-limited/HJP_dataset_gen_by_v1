package com.example.hjp.data

import android.content.Context
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardStore
import com.hjp.tool.contact.BusinessCardJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AssetBusinessCardRepository(
    context: Context,
    private val assetPath: String = "cards/business_cards.json",
    json: Json = Json { ignoreUnknownKeys = true },
) : BusinessCardStore {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile private var cached: List<BusinessCardRecord>? = null
    private val codec = BusinessCardJsonCodec(json)

    override suspend fun loadAll(): List<BusinessCardRecord> {
        cached?.let { return it }
        return mutex.withLock {
            cached?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                appContext.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { reader ->
                    codec.decode(reader.readText())
                }
            }.also { cached = it }
        }
    }

    override suspend fun getById(cardId: String): BusinessCardRecord? =
        loadAll().firstOrNull { it.id == cardId.trim() }
}

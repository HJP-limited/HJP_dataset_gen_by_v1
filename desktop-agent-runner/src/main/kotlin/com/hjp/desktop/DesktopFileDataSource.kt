package com.hjp.desktop

import com.hjp.tool.contact.BusinessCardJsonCodec
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardUpdateResult
import com.hjp.tool.contact.MutableBusinessCardStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads the app's real JSON asset; updates are copy-on-write in memory and never alter the asset. */
class DesktopFileDataSource(
    private val file: File,
    private val codec: BusinessCardJsonCodec = BusinessCardJsonCodec(),
) : MutableBusinessCardStore {
    private val mutex = Mutex()
    @Volatile private var cards: LinkedHashMap<String, BusinessCardRecord>? = null
    @Volatile private var dataRevision = 0L

    override suspend fun revision(): Long = dataRevision

    override suspend fun loadAll(): List<BusinessCardRecord> = requireCards().values.toList()

    override suspend fun getById(cardId: String): BusinessCardRecord? = requireCards()[cardId.trim()]

    override suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult? = mutex.withLock {
        val active = cards ?: loadCards().also { cards = it }
        val before = active[cardId.trim()] ?: return@withLock null
        fun value(field: String, current: String): String = when {
            field in updates -> updates.getValue(field)
            field in clearFields -> ""
            else -> current
        }
        val after = before.copy(
            name = value("name", before.name), nameEn = value("name_en", before.nameEn),
            company = value("company", before.company), title = value("title", before.title),
            department = value("department", before.department), industry = value("industry", before.industry),
            location = value("location", before.location), phone = value("phone", before.phone),
            mobile = value("mobile", before.mobile), email = value("email", before.email),
            address = value("address", before.address), website = value("website", before.website),
            memo = value("memo", before.memo), updatedAt = updatedAt,
        )
        active[after.id] = after
        dataRevision++
        BusinessCardUpdateResult(before, after)
    }

    override suspend fun upsert(card: BusinessCardRecord): Unit = mutex.withLock {
        val active = cards ?: loadCards().also { cards = it }
        active[card.id] = card
        dataRevision++
        Unit
    }

    override suspend fun delete(cardId: String): Boolean = mutex.withLock {
        val active = cards ?: loadCards().also { cards = it }
        val deleted = active.remove(cardId.trim()) != null
        if (deleted) dataRevision++
        deleted
    }

    private suspend fun requireCards(): LinkedHashMap<String, BusinessCardRecord> {
        cards?.let { return it }
        return mutex.withLock { cards ?: loadCards().also { cards = it } }
    }

    private suspend fun loadCards(): LinkedHashMap<String, BusinessCardRecord> = withContext(Dispatchers.IO) {
        if (!file.isFile || !file.canRead()) {
            error("명함 asset 파일을 찾을 수 없습니다: ${file.absolutePath}")
        }
        LinkedHashMap(codec.decode(file.readText(Charsets.UTF_8)).associateBy { it.id })
    }
}

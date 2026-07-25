package com.example.hjp.data

import android.content.Context
import com.hjp.tool.contact.BusinessCardRecord
import com.hjp.tool.contact.BusinessCardUpdateResult
import com.hjp.tool.contact.MutableBusinessCardStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

class RoomBusinessCardRepository(
    context: Context,
    private val dao: BusinessCardDao,
    private val json: Json = Json,
) : MutableBusinessCardStore {
    private val seedRepository = AssetBusinessCardRepository(context)
    private val seedMutex = Mutex()
    private val dataRevision = AtomicLong(0L)

    override suspend fun revision(): Long = dataRevision.get()

    override suspend fun loadAll(): List<BusinessCardRecord> {
        seedIfEmpty()
        return dao.loadAll().map { it.toDomain(json) }
    }

    override suspend fun getById(cardId: String): BusinessCardRecord? {
        seedIfEmpty()
        return dao.getById(cardId.trim())?.toDomain(json)
    }

    override suspend fun update(
        cardId: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
        updatedAt: String,
    ): BusinessCardUpdateResult? {
        seedIfEmpty()
        val before = dao.getById(cardId.trim())?.toDomain(json) ?: return null
        val after = before.copy(
            name = resolveField("name", before.name, updates, clearFields),
            nameEn = resolveField("name_en", before.nameEn, updates, clearFields),
            company = resolveField("company", before.company, updates, clearFields),
            title = resolveField("title", before.title, updates, clearFields),
            department = resolveField("department", before.department, updates, clearFields),
            industry = resolveField("industry", before.industry, updates, clearFields),
            location = resolveField("location", before.location, updates, clearFields),
            phone = resolveField("phone", before.phone, updates, clearFields),
            mobile = resolveField("mobile", before.mobile, updates, clearFields),
            email = resolveField("email", before.email, updates, clearFields),
            address = resolveField("address", before.address, updates, clearFields),
            website = resolveField("website", before.website, updates, clearFields),
            memo = resolveField("memo", before.memo, updates, clearFields),
            updatedAt = updatedAt,
        )
        dao.update(after.toEntity(json))
        dataRevision.incrementAndGet()
        return BusinessCardUpdateResult(before, after)
    }

    override suspend fun upsert(card: BusinessCardRecord) {
        seedIfEmpty()
        dao.upsert(card.toEntity(json))
        dataRevision.incrementAndGet()
    }

    override suspend fun delete(cardId: String): Boolean {
        seedIfEmpty()
        val deleted = dao.deleteById(cardId.trim()) > 0
        if (deleted) dataRevision.incrementAndGet()
        return deleted
    }

    private suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        seedMutex.withLock {
            if (dao.count() > 0) return@withLock
            val seedCards = seedRepository.loadAll().map { it.toEntity(json) }
            dao.insertAll(seedCards)
            dataRevision.incrementAndGet()
        }
    }

    private fun resolveField(
        field: String,
        currentValue: String,
        updates: Map<String, String>,
        clearFields: Set<String>,
    ): String = when {
        field in updates -> updates.getValue(field)
        field in clearFields -> ""
        else -> currentValue
    }
}

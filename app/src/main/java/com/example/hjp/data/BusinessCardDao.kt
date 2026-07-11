package com.example.hjp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface BusinessCardDao {
    @Query("SELECT * FROM business_cards ORDER BY id")
    suspend fun loadAll(): List<BusinessCardEntity>

    @Query("SELECT * FROM business_cards WHERE id = :id")
    suspend fun getById(id: String): BusinessCardEntity?

    @Query("SELECT COUNT(*) FROM business_cards")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(cards: List<BusinessCardEntity>)

    @Update
    suspend fun update(card: BusinessCardEntity)
}

package com.example.hjp

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.hjp.data.HjpDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HjpDatabaseMigrationInstrumentedTest {
    @Test
    fun migrationTwoToThreePreservesCardsAndEmbeddingsAndBuildsFts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "hjp-migration-2-3-test.db"
        context.deleteDatabase(name)
        openHelper(context, name, object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createV2(db)
                db.execSQL(
                    "INSERT INTO business_cards VALUES " +
                        "('stable-id','김지원','Jiwon Kim','비전글로벌','대표','전략팀','금융','서울'," +
                        "'02-0000','010-1234-4312','private@example.com','서울','','투자 메모','[\"투자\"]','now')",
                )
                db.execSQL(
                    "INSERT INTO card_embeddings VALUES " +
                        "('stable-id','model-old',2,X'00000000','hash',1,1)",
                )
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).also { it.writableDatabase; it.close() }

        val upgraded = openHelper(context, name, object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) = error("v2 database expected")
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                HjpDatabase.MIGRATION_2_3.migrate(db)
            }
        })
        upgraded.writableDatabase.use { db ->
            db.query("SELECT id FROM business_cards").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("stable-id", cursor.getString(0))
            }
            db.query("SELECT card_id FROM card_embeddings").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("stable-id", cursor.getString(0))
            }
            db.query(
                "SELECT card_id FROM business_cards_fts WHERE business_cards_fts MATCH ?",
                arrayOf("\"김지원\""),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("stable-id", cursor.getString(0))
            }
        }
        upgraded.close()
        context.deleteDatabase(name)
    }

    private fun openHelper(
        context: Context,
        name: String,
        callback: SupportSQLiteOpenHelper.Callback,
    ): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(callback)
            .build(),
    )

    private fun createV2(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE business_cards (
              id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, name_en TEXT NOT NULL,
              company TEXT NOT NULL, title TEXT NOT NULL, department TEXT NOT NULL,
              industry TEXT NOT NULL, location TEXT NOT NULL, phone TEXT NOT NULL,
              mobile TEXT NOT NULL, email TEXT NOT NULL, address TEXT NOT NULL,
              website TEXT NOT NULL, memo TEXT NOT NULL, tags_json TEXT NOT NULL,
              updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE card_embeddings (
              card_id TEXT NOT NULL, model_name TEXT NOT NULL, dimension INTEGER NOT NULL,
              vector_blob BLOB NOT NULL, source_text_hash TEXT NOT NULL,
              created_at_millis INTEGER NOT NULL, updated_at_millis INTEGER NOT NULL,
              PRIMARY KEY(card_id, model_name),
              FOREIGN KEY(card_id) REFERENCES business_cards(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }
}

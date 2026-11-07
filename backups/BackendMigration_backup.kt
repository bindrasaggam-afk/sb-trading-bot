package com.sb.backend.database

import android.database.sqlite.SQLiteDatabase

/**
 * SB BOT - Backend Database Migration
 *
 * Existing DashboardDbHelper database version 4 से
 * version 5 पर migrate करने के लिए।
 *
 * यह migration existing tables या data को delete नहीं करती।
 */
object BackendMigration {

    /**
     * Existing main database का नया version.
     *
     * DashboardDbHelper अभी version 4 पर है।
     * Backend tables जोड़ने के बाद इसे version 5 करना है।
     */
    const val DATABASE_VERSION = 5

    /**
     * Fresh installation पर backend tables बनाना।
     *
     * इसे DashboardDbHelper.onCreate() से call करेंगे।
     */
    fun onCreate(db: SQLiteDatabase) {

        BackendSchema.create(db)
    }

    /**
     * Existing database upgrade.
     *
     * SQLiteOpenHelper स्वयं onUpgrade() को transaction
     * के अंदर चलाता है। इसलिए यहाँ अलग transaction शुरू
     * नहीं किया गया है।
     */
    fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {

        if (oldVersion < 5 && newVersion >= 5) {

            BackendSchema.create(db)
        }
    }

    /**
     * Backend schema को सुरक्षित तरीके से उपलब्ध कराना।
     *
     * CREATE IF NOT EXISTS होने के कारण पहले से बनी
     * backend tables दोबारा create नहीं होंगी।
     */
    fun ensureCurrent(db: SQLiteDatabase) {

        BackendSchema.create(db)
    }
}

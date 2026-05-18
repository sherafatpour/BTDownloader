package com.sherafatpour.bluetile.internal.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [DownloadEntity::class], version = 5, exportSchema = false)
internal abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
}

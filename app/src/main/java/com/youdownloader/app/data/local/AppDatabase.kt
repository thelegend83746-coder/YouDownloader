package com.youdownloader.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.youdownloader.app.data.model.DownloadStatus
import com.youdownloader.app.data.model.VideoEntity

class Converters {
    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String {
        return status?.name ?: DownloadStatus.QUEUED.name
    }

    @TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus {
        return try {
            if (value != null) DownloadStatus.valueOf(value) else DownloadStatus.QUEUED
        } catch (e: Exception) {
            DownloadStatus.QUEUED
        }
    }
}

@Database(entities = [VideoEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "you_downloader.db"
                ).fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

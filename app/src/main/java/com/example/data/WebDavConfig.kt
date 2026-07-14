package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "webdav_config")
data class WebDavConfig(
    @PrimaryKey val id: Int = 1, // Single row configuration
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val backupFilePath: String = "WatchAuth/backup.json",
    val isSyncEnabled: Boolean = false,
    val lastSyncTime: Long = 0
)

package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "otp_accounts")
data class OtpAccount(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val label: String,
    val secret: String,
    val issuer: String = "",
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val createdTime: Long = System.currentTimeMillis()
) : Serializable

package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OtpDao {
    @Query("SELECT * FROM otp_accounts ORDER BY createdTime DESC")
    fun getAllAccounts(): Flow<List<OtpAccount>>

    @Query("SELECT * FROM otp_accounts WHERE id = :id")
    suspend fun getAccountById(id: Int): OtpAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAccount(account: OtpAccount)

    @Delete
    suspend fun deleteAccount(account: OtpAccount)

    @Query("DELETE FROM otp_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: Int)

    @Query("SELECT * FROM webdav_config WHERE id = 1")
    fun getWebDavConfig(): Flow<WebDavConfig?>

    @Query("SELECT * FROM webdav_config WHERE id = 1")
    suspend fun getWebDavConfigSync(): WebDavConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWebDavConfig(config: WebDavConfig)
    
    @Query("SELECT * FROM otp_accounts")
    suspend fun getAllAccountsSync(): List<OtpAccount>
}

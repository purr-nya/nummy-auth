package com.example.data

import kotlinx.coroutines.flow.Flow

class OtpRepository(private val otpDao: OtpDao) {
    val allAccounts: Flow<List<OtpAccount>> = otpDao.getAllAccounts()
    val webDavConfig: Flow<WebDavConfig?> = otpDao.getWebDavConfig()

    suspend fun getAccountById(id: Int): OtpAccount? = otpDao.getAccountById(id)

    suspend fun insertAccount(account: OtpAccount) = otpDao.insertAccount(account)

    suspend fun deleteAccount(account: OtpAccount) = otpDao.deleteAccount(account)

    suspend fun deleteAccountById(id: Int) = otpDao.deleteAccountById(id)

    suspend fun getWebDavConfigSync(): WebDavConfig? = otpDao.getWebDavConfigSync()

    suspend fun insertWebDavConfig(config: WebDavConfig) = otpDao.insertWebDavConfig(config)
    
    suspend fun getAllAccountsSync(): List<OtpAccount> = otpDao.getAllAccountsSync()
}

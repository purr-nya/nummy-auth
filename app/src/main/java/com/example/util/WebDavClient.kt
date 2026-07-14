package com.example.util

import com.example.data.OtpAccount
import com.example.data.WebDavConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object WebDavClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val accountsListAdapterType = Types.newParameterizedType(List::class.java, OtpAccount::class.java)
    private val jsonAdapter = moshi.adapter<List<OtpAccount>>(accountsListAdapterType)

    private fun cleanUrl(serverUrl: String, path: String): String {
        val base = serverUrl.trim().removeSuffix("/")
        val cleanPath = path.trim().removePrefix("/")
        return "$base/$cleanPath"
    }

    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = config.serverUrl.trim().removeSuffix("/")
            val credential = Credentials.basic(config.username, config.password)
            
            // Standard PROPFIND or safe GET to test auth and server availability
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", credential)
                .addHeader("Depth", "0")
                .method("PROPFIND", "".toRequestBody("text/xml".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 207 || response.code == 405) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP Error ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun backupAccounts(config: WebDavConfig, accounts: List<OtpAccount>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val credential = Credentials.basic(config.username, config.password)
            val jsonContent = jsonAdapter.toJson(accounts)
            val requestBody = jsonContent.toRequestBody("application/json".toMediaType())

            // 1. Try to create parent directories if any
            val segments = config.backupFilePath.split("/").filter { it.isNotEmpty() }
            if (segments.size > 1) {
                var currentPath = ""
                // Create intermediate directories except the file itself (last segment)
                for (i in 0 until segments.size - 1) {
                    currentPath = "$currentPath/${segments[i]}"
                    val dirUrl = cleanUrl(config.serverUrl, currentPath)
                    
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .addHeader("Authorization", credential)
                        .method("MKCOL", null)
                        .build()
                    
                    client.newCall(mkcolRequest).execute().use { response ->
                        // 201 Created or 405 Method Not Allowed (directory already exists) are fine
                    }
                }
            }

            // 2. Put the backup file
            val fileUrl = cleanUrl(config.serverUrl, config.backupFilePath)
            val putRequest = Request.Builder()
                .url(fileUrl)
                .addHeader("Authorization", credential)
                .put(requestBody)
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("PUT failed with code ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreAccounts(config: WebDavConfig): Result<List<OtpAccount>> = withContext(Dispatchers.IO) {
        try {
            val credential = Credentials.basic(config.username, config.password)
            val fileUrl = cleanUrl(config.serverUrl, config.backupFilePath)
            
            val getRequest = Request.Builder()
                .url(fileUrl)
                .addHeader("Authorization", credential)
                .get()
                .build()

            client.newCall(getRequest).execute().use { response ->
                if (response.code == 404) {
                    Result.failure(Exception("未在WebDAV上找到备份文件，请先上传备份。"))
                } else if (!response.isSuccessful) {
                    Result.failure(Exception("GET failed with code ${response.code}: ${response.message}"))
                } else {
                    val bodyString = response.body?.string() ?: ""
                    val list = jsonAdapter.fromJson(bodyString)
                    if (list != null) {
                        Result.success(list)
                    } else {
                        Result.failure(Exception("解析备份文件失败，JSON格式不正确。"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadBackupRaw(config: WebDavConfig, rawJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val credential = Credentials.basic(config.username, config.password)
            val requestBody = rawJson.toRequestBody("application/json".toMediaType())

            val segments = config.backupFilePath.split("/").filter { it.isNotEmpty() }
            if (segments.size > 1) {
                var currentPath = ""
                for (i in 0 until segments.size - 1) {
                    currentPath = "$currentPath/${segments[i]}"
                    val dirUrl = cleanUrl(config.serverUrl, currentPath)
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .addHeader("Authorization", credential)
                        .method("MKCOL", null)
                        .build()
                    try {
                        client.newCall(mkcolRequest).execute().use {}
                    } catch (e: Exception) {
                        // Directory might exist
                    }
                }
            }

            val fileUrl = cleanUrl(config.serverUrl, config.backupFilePath)
            val putRequest = Request.Builder()
                .url(fileUrl)
                .addHeader("Authorization", credential)
                .put(requestBody)
                .build()

            client.newCall(putRequest).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("HTTP 错误 ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadBackupRaw(config: WebDavConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val credential = Credentials.basic(config.username, config.password)
            val fileUrl = cleanUrl(config.serverUrl, config.backupFilePath)
            
            val getRequest = Request.Builder()
                .url(fileUrl)
                .addHeader("Authorization", credential)
                .get()
                .build()

            client.newCall(getRequest).execute().use { response ->
                if (response.code == 404) {
                    Result.failure(Exception("未在WebDAV上找到备份文件，请先上传备份。"))
                } else if (!response.isSuccessful) {
                    Result.failure(Exception("HTTP 错误 ${response.code}: ${response.message}"))
                } else {
                    val bodyString = response.body?.string() ?: ""
                    Result.success(bodyString)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

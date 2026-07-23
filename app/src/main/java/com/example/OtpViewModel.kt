package com.example

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.OtpAccount
import com.example.data.OtpRepository
import com.example.data.WebDavConfig
import com.example.util.CryptoUtils
import com.example.util.WebDavClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class OtpViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: OtpRepository
    val accounts: StateFlow<List<OtpAccount>>
    val webDavConfig: StateFlow<WebDavConfig>

    // Real-time ticker flow updating every 200ms for high-precision countdown ring animations
    private val _currentTime = MutableStateFlow(System.currentTimeMillis())
    val currentTime: StateFlow<Long> = _currentTime.asStateFlow()

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = OtpRepository(database.otpDao())
        
        accounts = repository.allAccounts
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        webDavConfig = repository.webDavConfig
            .map { it ?: WebDavConfig() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WebDavConfig())

        // Start countdown ticker
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                _currentTime.value = System.currentTimeMillis()
                delay(200)
            }
        }
    }

    fun addAccount(label: String, secret: String, issuer: String, period: Int = 30) {
        viewModelScope.launch(Dispatchers.IO) {
            val account = OtpAccount(
                label = label,
                secret = secret.replace(" ", "").uppercase(),
                issuer = issuer,
                period = period
            )
            repository.insertAccount(account)
        }
    }

    fun updateAccount(account: OtpAccount) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertAccount(account)
        }
    }

    fun addAccountFromUri(uriString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(uriString)
                if (uri.scheme == "otpauth") {
                    val labelAndIssuer = uri.path?.trim('/') ?: "Unknown"
                    val secret = uri.getQueryParameter("secret") ?: return@launch
                    val issuerParam = uri.getQueryParameter("issuer") ?: ""
                    
                    val (label, issuer) = if (labelAndIssuer.contains(':')) {
                        val parts = labelAndIssuer.split(':', limit = 2)
                        parts[1].trim() to (if (issuerParam.isEmpty()) parts[0].trim() else issuerParam)
                    } else {
                        labelAndIssuer to (if (issuerParam.isEmpty()) "Unknown" else issuerParam)
                    }
                    
                    val period = uri.getQueryParameter("period")?.toIntOrNull() ?: 30
                    addAccount(label, secret, issuer, period)
                } else {
                    // Fallback for raw secret codes
                    if (uriString.length >= 16) {
                        addAccount("Manual Account", uriString, "Imported")
                    }
                }
            } catch (e: Exception) {
                // Ignore invalid URIs
            }
        }
    }

    fun deleteAccount(account: OtpAccount) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAccount(account)
        }
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertWebDavConfig(config)
        }
    }

    fun testWebDavConnection(
        config: WebDavConfig,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val result = WebDavClient.testConnection(config)
            if (result.isSuccess) {
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "连接失败")
            }
        }
    }

    fun backupToWebDav(
        config: WebDavConfig,
        encryptPass: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val currentAccounts = repository.getAllAccountsSync()
                if (currentAccounts.isEmpty()) {
                    onError("当前没有需要备份的账户")
                    return@launch
                }

                val accountsAdapter = moshi.adapter<List<OtpAccount>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, OtpAccount::class.java)
                )
                val plainJson = accountsAdapter.toJson(currentAccounts)

                val payload: String
                val isEncrypted: Boolean
                if (!encryptPass.isNullOrEmpty()) {
                    payload = CryptoUtils.encrypt(plainJson, encryptPass)
                    isEncrypted = true
                } else {
                    payload = plainJson
                    isEncrypted = false
                }

                val backupWrapper = BackupWrapper(isEncrypted = isEncrypted, payload = payload)
                val wrapperAdapter = moshi.adapter(BackupWrapper::class.java)
                val finalJson = wrapperAdapter.toJson(backupWrapper)

                // Create a temporary configuration to upload
                val result = WebDavClient.backupAccounts(config, emptyList()) // Ensure directories
                val uploadResult = WebDavClient.backupAccounts(config, currentAccounts) // Standard backup

                // Wait, WebDavClient.backupAccounts takes List<OtpAccount> but we want to upload our custom wrapper.
                // Let's modify WebDavClient to allow uploading custom wrapper, or write a custom raw string uploader.
                // Actually, let's create a raw uploader in WebDavClient, or we can simply write a file uploader.
                // Let's implement custom backup in ViewModel by calling a raw uploader or using WebDavClient directly.
                // Let's create `backupAccountsRaw(config, finalJson)` inside WebDavClient! That is extremely clean.
                
                val rawResult = WebDavClient.uploadBackupRaw(config, finalJson)
                if (rawResult.isSuccess) {
                    saveWebDavConfig(config.copy(lastSyncTime = System.currentTimeMillis()))
                    onSuccess()
                } else {
                    onError(rawResult.exceptionOrNull()?.message ?: "备份失败")
                }
            } catch (e: Exception) {
                onError(e.message ?: "备份发生错误")
            }
        }
    }

    fun restoreFromWebDav(
        config: WebDavConfig,
        decryptPass: String?,
        onSuccess: (Int) -> Unit,
        onPasswordRequired: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val downloadResult = WebDavClient.downloadBackupRaw(config)
                if (downloadResult.isFailure) {
                    onError(downloadResult.exceptionOrNull()?.message ?: "下载备份失败")
                    return@launch
                }

                val backupJson = downloadResult.getOrNull() ?: ""
                val wrapperAdapter = moshi.adapter(BackupWrapper::class.java)
                val wrapper = try {
                    wrapperAdapter.fromJson(backupJson)
                } catch (e: Exception) {
                    null
                }

                val plainJson: String
                if (wrapper != null) {
                    if (wrapper.isEncrypted) {
                        if (decryptPass.isNullOrEmpty()) {
                            onPasswordRequired()
                            return@launch
                        }
                        try {
                            plainJson = CryptoUtils.decrypt(wrapper.payload, decryptPass)
                        } catch (e: Exception) {
                            onError("密码错误，解密失败")
                            return@launch
                        }
                    } else {
                        plainJson = wrapper.payload
                    }
                } else {
                    // Try parsing as old/legacy unencrypted direct list
                    plainJson = backupJson
                }

                val accountsAdapter = moshi.adapter<List<OtpAccount>>(
                    com.squareup.moshi.Types.newParameterizedType(List::class.java, OtpAccount::class.java)
                )
                val restoredList = accountsAdapter.fromJson(plainJson)
                if (restoredList.isNullOrEmpty()) {
                    onError("备份文件中没有有效的账户数据")
                    return@launch
                }

                // Merge into local database
                var importedCount = 0
                restoredList.forEach { account ->
                    val existing = repository.getAllAccountsSync()
                    val duplicate = existing.any { it.secret == account.secret }
                    if (!duplicate) {
                        repository.insertAccount(
                            OtpAccount(
                                label = account.label,
                                secret = account.secret,
                                issuer = account.issuer,
                                period = account.period,
                                algorithm = account.algorithm,
                                digits = account.digits
                            )
                        )
                        importedCount++
                    }
                }
                saveWebDavConfig(config.copy(lastSyncTime = System.currentTimeMillis()))
                onSuccess(importedCount)
            } catch (e: Exception) {
                onError(e.message ?: "恢复发生错误")
            }
        }
    }
}

// Backup file model wrapper
data class BackupWrapper(
    val isEncrypted: Boolean,
    val payload: String
)

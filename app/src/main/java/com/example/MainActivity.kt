package com.example

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.example.data.OtpAccount
import com.example.data.WebDavConfig
import com.example.ui.theme.MyApplicationTheme
import com.example.util.QrCodeAnalyzer
import com.example.util.TotpUtils
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private var isAppLocked = mutableStateOf(false)
    private var isUnlockedSuccessfully = mutableStateOf(false)

    private val deviceLockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            isUnlockedSuccessfully.value = true
        } else {
            Toast.makeText(this, "安全锁验证失败，已退出", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check local storage for whether lock screen is enabled
        val sharedPrefs = getSharedPreferences("watch_auth_prefs", Context.MODE_PRIVATE)
        val isLockEnabled = sharedPrefs.getBoolean("app_lock_enabled", false)
        if (isLockEnabled) {
            isAppLocked.value = true
            triggerDeviceUnlock()
        }

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isAppLocked.value && !isUnlockedSuccessfully.value) {
                        // Display secure splash until lock cleared
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "App Locked",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "验证码安全锁已开启",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { triggerDeviceUnlock() },
                                    modifier = Modifier.testTag("unlock_button")
                                ) {
                                    Text("立即解锁")
                                }
                            }
                        }
                    } else {
                        MainScreen(
                            onToggleLock = { enabled ->
                                sharedPrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
                                if (enabled) {
                                    isAppLocked.value = true
                                    isUnlockedSuccessfully.value = false
                                    triggerDeviceUnlock()
                                } else {
                                    isAppLocked.value = false
                                    isUnlockedSuccessfully.value = false
                                }
                            },
                            isLockEnabled = isLockEnabled
                        )
                    }
                }
            }
        }
    }

    private fun triggerDeviceUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isDeviceSecure) {
            val intent = km.createConfirmDeviceCredentialIntent("手表验证器安全解锁", "验证您的指纹、图案或锁屏密码以显示二次验证码")
            if (intent != null) {
                deviceLockLauncher.launch(intent)
            } else {
                isUnlockedSuccessfully.value = true
            }
        } else {
            isUnlockedSuccessfully.value = true
            Toast.makeText(this, "系统未设置锁屏密码，安全锁自动停用", Toast.LENGTH_SHORT).show()
            getSharedPreferences("watch_auth_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("app_lock_enabled", false).apply()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    onToggleLock: (Boolean) -> Unit,
    isLockEnabled: Boolean,
    viewModel: OtpViewModel = viewModel()
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val currentTime by viewModel.currentTime.collectAsStateWithLifecycle()

    var isWatchMode by remember { mutableStateOf(true) } // Default to simulated beautiful watch layout
    var showScanDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }

    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Handle scanned QR codes
    val handleScannedUri: (String) -> Unit = { scannedText ->
        val parsed = TotpUtils.parseOtpAuthUri(scannedText)
        if (parsed != null) {
            val secret = parsed["secret"] ?: ""
            val label = parsed["label"] ?: "未知账户"
            val issuer = parsed["issuer"] ?: ""
            if (secret.isNotEmpty()) {
                viewModel.addAccount(label, secret, issuer)
                showScanDialog = false
                vibrateFeedback(context)
                Toast.makeText(context, "成功导入: $label", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "解析失败: 找不到密钥 (secret)", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "非法的身份验证二维码", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isWatchMode) {
            // Smartwatch Simulated layout
            WatchUi(
                accounts = accounts,
                currentTime = currentTime,
                onScanQr = {
                    if (cameraPermissionState.status.isGranted) {
                        showScanDialog = true
                    } else {
                        cameraPermissionState.launchPermissionRequest()
                    }
                },
                onManualAdd = { showManualDialog = true },
                onWebDavSync = { showWebDavDialog = true },
                onToggleLayout = { isWatchMode = false },
                onDeleteAccount = { viewModel.deleteAccount(it) }
            )
        } else {
            // Standard Smartphone grid/list layout
            PhoneUi(
                accounts = accounts,
                currentTime = currentTime,
                onScanQr = {
                    if (cameraPermissionState.status.isGranted) {
                        showScanDialog = true
                    } else {
                        cameraPermissionState.launchPermissionRequest()
                    }
                },
                onManualAdd = { showManualDialog = true },
                onWebDavSync = { showWebDavDialog = true },
                onToggleLayout = { isWatchMode = true },
                onDeleteAccount = { viewModel.deleteAccount(it) }
            )
        }

        // CameraX Scanning Dialog
        if (showScanDialog) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showScanDialog = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .testTag("scan_dialog"),
                    colors = CardDefaults.cardColors(containerColor = Color.Black)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CameraPreview(
                            modifier = Modifier.fillMaxSize(),
                            onQrCodeScanned = { qrText ->
                                handleScannedUri(qrText)
                            }
                        )

                        // Visual reticle overlay
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .drawBehind {
                                    val sizeFactor = 0.6f
                                    val boxSize = size.minDimension * sizeFactor
                                    val left = (size.width - boxSize) / 2
                                    val top = (size.height - boxSize) / 2

                                    // Outer dim mask
                                    drawRect(
                                        color = Color.Black.copy(alpha = 0.5f)
                                    )

                                    // Reticle clear box
                                    drawRect(
                                        color = Color.Transparent,
                                        topLeft = Offset(left, top),
                                        size = Size(boxSize, boxSize),
                                        blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                                    )

                                    // Green laser reticle edges
                                    val strokeWidth = 4.dp.toPx()
                                    val edgeLength = 24.dp.toPx()
                                    val color = Color(0xFF4CAF50)

                                    // Top-left corner
                                    drawLine(color, Offset(left, top), Offset(left + edgeLength, top), strokeWidth)
                                    drawLine(color, Offset(left, top), Offset(left, top + edgeLength), strokeWidth)

                                    // Top-right corner
                                    drawLine(color, Offset(left + boxSize, top), Offset(left + boxSize - edgeLength, top), strokeWidth)
                                    drawLine(color, Offset(left + boxSize, top), Offset(left + boxSize, top + edgeLength), strokeWidth)

                                    // Bottom-left corner
                                    drawLine(color, Offset(left, top + boxSize), Offset(left + edgeLength, top + boxSize), strokeWidth)
                                    drawLine(color, Offset(left, top + boxSize), Offset(left, top + boxSize - edgeLength), strokeWidth)

                                    // Bottom-right corner
                                    drawLine(color, Offset(left + boxSize, top + boxSize), Offset(left + boxSize - edgeLength, top + boxSize), strokeWidth)
                                    drawLine(color, Offset(left + boxSize, top + boxSize), Offset(left + boxSize, top + boxSize - edgeLength), strokeWidth)
                                }
                        )

                        // Close Button
                        IconButton(
                            onClick = { showScanDialog = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close Scanner", tint = Color.White)
                        }

                        Text(
                            text = "请对准双因子验证 (OTP) 二维码",
                            color = Color.White,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 24.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Manual Add Dialog
        if (showManualDialog) {
            var labelText by remember { mutableStateOf("") }
            var secretText by remember { mutableStateOf("") }
            var issuerText by remember { mutableStateOf("") }

            AlertDialog(
                onDismissRequest = { showManualDialog = false },
                title = { Text("手动添加账户", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = labelText,
                            onValueChange = { labelText = it },
                            label = { Text("账户名称 (例如: user@github)") },
                            modifier = Modifier.fillMaxWidth().testTag("manual_label_input"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = secretText,
                            onValueChange = { secretText = it },
                            label = { Text("密钥 (Secret Key)") },
                            modifier = Modifier.fillMaxWidth().testTag("manual_secret_input"),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                        OutlinedTextField(
                            value = issuerText,
                            onValueChange = { issuerText = it },
                            label = { Text("签发者 (例如: GitHub)") },
                            modifier = Modifier.fillMaxWidth().testTag("manual_issuer_input"),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (labelText.trim().isEmpty() || secretText.trim().isEmpty()) {
                                Toast.makeText(context, "名称与密钥不能为空", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            viewModel.addAccount(labelText, secretText, issuerText)
                            showManualDialog = false
                            Toast.makeText(context, "成功手动添加: $labelText", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("manual_save_button")
                    ) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showManualDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // WebDAV Sync Dialog
        if (showWebDavDialog) {
            var serverUrl by remember { mutableStateOf(webDavConfig.serverUrl) }
            var username by remember { mutableStateOf(webDavConfig.username) }
            var password by remember { mutableStateOf(webDavConfig.password) }
            var filePath by remember { mutableStateOf(webDavConfig.backupFilePath) }
            var isSyncEnabled by remember { mutableStateOf(webDavConfig.isSyncEnabled) }

            var isTesting by remember { mutableStateOf(false) }
            var isBackingUp by remember { mutableStateOf(false) }
            var isRestoring by remember { mutableStateOf(false) }

            var showEncryptPrompt by remember { mutableStateOf(false) }
            var backupPasswordText by remember { mutableStateOf("") }
            var isBackupAction by remember { mutableStateOf(true) } // true = backup, false = restore

            AlertDialog(
                onDismissRequest = { showWebDavDialog = false },
                modifier = Modifier.fillMaxWidth(0.95f),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("WebDAV 云同步备份", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 350.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                value = serverUrl,
                                onValueChange = { serverUrl = it },
                                label = { Text("WebDAV 服务器基准 URL") },
                                placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                                modifier = Modifier.fillMaxWidth().testTag("webdav_url_input"),
                                singleLine = true
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("用户名 / 账号邮箱") },
                                modifier = Modifier.fillMaxWidth().testTag("webdav_user_input"),
                                singleLine = true
                            )
                        }
                        item {
                            var passVisible by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("应用授权密码") },
                                visualTransformation = if (passVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passVisible = !passVisible }) {
                                        Icon(if (passVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("webdav_pass_input"),
                                singleLine = true
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = filePath,
                                onValueChange = { filePath = it },
                                label = { Text("备份文件路径") },
                                modifier = Modifier.fillMaxWidth().testTag("webdav_path_input"),
                                singleLine = true
                            )
                        }
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = isSyncEnabled,
                                    onCheckedChange = { isSyncEnabled = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("启用后台同步", fontSize = 14.sp)
                            }
                        }

                        if (webDavConfig.lastSyncTime > 0) {
                            item {
                                val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
                                val dateStr = sdf.format(Date(webDavConfig.lastSyncTime))
                                Text(
                                    "上次同步时间: $dateStr",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }

                        item {
                            Divider(modifier = Modifier.padding(vertical = 8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Connection Test Button
                                Button(
                                    onClick = {
                                        isTesting = true
                                        val temp = WebDavConfig(1, serverUrl, username, password, filePath, isSyncEnabled)
                                        viewModel.testWebDavConnection(temp, {
                                            isTesting = false
                                            Toast.makeText(context, "连接 WebDAV 成功！", Toast.LENGTH_SHORT).show()
                                        }, { err ->
                                            isTesting = false
                                            Toast.makeText(context, "连接失败: $err", Toast.LENGTH_LONG).show()
                                        })
                                    },
                                    enabled = !isTesting && !isBackingUp && !isRestoring && serverUrl.isNotEmpty(),
                                    modifier = Modifier.weight(1f).testTag("webdav_test_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                                ) {
                                    if (isTesting) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("测试连接", fontSize = 13.sp)
                                }

                                // Security Lock toggle inside Sync Dialog
                                var localLockEnabled by remember { mutableStateOf(isLockEnabled) }
                                Button(
                                    onClick = {
                                        localLockEnabled = !localLockEnabled
                                        onToggleLock(localLockEnabled)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (localLockEnabled) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (localLockEnabled) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(if (localLockEnabled) Icons.Default.LockOpen else Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (localLockEnabled) "解开安全锁" else "开启安全锁", fontSize = 11.sp)
                                }
                            }
                        }

                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                // Upload/Backup
                                Button(
                                    onClick = {
                                        isBackupAction = true
                                        showEncryptPrompt = true
                                    },
                                    enabled = !isTesting && !isBackingUp && !isRestoring && serverUrl.isNotEmpty(),
                                    modifier = Modifier.weight(1f).testTag("webdav_backup_button"),
                                ) {
                                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("备份数据")
                                }

                                // Download/Restore
                                Button(
                                    onClick = {
                                        isBackupAction = false
                                        showEncryptPrompt = true
                                    },
                                    enabled = !isTesting && !isBackingUp && !isRestoring && serverUrl.isNotEmpty(),
                                    modifier = Modifier.weight(1f).testTag("webdav_restore_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                                ) {
                                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("恢复数据")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val finalConfig = WebDavConfig(1, serverUrl, username, password, filePath, isSyncEnabled, webDavConfig.lastSyncTime)
                            viewModel.saveWebDavConfig(finalConfig)
                            showWebDavDialog = false
                            Toast.makeText(context, "WebDAV 配置已保存", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("webdav_save_button")
                    ) {
                        Text("保存配置")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWebDavDialog = false }) {
                        Text("关闭")
                    }
                }
            )

            // Password Encryption dialog overlay
            if (showEncryptPrompt) {
                AlertDialog(
                    onDismissRequest = { showEncryptPrompt = false },
                    title = { Text(if (isBackupAction) "备份加密保护" else "解密恢复账户") },
                    text = {
                        Column {
                            Text(
                                if (isBackupAction) "为了确保您的双因子密钥(Secrets)云端存储安全性，强烈建议设置一个密码来进行本地加密。若不需要加密可留空。"
                                else "如果您的备份文件已被加密，请输入解密密码；若是明文备份，请保持留空。"
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = backupPasswordText,
                                onValueChange = { backupPasswordText = it },
                                label = { Text("加密/解密密码") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().testTag("backup_crypto_pass_input")
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showEncryptPrompt = false
                                val currentConf = WebDavConfig(1, serverUrl, username, password, filePath, isSyncEnabled, webDavConfig.lastSyncTime)
                                viewModel.saveWebDavConfig(currentConf)

                                if (isBackupAction) {
                                    isBackingUp = true
                                    viewModel.backupToWebDav(currentConf, backupPasswordText, {
                                        isBackingUp = false
                                        Toast.makeText(context, "成功安全备份到云端！", Toast.LENGTH_SHORT).show()
                                    }, { err ->
                                        isBackingUp = false
                                        Toast.makeText(context, "备份失败: $err", Toast.LENGTH_LONG).show()
                                    })
                                } else {
                                    isRestoring = true
                                    viewModel.restoreFromWebDav(currentConf, backupPasswordText, { imported ->
                                        isRestoring = false
                                        Toast.makeText(context, "数据恢复成功！导入了 $imported 个新账户", Toast.LENGTH_LONG).show()
                                    }, {
                                        isRestoring = false
                                        showEncryptPrompt = true // Loop back for password
                                        Toast.makeText(context, "此备份文件已加密，请输入密码解密", Toast.LENGTH_LONG).show()
                                    }, { err ->
                                        isRestoring = false
                                        Toast.makeText(context, "恢复失败: $err", Toast.LENGTH_LONG).show()
                                    })
                                }
                                backupPasswordText = ""
                            }
                        ) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showEncryptPrompt = false
                            backupPasswordText = ""
                        }) {
                            Text("返回")
                        }
                    }
                )
            }
        }
    }
}

/**
 * Smartwatch layout: simulates a beautiful circular watch screen
 * complete with mechanical dial markings, digital clock, rotary crown,
 * circular progress timing bars, and optimized swipe layouts.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchUi(
    accounts: List<OtpAccount>,
    currentTime: Long,
    onScanQr: () -> Unit,
    onManualAdd: () -> Unit,
    onWebDavSync: () -> Unit,
    onToggleLayout: () -> Unit,
    onDeleteAccount: (OtpAccount) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Track active account index
    var activeIndex by remember { mutableStateOf(0) }
    if (activeIndex >= accounts.size && accounts.isNotEmpty()) {
        activeIndex = accounts.size - 1
    }

    // Outer wheel crown mechanical visual drag state
    var crownScrollValue by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070707)),
        contentAlignment = Alignment.Center
    ) {
        // 1. External Watch Chassis Frame
        Box(
            modifier = Modifier
                .size(380.dp)
                .drawBehind {
                    // Draw nice brushed titanium bezel
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF222222), Color(0xFF0F0F0F)),
                            center = center,
                            radius = size.minDimension / 2
                        ),
                        radius = size.minDimension / 2
                    )

                    // Tick markings
                    val borderStroke = 3.dp.toPx()
                    drawCircle(
                        color = Color(0xFF1E1E1E),
                        radius = (size.minDimension / 2) - borderStroke,
                        style = Stroke(width = borderStroke)
                    )

                    // Draw watch tick marks
                    val numTicks = 60
                    val tickColor = Color(0xFF3A3A3A)
                    for (i in 0 until numTicks) {
                        val angle = (i * 360f / numTicks) * (Math.PI / 180).toFloat()
                        val outerRadius = (size.minDimension / 2) - 4.dp.toPx()
                        val innerRadius = outerRadius - (if (i % 5 == 0) 8.dp.toPx() else 4.dp.toPx())
                        val stroke = if (i % 5 == 0) 2.dp.toPx() else 1.dp.toPx()

                        val startX = center.x + innerRadius * kotlin.math.cos(angle)
                        val startY = center.y + innerRadius * kotlin.math.sin(angle)
                        val endX = center.x + outerRadius * kotlin.math.cos(angle)
                        val endY = center.y + outerRadius * kotlin.math.sin(angle)

                        drawLine(
                            color = tickColor,
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = stroke
                        )
                    }
                }
                .padding(24.dp) // Simulated circular screen clip boundary
                .clip(CircleShape)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // 2. Simulated Smartwatch screen interior
            if (accounts.isEmpty()) {
                // Empty state for watch screen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Watch,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "暂无验证码",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "滑动右侧数字冠/点击添加",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = onScanQr,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onManualAdd,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                        ) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = onWebDavSync,
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFF333333), CircleShape)
                        ) {
                            Icon(Icons.Default.CloudSync, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onToggleLayout) {
                        Text("手机大屏模式", fontSize = 11.sp)
                    }
                }
            } else {
                // Circular active token presentation
                val activeAccount = accounts[activeIndex]
                val stepSeconds = activeAccount.period
                val currentSecs = (currentTime / 1000) % stepSeconds
                val remainingSecs = stepSeconds - currentSecs
                val progress = (currentTime % (stepSeconds * 1000)) / (stepSeconds * 1000f)

                // Compute standard TOTP
                val totpCode = remember(activeAccount, currentTime / (stepSeconds * 1000)) {
                    TotpUtils.generateTotp(activeAccount.secret, currentTime / 1000, stepSeconds, activeAccount.digits)
                }

                // Format: xxx xxx
                val formattedTotp = if (totpCode.length == 6) {
                    totpCode.substring(0, 3) + " " + totpCode.substring(3)
                } else totpCode

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer Circular Timing Progress Ring
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val strokeWidth = 5.dp.toPx()
                                val radius = (size.minDimension / 2) - 8.dp.toPx()
                                val timerColor = if (remainingSecs <= 5) Color(0xFFFF5252) else Color(0xFF4CAF50)

                                // Base tracking circle
                                drawCircle(
                                    color = Color(0xFF151515),
                                    radius = radius,
                                    style = Stroke(width = strokeWidth)
                                )

                                // Countdown swept arc
                                drawArc(
                                    color = timerColor,
                                    startAngle = -90f,
                                    sweepAngle = -360f * (1f - progress),
                                    useCenter = false,
                                    topLeft = Offset(center.x - radius, center.y - radius),
                                    size = Size(radius * 2, radius * 2),
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                    )

                    // Core Watch View Contents
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 18.dp)
                    ) {
                        // Top segment: Tiny clock & index tracker
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        ) {
                            val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                            Text(
                                text = timeFormat.format(Date(currentTime)),
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )

                            Text(
                                text = "${activeIndex + 1}/${accounts.size}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Center Segment: Code container and details
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = activeAccount.issuer.ifEmpty { "二次验证" },
                                color = MaterialTheme.colorScheme.secondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            
                            // Copiable Token box
                            Box(
                                modifier = Modifier
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(totpCode))
                                        vibrateFeedback(context)
                                        Toast.makeText(context, "已复制验证码 $totpCode", Toast.LENGTH_SHORT).show()
                                    }
                                    .background(Color(0xFF121212), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                                    .testTag("watch_code_box"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = formattedTotp,
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = activeAccount.label,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Display blinking warning for low time
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = if (remainingSecs <= 5) Color(0xFFFF5252) else Color(0xFF4CAF50),
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${remainingSecs}s",
                                    color = if (remainingSecs <= 5) Color(0xFFFF5252) else Color(0xFF4CAF50),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Bottom Actions Row: Very compact circle triggers
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Previous
                                IconButton(
                                    onClick = {
                                        if (activeIndex > 0) activeIndex-- else activeIndex = accounts.size - 1
                                        vibrateFeedback(context)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.ArrowBackIos, null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                                }

                                // Quick Delete
                                IconButton(
                                    onClick = {
                                        onDeleteAccount(activeAccount)
                                        vibrateFeedback(context)
                                        Toast.makeText(context, "已删除账户", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(28.dp).background(Color(0xFF221111), CircleShape)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFFF5252), modifier = Modifier.size(14.dp))
                                }

                                // Scan Trigger
                                IconButton(
                                    onClick = onScanQr,
                                    modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                }

                                // Sync trigger
                                IconButton(
                                    onClick = onWebDavSync,
                                    modifier = Modifier.size(28.dp).background(Color(0xFF222222), CircleShape)
                                ) {
                                    Icon(Icons.Default.CloudSync, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }

                                // Next
                                IconButton(
                                    onClick = {
                                        if (activeIndex < accounts.size - 1) activeIndex++ else activeIndex = 0
                                        vibrateFeedback(context)
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.ArrowForwardIos, null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                                }
                            }

                            // View Switcher Link
                            Text(
                                text = "手机全屏模式",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { onToggleLayout() }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. Simulated Side Rotary Crown Touch Input Controller
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-4).dp)
                .width(28.dp)
                .height(60.dp)
                .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF888888), Color(0xFF333333), Color(0xFF111111))
                    )
                )
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        crownScrollValue += dragAmount
                        // Detect rotation threshold for crown selection (tick haptic sound/vibration)
                        if (abs(crownScrollValue) > 40f && accounts.isNotEmpty()) {
                            vibrateFeedback(context)
                            if (crownScrollValue > 0) {
                                // Rotate down -> Next account
                                if (activeIndex < accounts.size - 1) activeIndex++ else activeIndex = 0
                            } else {
                                // Rotate up -> Previous account
                                if (activeIndex > 0) activeIndex-- else activeIndex = accounts.size - 1
                            }
                            crownScrollValue = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Crown ridges lines
            Column(
                modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(8) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(1.5.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                    )
                }
            }
        }
    }
}

/**
 * Standard Material Design 3 Phone Layout: Features high-density
 * searching, quick actions, beautiful dashboard stat summaries,
 * and standard lists of active secondary verification keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneUi(
    accounts: List<OtpAccount>,
    currentTime: Long,
    onScanQr: () -> Unit,
    onManualAdd: () -> Unit,
    onWebDavSync: () -> Unit,
    onToggleLayout: () -> Unit,
    onDeleteAccount: (OtpAccount) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredAccounts = remember(accounts, searchQuery) {
        if (searchQuery.trim().isEmpty()) {
            accounts
        } else {
            accounts.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                        it.issuer.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            "手表验证器",
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            "双因子身份校验 (MFA/2FA)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleLayout) {
                        Icon(Icons.Default.Watch, contentDescription = "Watch Mode", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onWebDavSync) {
                        Icon(Icons.Default.CloudSync, contentDescription = "WebDAV Sync", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                SmallFloatingActionButton(
                    onClick = onManualAdd,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Icon(Icons.Default.Edit, "手动输入")
                }
                FloatingActionButton(
                    onClick = onScanQr,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("scan_fab")
                ) {
                    Icon(Icons.Default.QrCodeScanner, "扫码添加")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索验证账户...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.Gray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, null, tint = Color.Gray)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("search_field"),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                ),
                singleLine = true
            )

            if (filteredAccounts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "未找到相关账号" else "您的安全验证库是空的",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "请换一个搜索词试一下" else "点击下方 '+' 扫码导入或手动录入密匙",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    state = rememberLazyListState()
                ) {
                    itemsIndexed(
                        items = filteredAccounts,
                        key = { _, account -> account.id }
                    ) { _, account ->
                        val stepSeconds = account.period
                        val currentSecs = (currentTime / 1000) % stepSeconds
                        val remainingSecs = stepSeconds - currentSecs
                        val progress = (currentTime % (stepSeconds * 1000)) / (stepSeconds * 1000f)

                        // TOTP Calculation
                        val totpCode = remember(account, currentTime / (stepSeconds * 1000)) {
                            TotpUtils.generateTotp(account.secret, currentTime / 1000, stepSeconds, account.digits)
                        }

                        val formattedTotp = if (totpCode.length == 6) {
                            totpCode.substring(0, 3) + " " + totpCode.substring(3)
                        } else totpCode

                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("otp_account_card_${account.id}"),
                            colors = CardDefaults.elevatedCardColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Left details column
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.Security,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = account.issuer.ifEmpty { "二次校验" },
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = formattedTotp,
                                        color = Color.White,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.sp,
                                        modifier = Modifier.clickable {
                                            clipboardManager.setText(AnnotatedString(totpCode))
                                            vibrateFeedback(context)
                                            Toast.makeText(context, "已复制验证码 $totpCode", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = account.label,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Right timer / delete actions
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.height(84.dp)
                                ) {
                                    // Quick Copy / Delete row
                                    Row {
                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(totpCode))
                                                vibrateFeedback(context)
                                                Toast.makeText(context, "已复制验证码 $totpCode", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Code", tint = Color.Gray, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                onDeleteAccount(account)
                                                vibrateFeedback(context)
                                                Toast.makeText(context, "已删除 ${account.label}", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Account", tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    // Countdown radial timer
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    ) {
                                        Text(
                                            text = "${remainingSecs}s",
                                            color = if (remainingSecs <= 5) Color(0xFFFF5252) else Color(0xFF4CAF50),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .drawBehind {
                                                    val timerColor = if (remainingSecs <= 5) Color(0xFFFF5252) else Color(0xFF4CAF50)
                                                    drawCircle(
                                                        color = Color(0xFF222222),
                                                        style = Stroke(width = 2.dp.toPx())
                                                    )
                                                    drawArc(
                                                        color = timerColor,
                                                        startAngle = -90f,
                                                        sweepAngle = -360f * (1f - progress),
                                                        useCenter = false,
                                                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                                    )
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * CameraX Live Preview Composable integrating ZXing frame analysis.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onQrCodeScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis.setAnalyzer(executor, QrCodeAnalyzer { qrText ->
                    onQrCodeScanned(qrText)
                })

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, executor)
            previewView
        },
        modifier = modifier
    )
}

/**
 * Trigger robust tactile vibration feedback
 */
fun vibrateFeedback(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            @Suppress("DEPRECATION")
            vibrator?.vibrate(40)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

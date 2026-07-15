package com.example

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.OtpAccount
import com.example.data.WebDavConfig
import com.example.ui.theme.MyApplicationTheme
import com.example.util.TotpUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val isAppLocked = mutableStateOf(false)
    private val isUnlockedSuccessfully = mutableStateOf(false)
    private val deviceLockLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) isUnlockedSuccessfully.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedPrefs = getSharedPreferences("watch_auth_prefs", Context.MODE_PRIVATE)
        val isLockEnabled = sharedPrefs.getBoolean("app_lock_enabled", false)
        if (isLockEnabled) {
            isAppLocked.value = true
            triggerDeviceUnlock()
        }
        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    if (isAppLocked.value && !isUnlockedSuccessfully.value) {
                        LockedScreen { triggerDeviceUnlock() }
                    } else {
                        MainScreen(onToggleLock = { enabled ->
                            sharedPrefs.edit().putBoolean("app_lock_enabled", enabled).apply()
                            if (enabled) {
                                isAppLocked.value = true
                                isUnlockedSuccessfully.value = false
                                triggerDeviceUnlock()
                            } else {
                                isAppLocked.value = false
                                isUnlockedSuccessfully.value = false
                            }
                        }, isLockEnabled = isLockEnabled)
                    }
                }
            }
        }
    }

    private fun triggerDeviceUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isDeviceSecure) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val intent = km.createConfirmDeviceCredentialIntent("安全解锁", "验证以显示验证码")
                if (intent != null) deviceLockLauncher.launch(intent) else isUnlockedSuccessfully.value = true
            } else isUnlockedSuccessfully.value = true
        } else isUnlockedSuccessfully.value = true
    }
}

@Composable
fun LockedScreen(onUnlock: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, tint = Color(0xFFFFB4AB), modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("已锁定", color = Color.White, fontSize = 14.sp)
            Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text("解锁") }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: OtpViewModel = viewModel(), onToggleLock: (Boolean) -> Unit, isLockEnabled: Boolean) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val currentTime by viewModel.currentTime.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf("main") }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    when (currentScreen) {
        "scan" -> Box(modifier = Modifier.fillMaxSize()) {
            QrCodeScannerView { code -> viewModel.addAccount("New", code, "Imported"); currentScreen = "main" }
            IconButton(onClick = { currentScreen = "main" }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.Default.Close, null, tint = Color.White)
            }
        }
        "manual" -> ManualAddScreen({ currentScreen = "main" }, { l, i, s -> viewModel.addAccount(l, s, i); currentScreen = "main" })
        "sync" -> WebDavConfigScreen(webDavConfig, { currentScreen = "main" }, { viewModel.saveWebDavConfig(it) }, { c, p -> viewModel.backupToWebDav(c, p, {}, {}) }, { c, p -> viewModel.restoreFromWebDav(c, p, { _ -> }, {}, {}) })
        else -> WatchFaceInterface(accounts, currentTime, isLockEnabled, onToggleLock, { if (cameraPermission.status.isGranted) currentScreen = "scan" else cameraPermission.launchPermissionRequest() }, { currentScreen = "manual" }, { currentScreen = "sync" }, { viewModel.deleteAccount(it) })
    }
}

@Composable
fun WatchFaceInterface(accounts: List<OtpAccount>, currentTime: Long, lockEnabled: Boolean, onToggleLock: (Boolean) -> Unit, onScan: () -> Unit, onManual: () -> Unit, onSync: () -> Unit, onDelete: (OtpAccount) -> Unit) {
    val configuration = LocalConfiguration.current
    val isRound = configuration.isScreenRound
    val screenWidth = configuration.screenWidthDp.dp
    var activeIndex by remember { mutableStateOf(0) }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(screenWidth).drawBehind {
            if (isRound) {
                drawCircle(brush = Brush.radialGradient(colors = listOf(Color(0xFF1A1A1A), Color(0xFF000000)), center = center, radius = size.minDimension / 2), radius = size.minDimension / 2)
                drawCircle(color = Color(0xFF2A2A2A), radius = (size.minDimension / 2) - 2.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
            } else {
                drawRect(brush = Brush.linearGradient(colors = listOf(Color(0xFF1A1A1A), Color(0xFF000000)), start = Offset.Zero, end = Offset(size.width, size.height)))
                drawRect(color = Color(0xFF2A2A2A), style = Stroke(width = 2.dp.toPx()))
            }
        }.padding(if (isRound) 24.dp else 12.dp).clip(if (isRound) CircleShape else RoundedCornerShape(16.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            if (accounts.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无账户", color = Color.Gray, fontSize = 12.sp)
                    IconButton(onClick = onScan, modifier = Modifier.padding(top = 8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) { Icon(Icons.Default.Add, null, tint = Color.Black) }
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        IconButton(onClick = onManual) { Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                        IconButton(onClick = onSync) { Icon(Icons.Default.Sync, null, tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                    }
                }
            } else {
                val acc = accounts.getOrNull(activeIndex) ?: accounts.first()
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTime)), color = Color.Gray, fontSize = 9.sp)
                        Icon(if (lockEnabled) Icons.Default.Lock else Icons.Default.LockOpen, null, tint = Color.Gray, modifier = Modifier.size(12.dp).clickable { onToggleLock(!lockEnabled) })
                    }
                    OtpDetailView(acc, currentTime, { onDelete(acc) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (accounts.size > 1) {
                            Icon(Icons.Default.ChevronLeft, null, tint = Color.Gray, modifier = Modifier.clickable { activeIndex = (activeIndex - 1 + accounts.size) % accounts.size })
                            Text("${activeIndex + 1}/${accounts.size}", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
                            Icon(Icons.Default.ChevronRight, null, tint = Color.Gray, modifier = Modifier.clickable { activeIndex = (activeIndex + 1) % accounts.size })
                        }
                        IconButton(onClick = onScan, modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) { Icon(Icons.Default.Add, null, tint = Color.Black, modifier = Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
fun OtpDetailView(acc: OtpAccount, time: Long, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val otp = remember(acc.secret, time) { try { TotpUtils.generateTotp(acc.secret) } catch (e: Exception) { "Error" } }
    val progress = 1f - ((time / 1000 % 30).toFloat() / 30f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(otp)); vibrateFeedback(context) }) {
        Text(acc.issuer.ifEmpty { "账户" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(otp.chunked(3).joinToString(" "), fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 4.dp)) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(24.dp), color = if (progress < 0.2f) Color.Red else MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            Text("${(progress * 30).toInt()}s", fontSize = 7.sp, color = Color.White)
        }
        Text("长按删除", color = Color.DarkGray, fontSize = 7.sp, modifier = Modifier.padding(top = 4.dp).clickable { onDelete() })
    }
}

@Composable
fun QrCodeScannerView(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {}
        }, ContextCompat.getMainExecutor(ctx))
        previewView
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun ManualAddScreen(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var lState by remember { mutableStateOf("") }
    var iState by remember { mutableStateOf("") }
    var sState by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("手动添加", color = Color.White)
        OutlinedTextField(value = lState, onValueChange = { lState = it }, label = { Text("名称") })
        OutlinedTextField(value = iState, onValueChange = { iState = it }, label = { Text("发行者") })
        OutlinedTextField(value = sState, onValueChange = { sState = it }, label = { Text("密钥") })
        Row {
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { onAdd(lState, iState, sState) }) { Text("确认") }
        }
    }
}

@Composable
fun WebDavConfigScreen(config: WebDavConfig, onDismiss: () -> Unit, onSave: (WebDavConfig) -> Unit, onBackup: (WebDavConfig, String) -> Unit, onRestore: (WebDavConfig, String) -> Unit) {
    var uState by remember { mutableStateOf(config.serverUrl) }
    var nState by remember { mutableStateOf(config.username) }
    var pState by remember { mutableStateOf(config.password) }
    var eState by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("WebDAV", color = Color.White)
        OutlinedTextField(value = uState, onValueChange = { uState = it }, label = { Text("URL") })
        OutlinedTextField(value = nState, onValueChange = { nState = it }, label = { Text("账号") })
        OutlinedTextField(value = pState, onValueChange = { pState = it }, label = { Text("密码") })
        OutlinedTextField(value = eState, onValueChange = { eState = it }, label = { Text("加密密码") })
        Row {
            val nc = config.copy(serverUrl = uState, username = nState, password = pState)
            TextButton(onClick = { onSave(nc) }) { Text("保存") }
            TextButton(onClick = { onBackup(nc, eState) }) { Text("备份") }
            TextButton(onClick = { onRestore(nc, eState) }) { Text("恢复") }
        }
        TextButton(onClick = onDismiss) { Text("返回") }
    }
}

fun vibrateFeedback(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    else @Suppress("DEPRECATION") vibrator.vibrate(50)
}

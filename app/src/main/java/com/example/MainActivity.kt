package com.example

import android.util.Size
import java.util.concurrent.Executors
import androidx.camera.core.ExperimentalGetImage
import android.Manifest
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
            val intent = km.createConfirmDeviceCredentialIntent("安全解锁", "验证以显示验证码")
            if (intent != null) deviceLockLauncher.launch(intent) else isUnlockedSuccessfully.value = true
        } else isUnlockedSuccessfully.value = true
    }
}

@Composable
fun LockedScreen(onUnlock: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Lock,
                null,
                tint = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("应用已锁定", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "请验证身份以继续",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                modifier = Modifier.padding(top = 8.dp)
            )
            Button(
                onClick = onUnlock,
                modifier = Modifier.padding(top = 32.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("点击解锁")
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: OtpViewModel = viewModel(), onToggleLock: (Boolean) -> Unit, isLockEnabled: Boolean) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val currentTime by viewModel.currentTime.collectAsStateWithLifecycle()
    var currentScreen by remember { mutableStateOf("main") }
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var isPermissionRequested by remember { mutableStateOf(false) }

    LaunchedEffect(cameraPermission.status.isGranted) {
        if (cameraPermission.status.isGranted && isPermissionRequested) {
            currentScreen = "scan"
            isPermissionRequested = false
        }
    }

    BackHandler(enabled = currentScreen != "main") {
        currentScreen = "main"
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState != "main") {
                slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut()
            } else {
                slideInVertically { -it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
            }
        },
        label = "screen_transition"
    ) { screen ->
        when (screen) {
            "scan" -> Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                QrCodeScannerView { code ->
                    viewModel.addAccountFromUri(code)
                    currentScreen = "main"
                }
                TopAppBar(
                    title = { Text("扫描二维码", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { currentScreen = "main" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.5f), titleContentColor = Color.White)
                )
            }
            "manual" -> ManualAddScreen(
                onDismiss = { currentScreen = "main" },
                onAdd = { l, i, s ->
                    viewModel.addAccount(l, s, i)
                    currentScreen = "main"
                }
            )
            "sync" -> WebDavConfigScreen(
                webDavConfig,
                onDismiss = { currentScreen = "main" },
                onSave = { viewModel.saveWebDavConfig(it) },
                onBackup = { c, p -> viewModel.backupToWebDav(c, p, {}, {}) },
                onRestore = { c, p -> viewModel.restoreFromWebDav(c, p, { _ -> }, {}, {}) }
            )
            else -> WatchFaceInterface(
                accounts = accounts,
                currentTime = currentTime,
                lockEnabled = isLockEnabled,
                onToggleLock = onToggleLock,
                onScan = {
                    if (cameraPermission.status.isGranted) {
                        currentScreen = "scan"
                    } else {
                        isPermissionRequested = true
                        cameraPermission.launchPermissionRequest()
                    }
                },
                onManual = { currentScreen = "manual" },
                onSync = { currentScreen = "sync" },
                onDelete = { viewModel.deleteAccount(it) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WatchFaceInterface(
    accounts: List<OtpAccount>,
    currentTime: Long,
    lockEnabled: Boolean,
    onToggleLock: (Boolean) -> Unit,
    onScan: () -> Unit,
    onManual: () -> Unit,
    onSync: () -> Unit,
    onDelete: (OtpAccount) -> Unit
) {
    val configuration = LocalConfiguration.current
    val isRound = configuration.isScreenRound
    val screenWidth = configuration.screenWidthDp.dp
    val pagerState = rememberPagerState(pageCount = { if (accounts.isEmpty()) 1 else accounts.size })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isRound) 4.dp else 2.dp),
            contentAlignment = Alignment.Center
        ) {
            if (accounts.isEmpty()) {
                EmptyStateView(onScan, onManual, onSync)
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    WatchTopBar(currentTime, lockEnabled, onToggleLock)

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        pageSpacing = 8.dp
                    ) { page ->
                        val acc = accounts[page]
                        OtpDetailView(acc, currentTime, onDelete = { onDelete(acc) })
                    }

                    WatchBottomBar(pagerState.currentPage, accounts.size, onScan)
                }
            }
        }
    }
}

@Composable
fun WatchTopBar(currentTime: Long, lockEnabled: Boolean, onToggleLock: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(currentTime)),
            color = Color.Gray,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black)
        )
        IconButton(
            onClick = { onToggleLock(!lockEnabled) },
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                if (lockEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                null,
                tint = if (lockEnabled) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun WatchBottomBar(currentPage: Int, totalPages: Int, onScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (totalPages > 1) {
            Text(
                "${currentPage + 1}/${totalPages}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.DarkGray,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        IconButton(
            onClick = onScan,
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
        ) {
            Icon(Icons.Default.Add, null, tint = Color.Black, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
fun EmptyStateView(onScan: () -> Unit, onManual: () -> Unit, onSync: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Icon(
            Icons.Default.Fingerprint,
            null,
            tint = Color.DarkGray,
            modifier = Modifier.size(100.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Text("无活跃账户", color = Color.Gray, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Spacer(modifier = Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            WatchActionButton(Icons.Default.QrCodeScanner, onScan, MaterialTheme.colorScheme.primary)
            WatchActionButton(Icons.Default.Edit, onManual, Color.DarkGray)
            WatchActionButton(Icons.Default.Sync, onSync, Color.DarkGray)
        }
    }
}

@Composable
fun WatchActionButton(icon: ImageVector, onClick: () -> Unit, color: Color) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .background(color.copy(alpha = 0.2f), CircleShape)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OtpDetailView(acc: OtpAccount, time: Long, onDelete: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val otp = remember(acc.secret, time) {
        try { TotpUtils.generateTotp(acc.secret) } catch (e: Exception) { "------" }
    }
    
    val secondsElapsed = (time / 1000 % 30).toFloat()
    val progress by animateFloatAsState(
        targetValue = 1f - (secondsElapsed / 30f),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "timer_progress"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .combinedClickable(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(otp))
                    vibrateFeedback(context)
                },
                onLongClick = { onDelete() }
            )
    ) {
        Text(
            acc.issuer.ifEmpty { "账户" }.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Text(
            otp.chunked(3).joinToString(" "),
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                fontSize = 48.sp
            ),
            color = Color.White
        )
        
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 12.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(52.dp),
                color = if (progress < 0.2f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                strokeWidth = 6.dp,
                trackColor = Color.DarkGray.copy(alpha = 0.3f)
            )
            Text(
                "${(progress * 30).toInt()}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                color = Color.White
            )
        }
        
        Text(
            "长按删除",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            color = Color.DarkGray,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
fun QrCodeScannerView(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasScanned by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var cameraProvider: ProcessCameraProvider? = null
        var launchJob: kotlinx.coroutines.Job? = null

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Cancel previous job if any
                launchJob?.cancel()
                cameraProvider?.unbindAll()
                
                launchJob = coroutineScope.launch {
                    // Delay to ensure the app is fully in the foreground, bypassing AppOps issues on Android 5-10
                    delay(500)
                    try {
                        val provider = cameraProviderFuture.get()
                        cameraProvider = provider
                        
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            return@launch
                        }

                        // Use a standard low resolution to avoid crash on weird watch screen ratios
                        val preview = Preview.Builder()
                            .setTargetResolution(Size(640, 480))
                            .build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                        val scanner = BarcodeScanning.getClient()
                        val analysis = ImageAnalysis.Builder()
                            .setTargetResolution(Size(640, 480))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                            processImageProxy(scanner, imageProxy) { result ->
                                if (!hasScanned) {
                                    hasScanned = true
                                    vibrateFeedback(context)
                                    onScanned(result)
                                }
                            }
                        }

                        // Try to find ANY available camera
                        val availableCameras = provider.availableCameraInfos
                        val cameraSelector = if (availableCameras.isNotEmpty()) {
                            // Find back camera, or front, or just the first one
                            when {
                                provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                                provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> CameraSelector.Builder().addCameraFilter { _ -> listOf(availableCameras.first()) }.build()
                            }
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                        // Retry binding in case of lingering AppOps issue
                        var retries = 3
                        while (retries > 0) {
                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    analysis
                                )
                                break // Success
                            } catch (e: Exception) {
                                e.printStackTrace()
                                retries--
                                delay(1000)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                launchJob?.cancel()
                cameraProvider?.unbindAll()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            launchJob?.cancel()
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        ScanningOverlay()
        
        Text(
            "请对准二维码",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
fun ScanningOverlay() {
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val lineY by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "line_pos"
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val frameSize = size.minDimension * 0.85f // Enlarged frame
        val left = (width - frameSize) / 2
        val top = (height - frameSize) / 2
        val right = left + frameSize
        val bottom = top + frameSize

        // Background mask
        val backgroundPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, 0f)
            lineTo(width, 0f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
            
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        
        drawPath(
            path = backgroundPath,
            color = Color.Black.copy(alpha = 0.5f),
            style = androidx.compose.ui.graphics.drawscope.Fill
        )

        // Frame corners - Larger and thicker
        val cornerLen = 32.dp.toPx()
        val stroke = 4.dp.toPx()
        val cornerColor = Color(0xFFD0BCFF)
        
        // TL
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLen, top), stroke)
        drawLine(cornerColor, Offset(left, top), Offset(left, top + cornerLen), stroke)
        // TR
        drawLine(cornerColor, Offset(right, top), Offset(right - cornerLen, top), stroke)
        drawLine(cornerColor, Offset(right, top), Offset(right, top + cornerLen), stroke)
        // BL
        drawLine(cornerColor, Offset(left, bottom), Offset(left + cornerLen, bottom), stroke)
        drawLine(cornerColor, Offset(left, bottom), Offset(left, bottom - cornerLen), stroke)
        // BR
        drawLine(cornerColor, Offset(right, bottom), Offset(right - cornerLen, bottom), stroke)
        drawLine(cornerColor, Offset(right, bottom), Offset(right, bottom - cornerLen), stroke)

        // Scanning line
        val scanLineY = top + (frameSize * lineY)
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color(0xFFD0BCFF), Color.Transparent)
            ),
            start = Offset(left + 12.dp.toPx(), scanLineY),
            end = Offset(right - 12.dp.toPx(), scanLineY),
            strokeWidth = 3.dp.toPx()
        )
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onSuccess: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.rawValue?.let { onSuccess(it) }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAddScreen(onDismiss: () -> Unit, onAdd: (String, String, String) -> Unit) {
    var lState by remember { mutableStateOf("") }
    var iState by remember { mutableStateOf("") }
    var sState by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手动添加", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(24.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = lState,
                onValueChange = { lState = it },
                label = { Text("显示名称", fontSize = 16.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = iState,
                onValueChange = { iState = it },
                label = { Text("发行者 (可选)", fontSize = 16.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge
            )
            OutlinedTextField(
                value = sState,
                onValueChange = { sState = it },
                label = { Text("密钥 (Secret Key)", fontSize = 16.sp) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { if (sState.isNotEmpty()) onAdd(lState, iState, sState) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = sState.isNotEmpty()
            ) {
                Text("保存账户", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavConfigScreen(
    config: WebDavConfig,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
    onBackup: (WebDavConfig, String) -> Unit,
    onRestore: (WebDavConfig, String) -> Unit
) {
    var uState by remember { mutableStateOf(config.serverUrl) }
    var nState by remember { mutableStateOf(config.username) }
    var pState by remember { mutableStateOf(config.password) }
    var eState by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 同步", fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(24.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = uState, onValueChange = { uState = it }, label = { Text("服务器 URL", fontSize = 14.sp) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = nState, onValueChange = { nState = it }, label = { Text("用户名", fontSize = 14.sp) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = pState, onValueChange = { pState = it }, label = { Text("应用密码", fontSize = 14.sp) }, modifier = Modifier.fillMaxWidth())
                }
            }
            
            OutlinedTextField(
                value = eState,
                onValueChange = { eState = it },
                label = { Text("加密主密码", fontSize = 14.sp) },
                placeholder = { Text("用于备份加密") },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = { onSave(config.copy(serverUrl = uState, username = nState, password = pState)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存配置", fontWeight = FontWeight.Bold) }
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onBackup(config.copy(serverUrl = uState, username = nState, password = pState), eState) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("备份", fontSize = 14.sp) }
                OutlinedButton(
                    onClick = { onRestore(config.copy(serverUrl = uState, username = nState, password = pState), eState) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("恢复", fontSize = 14.sp) }
            }
        }
    }
}

fun vibrateFeedback(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(50)
    }
}

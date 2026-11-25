package ar.com.cachito.assistant

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ar.com.cachito.assistant.model.*
import ar.com.cachito.assistant.viewmodel.CachitoViewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainActivity : ComponentActivity() {
    private val vm: CachitoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val darkColors = darkColorScheme(
                primary = Color(0xFF4C8DF6),
                secondary = Color(0xFF34D399),
                tertiary = Color(0xFFFBBF24),
                background = Color(0xFF0D1016),
                surface = Color(0xFF151A24),
                surfaceVariant = Color(0xFF1E2534),
                onBackground = Color(0xFFE8EAED),
                onSurface = Color(0xFFE8EAED)
            )
            MaterialTheme(colorScheme = darkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CachitoAppScreen(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CachitoAppScreen(vm: CachitoViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity

    val emotion by vm.emotion.collectAsState()
    val ticker by vm.ticker.collectAsState()
    val messages by vm.messages.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val settings by vm.settings.collectAsState()
    val nanoStatus by vm.nanoStatus.collectAsState()
    val localModelDownloaded by vm.localModelDownloaded.collectAsState()
    val localModelProgress by vm.localModelProgress.collectAsState()
    val localModelStatus by vm.localModelStatus.collectAsState()
    val isDownloadingLocalModel by vm.isDownloadingLocalModel.collectAsState()
    val openRouterModels by vm.availableOpenRouterModels.collectAsState()
    val googleModels by vm.availableGoogleModels.collectAsState()
    val groqModels by vm.availableGroqModels.collectAsState()
    val modelsFetchStatus by vm.modelsFetchStatus.collectAsState()
    val financeBook by vm.financeBook.collectAsState()
    val ragCount by vm.ragCount.collectAsState()
    val pendingDocName by vm.pendingDocName.collectAsState()
    val lastPdfUri by vm.lastScannedPdfUri.collectAsState()
    val detectedTargets by vm.detectedTargets.collectAsState()
    val referenceObjects by vm.referenceObjects.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showSettingsModal by remember { mutableStateOf(false) }
    var showFinanceModal by remember { mutableStateOf(false) }
    var showRagModal by remember { mutableStateOf(false) }
    var showVisionModal by remember { mutableStateOf(false) }
    var showHdScannerModal by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val gifImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .build()
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
            }
        }
    }

    val mlKitScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val pdfUri = scanResult?.pdf?.uri
            val pageUris = scanResult?.pages?.map { it.imageUri }.orEmpty()
            vm.handleMlKitScanResult(pdfUri, pageUris)
            Toast.makeText(context, "Escaneo Google ML Kit listo", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchGoogleMlKitScanner() {
        val act = activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(15)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(act)
            .addOnSuccessListener { intentSender ->
                mlKitScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { err ->
                Toast.makeText(context, "No se pudo abrir ML Kit: ${err.message}", Toast.LENGTH_SHORT).show()
            }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CachitoTopHeader(
                emotion = emotion,
                weather = ticker.weather,
                autoSpeak = settings.autoSpeak,
                gifImageLoader = gifImageLoader,
                onResetChat = {
                    vm.resetConversation()
                    Toast.makeText(context, "Conversación reiniciada", Toast.LENGTH_SHORT).show()
                },
                onToggleSpeak = { vm.toggleAutoSpeak() },
                onOpenFinance = { showFinanceModal = true },
                onOpenRag = { showRagModal = true },
                onOpenSettings = { showSettingsModal = true }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121620))
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (pendingDocName != null || lastPdfUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C273B))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "📄 ${pendingDocName ?: "PDF HD listo"}",
                            color = Color(0xFF93C5FD),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (lastPdfUri != null) {
                            TextButton(
                                onClick = {
                                    try {
                                        val uri = lastPdfUri!!
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newRawUri("Cachito_Escaneo_HD.pdf", uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        val chooser = Intent.createChooser(share, "Compartir / Guardar PDF HD").apply {
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(chooser)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error compartiendo PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Compartir PDF", fontSize = 11.sp, color = Color(0xFF34D399), maxLines = 1, softWrap = false)
                            }
                        }
                        IconButton(onClick = { vm.clearPendingAttachment() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Quitar", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showHdScannerModal = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = "Escáner HD / Google ML Kit", tint = Color(0xFF8AB4F8))
                    }
                    IconButton(onClick = { showVisionModal = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Radar Objetos En Vivo", tint = Color(0xFF34D399))
                    }
                    IconButton(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Hablale a Cachito...")
                            }
                            try { voiceLauncher.launch(intent) } catch (_: Exception) {}
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Dictar voz", tint = Color(0xFFFBBF24))
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Escribile a Cachito...", fontSize = 13.sp) },
                        maxLines = 4,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1A202E),
                            unfocusedContainerColor = Color(0xFF1A202E),
                            focusedBorderColor = Color(0xFF4C8DF6),
                            unfocusedBorderColor = Color(0xFF2B3448)
                        )
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    FilledIconButton(
                        onClick = {
                            val toSend = inputText
                            inputText = ""
                            vm.sendMessage(toSend)
                        },
                        enabled = !isSending && (inputText.isNotBlank() || pendingDocName != null),
                        modifier = Modifier.size(42.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF4C8DF6))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar", tint = Color.White)
                    }
                }

                NewsTickerBar(ticker = ticker, onRefresh = { vm.refreshLiveTicker() })
            }
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubbleItem(
                    msg = msg,
                    onSpeak = { vm.speakText(msg.text) },
                    onSaveRag = { vm.saveVerifiedSolution(msg.text.take(48), "Consulta móvil", msg.text) },
                    onOpenGemini = {
                        val lastUser = messages.lastOrNull { it.isUser }?.text ?: msg.text
                        vm.openPromptInGeminiApp(lastUser)
                    }
                )
            }
            if (isSending) {
                item {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF8AB4F8))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cachito está pensando...", color = Color(0xFF9AA6BE), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showHdScannerModal) {
        CameraXHdScannerDialog(
            onFinishPages = { files, analyzeNow ->
                showHdScannerModal = false
                vm.handleCameraXCapturedPages(files, analyzeNow)
                Toast.makeText(context, "PDF HD creado (${files.size} pág.)", Toast.LENGTH_SHORT).show()
            },
            onOpenGoogleMlKit = {
                showHdScannerModal = false
                launchGoogleMlKitScanner()
            },
            onDismiss = { showHdScannerModal = false }
        )
    }

    if (showFinanceModal) {
        FinanceDialog(
            book = financeBook,
            onDismiss = { showFinanceModal = false },
            onAddMovement = { k, amt, cur, cat, desc -> vm.addFinanceMovement(k, amt, cur, cat, desc) },
            onDeleteMovement = { vm.deleteFinanceMovement(it) },
            onAddGoal = { name, target, cur, saved -> vm.addFinanceGoal(name, target, cur, saved) },
            onAddGoalProgress = { id, delta -> vm.addProgressToGoal(id, delta) },
            onAskStrategy = {
                showFinanceModal = false
                vm.sendMessage("Analizá mis ingresos, gastos y objetivos en Cacho Contador y armame un plan concreto de ahorro.")
            }
        )
    }

    if (showRagModal) {
        RagDialog(
            ragCount = ragCount,
            solutions = vm.storage.getVerifiedSolutions(),
            onClearRag = { vm.clearRag() },
            onDismiss = { showRagModal = false }
        )
    }

    if (showVisionModal) {
        LiveVisionObjectsDialog(
            engineLabel = vm.visionHelper.activeVisionEngineLabel(),
            targets = detectedTargets,
            references = referenceObjects,
            onAnalyzeFrame = { bmp -> vm.analyzeLiveFrame(bmp) },
            onSaveReference = { label, bmp ->
                vm.saveReferenceObject(label, bmp)
                Toast.makeText(context, "Muestra '$label' guardada", Toast.LENGTH_SHORT).show()
            },
            onDeleteReference = { label -> vm.deleteReferenceObject(label) },
            onDismiss = { showVisionModal = false }
        )
    }

    if (showSettingsModal) {
        SettingsDialog(
            current = settings,
            nanoStatus = nanoStatus,
            localModelDownloaded = localModelDownloaded,
            localModelProgress = localModelProgress,
            localModelStatus = localModelStatus,
            isDownloadingLocalModel = isDownloadingLocalModel,
            openRouterModels = openRouterModels,
            googleModels = googleModels,
            groqModels = groqModels,
            modelsFetchStatus = modelsFetchStatus,
            onDownloadLocalModel = { url, name -> vm.downloadLocalOfflineModel(url, name) },
            onDeleteLocalModel = { vm.deleteLocalOfflineModel() },
            onFetchOpenRouterModels = { key -> vm.loadOpenRouterModelsFromApi(key) },
            onFetchGoogleModels = { key -> vm.loadGoogleModelsFromApi(key) },
            onFetchGroqModels = { key -> vm.loadGroqModelsFromApi(key) },
            onDownloadNano = { vm.downloadGeminiNano() },
            onSave = { updated ->
                vm.updateSettings(updated)
                showSettingsModal = false
            },
            onDismiss = { showSettingsModal = false }
        )
    }
}

private data class BackLensOption(
    val cameraId: String,
    val mpLabel: String,
    val maxPixels: Long
)

private fun applyCropAndPhotocopy(
    srcFile: File,
    cropTop: Float,
    cropBottom: Float,
    cropLeft: Float,
    cropRight: Float,
    photocopyMode: Boolean
): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(srcFile.absolutePath, bounds)
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (maxDim / sample > 2480) sample *= 2

    val orig = BitmapFactory.decodeFile(srcFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return srcFile

    val x = (orig.width * cropLeft).toInt().coerceIn(0, orig.width - 20)
    val y = (orig.height * cropTop).toInt().coerceIn(0, orig.height - 20)
    val w = (orig.width * (1f - cropLeft - cropRight)).toInt().coerceIn(20, orig.width - x)
    val h = (orig.height * (1f - cropTop - cropBottom)).toInt().coerceIn(20, orig.height - y)

    val cropped = Bitmap.createBitmap(orig, x, y, w, h)
    if (cropped !== orig) orig.recycle()

    val finalBmp = if (photocopyMode) {
        val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(out)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.9f
        val translate = (-0.5f * contrast + 0.5f) * 255f + 22f
        val highContrast = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        gray.postConcat(highContrast)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(gray)
        }
        canvas.drawBitmap(cropped, 0f, 0f, paint)
        cropped.recycle()
        out
    } else {
        cropped
    }

    val processedFile = File(srcFile.parentFile, "scan_proc_${System.currentTimeMillis()}.jpg")
    FileOutputStream(processedFile).use { fos ->
        finalBmp.compress(Bitmap.CompressFormat.JPEG, 92, fos)
    }
    finalBmp.recycle()
    return processedFile
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraXHdScannerDialog(
    onFinishPages: (List<File>, Boolean) -> Unit,
    onOpenGoogleMlKit: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var capturedPages by remember { mutableStateOf(emptyList<File>()) }

    var backLenses by remember { mutableStateOf(emptyList<BackLensOption>()) }
    var selectedLensIndex by remember { mutableStateOf(0) }

    var pendingRawFile by remember { mutableStateOf<File?>(null) }
    var pendingPreviewBmp by remember { mutableStateOf<Bitmap?>(null) }
    var cropTop by remember { mutableStateOf(0.05f) }
    var cropBottom by remember { mutableStateOf(0.05f) }
    var cropLeft by remember { mutableStateOf(0.05f) }
    var cropRight by remember { mutableStateOf(0.05f) }
    var photocopyMode by remember { mutableStateOf(true) }

    fun bindLens(provider: ProcessCameraProvider, pv: PreviewView, lensId: String?) {
        try {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }
            val imgCap = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            imageCapture = imgCap

            val selector = if (lensId != null) {
                CameraSelector.Builder()
                    .addCameraFilter { infos ->
                        val match = infos.filter {
                            try { Camera2CameraInfo.from(it).cameraId == lensId } catch (_: Exception) { false }
                        }
                        match.ifEmpty { infos }
                    }
                    .build()
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            provider.unbindAll()
            if (lifecycleOwner != null) {
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imgCap)
                if (torchOn) camera?.cameraControl?.enableTorch(true)
            }
        } catch (_: Exception) {}
    }

    LaunchedEffect(selectedLensIndex, cameraProviderRef, previewViewRef, pendingRawFile) {
        val prov = cameraProviderRef
        val pv = previewViewRef
        if (pendingRawFile == null && prov != null && pv != null) {
            val lensId = backLenses.getOrNull(selectedLensIndex)?.cameraId
            bindLens(prov, pv, lensId)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                camera?.cameraControl?.enableTorch(false)
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
        }
    }

    val currentLensLabel = backLenses.getOrNull(selectedLensIndex)?.let {
        "Lente ${selectedLensIndex + 1}/${backLenses.size} (${it.mpLabel})"
    } ?: "Lente Principal"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (pendingRawFile != null) "✂️ Recorte y Fotocopia" else "📸 Escáner HD",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (pendingRawFile == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = onOpenGoogleMlKit,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("✨ ML Kit", fontSize = 11.sp, color = Color(0xFF93C5FD), maxLines = 1, softWrap = false)
                        }
                        if (backLenses.size > 1) {
                            TextButton(
                                onClick = { selectedLensIndex = (selectedLensIndex + 1) % backLenses.size },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("🔄 Lente", fontSize = 11.sp, color = Color(0xFF34D399), maxLines = 1, softWrap = false)
                            }
                        }
                        IconButton(
                            onClick = {
                                torchOn = !torchOn
                                camera?.cameraControl?.enableTorch(torchOn)
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Linterna",
                                tint = if (torchOn) Color(0xFFFBBF24) else Color.LightGray
                            )
                        }
                    }
                }
            }
        },
        text = {
            if (pendingRawFile != null && pendingPreviewBmp != null) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                    ) {
                        Image(
                            bitmap = pendingPreviewBmp!!.asImageBitmap(),
                            contentDescription = "Vista previa recorte",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val l = cropLeft * size.width
                            val t = cropTop * size.height
                            val w = ((1f - cropLeft - cropRight).coerceAtLeast(0.1f)) * size.width
                            val h = ((1f - cropTop - cropBottom).coerceAtLeast(0.1f)) * size.height
                            drawRect(
                                color = Color(0xFF34D399),
                                topLeft = Offset(l, t),
                                size = Size(w, h),
                                style = Stroke(width = 6f)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Filtro Fotocopia (B/N nitidez)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Switch(checked = photocopyMode, onCheckedChange = { photocopyMode = it })
                    }

                    Text("Ajustar recuadro verde:", fontSize = 11.sp, color = Color(0xFF93C5FD))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Arr", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        Slider(value = cropTop, onValueChange = { cropTop = it.coerceAtMost(0.85f - cropBottom) }, valueRange = 0f..0.45f, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Aba", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        Slider(value = cropBottom, onValueChange = { cropBottom = it.coerceAtMost(0.85f - cropTop) }, valueRange = 0f..0.45f, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Izq", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        Slider(value = cropLeft, onValueChange = { cropLeft = it.coerceAtMost(0.85f - cropRight) }, valueRange = 0f..0.45f, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Der", fontSize = 10.sp, modifier = Modifier.width(26.dp))
                        Slider(value = cropRight, onValueChange = { cropRight = it.coerceAtMost(0.85f - cropLeft) }, valueRange = 0f..0.45f, modifier = Modifier.weight(1f))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                pendingRawFile = null
                                pendingPreviewBmp = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Repetir", fontSize = 11.sp)
                        }
                        Button(
                            onClick = {
                                val raw = pendingRawFile ?: return@Button
                                val finalFile = applyCropAndPhotocopy(raw, cropTop, cropBottom, cropLeft, cropRight, photocopyMode)
                                capturedPages = capturedPages + finalFile
                                pendingRawFile = null
                                pendingPreviewBmp = null
                                Toast.makeText(context, "✓ Página ${capturedPages.size} agregada", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                        ) {
                            Text("✓ Agregar Pág.", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "$currentLensLabel · Tocá para enfocar o usá ✨ ML Kit.",
                        fontSize = 11.sp,
                        color = Color(0xFF93C5FD)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    setOnTouchListener { v, event ->
                                        if (event.action == MotionEvent.ACTION_UP) {
                                            val point = meteringPointFactory.createPoint(event.x, event.y)
                                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                                                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                                .build()
                                            camera?.cameraControl?.startFocusAndMetering(action)
                                            v.performClick()
                                        }
                                        true
                                    }
                                }
                                previewViewRef = previewView
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    try {
                                        val provider = cameraProviderFuture.get()
                                        cameraProviderRef = provider

                                        val detected = provider.availableCameraInfos.mapNotNull { info ->
                                            try {
                                                val c2 = Camera2CameraInfo.from(info)
                                                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                                                if (facing != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
                                                val map = c2.getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                                                val sizes = map?.getOutputSizes(ImageFormat.JPEG).orEmpty()
                                                val maxPx = sizes.maxOfOrNull { it.width.toLong() * it.height.toLong() } ?: 0L
                                                val mp = ((maxPx / 1_000_000.0) + 0.5).toInt().coerceAtLeast(1)
                                                BackLensOption(c2.cameraId, "${mp}MP", maxPx)
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }.sortedByDescending { it.maxPixels }

                                        backLenses = detected
                                        selectedLensIndex = 0
                                        bindLens(provider, previewView, detected.firstOrNull()?.cameraId)
                                    } catch (_: Exception) {}
                                }, ContextCompat.getMainExecutor(ctx))
                                previewView
                            }
                        )

                        Text(
                            text = "Páginas: ${capturedPages.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .background(Color(0xB30F172A), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Button(
                        onClick = {
                            val cap = imageCapture ?: return@Button
                            if (capturing) return@Button
                            capturing = true
                            val photoFile = File(context.cacheDir, "scan_raw_${System.currentTimeMillis()}.jpg")
                            val outOpts = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                            cap.takePicture(
                                outOpts,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        capturing = false
                                        val prevOpts = BitmapFactory.Options().apply { inSampleSize = 4 }
                                        val bmp = BitmapFactory.decodeFile(photoFile.absolutePath, prevOpts)
                                        if (bmp != null) {
                                            cropTop = 0.05f
                                            cropBottom = 0.05f
                                            cropLeft = 0.05f
                                            cropRight = 0.05f
                                            pendingPreviewBmp = bmp
                                            pendingRawFile = photoFile
                                        } else {
                                            capturedPages = capturedPages + photoFile
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        capturing = false
                                        Toast.makeText(context, "Error capturando: ${exception.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        },
                        enabled = !capturing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C8DF6))
                    ) {
                        Text(if (capturing) "Capturando HD..." else "📷 Capturar Página HD", fontWeight = FontWeight.Bold)
                    }

                    if (capturedPages.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { onFinishPages(capturedPages, false) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Guardar PDF", fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = { onFinishPages(capturedPages, true) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                            ) {
                                Text("PDF + Leer", fontSize = 11.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun CachitoTopHeader(
    emotion: CachoEmotion,
    weather: WeatherInfo,
    autoSpeak: Boolean,
    gifImageLoader: ImageLoader,
    onResetChat: () -> Unit,
    onToggleSpeak: () -> Unit,
    onOpenFinance: () -> Unit,
    onOpenRag: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val gifRes = when (emotion) {
        CachoEmotion.NEUTRO -> R.drawable.cacho_neutro
        CachoEmotion.PENSANDO -> R.drawable.cacho_pensando
        CachoEmotion.ALEGRE -> R.drawable.cacho_alegre
        CachoEmotion.CONCENTRADO -> R.drawable.cacho_concentrado
        CachoEmotion.DUDA -> R.drawable.cacho_duda
        CachoEmotion.ALERTA -> R.drawable.cacho_alerta
    }

    val weatherEmoji = when {
        weather.weatherCode in listOf(95, 96, 99) -> "⛈️"
        weather.weatherCode in listOf(51, 53, 55, 61, 63, 65, 80, 81, 82) -> "🌧️"
        weather.weatherCode in listOf(2, 3, 45, 48) -> "⛅"
        else -> "☀️"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141924))
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(modifier = Modifier.size(66.dp)) {
                AsyncImage(
                    model = gifRes,
                    imageLoader = gifImageLoader,
                    contentDescription = "Don Cacho ${emotion.id}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color(0xFF4C8DF6), CircleShape)
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0xE6101520), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$weatherEmoji${weather.tempMax.toInt()}°",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (weather.alertLevel != WeatherAlertLevel.NONE) {
                        val triColor = if (weather.alertLevel == WeatherAlertLevel.RED) Color(0xFFEF4444) else Color(0xFFFBBF24)
                        Text(
                            text = " ▲",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = triColor,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Cachito",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "v2.44",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF8AB4F8),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .background(Color(0x264C8DF6), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
                Text(
                    text = if (weather.alertLevel != WeatherAlertLevel.NONE) weather.alertDescription else emotion.label,
                    fontSize = 11.sp,
                    color = if (weather.alertLevel == WeatherAlertLevel.RED) Color(0xFFF87171) else Color(0xFF9AA6BE),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onResetChat, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Reiniciar charla", tint = Color(0xFF93C5FD), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onOpenFinance, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Cacho Contador", tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onOpenRag, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Psychology, contentDescription = "Memoria RAG", tint = Color(0xFFC58AF9), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onToggleSpeak, modifier = Modifier.size(30.dp)) {
                Icon(
                    imageVector = if (autoSpeak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Voz",
                    tint = if (autoSpeak) Color(0xFFFBBF24) else Color(0xFF9AA6BE),
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Color(0xFFE8EAED), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun NewsTickerBar(ticker: TickerSnapshot, onRefresh: () -> Unit) {
    val scrollState = rememberScrollState()
    LaunchedEffect(ticker) {
        while (true) {
            if (scrollState.maxValue > 0) {
                scrollState.animateScrollTo(
                    value = scrollState.maxValue,
                    animationSpec = tween(durationMillis = 22000, easing = LinearEasing)
                )
                scrollState.scrollTo(0)
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0D13))
            .clickable { onRefresh() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "EN VIVO",
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.Black,
            modifier = Modifier
                .background(Color(0xFF34D399), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🌡️ ${ticker.weather.city}: ${ticker.weather.tempCurrent}°C (Máx ${ticker.weather.tempMax.toInt()}°C) · ",
                fontSize = 11.sp,
                color = Color(0xFFE2E8F0)
            )
            ticker.dollars.forEach { d ->
                Text(
                    text = "💵 ${d.name}: $${d.sell.toInt()} · ",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF93C5FD)
                )
            }
            if (ticker.marketOpen && ticker.markets.isNotEmpty()) {
                ticker.markets.forEach { m ->
                    val up = m.changePercent >= 0.0
                    val arrow = if (up) "▲" else "▼"
                    val col = if (up) Color(0xFF34D399) else Color(0xFFF87171)
                    Text(
                        text = "${m.label} ${"%.2f".format(Locale.US, m.price)} $arrow${"%.2f".format(Locale.US, kotlin.math.abs(m.changePercent))}% · ",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = col
                    )
                }
            } else {
                Text(
                    text = "📈 Bolsa NY/WTI (L-V 9–17 hs) · ",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
        }
    }
}

@Composable
fun ChatBubbleItem(
    msg: ChatMessage,
    onSpeak: () -> Unit,
    onSaveRag: () -> Unit,
    onOpenGemini: () -> Unit
) {
    val align = if (msg.isUser) Alignment.End else Alignment.Start
    val bg = if (msg.isUser) Color(0xFF233352) else Color(0xFF181D2A)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(bg, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            if (msg.attachmentLabel != null) {
                Text(
                    text = "📎 ${msg.attachmentLabel}",
                    fontSize = 11.sp,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(text = msg.text, color = Color(0xFFE8EAED), fontSize = 13.5.sp, lineHeight = 19.sp)

            if (!msg.isUser) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${msg.engineUsed} · ${msg.timestamp}",
                        fontSize = 10.sp,
                        color = Color(0xFF7C89A3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onSpeak, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text("🔊 Leer", fontSize = 11.sp, color = Color(0xFF8AB4F8), maxLines = 1, softWrap = false)
                        }
                        TextButton(onClick = onSaveRag, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text("★ Guardar", fontSize = 11.sp, color = Color(0xFF34D399), maxLines = 1, softWrap = false)
                        }
                    }
                }

                if (msg.showGeminiHandoff) {
                    Button(
                        onClick = onOpenGemini,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B4C7E))
                    ) {
                        Text("✨ Abrir en App Gemini (Cuenta del teléfono)", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun FinanceDialog(
    book: FinanceBook,
    onDismiss: () -> Unit,
    onAddMovement: (String, Double, String, String, String) -> Unit,
    onDeleteMovement: (String) -> Unit,
    onAddGoal: (String, Double, String, Double) -> Unit,
    onAddGoalProgress: (String, Double) -> Unit,
    onAskStrategy: () -> Unit
) {
    var kind by remember { mutableStateOf("gasto") }
    var amountTxt by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("ARS") }
    var category by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    var goalName by remember { mutableStateOf("") }
    var goalTarget by remember { mutableStateOf("") }
    var goalCur by remember { mutableStateOf("USD") }

    val ingArs = book.movements.filter { it.kind == "ingreso" && it.currency == "ARS" }.sumOf { it.amount }
    val gasArs = book.movements.filter { it.kind == "gasto" && it.currency == "ARS" }.sumOf { it.amount }
    val balArs = ingArs - gasArs

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💰 Cacho Contador (finanzas.json)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Balance ARS: $${"%.0f".format(balArs)} (Ing $${"%.0f".format(ingArs)} / Gas $${"%.0f".format(gasArs)}) · Dólar ref $${book.lastUsdRate.toInt()}",
                    fontSize = 12.sp,
                    color = Color(0xFF34D399)
                )

                Button(onClick = onAskStrategy, modifier = Modifier.fillMaxWidth()) {
                    Text("🧠 Pedir estrategia de ahorro a Cachito", fontSize = 12.sp)
                }

                HorizontalDivider()
                Text("Registrar movimiento", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = kind == "gasto", onClick = { kind = "gasto" }, label = { Text("Gasto (-)") })
                    FilterChip(selected = kind == "ingreso", onClick = { kind = "ingreso" }, label = { Text("Ingreso (+)") })
                    FilterChip(selected = currency == "ARS", onClick = { currency = "ARS" }, label = { Text("ARS") })
                    FilterChip(selected = currency == "USD", onClick = { currency = "USD" }, label = { Text("USD") })
                }
                OutlinedTextField(value = amountTxt, onValueChange = { amountTxt = it }, label = { Text("Monto") }, singleLine = true)
                OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Categoría (ej. Súper, Sueldo)") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Detalle") }, singleLine = true)
                Button(
                    onClick = {
                        val amt = amountTxt.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            onAddMovement(kind, amt, currency, category, desc)
                            amountTxt = ""
                            desc = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Guardar movimiento")
                }

                HorizontalDivider()
                Text("Nuevo objetivo (ARS / USD)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                OutlinedTextField(value = goalName, onValueChange = { goalName = it }, label = { Text("Nombre (ej. Notebook / Viaje)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = goalTarget,
                        onValueChange = { goalTarget = it },
                        label = { Text("Meta") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    FilterChip(selected = goalCur == "USD", onClick = { goalCur = "USD" }, label = { Text("USD") })
                    FilterChip(selected = goalCur == "ARS", onClick = { goalCur = "ARS" }, label = { Text("ARS") })
                }
                Button(
                    onClick = {
                        val t = goalTarget.toDoubleOrNull() ?: 0.0
                        if (goalName.isNotBlank() && t > 0) {
                            onAddGoal(goalName, t, goalCur, 0.0)
                            goalName = ""
                            goalTarget = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Agregar objetivo")
                }

                book.goals.forEach { g ->
                    val pct = if (g.targetAmount > 0) ((g.savedAmount / g.targetAmount) * 100).toInt().coerceIn(0, 100) else 0
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("🎯 ${g.name}: ${g.savedAmount}/${g.targetAmount} ${g.currency} ($pct%)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { onAddGoalProgress(g.id, if (g.currency == "USD") 50.0 else 50000.0) }) {
                                Text("+ Sumar aporte rápido", fontSize = 11.sp)
                            }
                        }
                    }
                }

                book.movements.take(8).forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${if (m.kind == "ingreso") "+" else "-"}${m.amount} ${m.currency} · ${m.category}",
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onDeleteMovement(m.id) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color(0xFFF87171), modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
fun RagDialog(
    ragCount: Int,
    solutions: List<RagSolution>,
    onClearRag: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🧠 Memoria RAG Local ($ragCount registros)", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onClearRag,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🗑️ Vaciar RAG (borrar historial y soluciones)", color = Color.White, fontSize = 12.sp)
                }

                if (solutions.isEmpty()) {
                    Text("No hay soluciones guardadas todavía. Tocá '★ Guardar' en cualquier respuesta de Cachito.", fontSize = 12.sp, color = Color.Gray)
                } else {
                    solutions.forEach { s ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text("★ ${s.title}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFFBBF24))
                                Text(s.solution.take(160), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
fun LiveVisionObjectsDialog(
    engineLabel: String,
    targets: List<DetectedTarget>,
    references: Map<String, java.io.File>,
    onAnalyzeFrame: suspend (Bitmap) -> List<DetectedTarget>,
    onSaveReference: (String, Bitmap) -> Unit,
    onDeleteReference: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = context as? LifecycleOwner
    var refName by remember { mutableStateOf("Mis llaves") }
    var latestFrame by remember { mutableStateOf<Bitmap?>(null) }
    val matchedHit = targets.firstOrNull { it.isReferenceMatch }
    val lastVibrateMs = remember { AtomicLong(0L) }
    val analyzing = remember { AtomicBoolean(false) }
    val lastAnalyzeMs = remember { AtomicLong(0L) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(matchedHit?.label) {
        if (matchedHit != null) {
            val now = System.currentTimeMillis()
            if (now - lastVibrateMs.get() > 1800L) {
                lastVibrateMs.set(now)
                try {
                    val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib?.vibrate(VibrationEffect.createOneShot(110, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib?.vibrate(110)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
            cameraExecutor.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📷 Radar de Objetos En Vivo", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motor: $engineLabel", fontSize = 11.sp, color = Color(0xFF34D399))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(270.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val previewView = PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                            }
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                try {
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }
                                    val analysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()
                                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        val now = System.currentTimeMillis()
                                        if (now - lastAnalyzeMs.get() < 450L || !analyzing.compareAndSet(false, true)) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }
                                        lastAnalyzeMs.set(now)
                                        try {
                                            val rawBmp = imageProxy.toBitmap()
                                            val rot = imageProxy.imageInfo.rotationDegrees
                                            val rotatedBmp = if (rot != 0) {
                                                val m = Matrix().apply { postRotate(rot.toFloat()) }
                                                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
                                            } else rawBmp
                                            latestFrame = rotatedBmp
                                            CoroutineScope(Dispatchers.Default).launch {
                                                try {
                                                    onAnalyzeFrame(rotatedBmp)
                                                } finally {
                                                    analyzing.set(false)
                                                }
                                            }
                                        } catch (_: Exception) {
                                            analyzing.set(false)
                                        } finally {
                                            imageProxy.close()
                                        }
                                    }
                                    cameraProvider.unbindAll()
                                    if (lifecycleOwner != null) {
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            analysis
                                        )
                                    }
                                } catch (_: Exception) {}
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        }
                    )

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        targets.forEach { t ->
                            val col = if (t.isReferenceMatch) Color(0xFF22C55E) else Color(0xFFFBBF24)
                            val strokeW = if (t.isReferenceMatch) 8f else 4f
                            drawRect(
                                color = col,
                                topLeft = Offset(t.leftRatio * size.width, t.topRatio * size.height),
                                size = Size((t.rightRatio - t.leftRatio) * size.width, (t.bottomRatio - t.topRatio) * size.height),
                                style = Stroke(width = strokeW)
                            )
                        }
                    }

                    if (matchedHit != null) {
                        Text(
                            text = "🔑 ¡ENCONTRADO: ${matchedHit.label} (${(matchedHit.confidence * 100).toInt()}%)!",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp)
                                .background(Color(0xFF22C55E), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Text(
                    text = "En cámara (${targets.size}): " + if (targets.isEmpty()) "Apuntá al objeto..." else targets.joinToString { "${it.label} (${(it.confidence * 100).toInt()}%)" },
                    fontSize = 11.5.sp,
                    color = Color(0xFFE2E8F0)
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = refName,
                        onValueChange = { refName = it },
                        label = { Text("Nombre muestra") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = { latestFrame?.let { onSaveReference(refName, it) } },
                        enabled = latestFrame != null
                    ) {
                        Text("📸 Guardar", fontSize = 11.sp)
                    }
                }

                HorizontalDivider()
                Text("Muestras entrenadas (${references.size}):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                references.keys.forEach { key ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔑 $key", fontSize = 12.sp)
                        IconButton(onClick = { onDeleteReference(key) }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = Color(0xFFF87171), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
fun SettingsDialog(
    current: AppSettings,
    nanoStatus: ar.com.cachito.assistant.ai.NanoAvailability,
    localModelDownloaded: Boolean,
    localModelProgress: Float,
    localModelStatus: String,
    isDownloadingLocalModel: Boolean,
    openRouterModels: List<String>,
    googleModels: List<String>,
    groqModels: List<String>,
    modelsFetchStatus: String,
    onDownloadLocalModel: (String, String) -> Unit,
    onDeleteLocalModel: () -> Unit,
    onFetchOpenRouterModels: (String) -> Unit,
    onFetchGoogleModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onDownloadNano: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedEngine by remember { mutableStateOf(current.preferredEngine) }
    var localModelUrl by remember { mutableStateOf(current.localModelUrl) }
    var localModelName by remember { mutableStateOf(current.localModelName) }
    var openRouterKey by remember { mutableStateOf(current.openRouterApiKey) }
    var openRouterModel by remember { mutableStateOf(current.openRouterModel) }
    var groqKey by remember { mutableStateOf(current.groqApiKey) }
    var groqModel by remember { mutableStateOf(current.groqModel) }
    var googleKey by remember { mutableStateOf(current.googleApiKey) }
    var googleModel by remember { mutableStateOf(current.googleModel) }
    var city by remember { mutableStateOf(current.city) }
    var showKeys by remember { mutableStateOf(false) }

    val keyTransform = if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
    val visibleEngines = remember(nanoStatus.isSupportedByHardware) {
        AiEngineMode.entries.filter { it != AiEngineMode.GEMINI_NANO || nanoStatus.isSupportedByHardware }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("⚙️ Ajustes Cachito v2.44", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showKeys = !showKeys }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = if (showKeys) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Mostrar u ocultar claves",
                        tint = Color(0xFF93C5FD),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔒 API Keys cifradas (AES-256) · Respaldo automático entre motores", fontSize = 10.5.sp, color = Color(0xFF34D399))

                Text("Motor IA Preferido:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                visibleEngines.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedEngine = mode }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedEngine == mode, onClick = { selectedEngine = mode })
                        Text(mode.label, fontSize = 12.sp)
                    }
                }

                HorizontalDivider()
                Text("🧠 IA Local Offline (LiteRT GPU/CPU + Web)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFFC58AF9))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(localModelStatus, fontSize = 11.sp, color = if (localModelDownloaded) Color(0xFF34D399) else Color(0xFFE2E8F0))
                        if (isDownloadingLocalModel) {
                            LinearProgressIndicator(progress = { localModelProgress }, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(
                            value = localModelName,
                            onValueChange = { localModelName = it },
                            label = { Text("Archivo (.task)") },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                        )
                        OutlinedTextField(
                            value = localModelUrl,
                            onValueChange = { localModelUrl = it },
                            label = { Text("URL descarga directa (.task)") },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onDownloadLocalModel(localModelUrl, localModelName) },
                                enabled = !isDownloadingLocalModel,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (localModelDownloaded) "↻ Redescargar" else "⬇️ Descargar", fontSize = 11.sp)
                            }
                            if (localModelDownloaded && !isDownloadingLocalModel) {
                                OutlinedButton(onClick = onDeleteLocalModel) {
                                    Text("🗑️", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("OpenRouter (Solo Gratis y Baratos < $0.50/M)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF34D399))
                OutlinedTextField(
                    value = openRouterKey,
                    onValueChange = { openRouterKey = it },
                    label = { Text("OpenRouter Key (sk-or-v1-...)") },
                    visualTransformation = keyTransform,
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = openRouterModel,
                        onValueChange = { openRouterModel = it },
                        label = { Text("Modelo OpenRouter") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = { onFetchOpenRouterModels(openRouterKey) }) {
                        Text("↻ Ver", fontSize = 11.sp)
                    }
                }
                if (openRouterModels.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        openRouterModels.forEach { mod ->
                            FilterChip(
                                selected = openRouterModel == mod,
                                onClick = { openRouterModel = mod },
                                label = { Text(mod, fontSize = 10.sp) }
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text("Google AI Studio (API Key + Search)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF93C5FD))
                OutlinedTextField(
                    value = googleKey,
                    onValueChange = { googleKey = it },
                    label = { Text("Google API Key") },
                    visualTransformation = keyTransform,
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = googleModel,
                        onValueChange = { googleModel = it },
                        label = { Text("Modelo Google") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = { onFetchGoogleModels(googleKey) }) {
                        Text("↻ Ver", fontSize = 11.sp)
                    }
                }
                if (googleModels.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        googleModels.forEach { mod ->
                            FilterChip(
                                selected = googleModel == mod,
                                onClick = { googleModel = mod },
                                label = { Text(mod, fontSize = 10.sp) }
                            )
                        }
                    }
                }

                HorizontalDivider()
                Text("Groq Cloud Gratis (console.groq.com)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFFFBBF24))
                OutlinedTextField(
                    value = groqKey,
                    onValueChange = { groqKey = it },
                    label = { Text("Groq API Key (gsk_...)") },
                    visualTransformation = keyTransform,
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = groqModel,
                        onValueChange = { groqModel = it },
                        label = { Text("Modelo Groq") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(onClick = { onFetchGroqModels(groqKey) }) {
                        Text("↻ Ver", fontSize = 11.sp)
                    }
                }
                if (groqModels.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        groqModels.forEach { mod ->
                            FilterChip(
                                selected = groqModel == mod,
                                onClick = { groqModel = mod },
                                label = { Text(mod, fontSize = 10.sp) }
                            )
                        }
                    }
                }

                if (modelsFetchStatus.isNotBlank()) {
                    Text(modelsFetchStatus, fontSize = 11.sp, color = Color(0xFF93C5FD))
                }

                HorizontalDivider()
                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("Ciudad Clima") }, singleLine = true)

                if (nanoStatus.isSupportedByHardware) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Gemini Nano Offline (AICore):", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text(nanoStatus.label, fontSize = 10.sp, color = Color.Gray)
                            if (!nanoStatus.isReady && nanoStatus.statusCode == 1) {
                                TextButton(onClick = onDownloadNano) {
                                    Text("⬇️ Descargar Gemini Nano", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    current.copy(
                        preferredEngine = selectedEngine,
                        localModelUrl = localModelUrl,
                        localModelName = localModelName,
                        openRouterApiKey = openRouterKey,
                        openRouterModel = openRouterModel,
                        groqApiKey = groqKey,
                        groqModel = groqModel,
                        googleApiKey = googleKey,
                        googleModel = googleModel,
                        city = city
                    )
                )
            }) {
                Text("Guardar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

package ar.com.cachito.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ar.com.cachito.assistant.model.*
import ar.com.cachito.assistant.viewmodel.CachitoViewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.util.Locale

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
    val googleModels by vm.availableGoogleModels.collectAsState()
    val groqModels by vm.availableGroqModels.collectAsState()
    val modelsFetchStatus by vm.modelsFetchStatus.collectAsState()
    val financeBook by vm.financeBook.collectAsState()
    val ragCount by vm.ragCount.collectAsState()
    val pendingDocName by vm.pendingDocName.collectAsState()
    val lastPdfUri by vm.lastScannedPdfUri.collectAsState()
    val detectedTargets by vm.detectedTargets.collectAsState()
    val referenceObjects: Map<String, java.io.File> by vm.referenceObjects.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showSettingsModal by remember { mutableStateOf(false) }
    var showFinanceModal by remember { mutableStateOf(false) }
    var showRagModal by remember { mutableStateOf(false) }
    var showVisionModal by remember { mutableStateOf(false) }
    var visionBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    val scannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
            val pdfUri = scanResult?.pdf?.uri
            val pageUris = scanResult?.pages?.map { it.imageUri } ?: emptyList()
            vm.handleScannedDocument(pdfUri, pageUris, sendToCachito = false)
        }
    }

    val cameraPreviewLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp ->
        if (bmp != null) {
            visionBitmap = bmp
            vm.analyzeCameraImageForObjects(bmp)
            showVisionModal = true
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    fun launchDocumentScanner() {
        val act = activity ?: return
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts)
            .getStartScanIntent(act)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
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
                onToggleSpeak = { vm.toggleAutoSpeak() },
                onOpenFinance = { showFinanceModal = true },
                onOpenRag = { showRagModal = true },
                onOpenSettings = { showSettingsModal = true }
            )
        },
        bottomBar = {
            // imePadding() + navigationBarsPadding() asegura que el teclado suba la barra sin tapar lo que escribís
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
                            text = "📄 ${pendingDocName ?: "PDF Multipágina listo"}",
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
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, lastPdfUri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Compartir / Guardar PDF"))
                                    } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Compartir PDF", fontSize = 11.sp, color = Color(0xFF34D399))
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
                    IconButton(onClick = { launchDocumentScanner() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.DocumentScanner, contentDescription = "Escanear PDF", tint = Color(0xFF8AB4F8))
                    }
                    IconButton(onClick = { showVisionModal = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "YOLO26 Objetos", tint = Color(0xFF34D399))
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
        VisionObjectsDialog(
            engineLabel = vm.visionHelper.activeVisionEngineLabel(),
            bitmap = visionBitmap,
            targets = detectedTargets,
            references = referenceObjects,
            onTakePhoto = { cameraPreviewLauncher.launch(null) },
            onSaveReference = { label, bmp -> vm.saveReferenceObject(label, bmp) },
            onDeleteReference = { label -> vm.deleteReferenceObject(label) },
            onDismiss = { showVisionModal = false }
        )
    }

    if (showSettingsModal) {
        SettingsDialog(
            current = settings,
            nanoStatus = nanoStatus,
            googleModels = googleModels,
            groqModels = groqModels,
            modelsFetchStatus = modelsFetchStatus,
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

@Composable
fun CachitoTopHeader(
    emotion: CachoEmotion,
    weather: WeatherInfo,
    autoSpeak: Boolean,
    gifImageLoader: ImageLoader,
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
                        color = Color.White
                    )
                    if (weather.alertLevel != WeatherAlertLevel.NONE) {
                        val triColor = if (weather.alertLevel == WeatherAlertLevel.RED) Color(0xFFEF4444) else Color(0xFFFBBF24)
                        Text(
                            text = " ▲",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = triColor
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
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "v2.1",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF8AB4F8),
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
            IconButton(onClick = onOpenFinance, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Cacho Contador", tint = Color(0xFF34D399), modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onOpenRag, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Psychology, contentDescription = "Memoria RAG", tint = Color(0xFFC58AF9), modifier = Modifier.size(19.dp))
            }
            IconButton(onClick = onToggleSpeak, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = if (autoSpeak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "Voz",
                    tint = if (autoSpeak) Color(0xFFFBBF24) else Color(0xFF9AA6BE),
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Ajustes", tint = Color(0xFFE8EAED), modifier = Modifier.size(19.dp))
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
                        color = Color(0xFF7C89A3)
                    )
                    Row {
                        TextButton(onClick = onSpeak, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text("🔊 Leer", fontSize = 11.sp, color = Color(0xFF8AB4F8))
                        }
                        TextButton(onClick = onSaveRag, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text("★ Guardar", fontSize = 11.sp, color = Color(0xFF34D399))
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
fun VisionObjectsDialog(
    engineLabel: String,
    bitmap: Bitmap?,
    targets: List<DetectedTarget>,
    references: Map<String, java.io.File>,
    onTakePhoto: () -> Unit,
    onSaveReference: (String, Bitmap) -> Unit,
    onDeleteReference: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var refName by remember { mutableStateOf("Mis llaves") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📷 Buscador YOLO26 / Pertenencias", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motor activo: $engineLabel", fontSize = 11.sp, color = Color(0xFF34D399))

                Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
                    Text("📸 Sacar foto para contar / buscar objetos", fontSize = 12.sp)
                }

                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(210.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captura analizada",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            targets.forEach { t ->
                                val col = if (t.isReferenceMatch) Color(0xFF34D399) else Color(0xFFFBBF24)
                                drawRect(
                                    color = col,
                                    topLeft = Offset(t.leftRatio * size.width, t.topRatio * size.height),
                                    size = Size((t.rightRatio - t.leftRatio) * size.width, (t.bottomRatio - t.topRatio) * size.height),
                                    style = Stroke(width = 4f)
                                )
                            }
                        }
                    }

                    Text("Detectados (${targets.size}): " + if (targets.isEmpty()) "Ninguno" else targets.joinToString { "${it.label} (${(it.confidence * 100).toInt()}%)" }, fontSize = 12.sp)

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = refName,
                            onValueChange = { refName = it },
                            label = { Text("Guardar foto como muestra") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(onClick = { onSaveReference(refName, bitmap) }) {
                            Text("Guardar")
                        }
                    }
                }

                HorizontalDivider()
                Text("Fotos de muestra guardadas (${references.size}):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
    googleModels: List<String>,
    groqModels: List<String>,
    modelsFetchStatus: String,
    onFetchGoogleModels: (String) -> Unit,
    onFetchGroqModels: (String) -> Unit,
    onDownloadNano: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedEngine by remember { mutableStateOf(current.preferredEngine) }
    var groqKey by remember { mutableStateOf(current.groqApiKey) }
    var groqModel by remember { mutableStateOf(current.groqModel) }
    var googleKey by remember { mutableStateOf(current.googleApiKey) }
    var googleModel by remember { mutableStateOf(current.googleModel) }
    var city by remember { mutableStateOf(current.city) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚙️ Ajustes Cachito v2.1", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Motor IA Preferido:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                AiEngineMode.entries.forEach { mode ->
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
                Text("Google AI Studio (API Key AQ. / AIza)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color(0xFF93C5FD))
                OutlinedTextField(value = googleKey, onValueChange = { googleKey = it }, label = { Text("Google API Key") }, singleLine = true)
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
                    Text("Tocá un modelo disponible de tu cuenta:", fontSize = 11.sp, color = Color(0xFF34D399))
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
                OutlinedTextField(value = groqKey, onValueChange = { groqKey = it }, label = { Text("Groq API Key (gsk_...)") }, singleLine = true)
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
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    current.copy(
                        preferredEngine = selectedEngine,
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


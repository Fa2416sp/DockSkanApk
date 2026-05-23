package com.example

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.example.data.AppDatabase
import com.example.data.DocumentDao
import com.example.data.DocumentEntity
import com.example.network.GeminiApiClient
import com.example.ui.theme.MyApplicationTheme
import com.example.util.ExportUtils
import com.example.util.ImageUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed class Screen {
    object Home : Screen()
    object CameraCapture : Screen()
    data class CropFilter(val tempImagePath: String) : Screen()
    data class DocumentDetail(val documentId: Long) : Screen()
}

class MainActivity : ComponentActivity() {
    private lateinit var database: AppDatabase
    private lateinit var documentDao: DocumentDao
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        database = AppDatabase.getDatabase(this)
        documentDao = database.documentDao()
        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            MyApplicationTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                Crossfade(targetState = currentScreen, label = "ScreenTransition") { screen ->
                    when (screen) {
                        is Screen.Home -> HomeScreen(
                            documentDao = documentDao,
                            onNavigateToCamera = { currentScreen = Screen.CameraCapture },
                            onNavigateToDetail = { currentScreen = Screen.DocumentDetail(it) },
                            onImageImported = { uri ->
                                val tempFile = copyUriToTempFile(this@MainActivity, uri)
                                if (tempFile != null) {
                                    currentScreen = Screen.CropFilter(tempFile.absolutePath)
                                } else {
                                    Toast.makeText(this@MainActivity, "Ошибка открытия файла", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        is Screen.CameraCapture -> CameraCaptureScreen(
                            onImageCaptured = { file ->
                                currentScreen = Screen.CropFilter(file.absolutePath)
                            },
                            onBack = { currentScreen = Screen.Home },
                            cameraExecutor = cameraExecutor
                        )
                        is Screen.CropFilter -> CropFilterScreen(
                            tempImagePath = screen.tempImagePath,
                            onSave = { title, finalBitmap, selectedFilter ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val documentsDir = File(filesDir, "documents")
                                    if (!documentsDir.exists()) documentsDir.mkdirs()

                                    val documentFile = File(documentsDir, "doc_${System.currentTimeMillis()}.jpg")
                                    ImageUtils.saveBitmapToFile(finalBitmap, documentFile)

                                    // Save in DB (with ocrText initially loading/empty)
                                    val newDoc = DocumentEntity(
                                        title = title,
                                        filePath = documentFile.absolutePath,
                                        ocrText = "Распознавание текста идет в фоновом режиме...",
                                        timestamp = System.currentTimeMillis(),
                                        enhancedType = selectedFilter
                                    )
                                    val docId = documentDao.insertDocument(newDoc)

                                    // Initiate background Gemini OCR text recognition
                                    launchOCRTask(docId, documentFile, title)

                                    withContext(Dispatchers.Main) {
                                        currentScreen = Screen.DocumentDetail(docId)
                                    }
                                }
                            },
                            onBack = { currentScreen = Screen.Home }
                        )
                        is Screen.DocumentDetail -> DocumentDetailScreen(
                            documentId = screen.documentId,
                            documentDao = documentDao,
                            onBack = { currentScreen = Screen.Home },
                            onRetryOCR = { doc ->
                                launchOCRTask(doc.id, File(doc.filePath), doc.title)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun launchOCRTask(docId: Long, documentFile: File, title: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Convert to Base64
                val bitmap = BitmapFactory.decodeFile(documentFile.absolutePath)
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                val bytes = outputStream.toByteArray()
                val base64Image = Base64.encodeToString(bytes, Base64.DEFAULT)

                // Call Gemini
                val ocrResult = GeminiApiClient.recognizeText(base64Image)

                // Update Database
                val currentDoc = documentDao.getDocumentById(docId)
                if (currentDoc != null) {
                    documentDao.updateDocument(currentDoc.copy(ocrText = ocrResult))
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in Gemini OCR", e)
                val currentDoc = documentDao.getDocumentById(docId)
                if (currentDoc != null) {
                    documentDao.updateDocument(currentDoc.copy(ocrText = "Ошибка OCR: ${e.message}"))
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

// Helper to copy gallery URI file into app cache
fun copyUriToTempFile(context: Context, uri: Uri): File? {
    val tempFile = File(context.cacheDir, "picked_${System.currentTimeMillis()}.jpg")
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }
        tempFile
    } catch (e: Exception) {
        Log.e("MainActivity", "Error copying uri file", e)
        null
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    documentDao: DocumentDao,
    onNavigateToCamera: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onImageImported: (Uri) -> Unit
) {
    val context = LocalContext.current
    val documentsFlow = remember { documentDao.getAllDocuments() }
    val documents by documentsFlow.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }

    val filteredDocs = documents.filter {
        it.title.contains(searchQuery, ignoreCase = true) ||
                it.ocrText.contains(searchQuery, ignoreCase = true)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onImageImported(uri)
        }
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Scanner,
                            contentDescription = "Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "DocScan AI",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCamera,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = "Сканировать",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search & Import Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск документов и OCR...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search Icon") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(imageVector = Icons.Default.PhotoLibrary, contentDescription = "Import Gallery")
                }
            }

            if (filteredDocs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = "Empty scanning illustration",
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                modifier = Modifier.size(56.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "Ваша галерея сканов пуста" else "Ничего не найдено",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "Нажмите кнопку камеры, чтобы отсканировать свой первый документ" else "Попробуйте изменить поисковый запрос документов",
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp, start = 16.dp, end = 16.dp)
                ) {
                    items(filteredDocs, key = { it.id }) { doc ->
                        DocumentCard(doc = doc, onClick = { onNavigateToDetail(doc.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun DocumentCard(doc: DocumentEntity, onClick: () -> Unit) {
    val dateString = remember(doc.timestamp) {
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(doc.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail image
            AsyncImage(
                model = File(doc.filePath),
                contentDescription = "Скан ${doc.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.LightGray)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = doc.title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(6.dp))

                val shortPreviewText = remember(doc.ocrText) {
                    if (doc.ocrText.startsWith("Ошибка OCR") || doc.ocrText.startsWith("Распознавание")) {
                        doc.ocrText
                    } else {
                        doc.ocrText.replace("\n", " ").take(65) + if (doc.ocrText.length > 65) "..." else ""
                    }
                }

                Text(
                    text = shortPreviewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Открыть скан",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraCaptureScreen(
    onImageCaptured: (File) -> Unit,
    onBack: () -> Unit,
    cameraExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(permission = Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        cameraPermissionState.launchPermissionRequest()
    }

    if (cameraPermissionState.status.isGranted) {
        var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
        var isFlashOn by remember { mutableStateOf(false) }
        var cameraControl: CameraControl? by remember { mutableStateOf(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Android View binding to CameraX Preview
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        imageCapture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            val camera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                            cameraControl = camera.cameraControl
                        } catch (exc: Exception) {
                            Log.e("CameraCaptureScreen", "Camera binding failed", exc)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            // Scanning alignment grid lines
            DocScannerGridOverlay()

            // Header Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White
                    )
                }

                Text(
                    text = "Выровняйте лист документа",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )

                IconButton(
                    onClick = {
                        isFlashOn = !isFlashOn
                        cameraControl?.enableTorch(isFlashOn)
                    },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash Toggle",
                        tint = if (isFlashOn) Color.Yellow else Color.White
                    )
                }
            }

            // Bottom Shutter controls
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(bottom = 48.dp, top = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shutter Ring Button
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                val captureUseCase = imageCapture ?: return@clickable
                                val photoFile = File(
                                    context.cacheDir,
                                    "scan_${System.currentTimeMillis()}.jpg"
                                )

                                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                                captureUseCase.takePicture(
                                    outputOptions,
                                    cameraExecutor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            onImageCaptured(photoFile)
                                        }

                                        override fun onError(exc: ImageCaptureException) {
                                            Log.e("Camera", "Photo capture failed: ${exc.message}", exc)
                                        }
                                    }
                                )
                            }
                    )
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.NoPhotography,
                    contentDescription = "Camera blocks",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Требуется разрешение на камеру",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "DocScan AI необходима камера для захвата и распознавания бумажных документов.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { cameraPermissionState.launchPermissionRequest() }) {
                    Text("Предоставить доступ")
                }
            }
        }
    }
}

@Composable
fun DocScannerGridOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 120.dp)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
        // Subtle guideline corner brackets which look scan-focused
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
        ) {
            // Horizontal laser sweep bar
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .height(1.dp)
                    .background(Color.Green.copy(alpha = 0.3f))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropFilterScreen(
    tempImagePath: String,
    onSave: (String, Bitmap, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val originalBitmap = remember { BitmapFactory.decodeFile(tempImagePath) }
    
    var documentTitle by remember { 
        mutableStateOf("Скан_" + SimpleDateFormat("dd_MM_yyyy_HHmm", Locale.getDefault()).format(Date())) 
    }
    
    // Filters selection: "original", "enhance", "mono", "gray"
    var selectedFilter by remember { mutableStateOf("original") }
    
    // Process filtered bitmap reactive
    val filteredBitmap = remember(originalBitmap, selectedFilter) {
        ImageUtils.applyFilter(originalBitmap, selectedFilter)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Коррекция и Фильтр", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Button(
                        onClick = { onSave(documentTitle, filteredBitmap, selectedFilter) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = "Ready")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Готово")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Name Input Field
            OutlinedTextField(
                value = documentTitle,
                onValueChange = { documentTitle = it },
                label = { Text("Название документа") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Enhanced Image Preview Container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = filteredBitmap.asImageBitmap(),
                    contentDescription = "Фильтр превью",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Filters Carousel Selection Row
            Text(
                text = "Фильтры улучшения документа:",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                FilterSelectionItem(
                    title = "Оригинал",
                    isSelected = selectedFilter == "original",
                    onClick = { selectedFilter = "original" },
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))

                FilterSelectionItem(
                    title = "Улучшенный",
                    isSelected = selectedFilter == "enhance",
                    onClick = { selectedFilter = "enhance" },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilterSelectionItem(
                    title = "ЧБ Скан",
                    isSelected = selectedFilter == "mono",
                    onClick = { selectedFilter = "mono" },
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                FilterSelectionItem(
                    title = "Серый",
                    isSelected = selectedFilter == "gray",
                    onClick = { selectedFilter = "gray" },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun FilterSelectionItem(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    documentId: Long,
    documentDao: DocumentDao,
    onBack: () -> Unit,
    onRetryOCR: (DocumentEntity) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Track loaded Document item from DB
    var documentFlow = remember(documentId) { documentDao.getAllDocuments() }
    val documents by documentFlow.collectAsState(initial = emptyList())
    val document = documents.find { it.id == documentId }

    if (document == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var selectedTab by remember { mutableStateOf(0) } // 0: Image, 1: OCR Text
    var ocrTextEditable by remember { mutableStateOf("") }
    var isEditingText by remember { mutableStateOf(false) }

    // Synchronize loaded text to state initially or once scanned completes
    LaunchedEffect(document.ocrText) {
        ocrTextEditable = document.ocrText
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = document.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Title rename Dialog, Delete scanning
                    IconButton(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                documentDao.deleteDocument(document)
                                File(document.filePath).delete()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Документ удален", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Doc", tint = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Document View Type Selection Tabs
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Изображение", fontWeight = FontWeight.Bold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Распознанный текст", fontWeight = FontWeight.Bold) }
                )
            }

            // Central Area View
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (selectedTab == 0) {
                    // High quality Image viewer
                    AsyncImage(
                        model = File(document.filePath),
                        contentDescription = "Document Scan Preview",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    // OCR Text display / edit block
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Автоматический OCR c Gemini:",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyLarge
                                )

                                Row {
                                    if (isEditingText) {
                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    documentDao.updateDocument(document.copy(ocrText = ocrTextEditable))
                                                    withContext(Dispatchers.Main) {
                                                        isEditingText = false
                                                        Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        ) {
                                            Icon(imageVector = Icons.Default.Save, contentDescription = "Save edit", tint = Color.Green)
                                        }
                                    } else {
                                        IconButton(onClick = { isEditingText = true }) {
                                            Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit text")
                                        }
                                    }

                                    // Refresh OCR text manually
                                    IconButton(
                                        onClick = {
                                            onRetryOCR(document)
                                            Toast.makeText(context, "Запущено повторное распознавание...", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Retry OCR")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (document.ocrText.startsWith("Распознавание текста идет")) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "Сканирование и ИИ-распознавание текста с Gemini...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                if (isEditingText) {
                                    OutlinedTextField(
                                        value = ocrTextEditable,
                                        onValueChange = { ocrTextEditable = it },
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                                RoundedCornerShape(12.dp)
                                            )
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                            .padding(16.dp)
                                    ) {
                                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                                            item {
                                                Text(
                                                    text = ocrTextEditable,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = MaterialTheme.colorScheme.onSurface
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

            // Premium exports toolbar (PDF, PNG, TXT options)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = "Экспортировать документ:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // PDF Button
                        Button(
                            onClick = {
                                ExportUtils.shareScannedDocument(
                                    context = context,
                                    title = document.title,
                                    imagePath = document.filePath,
                                    ocrText = document.ocrText,
                                    format = "pdf"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = "PDF")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PDF")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // PNG Button
                        Button(
                            onClick = {
                                ExportUtils.shareScannedDocument(
                                    context = context,
                                    title = document.title,
                                    imagePath = document.filePath,
                                    ocrText = document.ocrText,
                                    format = "png"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Image, contentDescription = "PNG")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("PNG")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // TXT Button
                        Button(
                            onClick = {
                                ExportUtils.shareScannedDocument(
                                    context = context,
                                    title = document.title,
                                    imagePath = document.filePath,
                                    ocrText = document.ocrText,
                                    format = "txt"
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Description, contentDescription = "TXT")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("TXT")
                        }
                    }
                }
            }
        }
    }
}

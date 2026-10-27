package Code_SyS.nas_server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import Code_SyS.nas_server.ui.theme.LanDropTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Сканер QR на камере телефона: считывает адрес с экрана хоста и сразу
 * открывает удалённый просмотр, без браузера и внешних сканеров.
 */
class ScanActivity : ComponentActivity() {

    private var cameraGranted by mutableStateOf(false)

    /** Отказ пользователя: вместо бесконечного ожидания показываем объяснение. */
    private var cameraDenied by mutableStateOf(false)

    /** Android покажет диалог ещё раз; иначе остаётся только «Настройки». */
    private var canAskAgain by mutableStateOf(false)

    /** Уже нашли рабочую ссылку: вторые срабатывания игнорируются. */
    @Volatile
    private var handled = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (granted) {
            cameraDenied = false
        } else {
            // Отказ не закрывает экран: без объяснения и кнопки настроек пользователь
            // оставался без сканера и без понятной причины.
            cameraDenied = true
            canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraGranted = hasCameraPermission()
        if (!cameraGranted) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            LanDropTheme {
                when {
                    cameraGranted -> ScanCameraScreen(
                        lifecycleOwner = this,
                        onClose = { finish() },
                        onResult = { raw -> handleScan(raw) },
                    )

                    cameraDenied -> CameraDeniedScreen(
                        canAskAgain = canAskAgain,
                        onRequest = { requestCameraPermission() },
                        onOpenSettings = { openAppSettings() },
                        onClose = { finish() },
                    )

                    // Системный диалог ещё открыт: показываем ожидание, а не сканер.
                    else -> CameraWaitingScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Возврат из «Настроек»: разрешение могло появиться без системного диалога.
        if (!cameraGranted && hasCameraPermission()) {
            cameraGranted = true
            cameraDenied = false
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Повторный запрос — пока Android показывает системный диалог. */
    private fun requestCameraPermission() {
        cameraDenied = false
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    /** После отказа «больше не спрашивать» разрешение включается только в настройках. */
    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                )
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Разрешение включается в «Настройки → Приложения → LanDrop»",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun handleScan(raw: String) {
        if (handled || isFinishing) return
        val target = ShareLinks.parseScanResult(raw)
        if (target == null) {
            // Камера продолжает искать: пользователь мог навести на чужой код.
            Toast.makeText(this, "Это не адрес LanDrop", Toast.LENGTH_SHORT).show()
            return
        }

        handled = true
        RecentHosts.remember(this, target)
        startActivity(
            Intent(this, RemoteActivity::class.java)
                .setData(Uri.parse(target.deepLink))
        )
        finish()
    }
}

@Composable
private fun ScanCameraScreen(
    lifecycleOwner: LifecycleOwner,
    onClose: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    /** Камера не запустилась: показываем причину и кнопку повтора. */
    var failure by remember { mutableStateOf<String?>(null) }

    /** Растёт при «Повторить» — перезапускает привязку камеры. */
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(previewView, attempt) {
        val view = previewView ?: return@LaunchedEffect
        failure = null
        status = null
        val provider = try {
            context.awaitCameraProvider()
        } catch (e: Exception) {
            failure = "Камера недоступна"
            return@LaunchedEffect
        }

        val executor = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(
            executor,
            QrAnalyzer(
                onResult = { text -> view.post { onResult(text) } },
            )
        )

        try {
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(view.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            awaitCancellation()
        } catch (e: CancellationException) {
            // Выход со сканера или повтор — это не ошибка камеры.
            throw e
        } catch (_: Exception) {
            failure = "Не удалось включить камеру"
        } finally {
            provider.unbindAll()
            executor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { view -> previewView = view },
            modifier = Modifier.fillMaxSize(),
        )

        // Затемнение помогает рамке и подсказкам читаться на любом кадре.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .42f))
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(252.dp)
                .border(2.dp, Color.White.copy(alpha = .9f), RoundedCornerShape(26.dp))
        )

        ScanHeader(
            title = "Наведите на QR",
            subtitle = "Код на экране другого телефона",
            onClose = onClose,
        )

        if (failure == null) {
            Text(
                text = status ?: "Сканирование…",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = .8f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp, start = 24.dp, end = 24.dp),
            )
        }

        failure?.let { message ->
            CameraFailureCard(
                message = message,
                onRetry = { attempt++ },
                onClose = onClose,
            )
        }
    }
}

/**
 * Камера не запустилась: объясняем, что проверить, и даём повтор без выхода
 * из сканера — чаще всего камеру заняло другое приложение.
 */
@Composable
private fun BoxScope.CameraFailureCard(
    message: String,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val cardShape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .clip(cardShape)
            .background(Color(0xFF14171E))
            .border(1.dp, Color.White.copy(alpha = .14f), cardShape)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Камеру могло занять другое приложение, либо доступ к ней " +
                "запрещён в настройках телефона.",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = .74f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(
                text = "Повторить",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF7B9CFF),
            )
        }
        TextButton(onClick = onClose) {
            Text(
                text = "Закрыть",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = .6f),
            )
        }
    }
}

/** Шапка сканера и связанных с ним экранов: возврат назад и короткая подсказка. */
@Composable
private fun BoxScope.ScanHeader(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .border(1.dp, Color.White.copy(alpha = .25f), CircleShape)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Text("←", fontSize = 19.sp, color = Color.White)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = subtitle,
                fontSize = 11.5.sp,
                color = Color.White.copy(alpha = .72f),
            )
        }
    }
}

/** Ожидание ответа системного диалога разрешения. */
@Composable
private fun CameraWaitingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = "Запрос доступа к камере…",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = .8f),
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * Экран вместо сканера, когда доступ к камере не выдан: объясняет причину и
 * доводит до разрешения — повторным диалогом или настройками приложения.
 */
@Composable
private fun CameraDeniedScreen(
    canAskAgain: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val cardShape = RoundedCornerShape(20.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ScanHeader(
            title = "Нет доступа к камере",
            subtitle = "Разрешение нужно для сканера QR",
            onClose = onClose,
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(cardShape)
                .background(Color(0xFF14171E))
                .border(1.dp, Color.White.copy(alpha = .14f), cardShape)
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (canAskAgain) "Разрешить доступ к камере" else "Камера отключена в настройках",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (canAskAgain) {
                    "Без камеры телефон не считает QR с экрана другого телефона."
                } else {
                    "Разрешение отключено: включите «Камера» в разделе «Разрешения» и вернитесь назад."
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = .74f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                onClick = if (canAskAgain) onRequest else onOpenSettings,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(
                    text = if (canAskAgain) "Разрешить доступ" else "Открыть настройки",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7B9CFF),
                )
            }
            if (canAskAgain) {
                TextButton(onClick = onOpenSettings) {
                    Text(
                        text = "Открыть настройки",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = .6f),
                    )
                }
            }
        }
    }
}

/**
 * Декодирует QR из кадров камеры. Повтор одного и того же кода не спамит
 * колбэком: пока камера смотрит на тот же QR, результат не повторяется.
 */
private class QrAnalyzer(
    private val onResult: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastText: String? = null
    private var lastAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format != android.graphics.ImageFormat.YUV_420_888) return
            val luminance = luminanceOf(image) ?: return
            val text = QrScanner.decode(luminance, image.width, image.height) ?: return
            val now = SystemClock.elapsedRealtime()
            if (text != lastText || now - lastAt > DUPLICATE_WINDOW_MS) {
                lastText = text
                lastAt = now
                onResult(text)
            }
        } finally {
            image.close()
        }
    }

    /** Y-плоскость без учёта rowStride: ZXing ждёт плотный массив шириной image.width. */
    private fun luminanceOf(image: ImageProxy): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        if (rowStride <= 0) return null

        val width = image.width
        val height = image.height
        val output = ByteArray(width * height)
        val row = ByteArray(rowStride)

        for (y in 0 until height) {
            val available = buffer.remaining()
            if (available <= 0) break
            val toRead = minOf(rowStride, available)
            buffer.get(row, 0, toRead)
            var sourceIndex = 0
            var x = 0
            while (x < width && sourceIndex < toRead) {
                output[y * width + x] = row[sourceIndex]
                x++
                sourceIndex += pixelStride
            }
        }
        return output
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2_500L
    }
}

private suspend fun android.content.Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }

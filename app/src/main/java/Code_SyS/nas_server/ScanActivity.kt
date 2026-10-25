package Code_SyS.nas_server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    /** Уже нашли рабочую ссылку: вторые срабатывания игнорируются. */
    @Volatile
    private var handled = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) {
            Toast.makeText(this, "Без камеры QR не отсканировать", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        setContent {
            LanDropTheme {
                if (cameraGranted) {
                    ScanCameraScreen(
                        lifecycleOwner = this,
                        onClose = { finish() },
                        onResult = { raw -> handleScan(raw) },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
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
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(previewView) {
        val view = previewView ?: return@LaunchedEffect
        val provider = try {
            context.awaitCameraProvider()
        } catch (e: Exception) {
            status = "Камера недоступна"
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
        } catch (_: Exception) {
            status = "Не удалось включить камеру"
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
                    text = "Наведите на QR",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = "Код на экране другого телефона",
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = .72f),
                )
            }
        }

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

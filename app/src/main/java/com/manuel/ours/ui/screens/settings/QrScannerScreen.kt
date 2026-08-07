package com.manuel.ours.ui.screens.settings

import com.manuel.ours.ui.components.OursTopBar
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconButton
import com.manuel.ours.ui.components.OursIconView


import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import com.manuel.ours.ui.components.AccentButton
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.Space
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Scans the household invite QR.
 *
 * Decoding uses ZXing over CameraX frames rather than ML Kit. ML Kit's unbundled
 * barcode model downloads itself at runtime — which this app cannot do, having no
 * `INTERNET` permission — and the bundled variant adds several megabytes plus a Play
 * Services dependency. ZXing was already here for *generating* the QR, so the scanner
 * costs nothing extra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    Scaffold(
            // contentWindowInsets = WindowInsets(0): the NavHost already sits inside the
            // outer Scaffold's padding, so consuming system-bar insets again inset every
            // one of these screens twice — most visibly the full-bleed QR viewfinder.
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),containerColor = Ours.surface) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OursTopBar(title = "Scan", onBack = onBack)
                    Spacer(Modifier.height(18.dp))
                    OursIconView(
                        icon = OursIcon.Camera,
                        contentDescription = null,
                        tint = Ours.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = "Camera access is needed to scan your partner's code.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Ours.onSurfaceVariant,
                    )
                    AccentButton(
                        label = "Allow camera",
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                }
                return@Box
            }

            CameraScanner(
                onDecoded = onScanned,
                modifier = Modifier.fillMaxSize(),
            )

            // Back sits over the viewfinder rather than in a bar above it: the camera
            // wants the whole screen, and a title telling you what you are pointing at
            // is not worth the strip it costs.
            // Over the viewfinder rather than in a bar above it: the camera wants the whole
            // screen. Still a real target — white on a live preview is the hardest thing in the
            // app to aim at.
            OursIconButton(
                icon = OursIcon.Back,
                contentDescription = "Back",
                onClick = onBack,
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(Space.s2),
            )

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Point at the QR code in your partner's Settings screen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CameraScanner(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    // Latch: a QR sits in frame for many frames, and firing navigation on each one
    // would push a dozen screens onto the back stack.
    var alreadyDecoded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    // Dropping stale frames matters more than seeing every one: a
                    // backed-up queue makes the viewfinder lag behind the phone.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, QrAnalyzer { text ->
                        if (!alreadyDecoded) {
                            alreadyDecoded = true
                            ContextCompat.getMainExecutor(ctx).execute { onDecoded(text) }
                        }
                    }) }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

/**
 * Decodes the luminance plane straight out of the YUV frame.
 *
 * QR decoding only needs brightness, so the chroma planes are skipped entirely — no
 * colour conversion, no bitmap allocation per frame.
 */
private class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            )
        )
    }

    override fun analyze(image: ImageProxy) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

            val source = PlanarYUVLuminanceSource(
                bytes,
                image.planes[0].rowStride,
                image.height,
                0,
                0,
                image.width,
                image.height,
                false,
            )
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            onDecoded(result.text)
        } catch (_: NotFoundException) {
            // No QR in this frame — overwhelmingly the common case.
        } catch (_: Exception) {
            // A malformed frame must not kill the analyzer thread.
        } finally {
            reader.reset()
            image.close()
        }
    }
}

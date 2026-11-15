package com.raj.zcoderemote.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.raj.zcoderemote.link.RemoteLink
import java.util.concurrent.Executors

/** Live camera QR scanner used to capture the desktop's remote-control link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onLinkParsed: (RemoteLink) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var invalidQr by remember { mutableStateOf<String?>(null) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasPermission = it
        }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Scan QR code") }) },
    ) { innerPadding ->
        if (!hasPermission) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Camera permission is needed to scan the QR code shown by ZCode on your desktop.")
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera permission")
                }
                Button(onClick = onClose) { Text("Back") }
            }
            return@Scaffold
        }

        val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
        DisposableEffect(Unit) {
            onDispose { analysisExecutor.shutdown() }
        }

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            AndroidPreview(
                modifier = Modifier.fillMaxSize(),
                onQrText = { rawText ->
                    val link = rawText?.let { RemoteLink.parse(it) }
                    if (link != null) {
                        onLinkParsed(link)
                    } else if (rawText != null) {
                        invalidQr = "That QR code isn't a ZCode remote-control link."
                    }
                },
                lifecycleOwner = lifecycleOwner,
                executor = analysisExecutor,
            )
            invalidQr?.let { message ->
                Text(
                    message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AndroidPreview(
    onQrText: (String?) -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    executor: java.util.concurrent.Executor,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var handled by remember { mutableStateOf(false) }

    AndroidViewCompat(modifier = modifier) { previewView ->
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            val scanner = BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { imageProxy ->
                analyzeFrame(imageProxy, scanner) { text ->
                    if (text != null && !handled) {
                        handled = true
                        onQrText(text)
                    }
                }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (e: Exception) {
                // Emulators without a camera land here; paste flow still works.
            }
        }, ContextCompat.getMainExecutor(context))
    }
}

private fun analyzeFrame(
    imageProxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onResult: (String?) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(
        mediaImage,
        imageProxy.imageInfo.rotationDegrees,
    )
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            onResult(barcodes.firstOrNull()?.rawValue)
        }
        .addOnFailureListener { onResult(null) }
        .addOnCompleteListener { imageProxy.close() }
}

@Composable
private fun AndroidViewCompat(
    modifier: Modifier = Modifier,
    onPreviewView: (PreviewView) -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).also(onPreviewView)
        },
    )
}

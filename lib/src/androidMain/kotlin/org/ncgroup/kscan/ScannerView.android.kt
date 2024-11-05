package org.ncgroup.kscan

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun ScannerView(
    codeTypes: List<BarcodeFormat>,
    colors: ScannerColors,
    result: (BarcodeResult) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var barcodeAnalyzer by remember { mutableStateOf<BarcodeAnalyzer?>(null) }

    val cameraProviderFuture =
        remember {
            ProcessCameraProvider.getInstance(context)
                .get()
        }

    var camera: Camera? by remember { mutableStateOf(null) }
    var cameraControl: CameraControl? by remember { mutableStateOf(null) }

    var torchEnabled by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }

    val barcodes = remember { mutableSetOf<Barcode>() }
    var showBottomSheet by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) {
        camera?.cameraInfo?.torchState?.observe(lifecycleOwner) {
            torchEnabled = it == TorchState.ON
        }
    }

    LaunchedEffect(Unit) {
        camera?.cameraInfo?.zoomState?.observe(lifecycleOwner) {
            zoomRatio = it.zoomRatio
            maxZoomRatio = it.maxZoomRatio
        }
    }

    val frame = LocalDensity.current.run { 260.dp.toPx() }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->

                val previewView = PreviewView(context)
                val preview = Preview.Builder().build()
                val selector =
                    CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()

                preview.setSurfaceProvider(previewView.surfaceProvider)

                val imageAnalysis =
                    ImageAnalysis.Builder()
                        .setTargetResolution(Size(1200, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                imageAnalysis.setAnalyzer(
                    ContextCompat.getMainExecutor(context),
                    BarcodeAnalyzer(
                        camera = camera,
                        frame = frame,
                        codeTypes = codeTypes,
                        onSuccess = {
                            if (showBottomSheet) return@BarcodeAnalyzer
                            if (it.count() == 1) {
                                result(BarcodeResult.OnSuccess(it.first()))
                                barcodes.clear()
                            } else if (it.count() > 1) {
                                barcodes.addAll(it)
                                showBottomSheet = true
                            }
                        },
                        onFailed = { result(BarcodeResult.OnFailed(Exception(it))) },
                        onCanceled = { result(BarcodeResult.OnCanceled) },
                    ).also { barcodeAnalyzer = it },
                )

                camera =
                    bindCamera(
                        lifecycleOwner = lifecycleOwner,
                        cameraProviderFuture = cameraProviderFuture,
                        selector = selector,
                        preview = preview,
                        imageAnalysis = imageAnalysis,
                        result = result,
                        cameraControl = { cameraControl = it },
                    )

                previewView
            },
            onRelease = {
                cameraProviderFuture.unbind()
            },
        )

        ScannerUI(
            onCancel = { result(BarcodeResult.OnCanceled) },
            torchEnabled = torchEnabled,
            onTorchEnabled = { cameraControl?.enableTorch(it) },
            zoomRatio = zoomRatio,
            zoomRatioOnChange = { cameraControl?.setZoomRatio(it) },
            maxZoomRatio = maxZoomRatio,
            colors = colors,
        )

        if (showBottomSheet) {
            ScannerBarcodeSelectionBottomSheet(
                barcodes = barcodes.toList(),
                sheetState = sheetState,
                onDismissRequest = {
                    showBottomSheet = false
                    barcodes.clear()
                },
                result = {
                    result(it)
                    showBottomSheet = false
                    barcodes.clear()
                },
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            camera = null
            cameraControl = null
            barcodeAnalyzer = null
        }
    }
}

internal fun bindCamera(
    lifecycleOwner: LifecycleOwner,
    cameraProviderFuture: ProcessCameraProvider?,
    selector: CameraSelector,
    preview: Preview,
    imageAnalysis: ImageAnalysis,
    result: (BarcodeResult) -> Unit,
    cameraControl: (CameraControl?) -> Unit,
): Camera? {
    return runCatching {
        cameraProviderFuture?.unbindAll()
        cameraProviderFuture?.bindToLifecycle(
            lifecycleOwner,
            selector,
            preview,
            imageAnalysis,
        ).also {
            cameraControl(it?.cameraControl)
        }
    }.getOrElse {
        result(BarcodeResult.OnFailed(Exception(it)))
        null
    }
}

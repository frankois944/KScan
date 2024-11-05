package org.ncgroup.kscan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.hasTorch
import platform.AVFoundation.torchMode

@OptIn(ExperimentalForeignApi::class, ExperimentalMaterial3Api::class)
@Composable
actual fun ScannerView(
    codeTypes: List<BarcodeFormat>,
    result: (BarcodeResult) -> Unit,
) {
    var torchEnabled by remember { mutableStateOf(false) }
    var zoomRatio by remember { mutableStateOf(1f) }
    var maxZoomRatio by remember { mutableStateOf(1f) }
    val barcodes = remember { mutableSetOf<Barcode>() }
    var showBottomSheet by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()

    val captureDevice: AVCaptureDevice? =
        remember {
            AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        }

    if (captureDevice == null) return

    val frame = LocalDensity.current.run { 260.dp.toPx() }

    val cameraViewController =
        remember {
            CameraViewController(
                frame = frame,
                device = captureDevice,
                codeTypes = codeTypes,
                onBarcodeSuccess = { scannedBarcodes ->
                    if (showBottomSheet) return@CameraViewController
                    if (scannedBarcodes.count() == 1) {
                        result(BarcodeResult.OnSuccess(scannedBarcodes.first()))
                        barcodes.clear()
                    } else if (scannedBarcodes.count() > 1) {
                        barcodes.addAll(scannedBarcodes)
                        showBottomSheet = true
                    }
                },
                onBarcodeFailed = { error ->
                    result(BarcodeResult.OnFailed(error))
                },
                onBarcodeCanceled = {
                    result(BarcodeResult.OnCanceled)
                },
                onMaxZoomRatioAvailable = { maxRatio ->
                    maxZoomRatio = maxRatio
                },
            )
        }

    Box(modifier = Modifier.fillMaxSize()) {
        UIKitViewController(
            factory = { cameraViewController },
            modifier = Modifier.fillMaxSize(),
        )

        ScannerUI(
            onCancel = { result(BarcodeResult.OnCanceled) },
            torchEnabled = torchEnabled,
            onTorchEnabled = { enabled ->
                runCatching {
                    if (captureDevice.hasTorch) {
                        captureDevice.lockForConfiguration(null)
                        captureDevice.torchMode =
                            if (enabled) {
                                AVCaptureTorchModeOn
                            } else {
                                AVCaptureTorchModeOff
                            }
                        captureDevice.unlockForConfiguration()
                        torchEnabled = enabled
                    }
                }
            },
            zoomRatio = zoomRatio,
            zoomRatioOnChange = { ratio ->
                cameraViewController.setZoom(ratio)
                zoomRatio = ratio
            },
            maxZoomRatio = maxZoomRatio,
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
}
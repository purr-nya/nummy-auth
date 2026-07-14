package com.example.util

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer

class QrCodeAnalyzer(private val onQrCodeScanned: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
        )
        setHints(hints)
    }

    override fun analyze(image: ImageProxy) {
        try {
            val planes = image.planes
            if (planes.isEmpty()) {
                image.close()
                return
            }

            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val width = image.width
            val height = image.height

            // Pack the Y plane tightly into a width * height array to eliminate row stride padding
            val tightlyPackedY = ByteArray(width * height)
            val rowBuffer = ByteArray(rowStride)
            var offset = 0
            for (row in 0 until height) {
                yBuffer.position(row * rowStride)
                val bytesToRead = minOf(rowStride, yBuffer.remaining())
                yBuffer.get(rowBuffer, 0, bytesToRead)
                System.arraycopy(rowBuffer, 0, tightlyPackedY, offset, width)
                offset += width
            }

            val source = PlanarYUVLuminanceSource(
                tightlyPackedY, width, height, 0, 0, width, height, false
            )

            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decode(bitmap)
            onQrCodeScanned(result.text)
        } catch (e: NotFoundException) {
            // No QR code found in this frame, normal behavior
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            image.close()
        }
    }
}

package com.example.util

import android.graphics.*
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    fun applyFilter(src: Bitmap, filterType: String): Bitmap {
        return when (filterType.lowercase()) {
            "gray" -> toGrayscale(src)
            "mono" -> toBlackAndWhiteThreshold(src)
            "enhance" -> toDocumentEnhancement(src)
            else -> src
        }
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)
        
        val canvas = Canvas(dest)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)
        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    private fun toBlackAndWhiteThreshold(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        // Use a fast pixel-by-pixel thresholding for solid true-monochrome
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            
            // Calculate luminance
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            
            // Apply simple binarization threshold at 128 (middle point)
            val newColor = if (luminance > 128) Color.WHITE else Color.BLACK
            pixels[i] = newColor
        }
        
        dest.setPixels(pixels, 0, width, 0, 0, width, height)
        return dest
    }

    private fun toDocumentEnhancement(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val dest = Bitmap.createBitmap(width, height, src.config ?: Bitmap.Config.ARGB_8888)
        
        val canvas = Canvas(dest)
        val paint = Paint()
        
        // Boost contrast and white-point thresholding
        val scale = 2.0f
        val translate = -90f // removes grey shadows and whites the page
        
        val contrastMatrix = floatArrayOf(
            scale, 0f, 0f, 0f, translate,
            0f, scale, 0f, 0f, translate,
            0f, 0f, scale, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        )
        
        val matrix = ColorMatrix()
        matrix.setSaturation(0.2f) // keep a minor hint of original highlight tags/colors
        val finalMatrix = ColorMatrix().apply {
            set(contrastMatrix)
            postConcat(matrix)
        }
        
        val filter = ColorMatrixColorFilter(finalMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(src, 0f, 0f, paint)
        return dest
    }

    fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
    }
}

package com.example.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object ExportUtils {
    private const val TAG = "ExportUtils"

    fun shareScannedDocument(
        context: Context,
        title: String,
        imagePath: String,
        ocrText: String,
        format: String
    ) {
        val sanitizedTitle = title.replace("[^a-zA-Z0-9А-Яа-я]".toRegex(), "_").ifBlank { "scanned_doc" }
        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            Toast.makeText(context, "Изображение документа не найдено", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val shareFile = when (format.lowercase()) {
                "txt" -> createTxtFile(context, sanitizedTitle, ocrText)
                "png" -> createPngFile(context, sanitizedTitle, imageFile)
                "pdf" -> createPdfFile(context, sanitizedTitle, imageFile, ocrText)
                else -> null
            }

            if (shareFile != null && shareFile.exists()) {
                val mimeType = when (format.lowercase()) {
                    "txt" -> "text/plain"
                    "png" -> "image/png"
                    "pdf" -> "application/pdf"
                    else -> "*/*"
                }

                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "com.example.fileprovider",
                    shareFile
                )

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, "Экспортировано из DocScan AI")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(Intent.createChooser(intent, "Поделиться документом ($format)"))
            } else {
                Toast.makeText(context, "Ошибка создания файла экспорта", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting document", e)
            Toast.makeText(context, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createTxtFile(context: Context, title: String, text: String): File {
        val file = File(context.cacheDir, "$title.txt")
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray())
        }
        return file
    }

    private fun createPngFile(context: Context, title: String, sourceImageFile: File): File {
        val file = File(context.cacheDir, "$title.png")
        val bitmap = BitmapFactory.decodeFile(sourceImageFile.absolutePath)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }

    private fun createPdfFile(context: Context, title: String, imageFile: File, ocrText: String): File {
        val pdfDocument = PdfDocument()

        // Page 1: Image
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        // Standard A4 dimensions in postscript points (595 x 842)
        val pageInfo1 = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page1 = pdfDocument.startPage(pageInfo1)
        val canvas1 = page1.canvas

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // Calculate fit margins
        val margin = 30f
        val maxW = 595f - 2 * margin
        val maxH = 842f - 2 * margin

        val scale = Math.min(maxW / bitmap.width, maxH / bitmap.height)
        val finalW = bitmap.width * scale
        val finalH = bitmap.height * scale

        // Draw centered on canvas
        val left = (595f - finalW) / 2f
        val top = (842f - finalH) / 2f

        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
        val destRect = android.graphics.RectF(left, top, left + finalW, top + finalH)

        canvas1.drawBitmap(bitmap, srcRect, destRect, paint)
        pdfDocument.finishPage(page1)

        // Page 2: Text (if OCR is present)
        if (ocrText.isNotBlank()) {
            val pageInfo2 = PdfDocument.PageInfo.Builder(595, 842, 2).create()
            val page2 = pdfDocument.startPage(pageInfo2)
            val canvas2 = page2.canvas

            // Standard margins for text page
            val txtMargin = 40f
            val printableWidth = 595 - (2 * txtMargin).toInt()

            val textPaint = TextPaint().apply {
                isAntiAlias = true
                textSize = 12f
                color = android.graphics.Color.BLACK
            }

            // Document Title Header
            val titlePaint = TextPaint().apply {
                isAntiAlias = true
                textSize = 16f
                color = android.graphics.Color.DKGRAY
                isFakeBoldText = true
            }

            canvas2.drawText("Распознанный текст: $title", txtMargin, 50f, titlePaint)

            // Draw OCR Text using StaticLayout (built-in multiliner wrapping)
            canvas2.save()
            canvas2.translate(txtMargin, 80f)

            @Suppress("DEPRECATION")
            val staticLayout = StaticLayout(
                ocrText,
                textPaint,
                printableWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.15f,
                0f,
                false
            )

            staticLayout.draw(canvas2)
            canvas2.restore()

            pdfDocument.finishPage(page2)
        }

        val file = File(context.cacheDir, "$title.pdf")
        FileOutputStream(file).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        return file
    }
}

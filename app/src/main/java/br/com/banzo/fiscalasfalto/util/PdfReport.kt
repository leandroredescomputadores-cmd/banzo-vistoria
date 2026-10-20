package br.com.banzo.fiscalasfalto.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import br.com.banzo.fiscalasfalto.data.Occurrence
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReport {
    fun createDaily(context: Context, items: List<Occurrence>, date: Date = Date()): File {
        val pdf = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842
        val margin = 38f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; isFakeBoldText = true }
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; isFakeBoldText = true }
        val dateText = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(date)
        val output = File(context.getExternalFilesDir(null) ?: context.filesDir, "Relatorio_Recomposicao_$dateText.pdf")
        output.parentFile?.mkdirs()
        var pageNumber = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas = page.canvas
        var y = 42f
        fun newPage() {
            pdf.finishPage(page)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            y = 42f
            canvas.drawText("Fiscalização de Recomposição Asfáltica — continuação", margin, y, headingPaint)
            y += 26f
        }
        canvas.drawText("RELATÓRIO DIÁRIO DE FISCALIZAÇÃO", margin, y, titlePaint); y += 24f
        canvas.drawText("Data: $dateText", margin, y, paint); y += 15f
        canvas.drawText("Total de ocorrências: ${items.size}", margin, y, paint); y += 24f
        if (items.isEmpty()) canvas.drawText("Nenhuma ocorrência registrada para esta data.", margin, y, paint)
        items.forEachIndexed { index, item ->
            if (y > 690f) newPage()
            canvas.drawText("${index + 1}. ${item.id} — ${item.pathology}", margin, y, headingPaint); y += 17f
            val lines = listOf(
                "Status: ${item.status}${if (item.workOrderNumber.isNotBlank()) " | OS: ${item.workOrderNumber}" else ""}",
                "Endereço: ${item.address.ifBlank { "Não informado" }}",
                "Referência: ${item.reference.ifBlank { "Não informada" }}",
                "Coordenadas: ${item.latitude?.toString() ?: "—"}, ${item.longitude?.toString() ?: "—"}",
                "Fiscal: ${item.inspector.ifBlank { "Não informado" }}",
                "Descrição: ${(if (item.pathology == "Outra — especificar") item.pathologyOther else item.pathology).let { if (item.description.isBlank()) it else "$it — ${item.description}" }}"
            )
            lines.forEach { line ->
                if (y > 755f) newPage()
                canvas.drawText(line.take(100), margin, y, paint); y += 14f
            }
            item.photoPaths.split("\n").filter { it.isNotBlank() }.take(3).forEach { path ->
                val bitmap = BitmapFactory.decodeFile(path)
                if (bitmap != null) {
                    if (y > 610f) newPage()
                    val maxW = 155f; val maxH = 105f
                    val scale = minOf(maxW / bitmap.width, maxH / bitmap.height)
                    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    canvas.drawBitmap(bitmap, null, Rect(margin.toInt(), y.toInt(), margin.toInt() + w, y.toInt() + h), null)
                    y += h + 8f
                    bitmap.recycle()
                }
            }
            y += 12f
            if (y > 770f && index < items.lastIndex) newPage()
        }
        pdf.finishPage(page)
        FileOutputStream(output).use { pdf.writeTo(it) }
        pdf.close()
        return output
    }
}

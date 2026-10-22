package br.com.banzo.fiscalasfalto.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import br.com.banzo.fiscalasfalto.data.Occurrence
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfReport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val LEFT = 42f
    private const val RIGHT = 553f
    private const val BOTTOM = 790f
    private val ink = android.graphics.Color.rgb(38, 33, 30)
    private val peach = android.graphics.Color.rgb(205, 137, 82)
    private val muted = android.graphics.Color.rgb(95, 91, 87)

    fun createSingle(context: Context, item: Occurrence): File = createDaily(context, listOf(item), Date())

    fun createDaily(context: Context, items: List<Occurrence>, date: Date = Date()): File {
        val pdf = PdfDocument()
        val dateText = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR")).format(date)
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "relatorios").apply { mkdirs() }
        val output = File(dir, "Banzo_Vistoria_Relatorio_$stamp.pdf")
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 9.5f }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 8f }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 12f; isFakeBoldText = true }
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 18f; isFakeBoldText = true }
        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = peach; textSize = 10f; isFakeBoldText = true }
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: android.graphics.Canvas
        var y = 0f

        fun startPage(continuation: Boolean = false) {
            pageNo++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            canvas.drawColor(android.graphics.Color.WHITE)
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
            canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 12f, bar)
            canvas.drawText("BANZO ENGENHARIA", LEFT, 38f, brand)
            canvas.drawText("FISCALIZAÇÃO TÉCNICA", LEFT, 51f, small)
            canvas.drawText(if (continuation) "RELATÓRIO TÉCNICO • CONTINUAÇÃO" else "RELATÓRIO TÉCNICO DE VISTORIA", LEFT, 78f, title)
            canvas.drawLine(LEFT, 91f, RIGHT, 91f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = peach; strokeWidth = 2f })
            y = 111f
        }
        fun finishPage() {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; textSize = 8f }
            canvas.drawLine(LEFT, 808f, RIGHT, 808f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = peach; strokeWidth = 0.8f })
            canvas.drawText("Banzo Engenharia • Documento de fiscalização gerado pelo Banzo Vistoria", LEFT, 824f, p)
            canvas.drawText("Página $pageNo", RIGHT - 42f, 824f, p)
            pdf.finishPage(page)
        }
        fun ensureSpace(height: Float) { if (y + height > BOTTOM) { finishPage(); startPage(true) } }
        fun wrap(text: String, maxWidth: Float, paint: Paint): List<String> {
            val result = mutableListOf<String>()
            text.split(Regex("\\s+")).forEach { word ->
                if (result.isEmpty()) result.add(word) else {
                    val candidate = result.last() + " " + word
                    if (paint.measureText(candidate) <= maxWidth) result[result.lastIndex] = candidate else result.add(word)
                }
            }
            return result.ifEmpty { listOf("") }
        }
        fun paragraph(label: String, value: String) {
            val full = "$label: ${value.ifBlank { "Não informado" }}"
            val lines = wrap(full, RIGHT - LEFT, body)
            ensureSpace(lines.size * 13f + 3f)
            lines.forEach { canvas.drawText(it, LEFT, y, body); y += 13f }
            y += 2f
        }

        startPage()
        canvas.drawText("Data de emissão: $dateText", LEFT, y, body); y += 16f
        canvas.drawText("Quantidade de registros: ${items.size}", LEFT, y, body); y += 22f
        if (items.isEmpty()) { canvas.drawText("Nenhuma ocorrência registrada para esta data.", LEFT, y, body); y += 20f }
        items.forEachIndexed { index, item ->
            ensureSpace(38f)
            val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(248, 243, 237) }
            canvas.drawRoundRect(RectF(LEFT, y - 13f, RIGHT, y + 8f), 4f, 4f, sectionPaint)
            canvas.drawText("${index + 1}. ${item.id}  •  ${item.pathology}", LEFT + 7f, y + 1f, heading)
            y += 23f
            paragraph("Status", item.status)
            paragraph("Fiscal responsável", item.inspector)
            paragraph("Endereço", item.address)
            paragraph("Ponto de referência", item.reference)
            paragraph("Ordem de serviço", item.workOrderNumber)
            paragraph("Coordenadas", "${item.latitude?.toString() ?: "—"}, ${item.longitude?.toString() ?: "—"}")
            paragraph("Descrição técnica / providência", listOf(if (item.pathology == "Outra — especificar") item.pathologyOther else item.pathology, item.description).filter { it.isNotBlank() }.joinToString(" — "))
            val photos = item.photoPaths.split("\n", ";").map { it.trim() }.filter { it.isNotBlank() }.take(4)
            photos.forEachIndexed { photoIndex, path ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var sample = 1
                    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
                    val original = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    if (original != null) {
                        val ratio = minOf(430f / original.width, 220f / original.height, 1f)
                        val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(original, (original.width * ratio).toInt().coerceAtLeast(1), (original.height * ratio).toInt().coerceAtLeast(1), true) else original
                        val bytes = ByteArrayOutputStream().use { stream -> scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream); stream.toByteArray() }
                        val optimized = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (optimized != null) {
                            ensureSpace(minOf(optimized.height.toFloat(), 225f) + 27f)
                            canvas.drawText("Evidência fotográfica ${photoIndex + 1}", LEFT, y, small); y += 8f
                            val h = minOf(220f, optimized.height.toFloat())
                            canvas.drawBitmap(optimized, null, RectF(LEFT, y, LEFT + optimized.width * (h / optimized.height), y + h), null)
                            y += h + 14f
                            optimized.recycle()
                        }
                        if (scaled !== original) scaled.recycle()
                        original.recycle()
                    }
                }
            }
            y += 10f
        }
        finishPage()
        FileOutputStream(output).use { pdf.writeTo(it) }
        pdf.close()
        return output
    }
}

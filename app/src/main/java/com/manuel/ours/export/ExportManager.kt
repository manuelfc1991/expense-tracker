package com.manuel.ours.export

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.MonthSummary
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV and PDF export, shared through the system share sheet. */
object ExportManager {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun shareCsv(context: Context, transactions: List<Transaction>, month: YearMonth) {
        val file = File(exportDir(context), "ours-${month.year}-${month.monthValue}.csv")
        file.writeText(buildCsv(transactions))
        share(context, file, "text/csv")
    }

    fun buildCsv(transactions: List<Transaction>): String = buildString {
        appendLine("Date,Merchant,Category,Type,Amount (INR),Paid by,Split,Source,Bank,Reference")
        transactions.sortedBy { it.occurredAt }.forEach { txn ->
            appendLine(
                listOf(
                    Instant.ofEpochMilli(txn.occurredAt).atZone(zone).format(dateFormatter),
                    txn.merchant,
                    txn.category.label,
                    txn.type.name,
                    // Plain decimal, no ₹ symbol or grouping — spreadsheets need to
                    // parse this as a number, not display it prettily.
                    "%.2f".format(txn.amountPaise / 100.0),
                    txn.ownerName,
                    txn.splitType.name,
                    txn.source.name,
                    txn.bank.orEmpty(),
                    txn.refNo.orEmpty(),
                ).joinToString(",") { escapeCsv(it) }
            )
        }
    }

    private fun escapeCsv(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    fun sharePdf(
        context: Context,
        summary: MonthSummary,
        transactions: List<Transaction>,
        month: YearMonth,
    ) {
        val file = File(exportDir(context), "ours-${month.year}-${month.monthValue}.pdf")
        writePdf(file, summary, transactions, month)
        share(context, file, "application/pdf")
    }

    private fun writePdf(
        file: File,
        summary: MonthSummary,
        transactions: List<Transaction>,
        month: YearMonth,
    ) {
        val document = PdfDocument()
        val titlePaint = Paint().apply { textSize = 22f; isFakeBoldText = true }
        val headingPaint = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 10f }
        val dimPaint = Paint().apply { textSize = 9f; color = 0xFF777777.toInt() }

        var pageNumber = 1
        var page = document.startPage(pageInfo(pageNumber))
        var canvas = page.canvas
        var y = MARGIN + 24f

        canvas.drawText("Ours — ${month.format(monthFormatter)}", MARGIN, y, titlePaint)
        y += 28f

        canvas.drawText("Total spent: ${Money.format(summary.totalSpentPaise)}", MARGIN, y, headingPaint)
        y += 16f
        canvas.drawText("Total received: ${Money.format(summary.totalReceivedPaise)}", MARGIN, y, bodyPaint)
        y += 14f
        canvas.drawText("Net: ${Money.format(summary.netPaise)}", MARGIN, y, bodyPaint)
        y += 24f

        canvas.drawText("By category", MARGIN, y, headingPaint)
        y += 16f
        summary.byCategory.forEach { cat ->
            canvas.drawText(cat.category.label, MARGIN, y, bodyPaint)
            canvas.drawText(Money.format(cat.totalPaise), PAGE_WIDTH - MARGIN - 90f, y, bodyPaint)
            y += 13f
        }
        y += 14f

        if (summary.byMember.size > 1) {
            canvas.drawText("Who spent what", MARGIN, y, headingPaint)
            y += 16f
            summary.byMember.forEach { member ->
                canvas.drawText(member.displayName, MARGIN, y, bodyPaint)
                canvas.drawText(Money.format(member.totalPaise), PAGE_WIDTH - MARGIN - 90f, y, bodyPaint)
                y += 13f
            }
            y += 14f
        }

        canvas.drawText("Transactions", MARGIN, y, headingPaint)
        y += 16f

        transactions.sortedBy { it.occurredAt }.forEach { txn ->
            if (y > PAGE_HEIGHT - MARGIN - 20f) {
                document.finishPage(page)
                pageNumber++
                page = document.startPage(pageInfo(pageNumber))
                canvas = page.canvas
                y = MARGIN + 20f
            }
            val date = Instant.ofEpochMilli(txn.occurredAt).atZone(zone)
                .format(DateTimeFormatter.ofPattern("dd MMM"))
            canvas.drawText(date, MARGIN, y, dimPaint)
            canvas.drawText(txn.merchant.take(36), MARGIN + 46f, y, bodyPaint)
            canvas.drawText(txn.category.label.take(18), MARGIN + 250f, y, dimPaint)
            canvas.drawText(
                (if (txn.type == TxnType.CREDIT) "+" else "") + Money.format(txn.amountPaise),
                PAGE_WIDTH - MARGIN - 90f,
                y,
                bodyPaint,
            )
            y += 13f
        }

        document.finishPage(page)
        file.outputStream().use { document.writeTo(it) }
        document.close()
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), number).create()

    private fun exportDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    private fun share(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share ${file.name}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private const val MARGIN = 36f
    private const val PAGE_WIDTH = 595f // A4 at 72dpi
    private const val PAGE_HEIGHT = 842f
}

package com.diagnosepro.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import androidx.core.content.FileProvider
import java.io.File

/** Sharing helpers: text, CSV and PDF via FileProvider (no storage permission needed). */
object Exporter {

    private fun exportDir(ctx: Context) = File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    fun shareText(ctx: Context, title: String, text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareFile(ctx: Context, file: File, mime: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uriFor(ctx, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** UTF-8 with BOM so Excel opens Hebrew column names correctly. */
    fun shareCsv(ctx: Context, name: String, csv: String, title: String) {
        val f = File(exportDir(ctx), name)
        f.writeText("﻿" + csv, Charsets.UTF_8)
        shareFile(ctx, f, "text/csv", title)
    }

    /**
     * Renders plain text to an A4 PDF. StaticLayout runs the full Unicode bidi algorithm,
     * so Hebrew lines are right-aligned and codes/numbers inside them keep their LTR order.
     */
    fun sharePdf(ctx: Context, name: String, text: String, rtl: Boolean, title: String) {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842; val margin = 40
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, pageW - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR)
            .setLineSpacing(2f, 1f)
            .build()
        var line = 0
        var pageNo = 1
        while (line < layout.lineCount) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo++).create())
            val c = page.canvas
            val startTop = layout.getLineTop(line)
            var last = line
            while (last < layout.lineCount && layout.getLineBottom(last) - startTop <= pageH - 2 * margin) last++
            c.save()
            c.translate(margin.toFloat(), (margin - startTop).toFloat())
            c.clipRect(0, startTop, pageW - 2 * margin, layout.getLineBottom(last - 1))
            layout.draw(c)
            c.restore()
            doc.finishPage(page)
            line = last
        }
        val f = File(exportDir(ctx), name)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        shareFile(ctx, f, "application/pdf", title)
    }
}

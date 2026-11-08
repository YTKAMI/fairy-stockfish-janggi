package com.kami.jangginative

import android.content.Context
import android.graphics.*
import android.view.View

class JanggiBoardView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cells = IntArray(90)
    private val labels = arrayOf("", "宮", "士", "象", "馬", "車", "包", "卒")
    private val boardColor = Color.rgb(226, 211, 174)
    private val lineColor = Color.rgb(126, 103, 69)
    private val choColor = Color.rgb(164, 51, 41)
    private val hanColor = Color.rgb(26, 111, 83)

    fun setPosition(fen: String) { cells = parseFen(fen); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = minOf(width * .025f, height * .018f).coerceAtLeast(8f)
        val w = width - margin * 2f
        val h = height - margin * 2f
        val dx = w / 8f
        val dy = h / 9f

        paint.style = Paint.Style.FILL
        paint.color = boardColor
        canvas.drawRect(margin, margin, width - margin, height - margin, paint)

        paint.color = lineColor
        paint.strokeWidth = 1.35f
        paint.style = Paint.Style.STROKE
        for (r in 0..9) canvas.drawLine(margin, margin + r * dy, width - margin, margin + r * dy, paint)
        for (c in 0..8) canvas.drawLine(margin + c * dx, margin, margin + c * dx, height - margin, paint)

        paint.strokeWidth = 1.1f
        canvas.drawLine(margin + 3 * dx, margin, margin + 5 * dx, margin + 2 * dy, paint)
        canvas.drawLine(margin + 5 * dx, margin, margin + 3 * dx, margin + 2 * dy, paint)
        canvas.drawLine(margin + 3 * dx, margin + 7 * dy, margin + 5 * dx, margin + 9 * dy, paint)
        canvas.drawLine(margin + 5 * dx, margin + 7 * dy, margin + 3 * dx, margin + 9 * dy, paint)

        val radius = minOf(dx, dy) * .36f
        for (i in cells.indices) {
            val p = cells[i]
            if (p == 0) continue
            val x = margin + (i % 9) * dx
            val y = margin + (i / 9) * dy
            val cho = p < 8
            val color = if (cho) choColor else hanColor

            paint.style = Paint.Style.FILL
            paint.setShadowLayer(1.5f, 0f, 1f, Color.argb(45, 45, 35, 20))
            paint.color = Color.rgb(245, 231, 197)
            canvas.drawCircle(x, y, radius, paint)
            paint.clearShadowLayer()

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.35f
            paint.color = color
            canvas.drawCircle(x, y, radius, paint)

            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = minOf(dx, dy) * .43f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.color = color
            val type = p and 7
            val label = if (!cho && type == 7) "兵" else labels[type]
            canvas.drawText(label, x, y - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    private fun parseFen(fen: String): IntArray {
        val out = IntArray(90)
        val rows = fen.substringBefore(' ').split('/')
        var k = 0
        for (row in rows) for (ch in row) {
            if (ch.isDigit()) k += ch.digitToInt()
            else {
                val type = when (ch.lowercaseChar()) {
                    'k' -> 1; 'a' -> 2; 'b' -> 3; 'n' -> 4
                    'r' -> 5; 'c' -> 6; 'p' -> 7; else -> 0
                }
                out[k++] = if (ch.isUpperCase()) type else 8 + type
            }
        }
        return out
    }
}

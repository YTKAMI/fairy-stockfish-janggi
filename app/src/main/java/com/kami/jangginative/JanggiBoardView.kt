package com.kami.jangginative

import android.content.Context
import android.graphics.*
import android.view.View

class JanggiBoardView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cells = IntArray(90)
    private val labels = arrayOf("", "宮", "士", "象", "馬", "車", "包", "卒")
    fun setPosition(fen: String) { cells = parseFen(fen); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = 24f
        val w = width - margin * 2; val h = height - margin * 2
        val dx = w / 8f; val dy = h / 9f
        paint.color = Color.rgb(226,211,174); paint.style = Paint.Style.FILL; canvas.drawRect(margin, margin, width-margin, height-margin, paint)
        paint.color = Color.rgb(94,67,39); paint.strokeWidth = 2f; paint.style = Paint.Style.STROKE
        for (r in 0..9) canvas.drawLine(margin, margin+r*dy, width-margin, margin+r*dy, paint)
        for (c in 0..8) canvas.drawLine(margin+c*dx, margin, margin+c*dx, height-margin, paint)
        paint.strokeWidth = 1.5f
        canvas.drawLine(margin+3*dx, margin, margin+5*dx, margin+2*dy, paint); canvas.drawLine(margin+5*dx, margin, margin+3*dx, margin+2*dy, paint)
        canvas.drawLine(margin+3*dx, margin+7*dy, margin+5*dx, margin+9*dy, paint); canvas.drawLine(margin+5*dx, margin+7*dy, margin+3*dx, margin+9*dy, paint)
        for (i in cells.indices) {
            val p=cells[i]; if(p==0) continue
            val x=margin+(i%9)*dx; val y=margin+(i/9)*dy; val side=if(p<8)0 else 1; val type=p and 7
            paint.style=Paint.Style.FILL; paint.color=Color.rgb(241,225,187); canvas.drawCircle(x,y,minOf(dx,dy)*.38f,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=if(side==0) Color.rgb(20,105,83) else Color.rgb(166,48,38); canvas.drawCircle(x,y,minOf(dx,dy)*.38f,paint)
            paint.style=Paint.Style.FILL; paint.textAlign=Paint.Align.CENTER; paint.textSize=minOf(dx,dy)*.48f; paint.typeface=Typeface.DEFAULT_BOLD; paint.color=if(side==0) Color.rgb(20,105,83) else Color.rgb(166,48,38); canvas.drawText(labels[type],x,y+paint.textSize*.17f,paint)
        }
    }
    private fun parseFen(fen:String):IntArray {
        val out=IntArray(90); val rows=fen.substringBefore(' ').split('/'); var k=0
        for(row in rows) for(ch in row) { if(ch.isDigit()) k+=ch.digitToInt() else { val t=when(ch.lowercaseChar()){'k'->1;'a'->2;'b'->3;'n'->4;'r'->5;'c'->6;'p'->7;else->0}; out[k++]=if(ch.isUpperCase())t else 8+t } }
        return out
    }
}

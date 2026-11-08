package com.kami.jangginative

import android.content.Context
import java.io.*

class EngineProcess(private val context: Context) {
    companion object { const val START_FEN = "rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1" }
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    @Synchronized fun start() {
        if (process != null) return
        val file = copyAsset("fairy-stockfish")
        val nnueFile = copyAsset("janggi-9991472750de.nnue")
        if (!file.setExecutable(true, true)) throw IOException("엔진 실행 권한을 설정하지 못했습니다.")
        process = ProcessBuilder(file.absolutePath).directory(context.filesDir).redirectErrorStream(true).start()
        writer = process!!.outputStream.bufferedWriter(); reader = process!!.inputStream.bufferedReader()
        send("uci"); readUntil("uciok")
        send("setoption name UCI_Variant value janggi"); send("setoption name Threads value 1"); send("setoption name Hash value 128")
        send("setoption name EvalFile value ${nnueFile.absolutePath}")
        send("isready"); readUntil("readyok")
    }
    private fun copyAsset(name:String):File {
        val file=File(context.filesDir,name)
        if(!file.exists() || file.length()==0L) context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
    private fun send(s:String) { writer?.apply { write(s); newLine(); flush() } ?: throw IOException("엔진이 실행 중이 아닙니다.") }
    private fun readUntil(marker:String):String {
        val lines=StringBuilder(); val r=reader?:throw IOException("엔진 출력이 없습니다.")
        repeat(5000) { val line=r.readLine()?:throw EOFException("엔진이 종료됐습니다."); lines.append(line).append('\n'); if(line.startsWith(marker)) return lines.toString() }
        throw IOException("엔진 응답 시간 초과: $marker")
    }
    @Synchronized fun command(command:String, until:String):String { send(command); return readUntil(until) }
    @Synchronized fun close() { try { send("quit") } catch (_:Exception) {}; try { process?.destroy() } catch (_:Exception) {}; process=null; writer=null; reader=null }
}

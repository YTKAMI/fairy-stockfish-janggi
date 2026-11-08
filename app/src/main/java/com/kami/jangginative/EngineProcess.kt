package com.kami.jangginative

import android.content.Context
import java.io.*

class EngineProcess(private val context: Context) {
    companion object {
        const val START_FEN = "rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1"
    }
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var enginePath: String = ""

    @Synchronized fun start() {
        val current=process
        if(current!=null && current.isAlive && writer!=null && reader!=null) return
        clearProcess()
        val file=File(context.applicationInfo.nativeLibraryDir,"libfairy-stockfish.so")
        enginePath=file.absolutePath
        if(!file.exists()) throw FileNotFoundException("엔진 파일을 찾지 못했습니다: "+enginePath)
        if(file.length()<1_000_000L) throw IOException("엔진 파일 크기가 비정상입니다: "+file.length()+" bytes")
        if(!file.canExecute() && !file.setExecutable(true,true))
            throw IOException("엔진 실행 권한이 없습니다: "+enginePath)
        try {
            val p=ProcessBuilder(file.absolutePath)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .start()
            val out=BufferedWriter(OutputStreamWriter(p.outputStream,Charsets.UTF_8))
            val input=BufferedReader(InputStreamReader(p.inputStream,Charsets.UTF_8))
            process=p;writer=out;reader=input
            send("uci")
            val uci=readUntil("uciok")
            if(!uci.contains("UCI_Variant") || !uci.contains("janggi"))
                throw IOException("실행 파일이 장기 변형을 지원하지 않습니다. UCI 응답: "+uci.take(1200))
            send("setoption name UCI_Variant value janggi")
            send("setoption name Threads value 1")
            send("setoption name Hash value 128")
            send("isready")
            readUntil("readyok")
            if(!p.isAlive) throw IOException("UCI 초기화 직후 엔진 프로세스가 종료됐습니다.")
        } catch(e:Exception) {
            val detail=buildString {
                append("엔진 실행 실패: ").append(e.message ?: e.javaClass.simpleName)
                val p=process
                if(p!=null) {
                    append(" / pid=").append(p.pid())
                    if(!p.isAlive) try { append(" / exit=").append(p.exitValue()) } catch(_:Exception) {}
                }
                append(" / 경로=").append(enginePath)
            }
            clearProcess()
            throw IOException(detail,e)
        }
    }

    private fun send(s:String) {
        val p=process
        if(p==null || !p.isAlive) {
            val exit=if(p!=null)try{p.exitValue().toString()}catch(_:Exception){"unknown"}else "no-process"
            throw IOException("엔진 프로세스가 실행 중이 아닙니다 (상태="+exit+", writer="+(writer!=null)+").")
        }
        val w=writer ?: throw IOException("엔진 입력 스트림이 초기화되지 않았습니다 (pid="+p.pid()+").")
        try { w.write(s);w.newLine();w.flush() }
        catch(e:IOException){throw IOException("엔진 명령 전송 실패: "+s+" (pid="+p.pid()+", alive="+p.isAlive+")",e)}
    }

    private fun readUntil(marker:String):String {
        val p=process ?: throw IOException("엔진 프로세스가 생성되지 않았습니다.")
        val r=reader ?: throw IOException("엔진 출력 스트림이 초기화되지 않았습니다.")
        val lines=StringBuilder()
        repeat(5000) {
            val line=r.readLine()
            if(line==null) {
                val exit=if(p.isAlive)"still-running" else try{p.exitValue().toString()}catch(_:Exception){"unknown"}
                throw EOFException("엔진 출력이 끊겼습니다 (기대="+marker+", exit="+exit+", 출력="+lines.toString().take(1600)+")")
            }
            lines.append(line).append('\n')
            if(line.startsWith(marker)) return lines.toString()
        }
        throw IOException("엔진 응답 시간 초과: "+marker+" / 출력="+lines.toString().take(1600))
    }

    @Synchronized fun command(command:String, until:String):String {
        if(process==null || writer==null) start()
        send(command)
        return readUntil(until)
    }

    private fun clearProcess() {
        try { writer?.close() } catch(_:Exception) {}
        try { reader?.close() } catch(_:Exception) {}
        try { process?.destroy() } catch(_:Exception) {}
        try { if(process?.isAlive==true) process?.destroyForcibly() } catch(_:Exception) {}
        process=null;writer=null;reader=null
    }

    @Synchronized fun close() {
        try { if(process?.isAlive==true) send("quit") } catch(_:Exception) {}
        clearProcess()
    }
}

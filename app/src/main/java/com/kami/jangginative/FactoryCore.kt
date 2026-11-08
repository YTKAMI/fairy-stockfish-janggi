package com.kami.jangginative

import android.content.Context
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.*

data class FactoryConfig(val games:Int=100,val workers:Int=2,val nodes:Int=20000,val hashMb:Int=512,val maxPlies:Int=200)
data class FactoryProgress(val games:Int,val totalGames:Int,val positions:Long,val choWins:Int,val hanWins:Int,val message:String)

private class JanggiEngine(ctx:Context,hash:Int):Closeable {
    private val p:Process
    private val w:BufferedWriter
    private val r:BufferedReader
    init {
        val f=File(ctx.applicationInfo.nativeLibraryDir,"libfairy-stockfish.so")
        if(!f.exists())throw FileNotFoundException("내장 ARM64 엔진 파일이 없습니다.")
        if(!f.setExecutable(true,true))throw IOException("엔진 실행 권한 설정 실패")
        p=ProcessBuilder(f.absolutePath).directory(ctx.filesDir).redirectErrorStream(true).start()
        w=p.outputStream.bufferedWriter();r=p.inputStream.bufferedReader()
        send("uci");val opts=StringBuilder();until("uciok",30){opts.append(it).append('\n')}
        if(!opts.contains("UCI_Variant")||!opts.contains("janggi"))throw IOException("장기 규칙 미지원 엔진")
        send("setoption name UCI_Variant value janggi");send("setoption name Threads value 1")
        send("setoption name Hash value "+hash.coerceAtLeast(16));send("isready");until("readyok",60)
    }
    private fun send(s:String){w.write(s);w.newLine();w.flush()}
    private fun until(prefix:String,sec:Int,cb:(String)->Unit={}):String{
        val end=System.currentTimeMillis()+sec*1000L
        while(System.currentTimeMillis()<end){val s=r.readLine()?:throw EOFException("엔진 종료");cb(s);if(s.startsWith(prefix))return s}
        throw IOException("엔진 응답 시간 초과: "+prefix)
    }
    @Synchronized fun legal(fen:String):List<String>{send("position fen "+fen);send("go perft 1");val out=mutableListOf<String>()
        val re=Regex("^([a-i](?:10|[1-9])[a-i](?:10|[1-9])(?:[a-z])?):\\s*\\d+")
        until("Nodes searched:",60){s->re.find(s.trim())?.let{out.add(it.groupValues[1])}};return out}
    @Synchronized fun check(fen:String):Boolean{send("position fen "+fen);send("d");var v=false
        until("Checkers:",15){s->if(s.startsWith("Checkers:")){val t=s.substringAfter(':').trim();v=t.isNotEmpty()&&t!="-"}};return v}
    @Synchronized fun think(fen:String,nodes:Int,moves:List<String>):Pair<String,Int>{send("position fen "+fen)
        send("go "+(if(moves.isEmpty())"" else "searchmoves "+moves.joinToString(" ")+" ")+"nodes "+nodes.coerceAtLeast(1))
        var score=0;while(true){val s=r.readLine()?:throw EOFException("엔진 종료");Regex("""score cp (-?\d+)""").find(s)?.let{score=it.groupValues[1].toInt()}
            if(s.startsWith("bestmove "))return (s.split(Regex("\\s+")).getOrNull(1)?:"(none)") to score.coerceIn(-3000,3000)}}
    override fun close(){try{send("quit")}catch(_:Exception){};try{if(!p.waitFor(1500,java.util.concurrent.TimeUnit.MILLISECONDS))p.destroyForcibly()}catch(_:Exception){}}
}

object JanggiDataFactory {
    const val START_FEN="rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1"
    private val layouts=arrayOf("마상상마","상마마상","마상마상","상마상마")
    private val values=intArrayOf(0,0,3,3,5,13,7,2)
    private data class Ply(val f:Int,val t:Int,val cap:Boolean)
    private data class Rec(val b:ByteArray,val side:Byte,val move:Short,val score:Short,val result:Byte)

    fun parseFen(fen:String):ByteArray{val a=ArrayList<Byte>(90);fen.substringBefore(' ').split('/').forEach{row->row.forEach{c->
        if(c.isDigit())repeat(c.digitToInt()){a.add(0)}else{val t=when(c.lowercaseChar()){'k'->1;'a'->2;'b'->3;'n'->4;'r'->5;'c'->6;'p'->7;else->0};a.add((if(c.isUpperCase())t else 8+t).toByte())}}}
        require(a.size==90){"장기판 칸 수 오류"};return a.toByteArray()}

    fun run(ctx:Context,cfg:FactoryConfig,stop:AtomicBoolean,report:(FactoryProgress)->Unit){
        require(cfg.games>0&&cfg.workers>0&&cfg.nodes>0&&cfg.maxPlies>=2)
        require(cfg.hashMb in 16..4096){"전체 Hash는 16MB~4096MB 범위"}
        val workers=minOf(cfg.workers,Runtime.getRuntime().availableProcessors().coerceAtLeast(1),cfg.games,(cfg.hashMb/16).coerceAtLeast(1))
        val hash=(cfg.hashMb/workers).coerceAtLeast(16)
        val dir=File(ctx.getExternalFilesDir(null)?:ctx.filesDir,"JanggiNPZ").apply{mkdirs()}
        val pool=java.util.concurrent.Executors.newFixedThreadPool(workers)
        val done=java.util.concurrent.atomic.AtomicInteger();val pos=java.util.concurrent.atomic.AtomicLong()
        val cw=java.util.concurrent.atomic.AtomicInteger();val hw=java.util.concurrent.atomic.AtomicInteger()
        val jobs=(0 until workers).map{worker->pool.submit{
            JanggiEngine(ctx,hash).use{engine->val rng=Random(System.nanoTime()+worker*7919L);val buf=mutableListOf<Rec>()
                val count=cfg.games/workers+if(worker<cfg.games%workers)1 else 0
                repeat(count){if(stop.get())return@repeat
                    val start=startFen(rng);val board=parseFen(start);val hist=mutableListOf<String>();val played=mutableListOf<Ply>();val recs=mutableListOf<Rec>()
                    var passes=0;var result=0;var reason="수 제한"
                    for(ply in 0 until minOf(300,cfg.maxPlies)){if(stop.get())break
                        val fen=start+(if(hist.isEmpty())"" else " moves "+hist.joinToString(" "))
                        val side=ply and 1;val check=engine.check(fen);val legal=engine.legal(fen).toMutableList()
                        if(!check){val pass=pass(board,side);if(!legal.contains(pass))legal.add(pass)}
                        if(legal.none{!isPass(it)}&&check){result=if(side==0)-1 else 1;reason="외통";break}
                        if(passes>=2){result=material(board);reason="연속 한수쉼";break}
                        if(played.size>=200&&played.isNotEmpty()&&!played.last().cap&&!check){result=material(board);reason="200수";break}
                        var allowed=legal.filterNot{blocked(board,played,it)};if(allowed.isEmpty())allowed=legal
                        if(allowed.isEmpty()){result=if(side==0)-1 else 1;reason=if(check)"외통" else "합법수 없음";break}
                        val (best,score)=engine.think(fen,cfg.nodes,allowed)
                        if(best=="(none)"||best=="none"||best=="0000"){result=if(side==0)-1 else 1;reason="외통";break}
                        val (f,t)=coords(best);recs.add(Rec(board.copyOf(),side.toByte(),(f*90+t).toShort(),score.toShort(),0))
                        val cap=f!=t&&board[t].toInt()!=0
                        if(f==t)passes++ else{passes=0;board[t]=board[f];board[f]=0}
                        played.add(Ply(f,t,cap));hist.add(best)
                    }
                    if(result==0)result=material(board)
                    if(!stop.get()){buf.addAll(recs.map{it.copy(result=(if(it.side.toInt()==0)result else -result).toByte())});if(result>0)cw.incrementAndGet()else hw.incrementAndGet()
                        val n=done.incrementAndGet();val total=pos.addAndGet(recs.size.toLong())
                        report(FactoryProgress(n,cfg.games,total,cw.get(),hw.get(),n.toString()+"/"+cfg.games+"판 · "+total+"국면 · 초 "+cw.get()+"승 / 한 "+hw.get()+"승 · "+reason))}
                    if(buf.size>=2000)flush(dir,buf,worker)
                };flush(dir,buf,worker)}
        }}
        try{jobs.forEach{it.get()}}finally{pool.shutdownNow()}
    }

    private fun startFen(r:Random):String{val cho=layouts[r.nextInt(4)];val han=layouts[r.nextInt(4)];val b=Array(10){CharArray(9){' '}}
        fun back(row:Int,a:String,cols:IntArray,up:Boolean){fun cv(c:Char)=if(up)c.uppercaseChar()else c.lowercaseChar()
            b[row][0]=cv('r');b[row][8]=cv('r');b[row][3]=cv('a');b[row][5]=cv('a');for(i in 0..3)b[row][cols[i]]=cv(if(a[i]=='마')'n' else 'b')}
        back(0,han,intArrayOf(7,6,2,1),false);back(9,cho,intArrayOf(1,2,6,7),true)
        b[1][4]='k';b[8][4]='K';b[2][1]='c';b[2][7]='c';b[7][1]='C';b[7][7]='C'
        intArrayOf(0,2,4,6,8).forEach{b[3][it]='p';b[6][it]='P'}
        return b.joinToString("/"){row->val s=StringBuilder();var e=0;row.forEach{c->if(c==' ')e++else{if(e>0){s.append(e);e=0};s.append(c)}};if(e>0)s.append(e);s.toString()}+" w - - 0 1"}
    private fun coords(m:String):Pair<Int,Int>{val x=Regex("^([a-i])(10|[1-9])([a-i])(10|[1-9])(?:[a-z])?$").matchEntire(m)?:throw IOException("좌표 해석 오류: "+m)
        return ((10-x.groupValues[2].toInt())*9+(x.groupValues[1][0]-'a')) to ((10-x.groupValues[4].toInt())*9+(x.groupValues[3][0]-'a'))}
    private fun sq(i:Int)=('a'+i%9).toString()+(10-i/9)
    private fun isPass(m:String)=coords(m).let{it.first==it.second}
    private fun pass(b:ByteArray,side:Int):String{val k=if(side==0)1 else 9;val i=b.indexOf(k.toByte());if(i<0)throw IOException("왕을 찾지 못함");val s=sq(i);return s+s}
    private fun blocked(b:ByteArray,m:List<Ply>,s:String):Boolean{val(f,t)=coords(s);val n=m.size;if(f==t||n<4)return false
        val p=b[f].toInt();if(p==0||(p and 7)==1||(p and 7)==2||b[t].toInt()!=0)return false
        if(m[n-4].f!=f||m[n-4].t!=t||m[n-2].f!=t||m[n-2].t!=f)return false
        for(i in maxOf(0,n-4) until n)if(m[i].cap)return false;return true}
    private fun material(b:ByteArray):Int{var c=0;var h=3;b.forEach{p->val v=p.toInt();if(v!=0){val x=values[v and 7]*2;if(v ushr 3==0)c+=x else h+=x}};return if(c>h)1 else -1}
    private fun flush(dir:File,a:MutableList<Rec>,worker:Int){if(a.isEmpty())return
        val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date());val f=File(dir,"janggi_"+stamp+"_"+worker+"_"+a.size+".npz")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(f))).use{z->
            npy(z,"boards.npy",a.flatMap{it.b.asIterable()}.toByteArray(),"|i1",a.size,90)
            npy(z,"side.npy",a.map{it.side}.toByteArray(),"|i1",a.size)
            npy(z,"move.npy",shorts(a.map{it.move}),"<i2",a.size);npy(z,"score.npy",shorts(a.map{it.score}),"<i2",a.size)
            npy(z,"result.npy",a.map{it.result}.toByteArray(),"|i1",a.size)}
        a.clear()}
    private fun shorts(a:List<Short>)=ByteBuffer.allocate(a.size*2).order(ByteOrder.LITTLE_ENDIAN).apply{a.forEach{putShort(it)}}.array()
    private fun npy(z:ZipOutputStream,name:String,data:ByteArray,descr:String,vararg shape:Int){val sh=if(shape.size==1)"("+shape[0]+",)" else "("+shape.joinToString(", ")+")"
        val d="{'descr': '$descr', 'fortran_order': False, 'shape': $sh, }";val pad=(16-((10+d.toByteArray(Charsets.US_ASCII).size+1)%16))%16
        val h=(d+" ".repeat(pad)+"\n").toByteArray(Charsets.US_ASCII);val head=ByteBuffer.allocate(10+h.size).order(ByteOrder.LITTLE_ENDIAN)
        head.put(byteArrayOf(0x93.toByte(),0x4e,0x55,0x4d,0x50,0x59,1,0));head.putShort(h.size.toShort());head.put(h)
        z.putNextEntry(ZipEntry(name));z.write(head.array());z.write(data);z.closeEntry()}
}

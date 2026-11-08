package com.kami.jangginative

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var board: JanggiBoardView
    private lateinit var engine: EngineProcess
    private val executor = Executors.newSingleThreadExecutor()
    private var gameMoves = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = EngineProcess(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 18, 16, 16); setBackgroundColor(Color.rgb(247,244,236)) }
        root.addView(TextView(this).apply { text = "장기 데이터 공장"; textSize = 24f; setTextColor(Color.rgb(35,45,40)); gravity = Gravity.CENTER; setPadding(0,8,0,14) })
        board = JanggiBoardView(this)
        root.addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { text = "엔진 준비 중"; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(4,12,4,12) }
        root.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(Button(this).apply { text = "엔진 확인"; setOnClickListener { checkEngine() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "새 대국"; setOnClickListener { gameMoves.clear(); board.setPosition(EngineProcess.START_FEN); status.text = "새 대국" } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        root.addView(TextView(this).apply { text = "데이터 생성 설정과 NPZ 내보내기는 엔진 연결 및 규칙 검증을 마친 뒤 활성화됩니다."; textSize = 12f; setTextColor(Color.GRAY); setPadding(4,10,4,0) })
        setContentView(root)
        board.setPosition(EngineProcess.START_FEN)
        executor.execute { try { engine.start(); runOnUiThread { status.text = "Fairy-Stockfish 장기 엔진 준비 완료" } } catch (e: Exception) { runOnUiThread { status.text = "엔진 초기화 실패: ${e.message}" } } }
    }
    private fun checkEngine() { executor.execute { try { engine.command("isready", "readyok"); runOnUiThread { status.text = "엔진 응답 정상" } } catch (e: Exception) { runOnUiThread { status.text = "엔진 오류: ${e.message}" } } } }
    override fun onDestroy() { executor.shutdownNow(); try { engine.close() } catch (_: Exception) {}; super.onDestroy() }
}

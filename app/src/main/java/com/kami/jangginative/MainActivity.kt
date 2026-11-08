package com.kami.jangginative

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var board: JanggiBoardView
    private lateinit var engine: EngineProcess
    private val executor = Executors.newSingleThreadExecutor()
    private var engineReady = false
    private var gameMoves = mutableListOf<String>()

    private val ink = Color.rgb(48, 55, 47)
    private val muted = Color.rgb(119, 112, 94)
    private val paper = Color.rgb(248, 245, 235)
    private val red = Color.rgb(165, 57, 45)
    private val green = Color.rgb(31, 111, 84)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = EngineProcess(this)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
            setBackgroundColor(paper)
        }
        root.addView(TextView(this).apply {
            text = "장기 데이터 공장"
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ink)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
        })

        val engineBadge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.rgb(239, 233, 216), dp(18))
        }
        statusDot = View(this).apply {
            background = rounded(Color.rgb(190, 137, 54), dp(20))
        }
        engineBadge.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)))
        status = TextView(this).apply {
            text = "엔진 연결 확인 중"
            textSize = 12f
            setTextColor(muted)
            setPadding(dp(8), 0, 0, 0)
        }
        engineBadge.addView(status)
        root.addView(engineBadge, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(8)
        })

        val boardFrame = LinearLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(Color.rgb(233, 222, 194), dp(12), Color.rgb(215, 199, 162), dp(1))
        }
        board = JanggiBoardView(this)
        boardFrame.addView(board, LinearLayout.LayoutParams(-1, -1))
        root.addView(boardFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(2)
            bottomMargin = dp(10)
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = rounded(Color.WHITE, dp(12), Color.rgb(232, 225, 207), dp(1))
        }
        statusCard.addView(TextView(this).apply {
            text = "현재 상태"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
        })
        statusCard.addView(TextView(this).apply {
            text = "엔진 초기화와 규칙 검증을 진행하고 있습니다."
            textSize = 13f
            setTextColor(ink)
            setPadding(0, dp(4), 0, 0)
        })
        root.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("엔진 확인", red) { checkEngine() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(5) })
        actions.addView(actionButton("새 대국", green) {
            gameMoves.clear()
            board.setPosition(EngineProcess.START_FEN)
            setStatus("새 대국을 시작했습니다.", false)
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(5) })
        root.addView(actions)

        root.addView(TextView(this).apply {
            text = "데이터 생성 · 엔진 검증 완료 후 활성화"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(9), 0, 0)
        })
        setContentView(root)
        board.setPosition(EngineProcess.START_FEN)

        executor.execute {
            try {
                engine.start()
                runOnUiThread { engineReady = true; setStatus("Fairy-Stockfish 장기 엔진 연결 완료", true) }
            } catch (e: Exception) {
                runOnUiThread { setStatus("엔진 오류: ${e.message ?: "초기화 실패"}", false) }
            }
        }
    }

    private fun actionButton(label: String, color: Int, click: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(color, dp(10))
            stateListAnimator = null
            setOnClickListener { click() }
        }

    private fun setStatus(message: String, good: Boolean) {
        status.text = message
        statusDot.background = rounded(if (good) green else Color.rgb(190, 137, 54), dp(20))
    }

    private fun checkEngine() {
        executor.execute {
            try {
                engine.command("isready", "readyok")
                runOnUiThread { engineReady = true; setStatus("엔진 응답 정상 · 준비 완료", true) }
            } catch (e: Exception) {
                runOnUiThread { engineReady = false; setStatus("엔진 오류: ${e.message}", false) }
            }
        }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeColor != null) setStroke(strokeWidth, strokeColor)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        try { engine.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}

package com.kami.jangginative

import android.Manifest
import android.app.Activity
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class MainActivity:Activity(){
    private lateinit var status:TextView
    private lateinit var engineDot:View
    private lateinit var board:JanggiBoardView
    private lateinit var engine:EngineProcess
    private lateinit var games:EditText
    private lateinit var workers:EditText
    private lateinit var nodes:EditText
    private lateinit var hash:EditText
    private lateinit var plies:EditText
    private lateinit var progress:ProgressBar
    private lateinit var factoryStatus:TextView
    private val executor=Executors.newSingleThreadExecutor()
    private var isPaused=false
    private val paper=Color.rgb(248,245,235)
    private val ink=Color.rgb(48,55,47)
    private val muted=Color.rgb(119,112,94)
    private val red=Color.rgb(165,57,45)
    private val green=Color.rgb(31,111,84)
    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context?,i:Intent?){
            if(i==null)return
            factoryStatus.text=i.getStringExtra(FactoryService.EXTRA_MESSAGE)?:"데이터 생성 상태 수신 중"
            val d=i.getIntExtra(FactoryService.EXTRA_DONE,0)
            if(d>=0){progress.max=i.getIntExtra(FactoryService.EXTRA_TOTAL,100).coerceAtLeast(1);progress.progress=d.coerceAtMost(progress.max)}
            else progress.progress=0
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        engine=EngineProcess(this)
        window.statusBarColor=paper;window.navigationBarColor=paper
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        if(Build.VERSION.SDK_INT>=33)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),91)
        val filter=IntentFilter(FactoryService.ACTION_PROGRESS)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver,filter)

        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(paper)}
        val header=TextView(this).apply{text="장기 데이터 공장";textSize=24f;typeface=Typeface.DEFAULT_BOLD;setTextColor(ink);gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(10))}
        root.addView(header)
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(4),dp(12),dp(18))}
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))

        val engineCard=card()
        val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        engineDot=View(this).apply{background=round(green,dp(20))}
        line.addView(engineDot,LinearLayout.LayoutParams(dp(9),dp(9)))
        status=TextView(this).apply{text="엔진 연결 확인 중";textSize=13f;setTextColor(ink);setPadding(dp(8),0,0,0)}
        line.addView(status)
        engineCard.addView(line)
        body.addView(engineCard,marginBottom(8))

        val boardFrame=LinearLayout(this).apply{setPadding(dp(5),dp(5),dp(5),dp(5));background=round(Color.rgb(233,222,194),dp(12),Color.rgb(215,199,162))}
        board=JanggiBoardView(this)
        boardFrame.addView(board,LinearLayout.LayoutParams(-1,dp(500)))
        body.addView(boardFrame,marginBottom(10))
        val gameButtons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        gameButtons.addView(button("엔진 확인",red){checkEngine()},LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(5)})
        gameButtons.addView(button("새 대국",green){board.setPosition(EngineProcess.START_FEN);setStatus("새 대국 준비",true)},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(5)})
        body.addView(gameButtons,marginBottom(12))

        val settings=card()
        settings.addView(sectionTitle("데이터 생성 설정"))
        games=field("대국 수","100");workers=field("동시 엔진 수","2")
        nodes=field("탐색 노드 수","20000");hash=field("전체 Hash 메모리(MB)","512")
        plies=field("최대 수","200")
        settings.addView(fieldRow(games,workers));settings.addView(fieldRow(nodes,hash));settings.addView(fieldRow(plies,null))
        val note=TextView(this).apply{text="Hash는 전체 엔진 합계 최대 4096MB이며, 엔진별로 자동 분배됩니다. 기기 발열과 메모리 여유를 고려해 낮은 값부터 시작하세요.";textSize=11f;setTextColor(muted);setPadding(0,dp(8),0,0)}
        settings.addView(note)
        body.addView(settings,marginBottom(10))

        val dataCard=card()
        dataCard.addView(sectionTitle("데이터 공장"))
        factoryStatus=TextView(this).apply{text="대기 중 · NPZ는 앱 전용 JanggiNPZ 폴더에 저장됩니다.";textSize=12f;setTextColor(ink)}
        dataCard.addView(factoryStatus)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=0}
        dataCard.addView(progress,LinearLayout.LayoutParams(-1,dp(8)).apply{topMargin=dp(9);bottomMargin=dp(10)})
        val factoryButtons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        factoryButtons.addView(button("데이터 생성",green){startFactory()},LinearLayout.LayoutParams(0,dp(48),1f).apply{rightMargin=dp(3)})
        factoryButtons.addView(button("일시 정지",Color.rgb(177,126,45)){togglePause()},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(3);rightMargin=dp(3)})
        factoryButtons.addView(button("생성 중지",red){stopFactory()},LinearLayout.LayoutParams(0,dp(48),1f).apply{leftMargin=dp(3)})
        dataCard.addView(factoryButtons)
        dataCard.addView(button("NPZ 파일 내보내기",Color.rgb(83,96,107)){exportNpz()},LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(8)})
        body.addView(dataCard)
        setContentView(root)
        board.setPosition(EngineProcess.START_FEN)
        executor.execute{try{engine.start();runOnUiThread{setStatus("Fairy-Stockfish 장기 엔진 연결 완료",true)}}catch(e:Exception){runOnUiThread{setStatus("엔진 오류: "+(e.message?:"초기화 실패"),false)}}}
    }
    private fun card()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(13),dp(11),dp(13),dp(12));background=round(Color.WHITE,dp(13),Color.rgb(232,225,207))}
    private fun sectionTitle(s:String)=TextView(this).apply{text=s;textSize=14f;typeface=Typeface.DEFAULT_BOLD;setTextColor(ink);setPadding(0,0,0,dp(8))}
    private fun field(label:String,value:String)=EditText(this).apply{hint=label;setText(value);textSize=14f;setSingleLine(true);inputType=android.text.InputType.TYPE_CLASS_NUMBER;setPadding(dp(9),0,dp(9),0);background=round(Color.rgb(248,247,241),dp(8),Color.rgb(225,219,204))}
    private fun fieldRow(a:EditText,b:EditText?):LinearLayout{
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun wrap(v:EditText){val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};val label=TextView(this@MainActivity).apply{text=v.hint;textSize=10f;setTextColor(muted);setPadding(0,0,0,dp(3))};box.addView(label);box.addView(v,LinearLayout.LayoutParams(-1,dp(42)));row.addView(box,LinearLayout.LayoutParams(0,dp(64),1f).apply{rightMargin=dp(5)})}
        wrap(a);if(b!=null)wrap(b)
        return row
    }
    private fun button(label:String,color:Int,click:()->Unit)=Button(this).apply{text=label;textSize=14f;isAllCaps=false;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE);background=round(color,dp(10));stateListAnimator=null;setOnClickListener{click()}}
    private fun round(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=radius.toFloat();if(stroke!=null)setStroke(dp(1),stroke)}
    private fun marginBottom(v:Int)=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(v)}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun number(v:EditText,default:Int)=v.text.toString().toIntOrNull()?:default
    private fun setStatus(s:String,ok:Boolean){status.text=s;engineDot.background=round(if(ok)green else red,dp(20))}
    private fun checkEngine(){executor.execute{try{engine.command("isready","readyok");runOnUiThread{setStatus("엔진 응답 정상 · 준비 완료",true)}}catch(e:Exception){runOnUiThread{setStatus("엔진 오류: "+e.message,false)}}}}
    private fun startFactory(){
        val g=number(games,100);val w=number(workers,2);val n=number(nodes,20000);val h=number(hash,512);val p=number(plies,200)
        if(g<1||w<1||n<1||h !in 16..4096||p<2){factoryStatus.text="설정 오류: 대국·엔진·노드는 양수, Hash 16~4096MB, 최대 수 2 이상이어야 합니다.";return}
        val i=Intent(this,FactoryService::class.java).setAction(FactoryService.ACTION_START)
            .putExtra(FactoryService.EXTRA_GAMES,g).putExtra(FactoryService.EXTRA_WORKERS,w)
            .putExtra(FactoryService.EXTRA_NODES,n).putExtra(FactoryService.EXTRA_HASH,h).putExtra(FactoryService.EXTRA_PLIES,p)
        if(Build.VERSION.SDK_INT>=26)startForegroundService(i)else startService(i)
        factoryStatus.text="데이터 생성 시작 요청 · 알림에서 진행 상황을 확인할 수 있습니다."
    }
    private fun togglePause(){isPaused=!isPaused;val action=if(isPaused)FactoryService.ACTION_PAUSE else FactoryService.ACTION_RESUME;startService(Intent(this,FactoryService::class.java).setAction(action));factoryStatus.text=if(isPaused)"일시 정지 요청" else "데이터 생성 재개 요청"}
    private fun stopFactory(){startService(Intent(this,FactoryService::class.java).setAction(FactoryService.ACTION_STOP));factoryStatus.text="중지 요청을 보냈습니다. 현재 탐색이 끝나면 정지합니다."}
    private fun exportNpz(){
        val dir=File(getExternalFilesDir(null)?:filesDir,"JanggiNPZ")
        val file=dir.listFiles()?.filter{it.extension=="npz"}?.maxByOrNull{it.lastModified()}
        if(file==null){factoryStatus.text="내보낼 NPZ 파일이 없습니다.";return}
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE,file.name).addCategory(Intent.CATEGORY_OPENABLE)
        pendingExport=file;startActivityForResult(i,92)
    }
    private var pendingExport:File?=null
    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==92&&resultCode==RESULT_OK&&data?.data!=null){val f=pendingExport?:return
            try{contentResolver.openOutputStream(data.data!!)?.use{out->f.inputStream().use{it.copyTo(out)}};factoryStatus.text="NPZ 내보내기 완료: "+f.name}
            catch(e:Exception){factoryStatus.text="내보내기 실패: "+e.message}}
    }
    override fun onDestroy(){try{unregisterReceiver(receiver)}catch(_:Exception){};executor.shutdownNow();try{engine.close()}catch(_:Exception){};super.onDestroy()}
}

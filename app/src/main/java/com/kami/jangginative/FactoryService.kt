package com.kami.jangginative

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

class FactoryService:Service(){
    companion object{
        const val ACTION_START="com.kami.jangginative.START_FACTORY"
        const val ACTION_STOP="com.kami.jangginative.STOP_FACTORY"
        const val ACTION_PAUSE="com.kami.jangginative.PAUSE_FACTORY"
        const val ACTION_RESUME="com.kami.jangginative.RESUME_FACTORY"
        const val ACTION_PROGRESS="com.kami.jangginative.FACTORY_PROGRESS"
        const val EXTRA_GAMES="games";const val EXTRA_WORKERS="workers";const val EXTRA_NODES="nodes";const val EXTRA_HASH="hash";const val EXTRA_PLIES="plies"
        const val EXTRA_MESSAGE="message";const val EXTRA_DONE="done";const val EXTRA_TOTAL="total";const val EXTRA_POSITIONS="positions"
        private const val CHANNEL="janggi_factory"
    }
    private val stop=AtomicBoolean(false)
    private val paused=AtomicBoolean(false)
    private var thread:Thread?=null
    private var wake:PowerManager.WakeLock?=null
    override fun onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26){val nm=getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel(CHANNEL,"장기 데이터 생성",NotificationManager.IMPORTANCE_LOW))}}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action==ACTION_STOP){stop.set(true);return START_NOT_STICKY}
        if(intent?.action==ACTION_PAUSE){paused.set(true);getSystemService(NotificationManager::class.java).notify(7,notification("일시 정지됨",true));sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName).putExtra(EXTRA_MESSAGE,"일시 정지됨"));return START_NOT_STICKY}
        if(intent?.action==ACTION_RESUME){paused.set(false);getSystemService(NotificationManager::class.java).notify(7,notification("데이터 생성 재개",false));sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName).putExtra(EXTRA_MESSAGE,"데이터 생성 재개"));return START_NOT_STICKY}
        if(intent?.action==ACTION_START && thread==null){
            val cfg=FactoryConfig(intent.getIntExtra(EXTRA_GAMES,100),intent.getIntExtra(EXTRA_WORKERS,2),intent.getIntExtra(EXTRA_NODES,20000),intent.getIntExtra(EXTRA_HASH,512),intent.getIntExtra(EXTRA_PLIES,200))
            stop.set(false);startForeground(7,notification("엔진 초기화 중"))
            val pm=getSystemService(POWER_SERVICE) as PowerManager
            wake=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"JanggiNative:Factory").also{it.acquire()}
            thread=Thread{
                try{JanggiDataFactory.run(this,cfg,stop,paused){p->
                    val i=Intent(ACTION_PROGRESS).setPackage(packageName).putExtra(EXTRA_MESSAGE,p.message).putExtra(EXTRA_DONE,p.games).putExtra(EXTRA_TOTAL,p.totalGames).putExtra(EXTRA_POSITIONS,p.positions)
                    sendBroadcast(i);getSystemService(NotificationManager::class.java).notify(7,notification(p.message))
                }
                }catch(e:Exception){sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName).putExtra(EXTRA_MESSAGE,"오류: "+(e.message?:e.javaClass.simpleName)).putExtra(EXTRA_DONE,-1))}
                finally{thread=null;try{wake?.let{if(it.isHeld)it.release()}}catch(_:Exception){};wake=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
            }.apply{name="JanggiDataFactory";start()}
        }
        return START_STICKY
    }
    private fun notification(message:String,isPaused:Boolean=false):Notification{
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent=PendingIntent.getService(this,1,Intent(this,FactoryService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle=PendingIntent.getService(this,2,Intent(this,FactoryService::class.java).setAction(if(isPaused)ACTION_RESUME else ACTION_PAUSE),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("장기 데이터 공장").setContentText(message.take(100)).setContentIntent(open).addAction(Notification.Action.Builder(null,if(isPaused)"재개" else "일시정지",toggle).build()).addAction(Notification.Action.Builder(null,"중지",stopIntent).build()).setOngoing(true).build()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){stop.set(true);try{wake?.let{if(it.isHeld)it.release()}}catch(_:Exception){};super.onDestroy()}
}

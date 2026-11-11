package app.findrive.car

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*

/** Started from the parked activity while visible. Navigation can then take the foreground. */
class DriveService: Service() {
    private val store by lazy { DriveStore.get(this) }
    private val manager by lazy { getSystemService(LocationManager::class.java) }
    private val main=Handler(Looper.getMainLooper())
    private var area:DriveArea?=null
    private var recorder:DriveRecorder?=null
    private var fused:AutoCloseable?=null
    private var lastSample=0L
    private val timeout=Runnable { finish("2시간 수색 기록을 종료했습니다.") }
    private val signalCheck=object:Runnable {
        override fun run() {
            if (area==null || store.active !== area) return
            if (!manager.isLocationEnabled || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
                finish("위치 사용이 중지되어 주행 기록을 종료했습니다."); return
            }
            if (SystemClock.elapsedRealtime()-lastSample > 15000) {
                recorder?.reset(); store.latest=null; store.note("위치 수신 대기 중")
            }
            main.postDelayed(this,5000)
        }
    }
    private val gps=object: LocationListener {
        override fun onLocationChanged(location:Location) {
            if(location.isFromMockProvider || !location.hasAccuracy()) { sample(null);return }
            val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1_000_000
            if(age !in 0..5000){sample(null);return}
            sample(DrivePoint(DriveCoord(location.latitude,location.longitude),System.currentTimeMillis()-age,"GPS",location.accuracy.toDouble()))
        }
        override fun onProviderDisabled(provider:String) { sample(null) }
    }
    private fun sample(point:DrivePoint?) {
        val current=area ?: return
        if(store.active !== current) return
        val now=System.currentTimeMillis()
        if(point==null || !point.coord.valid() || now-point.at !in 0..5000 || point.accuracy?.let{!it.isFinite() || it !in 0.0..35.0}==true) {
            recorder?.reset();store.latest=null;store.note("위치 수신 대기 중");return
        }
        lastSample=SystemClock.elapsedRealtime()
        val inside=current.center.distance(point.coord)<=current.radius
        store.latest=if(inside)point else null
        store.note(if(inside)"반경 안 · 위치 수신 중" else "반경 밖 · 기록 일시 정지")
        recorder?.accept(point,now)?.let { (from,to) ->
            store.enqueue(current,from,to) { error ->
                main.post { if(store.active === current) finish(error.message ?: "주행 기록을 저장할 수 없어 종료했습니다.") }
            }
        }
    }
    private fun finish(message:String) {
        val current=area
        if(current!=null && store.active===current) store.end(message)
        stopSelf()
    }
    override fun onStartCommand(intent:Intent?, flags:Int, startId:Int):Int {
        if(intent?.action==STOP){finish("주행 기록을 종료했습니다.");return START_NOT_STICKY}
        val current=store.active
        if(current==null || BuildConfig.SIMULATION){stopSelf();return START_NOT_STICKY}
        if(area===current)return START_NOT_STICKY
        if(area!=null)releaseInputs()
        area=current;recorder=DriveRecorder(current)
        try {
            val notifications=getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,"수색 주행 기록",NotificationManager.IMPORTANCE_LOW))
            val flagsPi=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),flagsPi)
            val stop=PendingIntent.getService(this,1,Intent(this,DriveService::class.java).setAction(STOP),flagsPi)
            val notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_drive_notification)
                .setContentTitle("FINDRIVE 주행 기록 중").setContentText("수색 반경 ${current.radius}m · 종료하려면 누르세요.")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null,"기록 종료",stop).build()).build()
            startForeground(4210,notification)
            check(manager.isLocationEnabled) {"차량 위치 사용이 꺼져 있습니다."}
            if(hasDriveFused(this)) {
                fused=subscribeDriveFused(this){p->main.post{if(area===current)sample(p)}}
            } else manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,2000,0f,gps,Looper.getMainLooper())
            store.ready=true
            main.postDelayed(timeout,2*60*60*1000L)
            main.postDelayed(signalCheck,5000)
        } catch(error:Exception) {finish("주행 기록을 시작하지 못했습니다. 차량 위치 설정과 권한을 확인해 주세요.")}
        return START_NOT_STICKY
    }
    private fun releaseInputs() {
        main.removeCallbacksAndMessages(null)
        runCatching{fused?.close()};fused=null;runCatching{manager.removeUpdates(gps)}
    }
    override fun onDestroy() {
        releaseInputs()
        if(area!=null && store.active===area) store.end("주행 기록을 종료했습니다.")
        area=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        private const val CHANNEL="findrive-drive"
        private const val STOP="app.findrive.car.END_SEARCH"
        internal fun start(context:Context,area:DriveArea) {
            val store=DriveStore.get(context)
            store.begin(area)
            try {context.startForegroundService(Intent(context,DriveService::class.java))}
            catch(e:Exception){store.end("주행 기록을 시작하지 못했습니다.");throw e}
        }
        internal fun stop(context:Context,message:String="주행 기록을 종료했습니다.") {
            DriveStore.get(context).end(message);context.stopService(Intent(context,DriveService::class.java))
        }
    }
}

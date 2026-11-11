package app.findrive.car

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

private fun JSONArray.items():List<JSONObject> = (0 until length()).mapNotNull{optJSONObject(it)}
class MainActivity:Activity(),CarActions {
    private val handler=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val prefs by lazy{getSharedPreferences("findrive-demo",Context.MODE_PRIVATE)}
    private val vault by lazy{ConnectionVault(this)}
    private val locationManager by lazy{getSystemService(LocationManager::class.java)}
    private lateinit var screen:CarScreen
    private lateinit var design:CarDesign
    private var gateway:VehicleGateway?=null
    private var vehicle=VehicleState()
    private var started=false
    private var busy=false
    private var wasSafe=false
    private var epoch=0
    private var token=""
    private var simulatedParked=true
    private var lastLocation:Location?=null
    private var snapshot=emptySnapshot()
    private var plans=JSONArray()
    private val driveStore by lazy { DriveStore.get(this) }
    private var roadPlans=JSONObject()
    private var lastCourseAttempt=0L
    private var localPasses=JSONArray()
    private var driveRevision=-1L
    private var readingDrive=false
    private var wasRecording=false
    private var permissionDrive:Pair<DriveArea,DriveRoad?>?=null
    private var pending=JSONArray()
    private var dialog:Dialog?=null
    private var dialogAction:Button?=null
    private var lastSync=""
    private val locationListener=object:LocationListener {
        override fun onLocationChanged(location:Location) {
            val hadPoint=point()!=null;lastLocation=location
            screen.updateTelemetry(locationText(),vehicle.navigationStatus)
            if(!hadPoint&&point()!=null)render()
        }
    }
    private val tick=object:Runnable {
        override fun run(){if(started){updateSafety();if(safe())refreshDrive();handler.postDelayed(this,500)}}
    }
    private val poll=object:Runnable {
        override fun run(){if(started){if(connected()&&safe())synchronize();handler.postDelayed(this,15_000)}}
    }
    private fun emptySnapshot()=JSONObject().put("pets",JSONArray()).put("reports",JSONArray()).put("sightings",JSONArray())
    private fun connected()=prefs.getBoolean("session",false)&&prefs.getBoolean("connected",false)
    private fun base()=if(prefs.getBoolean("custom-server",false))prefs.getString("url","")!!
        else BuildConfig.SERVICE_URL.ifBlank{prefs.getString("url",null)?.takeIf{it.isNotBlank()}?:if(BuildConfig.DEBUG)"http://10.0.2.2:8787" else ""}
    private fun safe()=started&&SafetyGate.canInteract(vehicle,SystemClock.elapsedRealtime())

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        simulatedParked=savedInstanceState?.getBoolean("simulatedParked",true)?:true
        runCatching {
            snapshot=JSONObject(prefs.getString("snapshot",snapshot.toString())!!)
            pending=JSONArray(prefs.getString("pending","[]")!!)
            plans=JSONArray(prefs.getString("plans","[]")!!)
            roadPlans=JSONObject(prefs.getString("road-plans","{}")!!)
            token=vault.read();lastSync=prefs.getString("last-sync","")?:""
        }.onFailure{prefs.edit().putBoolean("connected",false).apply()}
        design=CarDesign(this);screen=CarScreen(this,design,this,savedInstanceState)
        if(BuildConfig.SIMULATION)screen.root.addView(Switch(this).apply {
            text="시연 P단 · 실제 차량 신호 아님";textSize=22f;typeface=design.regular
            setTextColor(design.colors.ink);minimumHeight=design.dp(80);isChecked=simulatedParked
            setOnCheckedChangeListener{_,value->simulatedParked=value;gateway?.simulateParked(value)}
        })
        setContentView(screen.root);render()
        screen.updateSync(if(connected())if(lastSync.isBlank())"저장된 연결" else lastSync+" 갱신" else "연결 전")
    }
    override fun onStart() {
        super.onStart();started=true;vehicle=VehicleState();epoch++;wasSafe=false
        gateway=createGateway(this).also{it.start({state->runOnUiThread {
            if(started) {
                val hadPoint=point()!=null;val ready=vehicle.navigationReady
                vehicle=state;screen.updateTelemetry(locationText(),state.navigationStatus);updateSafety()
                if((!hadPoint&&point()!=null)||ready!=state.navigationReady)render()
            }
        }},{note->runOnUiThread {
            // Routine navigation callbacks belong in the connection page, not a repeating alert.
            if(started&&(note.contains("오류")||note.contains("취소")))screen.showNotice(note)
        }})}
        if(BuildConfig.SIMULATION)gateway?.simulateParked(simulatedParked)
        handler.post(tick);handler.post(poll);startLocation()
    }
    override fun onStop() {
        started=false;epoch++;busy=false;readingDrive=false;vehicle=VehicleState();handler.removeCallbacksAndMessages(null)
        dialog?.dismiss();dialog=null;dialogAction=null;gateway?.close();gateway=null
        locationManager.removeUpdates(locationListener);lastLocation=null;updateSafety();super.onStop()
    }
    override fun onDestroy(){io.shutdown();super.onDestroy()}
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("simulatedParked",simulatedParked);screen.save(outState);super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform back dispatch")
    override fun onBackPressed(){if(!screen.back())super.onBackPressed()}
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(code,permissions,grantResults)
        if(code==101){startLocation();gateway?.startLocation();screen.updateTelemetry(locationText(),vehicle.navigationStatus)}
        if(code==102){
            val request=permissionDrive;permissionDrive=null
            if(request!=null && safe())beginDrive(request.first,request.second)
            else if(request!=null)screen.showNotice("주차 후 다시 수색을 시작해 주세요.")
        }
    }
    override fun location() {
        val permissions=(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)+(gateway?.locationPermissions()?:emptyArray()))
            .filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(permissions.isNotEmpty())requestPermissions(permissions.toTypedArray(),101)
        else{startLocation();gateway?.startLocation();screen.showNotice("위치를 확인하고 있습니다.",warning=false)}
    }
    private fun startLocation() {
        if(driveStore.active!=null)return
        if(connected())gateway?.startLocation()
        if(!connected()||BuildConfig.SIMULATION||checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        runCatching{locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,5000,10f,locationListener)}
    }
    private fun point():VehicleFix? {
        if(BuildConfig.SIMULATION)return VehicleFix(37.5445,127.0374,SystemClock.elapsedRealtime(),"서울숲 시연 위치")
        driveStore.latest?.let{p->val age=System.currentTimeMillis()-p.at
            if(age in 0..5000)return VehicleFix(p.coord.lat,p.coord.lon,SystemClock.elapsedRealtime()-age,p.source)}
        val gps=lastLocation
        if(gps!=null&&SystemClock.elapsedRealtimeNanos()-gps.elapsedRealtimeNanos in 0 until 60_000_000_000&&gps.hasAccuracy()&&gps.accuracy<=100&&!gps.isFromMockProvider)
            return VehicleFix(gps.latitude,gps.longitude,gps.elapsedRealtimeNanos/1_000_000,"GPS · 오차 약 "+gps.accuracy.toInt()+"m")
        return vehicle.location?.takeIf{SafetyGate.usable(it,SystemClock.elapsedRealtime())}
    }
    private fun locationText()=point()?.let{it.source+" · 위치 수신됨"}?:vehicle.locationStatus
    private fun updateSafety() {
        if(!::screen.isInitialized)return
        val allowed=safe();screen.setInteraction(allowed,busy);dialogAction?.isEnabled=allowed&&!busy
        if(!allowed) {
            if(dialog!=null){dialog?.dismiss();dialog=null;dialogAction=null}
            if(wasSafe){currentFocus?.clearFocus();(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(screen.root.windowToken,0)}
        }
        if(allowed&&!wasSafe&&connected())handler.post{if(safe())synchronize()}
        wasSafe=allowed
    }
    private fun render(){screen.update(CarContent(connected(),prefs.getBoolean("paired",false),snapshot,plans,pending.length()+localPasses.length(),point(),vehicle.navigationReady,roadPlans,localPasses,driveStore.active,driveStore.status,driveRevision))}
    private fun refreshDrive() {
        val revision=driveStore.revision
        if(readingDrive || revision==driveRevision)return
        val ticket=epoch;readingDrive=true
        io.execute {
            val read=runCatching{driveStore.pending()}
            runOnUiThread {
                if(ticket!=epoch || !started)return@runOnUiThread
                readingDrive=false
                read.onSuccess{localPasses=it;driveRevision=revision
                    if(wasRecording && driveStore.active==null)startLocation()
                    wasRecording=driveStore.active!=null;render()
                }.onFailure{screen.showNotice("저장된 주행 기록을 읽지 못했습니다.")}
            }
        }
    }
    override fun connect() {
        try {
            val endpoint=ConnectionClient.validate(base())
            var id=prefs.getString("device-id",null)
            if(id==null){id=UUID.randomUUID().toString();check(prefs.edit().putString("device-id",id).commit())}
            if(token.isBlank()){token="fd_"+UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");vault.save(token)}
            screen.showNotice("")
            val request=JSONObject().put("kind","vehicle").put("deviceId",id).put("credential",token).put("consentVersion","2026-11-11")
            runRequest({ConnectionClient.request(endpoint,"/v1/sessions",body=request)}){result->
                token=result.getString("token");vault.save(token)
                check(prefs.edit().putString("url",endpoint).putBoolean("session",true).putBoolean("connected",true).remove("token").commit())
                render();if(safe())location();synchronize()
            }
        }catch(error:Exception){screen.showNotice(error.message?:"연결을 시작하지 못했습니다.")}
    }
    override fun refresh(){screen.showNotice("");synchronize()}
    private fun synchronize() {
        if(busy||!connected()||!safe())return
        val sent=JSONArray(pending.toString());val endpoint=base();val credential=token
        var driveBatch=JSONArray()
        runRequest({
            driveBatch=driveStore.pending(100)
            ConnectionClient.request(endpoint,"/v1/sync",credential,JSONObject().put("commands",sent).put("vehiclePasses",driveBatch))
        }){result->
            val received=result.getJSONObject("snapshot")
            received.getJSONArray("pets");received.getJSONArray("reports");received.getJSONArray("sightings")
            val ack=result.getJSONArray("appliedCommandIds");val ids=(0 until ack.length()).map{ack.getString(it)}.toSet()
            check(sent.items().all{it.getString("id") in ids}){"저장 확인이 누락되었습니다"}
            val remaining=JSONArray();pending.items().filter{it.getString("id") !in ids}.forEach{remaining.put(it)}
            val nextPlans=result.optJSONArray("plans")?:JSONArray()
            val active=driveStore.active
            if(active!=null && nextPlans.records().none{it.area(active.radius)==active}) DriveService.stop(this,"신고의 수색 기준이 바뀌어 주행 기록을 종료했습니다.")
            val retainedPlans=JSONObject()
            nextPlans.records().forEach{plan->
                val id=plan.optString("reportId");val old=roadPlans.optJSONObject(id);val area=old?.area()
                if(area!=null && area==plan.area(area.radius))retainedPlans.put(id,old)
            }
            val now=java.text.SimpleDateFormat("HH:mm",java.util.Locale.KOREA).format(java.util.Date())
            check(prefs.edit().putString("snapshot",received.toString()).putString("plans",nextPlans.toString()).putString("road-plans",retainedPlans.toString()).putString("pending",remaining.toString()).putString("last-sync",now).commit()){"기기에 저장하지 못했습니다"}
            snapshot=received;plans=nextPlans;pending=remaining;lastSync=now;roadPlans=retainedPlans
            val settled=result.optJSONArray("settledPassIds")
            if(settled!=null && settled.length()>0) {
                val sentIds=driveBatch.records().map{it.optString("id")}.toSet()
                val confirmed=(0 until settled.length()).map{settled.getString(it)}.filter{it in sentIds}
                io.execute{driveStore.acknowledge(confirmed)}
                if(confirmed.size==100)handler.postDelayed({if(started&&safe())synchronize()},1000)
            }
            screen.updateSync(now+" 갱신");render()
            val visible=screen.visibleDriveArea
            val saved=visible?.let{roadPlans.optJSONObject(it.reportId)}
            val updated=nextPlans.records().firstOrNull{it.optString("reportId")==visible?.reportId}
            if(visible!=null && saved?.area()==visible && updated!=null && active==null &&
                saved.optString("searchRevision")!=updated.optString("searchRevision") && SystemClock.elapsedRealtime()-lastCourseAttempt>=30000) {
                handler.post{if(started && safe() && !busy && driveStore.active==null && screen.visibleDriveArea==visible)planRoads(visible)}
            }
        }
    }
    override fun pair() {
        if(driveStore.active!=null || driveStore.hasUnsent()){screen.showNotice("차량 수색을 종료하고 주행 기록을 동기화해 주세요.");return}
        if(pending.length()>0){screen.showNotice("미전송 제보를 먼저 동기화해 주세요.");return}
        val field=input("6자리 연결 번호",InputType.TYPE_CLASS_NUMBER).apply {
            filters=arrayOf(android.text.InputFilter.LengthFilter(6));textSize=40f;letterSpacing=.18f;gravity=Gravity.CENTER
        }
        showDialog("휴대폰 연결","휴대폰 설정에서 ‘차량 연결 번호 만들기’를 누르세요.",field,"연결하기") {
            val code=field.text.toString()
            if(!Regex("[0-9]{6}").matches(code)){field.error="6자리 번호를 입력해 주세요";false}
            else {
                runRequest({ConnectionClient.request(base(),"/v1/pairings/claim",token,JSONObject().put("code",code))}){result->
                    token=result.getString("token");vault.save(token);prefs.edit().putBoolean("paired",true).apply()
                    screen.showNotice("휴대폰과 연결됐습니다.",warning=false);render();synchronize()
                };true
            }
        }
    }
    override fun disconnect() {
        confirm("이 차량의 연결을 지울까요?","미전송 제보 "+pending.length()+"건과 이 차량의 저장 자료를 지웁니다.\n휴대폰 자료는 유지됩니다.","연결 지우기") {
            runRequest({ConnectionClient.request(base(),"/v1/session",token,method="DELETE")}){clearConnection();recreate()}
        }
    }
    private fun clearConnection() {
        DriveService.stop(this,"");driveStore.clear();roadPlans=JSONObject();localPasses=JSONArray();permissionDrive=null
        vault.clear();token="";prefs.edit().clear().apply();pending=JSONArray();snapshot=emptySnapshot();plans=JSONArray()
        lastLocation=null;locationManager.removeUpdates(locationListener);gateway?.close();gateway=null;vehicle=VehicleState()
    }
    override fun sighting(report:JSONObject,pet:JSONObject) {
        confirm("직접 본 동물을 제보할까요?","사진 없는 목격은 미확인 단서로 공유됩니다.\n"+locationText(),"목격 제보 보내기"){sendSighting(report,pet)}
    }
    private fun sendSighting(report:JSONObject,pet:JSONObject) {
        val fix=point()?:run{screen.showNotice("새 위치가 필요합니다. 위치 사용과 수신 상태를 확인해 주세요.");return}
        val previous=prefs.getLong("last-"+report.getString("id"),0)
        if(System.currentTimeMillis()-previous<300_000){screen.showNotice("5분 안에 같은 신고에 보낸 제보가 있습니다.");return}
        val event=JSONObject().put("id",UUID.randomUUID().toString()).put("reportId",report.getString("id")).put("species",pet.getString("species"))
            .put("location",JSONObject().put("lat",fix.lat).put("lon",fix.lon).put("label",fix.source+" · 직접 목격"))
            .put("seenAt",Instant.now().toString()).put("confidence",JSONObject.NULL).put("source",if(BuildConfig.SIMULATION)"simulation" else "manual").put("status","pending")
        val next=JSONArray(pending.toString()).put(JSONObject().put("id",UUID.randomUUID().toString()).put("type","sighting.create").put("sighting",event))
        if(!prefs.edit().putString("pending",next.toString()).putLong("last-"+report.getString("id"),System.currentTimeMillis()).commit()){screen.showNotice("제보를 저장하지 못했습니다.");return}
        pending=next;screen.showNotice("제보를 저장했습니다. 연결되면 전송합니다.",warning=false);render();synchronize()
    }
    override fun report(report:JSONObject) {
        confirm("이 내용을 신고하고 숨길까요?","검토 담당자에게 접수합니다.","신고하기") {
            runRequest({ConnectionClient.request(base(),"/v1/abuse",token,JSONObject().put("kind","report").put("targetId",report.getString("id")).put("reason","차량에서 부적절한 공유 내용으로 신고"))}) {
                screen.showNotice("검토 요청을 접수했습니다.",warning=false);synchronize()
            }
        }
    }
    override fun route(lat:Double,lon:Double,name:String) {
        if(BuildConfig.SIMULATION){screen.showNotice("시연 앱에서는 실제 길 안내를 실행하지 않습니다.");return}
        confirm("이 위치로 안내할까요?",name+"\n기존 내비게이션 목적지가 바뀔 수 있습니다.","길 안내 시작") {
            runCatching{gateway?.route(lat,lon,name);screen.showNotice("길 안내를 요청했습니다.",warning=false)}
                .onFailure{screen.showNotice(it.message?:"길 안내를 요청하지 못했습니다.")}
        }
    }
    override fun chooseRadius(current:Int,recommended:Int,selected:(Int)->Unit) {
        val field=input("수색 반경 (m)",InputType.TYPE_CLASS_NUMBER).apply {
            setText(String.format(java.util.Locale.KOREA,"%d",current));setSelectAllOnFocus(true);filters=arrayOf(android.text.InputFilter.LengthFilter(5))
        }
        showDialog("수색 반경 변경","100~10,000m 사이에서 선택하세요. 이 신고의 권장 반경은 ${recommended}m입니다.",field,"반경 적용") {
            val meters=field.text.toString().toIntOrNull()
            if(meters==null||meters !in 100..10000){field.error="100~10,000m 사이의 값을 입력해 주세요.";false}
            else {selected(meters);true}
        }
    }
    override fun planRoads(area:DriveArea) {
        val endpoint=base();val credential=token
        if(!safe() || !connected() || busy)return
        lastCourseAttempt=SystemClock.elapsedRealtime()
        screen.showNotice("최근 단서와 지형으로 수색 코스를 계산하고 있습니다.",false)
        runRequest({ConnectionClient.request(endpoint,"/v1/reports/${area.reportId}/roads?radius=${area.radius}",credential,method="GET",timeoutMs=20000)}){result->
            check(result.area()==area){"수색 기준이 바뀌었습니다. 신고를 새로고침해 주세요."}
            val next=JSONObject(roadPlans.toString()).put(area.reportId,result)
            check(prefs.edit().putString("road-plans",next.toString()).commit()){"도로 계획을 저장하지 못했습니다."}
            roadPlans=next;screen.showNotice("");render()
        }
    }
    override fun drive(area:DriveArea,road:DriveRoad?) {
        if(BuildConfig.SIMULATION){screen.showNotice("도로 계획을 확인할 수 있습니다. 차량 주행 기록과 길 안내는 Pleos 앱에서 시작해 주세요.");return}
        if(driveStore.active!=null && driveStore.active!=area){screen.showNotice("진행 중인 수색을 종료한 뒤 다른 반경을 선택해 주세요.");return}
        val recording=driveStore.active==area
        val detail=(if(recording)"" else "선택한 반경 안의 위치와 통과 시각을 참여자와 공유합니다. 길 안내 중에도 기록하며, 종료 버튼 또는 2시간 뒤에 멈춥니다.")+
            (if(road!=null)"\n${road.name}의 진입점과 종점으로 안내합니다. 내비게이션이 정하는 연결 경로는 반경 밖을 지날 수 있습니다." else "")
        confirm(if(road==null)"주행을 기록할까요?" else "이 구간으로 안내할까요?",detail.trim(),if(recording)"길 안내 시작" else "수색 시작") {
            if(recording) {navigateRoad(road);return@confirm}
            val permissions=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
            permissions.addAll(gateway?.locationPermissions()?.toList().orEmpty())
            if(android.os.Build.VERSION.SDK_INT>=33)permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing=permissions.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
            if(missing.isNotEmpty()){
                permissionDrive=area to road
                // Android requires FINE and COARSE together when upgrading from approximate location.
                requestPermissions((if(Manifest.permission.ACCESS_FINE_LOCATION in missing)missing+Manifest.permission.ACCESS_COARSE_LOCATION else missing).distinct().toTypedArray(),102)
            }else beginDrive(area,road)
        }
    }
    private fun beginDrive(area:DriveArea,road:DriveRoad?) {
        if(!safe() || plans.records().none{it.area(area.radius)==area})return
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED ||
            gateway?.locationPermissions()?.any{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}==true || !locationManager.isLocationEnabled) {
            screen.showNotice("차량 위치 사용과 정확한 위치 권한을 켠 뒤 다시 시작해 주세요.");return
        }
        runCatching {
            locationManager.removeUpdates(locationListener);lastLocation=null;gateway?.stopLocation()
            DriveService.start(this,area);wasRecording=true
            screen.showNotice("");render()
            if(road!=null) {
                val deadline=SystemClock.elapsedRealtime()+5000
                fun handoff() {
                    if(!started || driveStore.active!=area)return
                    if(!safe()){screen.showNotice("주차 상태가 바뀌어 길 안내 요청을 취소했습니다.");return}
                    if(driveStore.ready)navigateRoad(road)
                    else if(SystemClock.elapsedRealtime()<deadline)handler.postDelayed({handoff()},120)
                    else screen.showNotice("위치 연결을 확인한 뒤 구간 길 안내를 다시 눌러 주세요.")
                }
                handler.post{handoff()}
            }
        }.onFailure{startLocation();screen.showNotice(it.message?:"주행 기록을 시작하지 못했습니다.")}
    }
    private fun navigateRoad(road:DriveRoad?) {
        if(road==null || !safe())return
        runCatching{checkNotNull(gateway).routeRoad(road);screen.showNotice("구간 길 안내를 요청했습니다.",false)}
            .onFailure{screen.showNotice(it.message?:"구간 길 안내를 요청하지 못했습니다.")}
    }
    override fun stopDrive() {
        if(!safe())return
        DriveService.stop(this);screen.showNotice("주행 기록을 종료했습니다.",false);startLocation();render();synchronize()
    }
    override fun mapCredits(area:DriveArea) {
        showDialog("도로 데이터","OpenFreeMap · © OpenMapTiles · © OpenStreetMap 기여자 · ODbL\n자동차 경로 계산 OSRM · 기본 제공 FOSSGIS\n선택한 수색 기준 주변의 도로를 사용합니다.",null,"지도 오류 제보") {
            val url="https://www.openstreetmap.org/fixthemap?lat=${area.center.lat}&lon=${area.center.lon}&zoom=17"
            runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{screen.showNotice("차량에 브라우저가 없습니다. openstreetmap.org/fixthemap에서 제보할 수 있습니다.")};true
        }
    }
    override fun policy() {
        val text=design.text(
            "수집\n기기 연결 정보, 반려동물 정보, 실종·목격 위치와 시각, 수색 경로.\n\n"+
            "목적\n공동 수색과 길 안내. 수색 계획 시 주변 지도 타일을 OpenFreeMap에, 반경 안의 코스 후보 좌표를 도로 서비스에 요청합니다. 차량에서 주행 기록을 시작하면 길 안내 중에도 반경 안의 위치와 통과 시각을 기록해 참여자에게 공유합니다. 화면이나 알림에서 종료할 수 있으며 최대 2시간 뒤 멈춥니다.\n\n"+
            "보관\n수색 자료는 직접 삭제할 때까지, 전송한 사진·영상은 7일.\n\n"+
            "철회\n차량 연결 해제는 이 차량의 자료와 권한을 지웁니다. 전체 자료 삭제는 휴대폰 설정의 ‘동의 철회·내 자료 삭제’에서 진행하세요.\n\n"+
            "동의하지 않으면 공유 기능을 사용하지 않습니다.\n팀 테스트 안내 · 2026-11-11",24,lines=Int.MAX_VALUE)
        val content=ScrollView(this).apply{addView(text)}
        showDialog("공유·위치정보 안내",null,content,"전체 안내",scrollBody=true) {
            runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(base()+"/privacy")))}
                .onFailure{screen.showNotice("차량에 브라우저가 없습니다. 휴대폰 설정에서 전체 안내를 확인하세요.")};true
        }
    }
    override fun licenses() {
        val text=assets.open("licenses/Asta-Sans-OFL.txt").bufferedReader().use{it.readText()}
        showDialog("Asta Sans", "42dot · SIL Open Font License 1.1",
            ScrollView(this).apply{addView(design.text(text,22,lines=Int.MAX_VALUE))},"확인",scrollBody=true,negative=null){true}
    }
    override fun advanced() {
        val field=input("서버 주소",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply{setText(base());textSize=24f;letterSpacing=0f}
        showDialog("고급 연결 설정","일반 연결에는 주소 입력이 필요하지 않습니다.",field,"주소 저장") {
            runCatching {
                check(token.isBlank()){"이미 만든 연결이 있습니다. 연결을 먼저 해제해 주세요."}
                val url=ConnectionClient.validate(field.text.toString());prefs.edit().putString("url",url).putBoolean("custom-server",true).apply()
            }.onFailure{field.error=it.message}.isSuccess
        }
    }
    private fun input(hintText:String,type:Int)=EditText(this).apply {
        hint=hintText;contentDescription=hintText;inputType=type;setSingleLine(true);typeface=design.regular
        imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setTextColor(design.colors.ink);setHintTextColor(design.colors.muted);setPadding(design.dp(24),design.dp(20),design.dp(24),design.dp(20))
        minHeight=design.dp(96);background=design.shape(design.colors.subtle,20)
    }
    private fun confirm(title:String,detail:String,positive:String,action:()->Unit) {
        showDialog(title,detail,null,positive){action();true}
    }
    private fun showDialog(title:String,detail:String?,view:View?,positive:String,scrollBody:Boolean=false,negative:String?="취소",action:()->Boolean) {
        if(!safe())return
        dialog?.dismiss()
        val modal=Dialog(this,R.style.Theme_Findrive_Dialog)
        val panel=design.column(32).apply{background=design.shape(design.colors.surface,28)}
        panel.addView(design.text(title,34,true))
        if(detail!=null){panel.addView(design.space(16));panel.addView(design.text(detail,26,false,design.colors.muted))}
        if(view!=null) {
            panel.addView(design.space(24))
            panel.addView(view,LinearLayout.LayoutParams(-1,if(scrollBody)design.dp(minOf(360,(resources.configuration.screenHeightDp-340).coerceAtLeast(120))) else -2))
        }
        panel.addView(design.space(28))
        val row=if(resources.configuration.screenWidthDp<650)design.column() else design.row()
        val accept=design.button(positive,true).apply {
            isEnabled=!busy
            setOnClickListener {
                if(safe()&&!busy&&action()){modal.dismiss();if(dialog===modal){dialog=null;dialogAction=null}}
            }
        }
        negative?.let {
            val cancel=design.button(it).apply{setOnClickListener{modal.dismiss()}}
            row.addView(cancel,LinearLayout.LayoutParams(if(row.orientation==LinearLayout.HORIZONTAL)0 else -1,-2,if(row.orientation==LinearLayout.HORIZONTAL)1f else 0f)
                .apply{if(row.orientation==LinearLayout.HORIZONTAL)marginEnd=design.dp(16) else bottomMargin=design.dp(16)})
        }
        row.addView(accept,LinearLayout.LayoutParams(if(row.orientation==LinearLayout.HORIZONTAL)0 else -1,-2,if(row.orientation==LinearLayout.HORIZONTAL)1f else 0f))
        panel.addView(row)
        // Small windows and the on-screen keyboard may leave less room than the dialog content.
        modal.setContentView(ScrollView(this).apply{addView(panel);isFillViewport=false})
        modal.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        modal.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        modal.window?.setDimAmount(.48f)
        modal.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        modal.setOnDismissListener{if(dialog===modal){dialog=null;dialogAction=null}}
        dialog=modal;dialogAction=accept;modal.show()
        modal.window?.setLayout(design.dp(minOf(800,resources.configuration.screenWidthDp-48)),-2)
        (view as? EditText)?.setOnEditorActionListener{_,id,_->if(id==EditorInfo.IME_ACTION_DONE){accept.performClick();true}else false}
    }
    private fun runRequest(work:()->JSONObject,done:(JSONObject)->Unit) {
        if(busy||!safe())return
        busy=true;updateSafety();val ticket=epoch
        io.execute {
            val result=runCatching(work)
            runOnUiThread {
                if(ticket!=epoch||!started)return@runOnUiThread
                busy=false
                result.onSuccess{runCatching{done(it)}.onFailure{error->screen.showNotice(error.message?:"처리를 완료하지 못했습니다.")}}
                    .onFailure {
                        if(it is ConnectionFailure&&it.status==401){runCatching{clearConnection()};recreate()}
                        else{screen.updateSync("연결 다시 확인");screen.showNotice((it as? ConnectionFailure)?.message?:"연결을 확인해 주세요. 저장된 자료는 유지합니다.")}
                    }
                updateSafety()
            }
        }
    }
}

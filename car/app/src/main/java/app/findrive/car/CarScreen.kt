package app.findrive.car

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
import android.location.Location
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun JSONArray?.objects()=(0 until (this?.length()?:0)).mapNotNull{this?.optJSONObject(it)}
data class CarContent(
    val connected:Boolean, val paired:Boolean, val snapshot:JSONObject, val plans:JSONArray,
    val pending:Int, val fix:VehicleFix?, val navigationReady:Boolean,
    val roadPlans:JSONObject=JSONObject(), val localPasses:JSONArray=JSONArray(),
    val recording:DriveArea?=null, val driveStatus:String="", val driveRevision:Long=0,
)
interface CarActions {
    fun connect(); fun pair(); fun location(); fun refresh(); fun policy(); fun advanced(); fun disconnect()
    fun route(lat:Double,lon:Double,name:String)
    fun planRoads(area:DriveArea)
    fun chooseRadius(current:Int,recommended:Int,selected:(Int)->Unit)
    fun drive(area:DriveArea,road:DriveRoad?)
    fun stopDrive()
    fun mapCredits(area:DriveArea)
    fun sighting(report:JSONObject,pet:JSONObject)
    fun report(report:JSONObject)
    fun licenses()
}
private data class SearchItem(val report:JSONObject,val pet:JSONObject,val plan:JSONObject?,val center:JSONObject,val distance:Float?,val latest:JSONObject?) {
    val id:String get()=report.optString("id")
    val name:String get()=pet.optString("name")
}

/** A bounded master/detail screen. Telemetry never reconstructs the content hierarchy. */
class CarScreen(private val context:Context, val design:CarDesign, private val actions:CarActions, saved:Bundle?) {
    private val c=design.colors
    private val width=context.resources.configuration.screenWidthDp
    private val wide=width>=1024 && context.resources.configuration.fontScale<=1.35f
    val root=design.column().apply {
        setBackgroundColor(c.background);fitsSystemWindows=true
        setPadding(design.dp(if(width<700)24 else 40),design.dp(24),design.dp(if(width<700)24 else 40),design.dp(24))
    }
    private val body=design.column()
    private val canvas=FrameLayout(context)
    private val locked=design.column(32).apply{gravity=Gravity.CENTER}
    private val status=design.text("차량 상태 확인 중",22,true,c.muted,1)
    private val connection=design.text("연결 전",22,false,c.muted,1)
    private val notice=design.text("",24,false,c.warning).apply{visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE}
    private val contentHost=design.column()
    private val controls=mutableListOf<Pair<View,Boolean>>()
    private val headerControls=mutableListOf<View>()
    private val scrollers=mutableMapOf<String,ScrollView>()
    private val offsets=mutableMapOf<String,Int>()
    private var locationLabel:TextView?=null
    private var navigationLabel:TextView?=null
    private var telemetryLocation="위치 사용을 허용해 주세요"
    private var telemetryNavigation="내비게이션 연결 중"
    private var allowed=false
    private var busy=false
    private var tab=saved?.getString("screen-tab")?:"search"
    private var selected=saved?.getString("screen-selection")
    private var page=saved?.getInt("screen-page")?:0
    private var driveReport=saved?.getString("drive-report")
    private var chosenRadius=saved?.getInt("drive-radius")?:0
    var visibleDriveArea:DriveArea?=null
        private set
    private var chosenRoad=saved?.getString("drive-road")
    private var data:CarContent?=null
    private var fingerprint=""
    private lateinit var searchTab:Button
    private lateinit var settingsTab:Button
    private var syncText="연결 전"

    init {
        saved?.getBundle("screen-offsets")?.let{bundle->bundle.keySet().forEach{offsets[it]=bundle.getInt(it)}}
        val header=if(width<700)design.column() else design.row()
        val brand=design.row()
        brand.addView(ImageView(context).apply{setImageResource(R.drawable.findrive_icon);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(design.dp(42),design.dp(42)).apply{marginEnd=design.dp(14)})
        brand.addView(design.text("FINDRIVE",30,true,lines=1))
        if(width<700)header.addView(brand) else header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
        val tabs=design.row()
        searchTab=design.button("주변 수색",glyph=CarGlyph.SEARCH).apply{if(width>=700){minWidth=design.dp(188);minimumWidth=minWidth};setOnClickListener{if(allowed){tab="search";driveReport=null;selected=null;render(force=true)}}}
        settingsTab=design.button("연결",glyph=CarGlyph.LINK).apply{if(width>=700){minWidth=design.dp(144);minimumWidth=minWidth};setOnClickListener{if(allowed){tab="settings";render(force=true)}}}
        headerControls.addAll(listOf(searchTab,settingsTab))
        tabs.addView(searchTab,LinearLayout.LayoutParams(if(width<700)0 else -2,-2,if(width<700)1f else 0f).apply{marginEnd=design.dp(12)})
        tabs.addView(settingsTab,LinearLayout.LayoutParams(if(width<700)0 else -2,-2,if(width<700)1f else 0f))
        header.addView(tabs,LinearLayout.LayoutParams(if(width<700)-1 else -2,-2).apply{if(width<700)topMargin=design.dp(20)})
        root.addView(header)
        val statusRow=design.row().apply{setPadding(0,design.dp(22),0,design.dp(24))}
        statusRow.addView(status,LinearLayout.LayoutParams(0,-2,1f));statusRow.addView(connection)
        root.addView(statusRow)
        body.addView(notice,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=design.dp(16)})
        body.addView(contentHost,LinearLayout.LayoutParams(-1,0,1f))
        canvas.addView(body,FrameLayout.LayoutParams(-1,-1))
        locked.addView(icon(CarGlyph.LOCK,72,c.muted));locked.addView(design.space(28))
        locked.addView(design.text("주차 후 이용해 주세요",40,true).apply{gravity=Gravity.CENTER})
        locked.addView(design.space(16));locked.addView(design.text("안전한 주차 상태가 확인되면\n수색 화면으로 돌아갑니다.",26,false,c.muted).apply{gravity=Gravity.CENTER})
        canvas.addView(locked,FrameLayout.LayoutParams(-1,-1));root.addView(canvas,LinearLayout.LayoutParams(-1,0,1f))
        setInteraction(false,false)
    }
    private fun icon(glyph:CarGlyph,size:Int,color:Int=c.accent)=ImageView(context).apply {
        setImageDrawable(CarIcon(glyph,color,design.dp(size)));layoutParams=LinearLayout.LayoutParams(design.dp(size),design.dp(size))
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun track(view:View,network:Boolean=false,action:()->Unit) {
        controls.add(view to network);view.isFocusable=true
        view.setOnClickListener{if(allowed&&(!network||!busy)){it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);action()}}
    }
    private fun action(label:String,primary:Boolean=false,glyph:CarGlyph?=null,network:Boolean=false,run:()->Unit)=design.button(label,primary,glyph).also{track(it,network,run)}
    private fun LinearLayout.addAction(view:View) {addView(view,LinearLayout.LayoutParams(-1,-2).apply{topMargin=design.dp(16)})}
    private fun surface(padding:Int=28)=design.column(padding).apply{background=design.shape(c.surface)}
    private fun heading(title:String,subtitle:String?=null,parent:LinearLayout=contentHost) {
        parent.addView(design.text(title,if(width<700)40 else 48,true));subtitle?.let{parent.addView(design.space(10));parent.addView(design.text(it,24,false,c.muted))}
        parent.addView(design.space(24))
    }
    private fun scroll(view:View,key:String):ScrollView=ScrollView(context).apply {
        isFillViewport=true;isVerticalScrollBarEnabled=true;clipToPadding=false
        addView(view,FrameLayout.LayoutParams(-1,-2));scrollers[key]=this
        post{scrollTo(0,offsets[key]?:0)}
    }
    fun showNotice(text:String,warning:Boolean=true) {
        if(notice.text.toString()!=text)notice.text=text
        notice.setTextColor(if(warning)c.warning else c.accent)
        notice.visibility=if(text.isBlank())View.GONE else View.VISIBLE
    }
    fun updateSync(text:String){syncText=text;updateConnection()}
    private fun updateConnection(){val value=if(busy)"연결 확인 중…" else syncText;if(connection.text.toString()!=value)connection.text=value}
    fun updateTelemetry(location:String,navigation:String) {
        telemetryLocation=location;telemetryNavigation=navigation
        locationLabel?.let{if(it.text.toString()!=location)it.text=location}
        navigationLabel?.let{if(it.text.toString()!=navigation)it.text=navigation}
    }
    fun setInteraction(safe:Boolean,loading:Boolean) {
        allowed=safe;busy=loading
        val label=if(safe)"주차 중 · 수색 가능" else "안전한 주차 상태 확인 중"
        if(status.text.toString()!=label){status.text=label;status.setTextColor(if(safe)c.accent else c.muted)}
        body.visibility=if(safe)View.VISIBLE else View.INVISIBLE
        locked.visibility=if(safe)View.GONE else View.VISIBLE
        headerControls.forEach{if(it.isEnabled!=safe)it.isEnabled=safe}
        controls.forEach{(view,network)->val enabled=safe&&(!network||!loading);if(view.isEnabled!=enabled)view.isEnabled=enabled}
        updateConnection()
    }
    fun save(bundle:Bundle) {
        scrollers.forEach{(key,view)->offsets[key]=view.scrollY}
        bundle.putString("screen-tab",tab);bundle.putString("screen-selection",selected);bundle.putInt("screen-page",page)
        bundle.putString("drive-report",driveReport);bundle.putInt("drive-radius",chosenRadius);bundle.putString("drive-road",chosenRoad)
        bundle.putBundle("screen-offsets",Bundle().apply{offsets.forEach{(key,value)->putInt(key,value)}})
    }
    fun back():Boolean {
        if(driveReport!=null && tab=="search"){driveReport=null;render(force=true);return true}
        if(tab!="search"||selected!=null){tab="search";selected=null;render(force=true);return true};return false
    }
    fun update(content:CarContent){data=content;render()}
    private fun render(force:Boolean=false) {
        val model=data?:return
        val fix=model.fix?.let{"${(it.lat*1000).toInt()}:${(it.lon*1000).toInt()}"}?:"unknown"
        // elapsedHours advances on every server response; it is not displayed here.
        val visualPlans=model.plans.objects().joinToString { plan ->
            listOf("reportId","center","radiusMeters","anchorId","segmentCount","segments","hasSimulation","searchRevision").joinToString { key -> plan.opt(key)?.toString()?:"" }
        }
        val next="${model.connected}:${model.paired}:${model.snapshot}:$visualPlans:${model.pending}:$fix:${model.navigationReady}:${model.roadPlans}:${model.driveRevision}:$tab:$selected:$page:$driveReport:$chosenRadius:$chosenRoad"
        if(!force&&next==fingerprint)return
        fingerprint=next
        scrollers.forEach{(key,view)->offsets[key]=view.scrollY};scrollers.clear();controls.clear()
        visibleDriveArea=null
        contentHost.removeAllViews();locationLabel=null;navigationLabel=null
        if(model.recording!=null) contentHost.addView(action("주행 기록 종료 · 반경 ${distance(model.recording.radius.toFloat())}",glyph=CarGlyph.LOCATION){actions.stopDrive()},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=design.dp(12)})
        for((button,active) in listOf(searchTab to (tab=="search"),settingsTab to (tab=="settings"))) {
            button.isSelected=active
            button.background=StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled),design.shape(c.disabled))
                addState(intArrayOf(),design.ripple(if(active)c.accentSurface else c.background))
            }
            button.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(c.muted,if(active)c.accent else c.muted)))
            button.compoundDrawableTintList=button.textColors
        }
        when {
            !model.connected -> onboarding()
            tab=="settings" -> settings(model)
            else -> search(model)
        }
        setInteraction(allowed,busy)
    }
    private fun onboarding() {
        val page=design.column()
        heading("작은 발견을,\n집으로 가는 길로.","휴대폰의 수색 기록을 차량에서 이어가세요.",page)
        val columns=if(wide)design.row().apply{gravity=Gravity.TOP} else design.column()
        val intro=surface(32)
        for((glyph,title,copy) in listOf(
            Triple(CarGlyph.PAW,"발견을 함께","휴대폰에서 확인한 신고와 제보를 공유해요."),
            Triple(CarGlyph.NAVIGATE,"필요한 곳으로","수색 기준과 최근 목격 위치로 안내받아요."),
            Triple(CarGlyph.SHIELD,"안전하게 참여","주차가 확인된 동안 차량 화면을 조작해요."),
        )) {
            val row=design.row();row.addView(icon(glyph,36),LinearLayout.LayoutParams(design.dp(36),design.dp(36)).apply{marginEnd=design.dp(20)})
            val copyColumn=design.column();copyColumn.addView(design.text(title,30,true));copyColumn.addView(design.space(8));copyColumn.addView(design.text(copy,24,false,c.muted))
            row.addView(copyColumn,LinearLayout.LayoutParams(0,-2,1f));intro.addView(row);intro.addView(design.space(28))
        }
        val consent=surface(32)
        consent.addView(design.text("함께 수색을 시작해요",34,true));consent.addView(design.space(20))
        consent.addView(design.text("반려동물 정보와 실종·목격 위치를\n같은 서버의 참여자에게 공유합니다.",26))
        consent.addView(design.space(16));consent.addView(design.text("수색 자료는 삭제할 때까지, 첨부 자료는 7일 보관합니다. 차량 카메라 원본은 사용하지 않습니다.",24,false,c.muted))
        consent.addAction(action("공유에 동의하고 시작하기",true,CarGlyph.LINK,true){actions.connect()})
        consent.addAction(action("개인정보·위치정보 안내",glyph=CarGlyph.SHIELD){actions.policy()})
        consent.addAction(action("고급 연결 설정"){actions.advanced()})
        if(wide)addColumns(columns,intro,consent) else addColumns(columns,consent,intro)
        page.addView(columns)
        contentHost.addView(scroll(page,"onboarding"),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun addColumns(container:LinearLayout,first:View,second:View) {
        if(wide){container.addView(first,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=design.dp(24)});container.addView(second,LinearLayout.LayoutParams(0,-2,1f))}
        else {container.addView(first,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=design.dp(24)});container.addView(second,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun items(model:CarContent):List<SearchItem> {
        val pets=model.snapshot.optJSONArray("pets").objects().associateBy{it.optString("id")}
        val plans=model.plans.objects().associateBy{it.optString("reportId")}
        val sightings=model.snapshot.optJSONArray("sightings").objects().filter{it.optString("status")!="dismissed"}.groupBy{it.optString("reportId")}
        return model.snapshot.optJSONArray("reports").objects().mapNotNull{report->
            if(report.optString("status")!="searching")return@mapNotNull null
            val pet=pets[report.optString("petId")]?:return@mapNotNull null
            val plan=plans[report.optString("id")];val center=plan?.optJSONObject("center")?:report.optJSONObject("location")?:return@mapNotNull null
            val lat=center.optDouble("lat",Double.NaN);val lon=center.optDouble("lon",Double.NaN)
            if(!lat.isFinite()||!lon.isFinite())return@mapNotNull null
            val distance=model.fix?.let{val meters=FloatArray(1);Location.distanceBetween(it.lat,it.lon,lat,lon,meters);meters[0]}
            if(distance!=null&&distance>5000&&report.optString("id")!=model.recording?.reportId&&report.optString("id")!=driveReport)return@mapNotNull null
            SearchItem(report,pet,plan,center,distance,sightings[report.optString("id")]?.maxByOrNull{it.optString("seenAt")})
        }.sortedBy{it.distance?:Float.MAX_VALUE}
    }
    private fun search(model:CarContent) {
        val items=items(model)
        val driveItem=items.firstOrNull{it.id==driveReport}
        if(driveItem?.plan!=null) {drivePage(driveItem,model);return}
        driveReport=null
        if(selected!=null&&items.none{it.id==selected})selected=null
        if(items.isEmpty()){empty(model);return}
        if(!wide&&selected!=null) {
            contentHost.addView(action("주변 수색으로",glyph=CarGlyph.BACK){selected=null;render(force=true)},LinearLayout.LayoutParams(-2,-2).apply{bottomMargin=design.dp(20)})
            contentHost.addView(scroll(detail(items.first{it.id==selected},model),"detail:$selected"),LinearLayout.LayoutParams(-1,0,1f));return
        }
        heading("주변 수색 ${items.size}",if(model.fix==null)"위치 확인 전 · 공유된 신고" else "현재 위치에서 5km 이내")
        val panes=design.row().apply{gravity=Gravity.TOP}
        val list=design.column()
        page=page.coerceIn(0,(items.size-1)/4)
        if(wide&&selected==null)selected=items[page*4].id
        for(item in items.drop(page*4).take(4)) {
            val row=surface(24).apply{background=design.ripple(if(item.id==selected)c.accentSurface else c.surface,24,if(item.id==selected)c.accent else null)}
            val top=design.row();top.addView(design.text(item.name,32,true,lines=1),LinearLayout.LayoutParams(0,-2,1f))
            item.distance?.let{top.addView(design.text(distance(it),24,true,c.accent,1),LinearLayout.LayoutParams(-2,-2).apply{marginStart=design.dp(12)})};row.addView(top)
            row.addView(design.space(12));row.addView(design.text(item.center.optString("label").ifBlank{"수색 기준 위치"},24,false,c.muted,1))
            row.addView(design.space(8));row.addView(design.text(item.pet.optString("features").ifBlank{if(item.pet.optString("species")=="cat")"고양이" else "강아지"},22,false,c.muted,2))
            row.tag="report:"+item.id;row.isSelected=item.id==selected;row.contentDescription="${item.name}, ${item.distance?.let{distance(it)}?:"거리 확인 전"}, 수색 기록 보기"
            track(row){selected=item.id;showNotice("");render(force=true)}
            list.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=design.dp(16)})
        }
        if(items.size>4) {
            val pager=design.row()
            val previous=action("이전",glyph=CarGlyph.BACK){page--;selected=null;render(force=true)}
            val next=action("다음",glyph=CarGlyph.CHEVRON){page++;selected=null;render(force=true)}
            if(page==0){controls.removeAll{it.first===previous};previous.isEnabled=false}
            if((page+1)*4>=items.size){controls.removeAll{it.first===next};next.isEnabled=false}
            pager.addView(previous,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=design.dp(12)})
            pager.addView(next,LinearLayout.LayoutParams(0,-2,1f));list.addView(pager)
            list.addView(design.space(16));list.addView(design.text("${page+1} / ${(items.size+3)/4} 페이지",22,false,c.muted))
        }
        list.addAction(action("새로고침",glyph=CarGlyph.REFRESH,network=true){actions.refresh()})
        if(wide) {
            panes.addView(scroll(list,"list:$page"),LinearLayout.LayoutParams(design.dp(390),-1).apply{marginEnd=design.dp(28)})
            panes.addView(scroll(detail(items.first{it.id==selected},model),"detail:$selected"),LinearLayout.LayoutParams(0,-1,1f))
            contentHost.addView(panes,LinearLayout.LayoutParams(-1,0,1f))
        }else contentHost.addView(scroll(list,"list:$page"),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun detail(item:SearchItem,model:CarContent):LinearLayout {
        val panel=surface(32)
        val title=design.row();title.addView(design.text(item.name,40,true),LinearLayout.LayoutParams(0,-2,1f))
        title.addView(icon(CarGlyph.PAW,38));panel.addView(title)
        panel.addView(design.space(10));panel.addView(design.text(item.pet.optString("features"),26,false,c.muted,2))
        if(item.id.startsWith("demo-")){panel.addView(design.space(10));panel.addView(design.text("시연 자료 · 실제 실종 신고 아님",22,true,c.warning))}
        panel.addView(design.space(24))
        panel.addView(design.text(if(item.plan?.has("anchorId")==true)"보호자 확인 위치" else "최초 실종 위치",22,true,c.muted))
        panel.addView(design.space(8));panel.addView(design.text(item.center.optString("label").ifBlank{"수색 기준 위치"},28,true,lines=2))
        if(item.plan?.area()!=null) panel.addAction(action("반경 내 도로 수색",true,CarGlyph.SEARCH){
            selected=item.id;driveReport=item.id;chosenRadius=0;chosenRoad=null;showNotice("");render(force=true)
        })
        panel.addAction(action("이 위치로 길 안내",glyph=CarGlyph.NAVIGATE){actions.route(item.center.optDouble("lat"),item.center.optDouble("lon"),item.name+" 수색 기준")})
        panel.addView(design.space(28))
        val radius=item.plan?.optInt("radiusMeters")?:0
        panel.addView(design.text("수색 관측${if(radius>0)" · 반경 ${distance(radius.toFloat())}" else ""}",28,true))
        panel.addView(design.space(8));panel.addView(design.text("휴대폰 관측 개요 · 최근 기록일수록 진하게 표시",22,false,c.muted))
        panel.addView(CoverageView(context,item.plan,design),LinearLayout.LayoutParams(-1,design.dp(if(width<700)240 else 290)))
        val legend=design.column()
        for((label,color) in listOf("━  분석 중 미검출" to c.accent,"━  동물 검출" to c.warning,"┄  분석 공백" to c.muted)) {
            legend.addView(design.text(label,22,false,color,1))
        }
        // Labels are independent of route colour and never claim absence or identity.
        panel.addView(legend);panel.addView(design.space(12))
        panel.addView(design.text(if((item.plan?.optInt("segmentCount")?:0)==0)"아직 공유된 관측 경로가 없어요." else "${item.plan?.optInt("segmentCount")}개 관측 구간${if(item.plan?.optBoolean("hasSimulation")==true)" · 모의 경로 포함" else ""}",24,false,c.muted))
        item.latest?.let{latest->
            val location=latest.optJSONObject("location")
            if(location!=null) {
                panel.addView(design.space(20));panel.addView(design.text("최근 목격 · ${time(latest.optString("seenAt"))}",24,true))
                panel.addView(design.space(8));panel.addView(design.text(if(latest.optString("status")=="confirmed")"보호자가 관련 제보로 확인했어요." else "아직 보호자가 확인하지 않은 단서예요.",22,false,c.muted))
                panel.addAction(action("최근 목격 위치로 안내",glyph=CarGlyph.NAVIGATE){actions.route(location.optDouble("lat"),location.optDouble("lon"),"최근 목격 · "+item.name)})
            }
        }
        panel.addAction(action(if(BuildConfig.SIMULATION)"시연 목격 제보" else "현재 위치에서 목격 제보",glyph=CarGlyph.LOCATION){actions.sighting(item.report,item.pet)})
        panel.addView(design.space(24));panel.addView(design.text("관측 결과만으로 동물의 부재나 동일 개체를 확정할 수 없습니다.",22,false,c.muted))
        panel.addAction(action("내용 신고",glyph=CarGlyph.FLAG,network=true){actions.report(item.report)})
        return panel
    }
    private fun drivePage(item:SearchItem,model:CarContent) {
        val base=item.plan?.area() ?: return
        val cached=model.roadPlans.optJSONObject(item.id)
        val savedArea=cached?.area()
        val current=model.recording?.takeIf{it.reportId==item.id}
        val meters=current?.radius ?: chosenRadius.takeIf{it>0} ?: savedArea?.radius ?: minOf(500,base.radius)
        val area=base.copy(radius=meters)
        visibleDriveArea=area
        val same=cached?.takeIf{savedArea==area}
        val belief=same?.optJSONObject("posterior")
        val course=same?.optJSONObject("course")?.road(course=true)
        val roads=same?.driveRoads().orEmpty()
        val options=roads+listOfNotNull(course)
        val stale=current==null && same!=null && item.plan.optString("searchRevision").isNotBlank() &&
            same.optString("searchRevision")!=item.plan.optString("searchRevision")
        val observed=model.snapshot.optJSONArray("searchSegments").records().filter{it.optString("source")=="camera"}
        val passes=(model.snapshot.optJSONArray("vehiclePasses").records()+model.localPasses.records()).distinctBy{it.optString("id")}
        val traces=(passes+observed).asSequence().filter{it.optString("reportId")==item.id && (it.optJSONObject("from")?.millis("at")?:0)>=area.since}
            .mapNotNull{it.edge()}.filter{area.center.distance(it.from)<=meters+205}.toList()
        val progress=roadProgress(options,traces,area.center)
        val road=roads.firstOrNull{it.id==chosenRoad} ?: course ?: roads.minByOrNull{progress[it.id]?.lastAt ?: 0}
        val recommended=course!=null && road==course
        val index=roads.indexOf(road)
        val shell=design.column()
        shell.addView(action("신고로 돌아가기",glyph=CarGlyph.BACK){driveReport=null;render(force=true)},LinearLayout.LayoutParams(-2,-2))
        val label=(if(item.id.startsWith("demo-"))"시연 신고 · " else "")+item.center.optString("label")
        shell.addView(design.space(16));heading(item.name+" · 도로 수색",label,shell)
        val panes=if(wide)design.row().apply{gravity=Gravity.TOP} else design.column()
        val map=surface(24)
        map.addView(design.text("수색 반경 "+distance(meters.toFloat()),32,true))
        map.addView(DriveMap(context,design,area,options,road?.id,traces,belief?.heatRings(meters).orEmpty()),LinearLayout.LayoutParams(-1,design.dp(if(wide)360 else 255)))
        if(belief!=null)map.addView(design.text("주황색이 진한 곳부터 수색",24,true,c.ink))
        map.addView(design.text("북쪽 ↑   초록 선  선택한 길   파란 선  지나간 길",22,true,c.ink))
        map.addView(design.space(10))
        val ages=if(width<700)design.column() else design.row()
        val now=System.currentTimeMillis()
        for((label,hours) in listOf("방금" to 0,"6시간 전" to 6,"하루 전" to 24)) {
            val entry=design.row()
            entry.addView(View(context).apply{background=design.shape(driveInk(design,trailOpacity(now-hours*3600000L,now)),3)},LinearLayout.LayoutParams(design.dp(24),design.dp(6)).apply{marginEnd=design.dp(8)})
            entry.addView(design.text(label,22,false,c.muted))
            ages.addView(entry,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=design.dp(16)})
        }
        map.addView(ages)
        map.addView(design.space(10));map.addView(design.text("OpenFreeMap · © OpenMapTiles\n© OpenStreetMap 기여자",22,false,c.muted))
        val info=surface(28)
        if(model.recording==null) {
            info.addAction(action("반경 변경 · 현재 "+distance(meters.toFloat()),network=true){
                actions.chooseRadius(meters,base.radius){value->chosenRadius=value;chosenRoad=null;render(force=true)}
            })
        }
        if(road!=null) {
            val seen=progress[road.id]
            info.addView(design.text(if(recommended)"추천 수색 코스" else road.name,32,true))
            val streetNames=road.streets.filter{it!="이름 없는 도로"}.take(4)
            val distanceLabel=distance(road.length.toFloat())
            info.addView(design.text(if(recommended && streetNames.isNotEmpty())distanceLabel+" · "+streetNames.joinToString(" → ") else distanceLabel,24,false,c.muted))
            if(stale)info.addView(design.text("새 단서 또는 경과 시간을 반영해 코스를 갱신해 주세요.",22,false,c.warning))
            if(!stale)info.addAction(action(if(recommended)"추천 코스로 안내" else if(current==null)"이 구간 수색 시작" else "이 구간 길 안내",true,CarGlyph.NAVIGATE){actions.drive(area,road)})
            if(seen!=null && seen.lastAt>0)info.addView(design.text("약 ${seen.percent}% 통과 · 마지막 "+time(Instant.ofEpochMilli(seen.lastAt).toString()),22,false,c.muted))
            if(!recommended && roads.size>1) {
                val row=design.row()
                row.addView(action("이전 구간"){chosenRoad=roads[(index-1+roads.size)%roads.size].id;render(force=true)},LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=design.dp(10)})
                row.addView(action("다음 구간"){chosenRoad=roads[(index+1)%roads.size].id;render(force=true)},LinearLayout.LayoutParams(0,-2,1f))
                info.addAction(row)
            }
            if(course!=null && roads.isNotEmpty())info.addAction(action(if(recommended)"개별 도로 선택" else "추천 코스로 돌아가기"){
                chosenRoad=if(recommended)roads.first().id else null;render(force=true)
            })
        }else if(same!=null) {
            val text=if(!same.optBoolean("routingAvailable",true))"도로 서비스에 연결하지 못했어요. 다시 계산해 주세요." else "안내할 수 있는 도로를 찾지 못했어요. 반경을 바꿔 보세요."
            info.addView(design.text(text,24,false,c.muted))
        }
        info.addAction(action(if(same!=null)"수색 코스 다시 계산" else "수색 코스 추천",same==null,CarGlyph.SEARCH,true){chosenRoad=null;actions.planRoads(area)})
        if(belief!=null) {
            val counts=belief.optJSONObject("evidence")
            info.addView(design.text("반경 안에 있다고 가정한 상대 우선순위입니다.",22,false,c.muted))
            info.addView(design.text("확인 목격 ${counts?.optInt("sightings")?:0}건 · 카메라 미검출 ${counts?.optInt("clearSegments")?:0}구간",22,false,c.muted))
            val terrain=when {
                belief.optString("terrain")=="unavailable" -> "지형 자료 미수신"
                belief.optInt("mappedCells")==0 -> "분류 가능한 지형 없음"
                belief.optString("terrain")=="partial" -> "일부 지도 지형 반영"
                else -> "지도 지형 반영"
            }
            info.addView(design.text(terrain,22,false,c.muted))
        }else info.addView(design.text("계산할 때 주변 지도 타일과 반경 안의 도로 후보를 요청합니다.",22,false,c.muted))
        if(model.recording==null)info.addAction(action("길 안내 없이 주행 기록",glyph=CarGlyph.LOCATION){actions.drive(area,null)})
        if(model.driveStatus.isNotBlank()){info.addView(design.space(12));info.addView(design.text(model.driveStatus,22,true,c.muted))}
        addColumns(panes,map,info);shell.addView(panes);shell.addView(design.space(12))
        shell.addView(action("지도 출처와 도로 정보",glyph=CarGlyph.LINK){actions.mapCredits(area)})
        contentHost.addView(scroll(shell,"drive:"+item.id),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun empty(model:CarContent) {
        heading("주변 수색",if(model.fix==null)"현재 위치를 확인해 주세요" else "현재 위치에서 5km 이내")
        val panel=surface(40).apply{gravity=Gravity.CENTER_HORIZONTAL}
        panel.addView(design.space(20));panel.addView(icon(CarGlyph.SEARCH,80));panel.addView(design.space(28))
        panel.addView(design.text("지금은 주변에\n수색 신고가 없어요",40,true).apply{gravity=Gravity.CENTER})
        panel.addView(design.space(20));panel.addView(design.text("휴대폰에서 신고를 등록하면\n이곳에서 수색을 이어갈 수 있어요.",26,false,c.muted).apply{gravity=Gravity.CENTER})
        panel.addView(design.space(28))
        if(model.fix==null)panel.addAction(action("현재 위치 사용",true,CarGlyph.LOCATION){actions.location()})
        else if(!model.paired)panel.addAction(action("휴대폰 연결",true,CarGlyph.LINK){actions.pair()})
        panel.addAction(action("새로고침",primary=model.paired&&model.fix!=null,glyph=CarGlyph.REFRESH,network=true){actions.refresh()})
        panel.addView(design.space(20))
        contentHost.addView(scroll(panel,"empty"),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun settings(model:CarContent) {
        heading("연결", "휴대폰의 기록을 차량에서 이어가세요.")
        val page=design.column();val columns=if(wide)design.row().apply{gravity=Gravity.TOP} else design.column()
        val phone=surface(32)
        phone.addView(icon(CarGlyph.LINK,40));phone.addView(design.space(20));phone.addView(design.text(if(model.paired)"휴대폰과 연결됐어요" else "휴대폰을 연결해 주세요",32,true))
        phone.addView(design.space(12));phone.addView(design.text(if(model.paired)"휴대폰의 수색 기록을\n이 차량에서 이어갈 수 있어요." else "휴대폰 설정에서 연결 번호를 만들고\n6자리 번호를 입력하세요.",26,false,c.muted))
        phone.addAction(action(if(model.paired)"다른 휴대폰 연결" else "휴대폰 연결",!model.paired,CarGlyph.LINK){actions.pair()})
        phone.addView(design.space(18));phone.addView(design.text("전송 대기 ${model.pending}건",24,false,c.muted))
        phone.addAction(action("지금 동기화",primary=model.paired,glyph=CarGlyph.REFRESH,network=true){actions.refresh()})
        val location=surface(32)
        location.addView(icon(CarGlyph.LOCATION,40));location.addView(design.space(20));location.addView(design.text("위치와 길 안내",32,true))
        location.addView(design.space(16));locationLabel=design.text(telemetryLocation,26,false,c.muted).also{location.addView(it)}
        location.addAction(action("현재 위치 사용",glyph=CarGlyph.LOCATION){actions.location()})
        location.addView(design.space(24));navigationLabel=design.text(telemetryNavigation,26,false,c.muted).also{location.addView(it)}
        location.addView(design.space(12));location.addView(design.text("수색 기록을 시작하면 길 안내 중에도 선택한 반경 안의 주행을 기록합니다.",22,false,c.muted))
        addColumns(columns,phone,location);page.addView(columns);page.addView(design.space(24))
        val privacy=surface(28);privacy.addView(design.text("자료와 개인정보",30,true))
        privacy.addAction(action("개인정보·위치정보 안내",glyph=CarGlyph.SHIELD){actions.policy()})
        privacy.addAction(action("이 차량 연결·저장 자료 지우기",network=true){actions.disconnect()})
        privacy.addAction(action("오픈소스 라이선스"){actions.licenses()})
        privacy.addView(design.space(16));privacy.addView(design.text("휴대폰 카메라의 관측을 공유합니다.\n차량 카메라 직접 탐지는 제공하지 않습니다.",22,false,c.muted))
        page.addView(privacy);contentHost.addView(scroll(page,"settings"),LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun distance(meters:Float)=if(meters<1000)"${meters.toInt()}m" else String.format(Locale.KOREA,"%.1fkm",meters/1000)
    private fun time(value:String)=runCatching{DateTimeFormatter.ofPattern("MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))}.getOrDefault("시각 확인 필요")
}

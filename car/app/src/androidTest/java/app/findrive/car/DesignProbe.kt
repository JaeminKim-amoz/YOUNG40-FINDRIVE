package app.findrive.car

import android.app.Instrumentation
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Native rendering regression checks, with isolated synthetic data and no network/vehicle actions. */
object DesignProbe {
    fun run(instrumentation:Instrumentation) {
        val result=Bundle()
        try {
            val cases=listOf(Triple(480,1f,false),Triple(840,1f,false),Triple(1706,1f,false),Triple(1706,1f,true),Triple(1100,1.5f,false),Triple(480,1.5f,true))
            val artifacts=File(instrumentation.targetContext.filesDir,"design-probe").apply{mkdirs()}
            instrumentation.runOnMainSync {
                for((width,scale,night) in cases) {
                    val config=Configuration(instrumentation.targetContext.resources.configuration).apply {
                        screenWidthDp=width;screenHeightDp=1120;fontScale=scale
                        uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(night)Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val context=ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config),R.style.Theme_Findrive)
                    val design=CarDesign(context)
                    var refreshes=0
                    var handedOff:DriveRoad?=null
                    val actions=object:CarActions {
                        override fun connect() {};override fun pair() {};override fun location() {};override fun refresh(){refreshes++}
                        override fun policy() {};override fun advanced() {};override fun disconnect() {};override fun licenses() {}
                        override fun planRoads(area:DriveArea) {};override fun drive(area:DriveArea,road:DriveRoad?) {handedOff=road};override fun stopDrive() {};override fun mapCredits(area:DriveArea) {}
                        override fun chooseRadius(current:Int,recommended:Int,selected:(Int)->Unit){selected(700)}
                        override fun route(lat:Double,lon:Double,name:String) {};override fun sighting(report:JSONObject,pet:JSONObject) {};override fun report(report:JSONObject) {}
                    }
                    val screen=CarScreen(context,design,actions,null)
                    val model=fixture()
                    fun layout() {
                        screen.root.measure(View.MeasureSpec.makeMeasureSpec(design.dp(width),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(design.dp(1120),View.MeasureSpec.EXACTLY))
                        screen.root.layout(0,0,screen.root.measuredWidth,screen.root.measuredHeight)
                    }
                    fun checkControls() {
                        val buttons=descendants(screen.root).filterIsInstance<Button>().toList()
                        check(buttons.isNotEmpty()) {"No buttons: $width/$scale/$night"}
                        buttons.forEach { button ->
                            check(button.measuredHeight>=design.dp(80)) {"Small target: ${button.text}"}
                            check(button.measuredWidth<=design.dp(width)) {"Button outside viewport: ${button.text}"}
                            val textLayout=button.layout
                            check(textLayout==null || textLayout.lineCount<=button.maxLines) {"Clipped button: ${button.text} at $width/$scale"}
                            check(textLayout==null || button.measuredHeight>=textLayout.height+button.compoundPaddingTop+button.compoundPaddingBottom) {"Clipped button height: ${button.text}"}
                        }
                    }
                    screen.update(model);screen.setInteraction(true,false);layout();checkControls()
                    // First wide render chooses the default selection. Subsequent unchanged syncs retain the tree.
                    screen.update(model);layout()
                    val row=screen.root.findViewWithTag<View>("report:demo-0")
                    check(row!=null) {"Missing report at $width"}
                    screen.updateTelemetry("위치 수신됨","내비게이션 연결됨")
                    model.plans.getJSONObject(0).put("elapsedHours",999.5)
                    screen.update(model)
                    check(screen.root.findViewWithTag<View>("report:demo-0")===row) {"Telemetry/clock rebuilt content"}
                    val refresh=descendants(screen.root).filterIsInstance<Button>().first{it.text=="새로고침"}
                    refresh.performClick();check(refreshes==1)
                    screen.setInteraction(true,true);refresh.performClick();check(refreshes==1) {"Busy action submitted twice"}
                    screen.setInteraction(false,false);refresh.performClick();check(refreshes==1) {"Unsafe interaction accepted"}
                    check(descendants(screen.root).filterIsInstance<Button>().none{it.isEnabled}) {"Enabled control while locked"}
                    screen.setInteraction(true,false)
                    val bitmap=Bitmap.createBitmap(screen.root.width,screen.root.height,Bitmap.Config.ARGB_8888)
                    screen.root.draw(Canvas(bitmap))
                    File(artifacts,"search-${width}-${scale}-${if(night)"night" else "day"}.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
                    row.performClick();layout()
                    descendants(screen.root).filterIsInstance<Button>().first{it.text=="반경 내 도로 수색"}.performClick()
                    layout();checkControls()
                    check(descendants(screen.root).filterIsInstance<TextView>().any{it.text=="6시간 전"})
                    val go=descendants(screen.root).filterIsInstance<Button>().first{it.text=="추천 코스로 안내"}
                    go.performClick();check(handedOff?.stops?.size==3)
                    val map=descendants(screen.root).filterIsInstance<DriveMap>().first()
                    check(map.contentDescription.contains("주황색"))
                    descendants(screen.root).filterIsInstance<Button>().first{it.text=="개별 도로 선택"}.performClick();layout()
                    descendants(screen.root).filterIsInstance<Button>().first{it.text=="추천 코스로 돌아가기"}.performClick();layout()
                    descendants(screen.root).filterIsInstance<Button>().first{it.text.startsWith("반경 변경")}.performClick();layout()
                    check(descendants(screen.root).filterIsInstance<TextView>().any{it.text=="수색 반경 700m"})
                    val driveSaved=Bundle();screen.save(driveSaved)
                    val driveRestored=CarScreen(context,design,actions,driveSaved).apply{update(model);setInteraction(true,false)}
                    check(descendants(driveRestored.root).filterIsInstance<Button>().any{it.text=="수색 코스 추천"})
                    check(screen.back());layout()
                    descendants(screen.root).filterIsInstance<Button>().first{it.text=="연결"}.performClick();layout();checkControls()
                    val saved=Bundle();screen.save(saved)
                    val restored=CarScreen(context,design,actions,saved).apply{update(model);setInteraction(true,false)}
                    check(descendants(restored.root).filterIsInstance<TextView>().any{it.text=="위치와 길 안내"}) {"Settings tab lost on recreation"}
                    screen.update(model.copy(connected=false));layout();checkControls()
                    descendants(screen.root).filterIsInstance<TextView>().filter{it.text.startsWith("반려동물 정보와") || it.text.startsWith("수색 자료는")}.forEach { text ->
                        val laidOut=text.layout
                        check(laidOut!=null && (0 until laidOut.lineCount).none{laidOut.getEllipsisCount(it)>0}) {"Consent truncated at $width/$scale"}
                    }
                    if(width==480) {
                        val onboarding=Bitmap.createBitmap(screen.root.width,screen.root.height,Bitmap.Config.ARGB_8888)
                        screen.root.draw(Canvas(onboarding));File(artifacts,"onboarding-${scale}.png").outputStream().use{onboarding.compress(Bitmap.CompressFormat.PNG,100,it)};onboarding.recycle()
                    }
                }
            }
            result.putString("design","PASS: 6 native size/font/theme cases; targets, labels, lifecycle state, parking lock, busy guard, stable telemetry")
            result.putString("screenshots",artifacts.absolutePath)
            instrumentation.finish(-1,result)
        }catch(error:Throwable){result.putString("failure",error.toString());instrumentation.finish(0,result)}
    }
    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
    private fun fixture():CarContent {
        val pets=JSONArray();val reports=JSONArray();val plans=JSONArray()
        for(i in 0..5) {
            val location=JSONObject().put("lat",37.5445+i*.0001).put("lon",127.0374).put("label","서울숲 산책로 입구")
            pets.put(JSONObject().put("id","pet-$i").put("name",listOf("해피","나비","보리","콩이","초코","구름")[i]).put("species","dog").put("features","크림색 털 · 빨간 목줄"))
            reports.put(JSONObject().put("id","demo-$i").put("petId","pet-$i").put("status","searching").put("location",location))
            plans.put(JSONObject().put("reportId","demo-$i").put("since","2026-11-01T00:00:00Z").put("center",location).put("radiusMeters",900).put("segmentCount",0).put("segments",JSONArray()).put("elapsedHours",1.0))
        }
        val roadPlans=JSONObject()
        for(plan in plans.records()){
            val center=plan.getJSONObject("center")
            val points=JSONArray().put(center).put(JSONObject().put("lat",center.getDouble("lat")+.0005).put("lon",127.0380))
                .put(JSONObject().put("lat",center.getDouble("lat")+.0012).put("lon",127.0381))
            val road=JSONObject().put("id","single").put("name","서울숲길").put("points",points).put("lengthMeters",150)
            val course=JSONObject(road.toString()).put("id","course-test").put("name","추천 수색 코스").put("stops",points).put("streets",JSONArray().put("서울숲길"))
            val ring=JSONObject().put("inner",0).put("outer",500).put("mass",JSONArray().put(.4).put(.6))
            val posterior=JSONObject().put("rings",JSONArray().put(ring)).put("terrain","mapped").put("mappedCells",2)
            roadPlans.put(plan.getString("reportId"),JSONObject(plan.toString()).put("radiusMeters",500).put("roads",JSONArray().put(road)).put("course",course).put("posterior",posterior))
        }
        return CarContent(true,false,JSONObject().put("pets",pets).put("reports",reports).put("sightings",JSONArray()),plans,0,VehicleFix(37.5445,127.0374,0,"시연 위치"),true,roadPlans)
    }
}

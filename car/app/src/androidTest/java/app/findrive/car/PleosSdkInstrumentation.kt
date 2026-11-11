package app.findrive.car

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit opt-in integration probe. Run only on the official, parked Pleos emulator. */
class PleosSdkInstrumentation : Instrumentation() {
    private var designProbe = false
    private var waypointProbe=false
    private var courseProbe=false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); designProbe=arguments?.getString("probe")=="design";courseProbe=arguments?.getString("probe")=="course";waypointProbe=courseProbe||arguments?.getString("probe")=="waypoints"; start() }
    override fun onStart() {
        if(designProbe) { DesignProbe.run(this); return }
        val ready = CountDownLatch(1)
        val response = CountDownLatch(1)
        var gateway: VehicleGateway? = null
        var current = VehicleState()
        var navigationResult = "No confirmed destination/guidance within 45 seconds"
        var requested = false
        var routingCompletedCallback = false
        val result = Bundle()
        try {
            check(android.os.Build.PRODUCT.startsWith("crp_avd")) { "Only the official emulator is allowed" }
            check(!BuildConfig.SIMULATION) { "Requires the real Pleos flavor" }
            val road=if(courseProbe){
                val base=BuildConfig.SERVICE_URL
                check(targetContext.packageName.endsWith(".qa") && base=="http://10.0.2.2:8792")
                val session=ConnectionClient.request(base,"/v1/sessions",body=JSONObject().put("kind","vehicle").put("consentVersion","2026-11-11"))
                val plan=ConnectionClient.request(base,"/v1/reports/demo-happy-report/roads?radius=500",session.getString("token"),method="GET",timeoutMs=20000)
                val rings=plan.getJSONObject("posterior").heatRings(500)
                check(rings.size==14)
                checkNotNull(plan.optJSONObject("course")?.road(course=true)).also {
                    result.putInt("courseMeters",it.length);result.putInt("heatCells",rings.sumOf{r->r.density.size})
                }
            }else DriveRoad("probe","FINDRIVE QA · 서울시청",190,listOf(DriveCoord(37.5651,126.9784),DriveCoord(37.5667,126.9784)))
            val expectedStops=road.navigationStops().dropLast(1)
            val targetLat=road.navigationStops().last().lat
            val targetLon=road.navigationStops().last().lon
            runOnMainSync {
                gateway = createGateway(targetContext)
                gateway!!.start({ state ->
                    current = state
                    fun matches(stop:NavigationStop,coord:DriveCoord,name:String)=stop.position.distance(coord)<5 || stop.display?.distance(coord)?.let{it<5}==true || stop.name==name
                    val stopsMatch=!waypointProbe || state.waypoints.size==expectedStops.size && expectedStops.indices.all { index ->
                        matches(state.waypoints[index],expectedStops[index],road.name+if(index==0)" 진입" else " 중간")
                    }
                    if(requested && stopsMatch && state.navigationGuiding && state.destinationLat?.let{kotlin.math.abs(it-targetLat)<0.00001}==true && state.destinationLon?.let{kotlin.math.abs(it-targetLon)<0.00001}==true){navigationResult="Guidance and requested destination confirmed through SDK";response.countDown()}
                    if (SafetyGate.canInteract(state, SystemClock.elapsedRealtime())) ready.countDown()
                }, { message ->
                    if(message.contains("경로 탐색 완료"))routingCompletedCallback=true
                    if(message.contains("오류"))navigationResult=message

                })
            }
            check(ready.await(15, TimeUnit.SECONDS)) { "Vehicle P not ready: $current" }
            check(!current.navigationReady) { "Navigation initialized before a route request" }
            result.putString("vehicle", "P confirmed through Vehicle SDK; navigation initialized on demand")
            runOnMainSync { requested=true;if(waypointProbe)gateway!!.routeRoad(road) else gateway!!.route(targetLat, targetLon, "FINDRIVE QA · 서울시청") }
            repeat(22){if(response.count>0){runOnMainSync{gateway!!.refreshNavigation()};response.await(2,TimeUnit.SECONDS)}}
            result.putString("navigation", navigationResult)
            result.putBoolean("routingCompletedCallback", routingCompletedCallback)
            result.putBoolean("guidanceConfirmed", response.count==0L)
            result.putBoolean("destinationGuidance",current.navigationGuiding && current.destinationLat?.let{kotlin.math.abs(it-targetLat)<.00001}==true && current.destinationLon?.let{kotlin.math.abs(it-targetLon)<.00001}==true)
            result.putInt("waypointsReported",current.waypoints.size)
            result.putString("finalVehicleState",current.toString())
            finish(if (response.count==0L) -1 else 0, result)
        } catch (error: Exception) {
            result.putString("failure", error.message); finish(0, result)
        } finally { runOnMainSync { gateway?.close() } }
    }
}

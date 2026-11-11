package app.findrive.car

import android.content.Context
import android.content.pm.PackageManager
import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

fun createGateway(context: Context): VehicleGateway = PleosGateway(context.applicationContext)
@Suppress("DEPRECATION")
private class PleosGateway(private val context: Context) : VehicleGateway {
    private val vehicle = Vehicle(context)
    private var navi: NaviHelper? = null
    private var navigationConnecting = false
    private var navigationGeneration = 0
    private var pendingRoute: RouteWithWaypointsRequest? = null
    private var navigationTimeout: Runnable? = null
    private val fused = FusedLocation(context)
    private val handler = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var fusedStarted = false
    private var active = false
    private var generation = 0
    private var current = VehicleState()
    private var destinationName = ""
    private var navigationPhase = "내비게이션 연결 중"
    private var state: ((VehicleState) -> Unit)? = null
    private var message: ((String) -> Unit)? = null
    private fun publish() { if (active) state?.invoke(current) }
    private fun note(value: String) { if (active) message?.invoke(value) }
    private fun navigation(value: String, guiding: Boolean? = null) { handler.post { if (active) { navigationPhase=value; current = current.copy(navigationGuiding = guiding ?: current.navigationGuiding, navigationStatus = value + if(destinationName.isNotBlank()) " · $destinationName" else ""); publish(); note(value) } } }
    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { navigation("경로를 탐색하고 있습니다") }
        override fun onRoutingCompleted() { navigation("경로 탐색 완료 · 내비게이션 안내 상태 확인 중"); refreshNavigation() }
        override fun onNavigationStatus(status: NavigationStatus) { navigation(when(status) { NavigationStatus.ROUTE_DRIVE -> "목적지 안내 중"; NavigationStatus.FREE_DRIVE -> "내비게이션 대기 중"; NavigationStatus.INITIALIZING -> "내비게이션 준비 중"; else -> "내비게이션 비활성" },status==NavigationStatus.ROUTE_DRIVE) }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { handler.post { if(active){destinationName=destinationInfo.name; current=current.copy(destinationLat=destinationInfo.latitude.takeIf{destinationName.isNotBlank()},destinationLon=destinationInfo.longitude.takeIf{destinationName.isNotBlank()},navigationStatus=navigationPhase+if(destinationName.isNotBlank())" · $destinationName" else "");publish()} } }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { navigation(when(routeStateInfo.naviMode){NaviMode.ROUTE_GUIDING->"목적지 안내 중";NaviMode.SIMULATION_GUIDING->"모의 주행 안내 중";NaviMode.SAFE_GUIDING->"내비게이션 대기 중";else->"내비게이션 비활성"},routeStateInfo.naviMode==NaviMode.ROUTE_GUIDING) }
        override fun onRouteCancelled() { navigation("경로 안내가 종료되었습니다") }
        override fun onWayPointInfo(waypointInfoList:List<WaypointInfo>) { handler.post { if(active) {
            current=current.copy(waypoints=waypointInfoList.map{p->NavigationStop(p.name,DriveCoord(p.latitude,p.longitude),
                p.displayLatitude?.let{lat->p.displayLongitude?.let{lon->DriveCoord(lat,lon)}})});publish()
        } } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { navigation("목적지에 도착했습니다") }
        override fun onError(errorCode: NaviErrorCode) { navigation("내비게이션 응답 오류 · $errorCode") }
    }
    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) { handler.post {
            if (!active) return@post
            val age = location?.let { System.currentTimeMillis() - it.timestampMillis }
            val fix = if (location != null && age != null && age in 0 until 60_000) VehicleFix(location.latitude, location.longitude, SystemClock.elapsedRealtime() - age, "Pleos 융합 위치") else null
            current = current.copy(location = fix?.takeIf { SafetyGate.usable(it, SystemClock.elapsedRealtime()) }, locationStatus = if (fix == null) "새 위치 수신 대기" else "Pleos 융합 위치 · 정확도 수치 미제공")
            publish()
        } }
        override fun onError(exception: Exception) { handler.post { if (active) { current = current.copy(location = null, locationStatus = "차량 위치를 받지 못했습니다"); publish() } } }
    }
    override fun start(onState: (VehicleState) -> Unit, onMessage: (String) -> Unit) {
        active = true; state = onState; message = onMessage; publish()
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
                handler.post {
                    if (!active) return@post
                    if (!ready) { ux = null; current = current.copy(uxAllowed = null); publish() }
                    else runCatching {
                        ux = service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                        ux?.registerListener { restrictions ->
                            current = current.copy(uxAllowed = !restrictions.isRequiresDistractionOptimization && restrictions.activeRestrictions == 0, uxSampledAt = SystemClock.elapsedRealtime()); publish()
                        }
                        readUx()
                    }.onFailure { current = current.copy(uxAllowed = null); publish() }
                }
            }
        } catch (_: Exception) { note("주행 규제 상태를 확인할 수 없어 조작을 잠갔습니다") }
        try { vehicle.initialize(); readGear() } catch (_: Exception) { note("차량 상태를 확인하지 못했습니다") }
    }
    private fun connectNavigation() {
        if(navigationConnecting)return
        navigationConnecting=true
        val ticket=++navigationGeneration
        fun fail() {
            if(!active||ticket!=navigationGeneration)return
            navigationGeneration++;navigationConnecting=false;pendingRoute=null
            navigationTimeout?.let{handler.removeCallbacks(it)};navigationTimeout=null
            runCatching{navi?.release()};navi=null
            current=current.copy(navigationReady=false,navigationStatus="내비게이션 연결 오류 · 다시 시도해 주세요")
            publish();note(current.navigationStatus)
        }
        navigationTimeout=Runnable{fail()}.also{handler.postDelayed(it,15_000)}
        current=current.copy(navigationStatus="길 안내 준비 중…");publish()
        try {
            val helper=NaviHelper(context).also{navi=it}
            helper.initialize(onConnected={handler.post {
                if(active&&ticket==navigationGeneration) {
                    navigationConnecting=false;navigationTimeout?.let{handler.removeCallbacks(it)};navigationTimeout=null
                    helper.addListener(listener);current=current.copy(navigationReady=true,navigationStatus="내비게이션 연결됨");publish()
                    val request=pendingRoute;pendingRoute=null
                    if(request!=null) {
                        if(SafetyGate.canInteract(current,SystemClock.elapsedRealtime()))runCatching{sendRoute(request)}.onFailure{navigation("길 안내 요청 오류 · 다시 시도해 주세요")}
                        else navigation("주차 상태가 바뀌어 길 안내 요청을 취소했습니다")
                    }else refreshNavigation()
                }
            }},onFailed={handler.post{fail()}})
        }catch(_:Exception){fail()}
    }
    private fun readUx() {
        try {
            val restrictions = ux?.currentCarUxRestrictions
            current = current.copy(uxAllowed = restrictions?.let { !it.isRequiresDistractionOptimization && it.activeRestrictions == 0 }, uxSampledAt = SystemClock.elapsedRealtime())
        } catch (_: Exception) { current = current.copy(uxAllowed = null) }
    }
    private fun readGear() {
        if (!active) return
        val ticket = ++generation
        val timeout = Runnable { if (active && ticket == generation) { generation++; current = current.copy(parked = null); readUx(); publish(); handler.postDelayed({ readGear() }, 1000) } }
        handler.postDelayed(timeout, 2000)
        fun complete(parked: Boolean?) { handler.post { if (active && ticket == generation) {
            generation++; handler.removeCallbacks(timeout); readUx()
            current = current.copy(parked = parked, sampledAt = SystemClock.elapsedRealtime()); publish(); handler.postDelayed({ readGear() }, 1000)
        } } }
        try { vehicle.getGear().getCurrentGearSelectionState(onComplete = { gear -> complete(gear?.let { it == VehicleGear.GEAR_PARK }) }, onFailed = { complete(null) }) }
        catch (_: Exception) { complete(null) }
    }
    override fun locationPermissions(): Array<String> = if (runCatching { fused.isSupported() }.getOrDefault(false)) arrayOf("pleos.car.permission.FUSED_LOCATION") else emptyArray()
    override fun startLocation() {
        if (!active || fusedStarted) return
        try {
            if (!fused.isSupported()) { current = current.copy(locationStatus = "차량 융합 위치 미지원 · GPS 사용 가능"); publish(); return }
            if (context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") != PackageManager.PERMISSION_GRANTED) { current = current.copy(locationStatus = "위치 사용을 허용해 주세요"); publish(); return }
            fused.initialize(); fusedStarted = true; fused.registerFusedLocationCallback(locationListener); fused.getLastKnownLocation(locationListener)
        } catch (_: Exception) { current = current.copy(locationStatus = "차량 위치 연결 실패"); publish() }
    }
    override fun refreshNavigation() { handler.post { if (active && current.navigationReady) runCatching { navi?.getRouteStateInfo(); navi?.getDestinationInfo();navi?.getWaypointInfo() } } }
    override fun route(lat: Double, lon: Double, name: String) {
        check(SafetyGate.canInteract(current, SystemClock.elapsedRealtime())) { "주차 상태와 주행 규제 상태가 확인되어야 합니다." }
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
        val request=RouteInfo(latitude = lat, longitude = lon, poiName = name, poiId = "", address = name, poiSubId = "")
        submit(RouteWithWaypointsRequest(request, emptyList()))
    }
    override fun routeRoad(road:DriveRoad) {
        require(road.points.size>=2 && road.points.all{it.valid()})
        val navigation=road.navigationStops()
        require(navigation.size in 2..3 && navigation.all{it.valid()})
        val end=navigation.last()
        val destination=RouteInfo(latitude=end.lat,longitude=end.lon,poiName=road.name+" 수색 종점",poiId="",address=road.name,poiSubId="")
        val stops=navigation.dropLast(1)
        val waypoints=stops.mapIndexed { i,p -> RequestWaypointInfo(latitude=p.lat,longitude=p.lon,
            waypointIndex=if(i==0)WaypointIndex.FIRST else WaypointIndex.SECOND,poiName=road.name+if(i==0)" 진입" else " 중간",
            poiId="",address=road.name,poiSubId="") }
        submit(RouteWithWaypointsRequest(destination,waypoints))
    }
    private fun submit(request:RouteWithWaypointsRequest) {
        check(SafetyGate.canInteract(current,SystemClock.elapsedRealtime())) {"주차 상태를 확인해 주세요."}
        if(current.navigationReady)sendRoute(request) else {pendingRoute=request;connectNavigation()}
    }
    private fun sendRoute(request:RouteWithWaypointsRequest) {
        current=current.copy(navigationGuiding=false,destinationLat=null,destinationLon=null,waypoints=emptyList())
        val helper=checkNotNull(navi)
        if(request.waypoints.isEmpty())helper.requestRoute(request.destination) else helper.requestRouteWithWaypoints(request)
        navigation("경로 요청 전송 · 응답을 기다리고 있습니다")
    }
    override fun stopLocation() {
        if(fusedStarted) {
            runCatching{fused.unregisterFusedLocationCallback(locationListener)};runCatching{fused.release()};fusedStarted=false
        }
        current=current.copy(location=null)
    }
    override fun close() {
        active = false; generation++; navigationGeneration++; navigationConnecting=false;pendingRoute=null;navigationTimeout=null;handler.removeCallbacksAndMessages(null)
        stopLocation()
        runCatching { ux?.unregisterListener() }; ux = null; runCatching { car?.disconnect() }; car = null
        runCatching { navi?.removeListener(listener) }; runCatching { navi?.release() };navi=null;runCatching { vehicle.release() }
        current = VehicleState(); state = null; message = null
    }
}

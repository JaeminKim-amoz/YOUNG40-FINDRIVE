package app.findrive.car

data class VehicleFix(val lat: Double, val lon: Double, val sampledAt: Long, val source: String)
data class NavigationStop(val name:String,val position:DriveCoord,val display:DriveCoord?)
data class VehicleState(
    val parked: Boolean? = null,
    val sampledAt: Long = 0,
    val navigationReady: Boolean = false,
    val uxAllowed: Boolean? = null,
    val uxSampledAt: Long = 0,
    val location: VehicleFix? = null,
    val locationStatus: String = "위치 권한 확인 전",
    val navigationStatus: String = "길 안내를 시작하면 내비게이션에 연결합니다",
    val navigationGuiding: Boolean = false,
    val destinationLat: Double? = null,
    val destinationLon: Double? = null,
    val waypoints: List<NavigationStop> = emptyList(),
)
object SafetyGate {
    fun fresh(at: Long, now: Long, ttl: Long) = now >= at && now - at < ttl
    fun canInteract(state: VehicleState, now: Long): Boolean = state.parked == true && state.uxAllowed == true && fresh(state.sampledAt, now, 3_000) && fresh(state.uxSampledAt, now, 3_000)
    fun usable(fix: VehicleFix?, now: Long) = fix != null && fix.lat.isFinite() && fix.lon.isFinite() && fix.lat in -90.0..90.0 && fix.lon in -180.0..180.0 && fresh(fix.sampledAt, now, 60_000)
}
interface VehicleGateway {
    fun start(onState: (VehicleState) -> Unit, onMessage: (String) -> Unit)
    fun route(lat: Double, lon: Double, name: String)
    fun routeRoad(road: DriveRoad)
    fun refreshNavigation() {}
    fun locationPermissions(): Array<String> = emptyArray()
    fun startLocation() {}
    fun stopLocation() {}
    fun simulateParked(parked: Boolean) {}
    fun close()
}

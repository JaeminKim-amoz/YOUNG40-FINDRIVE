package app.findrive.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

fun createGateway(context: Context): VehicleGateway = SimulatorGateway()
private class SimulatorGateway : VehicleGateway {
    private val handler = Handler(Looper.getMainLooper())
    private var parked = true
    private var state: ((VehicleState) -> Unit)? = null
    private var message: ((String) -> Unit)? = null
    private val tick = object : Runnable { override fun run() { state?.invoke(VehicleState(parked, SystemClock.elapsedRealtime(), false, true, SystemClock.elapsedRealtime(), navigationStatus = "시뮬레이터 · 실제 내비게이션 없음")); handler.postDelayed(this, 1000) } }
    override fun start(onState: (VehicleState) -> Unit, onMessage: (String) -> Unit) { state = onState; message = onMessage; handler.post(tick) }
    override fun simulateParked(parked: Boolean) { this.parked = parked; state?.invoke(VehicleState(parked, SystemClock.elapsedRealtime(), false, true, SystemClock.elapsedRealtime(), navigationStatus = "시뮬레이터 · 실제 내비게이션 없음")) }
    override fun route(lat: Double, lon: Double, name: String) { message?.invoke("시뮬레이터에서는 내비게이션을 실행하지 않습니다. Pleos 빌드를 사용하세요.") }
    override fun routeRoad(road: DriveRoad) { message?.invoke("구간 길 안내는 Pleos 앱에서 사용할 수 있습니다.") }
    override fun close() { handler.removeCallbacksAndMessages(null); state = null; message = null }
}

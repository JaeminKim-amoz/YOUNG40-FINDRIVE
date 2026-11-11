package app.findrive.car
import org.junit.Assert.*
import org.junit.Test
class SafetyGateTest {
    @Test fun unknownMovingAndStaleStatesStayLocked() {
        assertFalse(SafetyGate.canInteract(VehicleState(), 10000))
        assertFalse(SafetyGate.canInteract(VehicleState(false, 10000), 10001))
        assertFalse(SafetyGate.canInteract(VehicleState(true, 10000, uxAllowed=true, uxSampledAt=10000), 13000))
        assertFalse(SafetyGate.canInteract(VehicleState(true, 10000, uxAllowed=true, uxSampledAt=10000), 9000))
        assertFalse(SafetyGate.canInteract(VehicleState(true, 10000), 10001))
        assertFalse(SafetyGate.canInteract(VehicleState(true, 10000, uxAllowed=false, uxSampledAt=10000), 10001))
        assertFalse(SafetyGate.canInteract(VehicleState(true, 10000, uxAllowed=true, uxSampledAt=6000), 10001))
        assertTrue(SafetyGate.canInteract(VehicleState(true, 10000, uxAllowed=true, uxSampledAt=10000), 10001))
    }
    @Test fun fusedLocationRejectsStaleFutureAndInvalidCoordinates() {
        assertTrue(SafetyGate.usable(VehicleFix(37.5,127.0,10000,"fused"),10001))
        assertFalse(SafetyGate.usable(VehicleFix(37.5,127.0,10000,"fused"),70000))
        assertFalse(SafetyGate.usable(VehicleFix(37.5,127.0,10000,"fused"),9999))
        assertFalse(SafetyGate.usable(VehicleFix(Double.NaN,127.0,10000,"fused"),10001))
    }
}

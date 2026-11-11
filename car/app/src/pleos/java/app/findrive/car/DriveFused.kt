package app.findrive.car

import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

internal fun hasDriveFused(context:Context)=runCatching{FusedLocation(context).isSupported()}.getOrDefault(false)
internal fun subscribeDriveFused(context:Context,onLocation:(DrivePoint?)->Unit):AutoCloseable {
    val api=FusedLocation(context)
    val listener=object:FusedLocationListener {
        override fun onChanged(location:LocationData?) {
            onLocation(location?.let { DrivePoint(DriveCoord(it.latitude,it.longitude),it.timestampMillis,"Pleos 융합 위치") })
        }
        override fun onError(exception:Exception) {onLocation(null)}
    }
    try {api.initialize();api.registerFusedLocationCallback(listener);api.getLastKnownLocation(listener)}
    catch(e:Exception){runCatching{api.release()};throw e}
    return AutoCloseable {try{api.unregisterFusedLocationCallback(listener)}finally{api.release()}}
}

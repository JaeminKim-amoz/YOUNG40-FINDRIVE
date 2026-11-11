package app.findrive.car

import android.content.Context
internal fun hasDriveFused(context:Context)=false
internal fun subscribeDriveFused(context:Context,onLocation:(DrivePoint?)->Unit):AutoCloseable =
    error("실제 차량 위치는 Pleos 앱에서 사용할 수 있습니다.")

package app.findrive.car

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

internal fun JSONObject.coord() = DriveCoord(optDouble("lat"),optDouble("lon"))
internal fun JSONObject.millis(key: String) = runCatching { Instant.parse(getString(key)).toEpochMilli() }.getOrDefault(0L)
internal fun JSONArray?.records(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
internal fun JSONObject.area(radius: Int = optInt("radiusMeters")): DriveArea? {
    val center = optJSONObject("center")?.coord() ?: return null
    val since = millis("since")
    if (!center.valid() || since <= 0 || radius !in 100..10000 || optString("reportId").isBlank()) return null
    return DriveArea(getString("reportId"), optString("anchorId").takeIf{it.isNotBlank() && it!="null"}, since, center, radius)
}
internal fun JSONObject.road(course: Boolean = false): DriveRoad? {
    val points=optJSONArray("points").records().map{it.coord()}
    val stops=optJSONArray("stops").records().map{it.coord()}
    if(points.size<2 || points.any{!it.valid()} || optString("id").isBlank() || optInt("lengthMeters")<=0)return null
    if(course && (stops.size !in 2..3 || stops.any{!it.valid()} || stops.first().distance(points.first())>2 || stops.last().distance(points.last())>2))return null
    val names=optJSONArray("streets")
    return DriveRoad(optString("id"),optString("name"),optInt("lengthMeters"),points,stops,
        (0 until (names?.length()?:0)).mapNotNull{names?.optString(it)?.takeIf(String::isNotBlank)})
}
internal fun JSONObject.driveRoads() = optJSONArray("roads").records().mapNotNull{it.road()}
internal data class HeatRing(val inner:Double,val outer:Double,val density:List<Double>)
internal fun JSONObject.heatRings(radius:Int):List<HeatRing> = optJSONArray("rings").records().mapNotNull{ring->
    val inner=ring.optDouble("inner");val outer=ring.optDouble("outer")
    val mass=ring.optJSONArray("mass") ?: return@mapNotNull null
    if(!inner.isFinite() || !outer.isFinite() || inner<0 || outer<=inner || outer>radius+0.1 || mass.length() !in 1..160)return@mapNotNull null
    val scale=mass.length()*radius.toDouble()*radius/(outer*outer-inner*inner)
    val density=(0 until mass.length()).map{mass.optDouble(it)*scale}
    if(density.any{!it.isFinite()||it<0})null else HeatRing(inner/radius,outer/radius,density)
}
internal fun JSONObject.edge(): DriveEdge? {
    val a = optJSONObject("from") ?: return null
    val b = optJSONObject("to") ?: return null
    val at = b.millis("at")
    return if (at > 0 && a.coord().valid() && b.coord().valid()) DriveEdge(a.coord(),b.coord(),at) else null
}

/** Durable outbox. Only acknowledged IDs are removed, after the new snapshot is saved. */
internal class DriveStore private constructor(context: Context): SQLiteOpenHelper(context,"findrive-drive.db",null,1) {
    companion object {
        @Volatile private var instance: DriveStore? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: DriveStore(context.applicationContext).also { instance=it }
        }
    }
    @Volatile var revision=0L
        private set
    @Volatile var active: DriveArea?=null
        private set
    @Volatile var status=""
        private set
    @Volatile var latest: DrivePoint?=null
    @Volatile var ready=false
    private val writer=Executors.newSingleThreadExecutor()
    private var ownerEpoch=0L
    private var waitingWrites=0
    override fun onCreate(db: SQLiteDatabase) = db.execSQL("CREATE TABLE passes (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun begin(area: DriveArea) {
        check(active == null) {"진행 중인 수색을 먼저 종료해 주세요."}
        active=area; latest=null; ready=false; status="위치 수신 대기 중";revision++
    }
    @Synchronized fun note(value: String) { if (value!=status) { status=value; revision++ } }
    @Synchronized fun end(message: String) { active=null; latest=null; ready=false;status=message; revision++ }

    @Synchronized fun enqueue(area:DriveArea,from:DrivePoint,to:DrivePoint,onFailure:(Exception)->Unit) {
        if (active !== area) return
        val epoch=ownerEpoch
        waitingWrites++
        writer.execute {
            try { synchronized(this) { if(epoch==ownerEpoch)append(area,from,to) } }
            catch(error:Exception) { onFailure(error) }
            finally { synchronized(this){waitingWrites--} }
        }
    }
    @Synchronized fun hasUnsent()=waitingWrites>0 || pending(1).length()>0
    private fun append(area: DriveArea, from: DrivePoint, to: DrivePoint) {
        val db=writableDatabase
        val count=db.rawQuery("SELECT COUNT(*) FROM passes",null).use{it.moveToFirst();it.getInt(0)}
        check(count<6000) {"주행 기록을 동기화한 뒤 다시 시작해 주세요."}
        fun point(p:DrivePoint)=JSONObject().put("lat",p.coord.lat).put("lon",p.coord.lon).put("at",Instant.ofEpochMilli(p.at).toString()).also{j->p.accuracy?.let{j.put("accuracy",it)}}
        val id=UUID.randomUUID().toString()
        val payload=JSONObject().put("id",id).put("reportId",area.reportId).put("radiusMeters",area.radius)
            .put("anchorId",area.anchorId).put("from",point(from)).put("to",point(to))
        db.insertOrThrow("passes",null,ContentValues().apply{put("id",id);put("payload",payload.toString())})
        revision++
    }
    fun pending(limit:Int=6000): JSONArray {
        val result=JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM passes ORDER BY rowid LIMIT ?",arrayOf(limit.toString())).use {
            while(it.moveToNext()) result.put(JSONObject(it.getString(0)))
        }
        return result
    }
    @Synchronized fun acknowledge(ids:List<String>) {
        if(ids.isEmpty()) return
        writableDatabase.delete("passes","id IN ("+ids.joinToString(","){"?"}+")",ids.toTypedArray())
        revision++
    }
    @Synchronized fun clear() { ownerEpoch++;end(""); writableDatabase.delete("passes",null,null); revision++ }
}

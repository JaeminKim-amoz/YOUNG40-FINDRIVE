package app.findrive.car

import kotlin.math.*

data class DriveCoord(val lat: Double, val lon: Double) {
    fun valid() = lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180
    fun east(other: DriveCoord) = ((other.lon - lon + 540) % 360 - 180) * 111195 * cos(Math.toRadians(lat))
    fun north(other: DriveCoord) = (other.lat - lat) * 111195
    fun distance(other: DriveCoord) = hypot(east(other), north(other))
    fun towards(other: DriveCoord, fraction: Double) = DriveCoord(lat + (other.lat-lat)*fraction,
        (lon + ((other.lon-lon+540)%360-180)*fraction + 540)%360-180)
}

data class DriveArea(val reportId: String, val anchorId: String?, val since: Long, val center: DriveCoord, val radius: Int)
data class DrivePoint(val coord: DriveCoord, val at: Long, val source: String, val accuracy: Double? = null)
data class DriveEdge(val from: DriveCoord, val to: DriveCoord, val at: Long)
data class DriveRoad(val id: String, val name: String, val length: Int, val points: List<DriveCoord>,
    val stops: List<DriveCoord> = emptyList(), val streets: List<String> = emptyList()) {
    fun navigationStops(): List<DriveCoord> = stops.ifEmpty {
        if (length >= 100) listOf(points.first(), midpoint(), points.last()) else listOf(points.first(), points.last())
    }
    fun midpoint(): DriveCoord {
        val lengths = points.zipWithNext { a,b -> a.distance(b) }
        var remaining = lengths.sum()/2
        for ((i,length) in lengths.withIndex()) {
            if (length > 0 && remaining <= length) return points[i].towards(points[i+1], remaining/length)
            remaining -= length
        }
        return points.last()
    }
}

fun trailOpacity(at: Long, now: Long): Double {
    if (at <= 0 || at > now + 5000) return 0.0
    return 0.18 + 0.82 * 2.0.pow(-(now-at).coerceAtLeast(0).toDouble()/21_600_000)
}

/** Keep only adjacent, fresh fixes inside the selected circle. Never connect across an exit or a provider switch. */
class DriveRecorder(private val area: DriveArea) {
    private var previous: DrivePoint? = null
    fun reset() { previous = null }
    fun accept(point: DrivePoint, now: Long): Pair<DrivePoint, DrivePoint>? {
        if (!point.coord.valid() || point.at < area.since || now-point.at !in 0..5000 ||
            point.accuracy?.let { !it.isFinite() || it !in 0.0..35.0 } == true ||
            area.center.distance(point.coord) > area.radius) { reset(); return null }
        val last = previous
        if (last == null || last.source != point.source) { previous = point; return null }
        val dt = point.at-last.at
        if (dt <= 0) return null
        val meters = last.coord.distance(point.coord)
        if (dt > 15000 || meters > 195 || meters*1000/dt > 44) { previous=point; return null }
        if (dt < 1500 || meters < 8) return null
        previous = point
        return Pair(last, point)
    }
}

data class RoadProgress(val percent: Int, val lastAt: Long)

/** Compare local road tangents against measured movement. A perpendicular crossing does not cover a street. */
fun roadProgress(roads: List<DriveRoad>, traces: List<DriveEdge>, center: DriveCoord): Map<String, RoadProgress> {
    data class Sample(val road: String, val x: Double, val y: Double, val ux: Double, val uy: Double, val weight: Double, var at: Long = 0)
    val bins = HashMap<Pair<Int,Int>,MutableList<Sample>>()
    val samples = ArrayList<Sample>()
    fun cell(v: Double) = floor(v/50).toInt()
    for (road in roads) for ((a,b) in road.points.zipWithNext()) {
        val x = center.east(a); val y = center.north(a)
        val dx = center.east(b)-x; val dy = center.north(b)-y; val length = hypot(dx,dy)
        if (length < 0.1) continue
        val count = ceil(length/15).toInt()
        repeat(count) { i ->
            val fraction=(i+0.5)/count
            val sample=Sample(road.id,x+dx*fraction,y+dy*fraction,dx/length,dy/length,length/count)
            bins.getOrPut(cell(sample.x) to cell(sample.y)){ArrayList()}.add(sample)
            samples.add(sample)
        }
    }
    for (trace in traces) {
        val x=center.east(trace.from); val y=center.north(trace.from)
        val endX=center.east(trace.to); val endY=center.north(trace.to)
        val dx=endX-x; val dy=endY-y; val length=hypot(dx,dy)
        if (length < 3 || length > 205) continue
        for (cx in cell(min(x,endX)-18)..cell(max(x,endX)+18)) for (cy in cell(min(y,endY)-18)..cell(max(y,endY)+18)) {
            val nearby=bins[cx to cy] ?: continue
            for (p in nearby) {
                if (trace.at <= p.at || abs(dx*p.ux+dy*p.uy) < length*0.78) continue
                val t=((p.x-x)*dx+(p.y-y)*dy)/(length*length)
                if (t in 0.0..1.0 && hypot(p.x-x-t*dx,p.y-y-t*dy) <= 18) p.at=trace.at
            }
        }
    }
    return samples.groupBy{it.road}.mapValues{(_,s) ->
        RoadProgress((100*s.filter{it.at>0}.sumOf{it.weight}/s.sumOf{it.weight}).roundToInt(), s.maxOf{it.at})
    }
}

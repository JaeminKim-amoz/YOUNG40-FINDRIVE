package app.findrive.car

import org.junit.Assert.*
import org.junit.Test

class DriveGeometryTest {
    private val origin=DriveCoord(37.5445,127.0374)
    private val area=DriveArea("report",null,1000,origin,250)
    private fun coord(m:Double)=DriveCoord(origin.lat+m/111195,origin.lon)
    private fun point(m:Double,t:Long,accuracy:Double?=4.0)=DrivePoint(coord(m),t,"GPS",accuracy)
    @Test fun nativeHandoffRetainsPlannerViaAndLegacyMidpoint() {
        val shape=listOf(coord(0.0),coord(40.0),coord(150.0))
        val road=DriveRoad("course","추천 수색 코스",150,shape,shape)
        assertEquals(shape,road.navigationStops())
        assertNotEquals(road.midpoint(),road.navigationStops()[1])
        assertEquals(road.midpoint(),road.copy(stops=emptyList()).navigationStops()[1])
    }
    @Test fun noBridgesAcrossBoundaryGapsPoorAccuracyOrProviderChange() {
        val recorder=DriveRecorder(area)
        fun accept(m:Double,t:Long,acc:Double?=4.0)=recorder.accept(point(m,t,acc),t)
        assertNull(accept(0.0,1000));assertNotNull(accept(20.0,3000))
        assertNull(accept(260.0,10000));assertNull(accept(40.0,14000))
        assertNotNull(accept(65.0,17000));assertNull(accept(80.0,35000))
        assertNull(accept(90.0,38000,80.0));assertNull(accept(92.0,41000))
        assertNull(recorder.accept(point(93.0,43000).copy(source="Pleos"),43000))
        assertNull(recorder.accept(point(100.0,45000),51000))
    }
    @Test fun olderPassesFadeAndRemainVisibleAndOnlyRevisitedGeometryGetsNewTime() {
        val now=200000000L
        assertEquals(1.0,trailOpacity(now,now),1e-9)
        assertEquals(.59,trailOpacity(now-21600000,now),1e-9)
        assertEquals(.23125,trailOpacity(now-86400000,now),1e-9)
        assertEquals(0.0,trailOpacity(now+6000,now),0.0)
        val roads=listOf(DriveRoad("a","북쪽",100,listOf(coord(0.0),coord(100.0))),DriveRoad("b","남쪽",100,listOf(coord(-100.0),coord(0.0))))
        val old=now-86400000
        val seen=roadProgress(roads,listOf(DriveEdge(coord(-100.0),coord(0.0),old),DriveEdge(coord(0.0),coord(48.0),now)),origin)
        assertEquals(old,seen.getValue("b").lastAt);assertEquals(100,seen.getValue("b").percent)
        assertEquals(now,seen.getValue("a").lastAt);assertTrue(seen.getValue("a").percent in 40..52)
        val cross=DriveEdge(DriveCoord(origin.lat,origin.lon-.0005),DriveCoord(origin.lat,origin.lon+.0005),now)
        assertTrue(roadProgress(roads,listOf(cross),origin).values.all{it.percent==0 && it.lastAt==0L})
    }
}

package app.findrive.car

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min

/** A labelled observation schematic. Geometry is prepared outside onDraw. */
class CoverageView @JvmOverloads constructor(context:Context,plan:JSONObject?=null,private val design:CarDesign=CarDesign(context)):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash=DashPathEffect(floatArrayOf(design.dp(8).toFloat(),design.dp(6).toFloat()),0f)
    private val colors=design.colors
    private val meters=(plan?.optDouble("radiusMeters",500.0)?:500.0).coerceAtLeast(1.0)
    private val normalized=ArrayList<Pair<Long,FloatArray>>()
    private var lines=emptyArray<FloatArray>()
    private var cx=0f;private var cy=0f;private var radius=0f
    init {
        val center=plan?.optJSONObject("center")
        val segments=plan?.optJSONArray("segments")
        if(center!=null&&segments!=null) {
            val lat=center.optDouble("lat");val lon=center.optDouble("lon")
            for(i in 0 until min(segments.length(),250)) {
                val segment=segments.optJSONObject(i)?:continue
                val points=segment.optJSONArray("points")?:continue
                val a=points.optJSONObject(0)?:continue;val b=points.optJSONObject(1)?:continue
                fun x(p:JSONObject)=((((p.optDouble("lon")-lon+540)%360)-180)*111195*cos(Math.toRadians(lat))/meters).toFloat()
                fun y(p:JSONObject)=(-(p.optDouble("lat")-lat)*111195/meters).toFloat()
                val kind=when(segment.optString("coverage")){"clear"->0f;"detected"->2f;else->1f}
                val line=floatArrayOf(x(a),y(a),x(b),y(b),kind,if(segment.optString("source")=="simulation"||kind==1f)1f else 0f)
                if(line.all{it.isFinite()})normalized.add(segment.millis("at") to line)
            }
            normalized.sortBy{it.first}
        }
        paint.typeface=design.regular
        contentDescription="관측 개요, 도로 지도 아님. 계획 반경 "+meters.toInt()+"미터, "+normalized.size+"개 표시 구간. 분석 중 미검출, 동물 검출, 분석 공백을 색과 선으로 구분합니다."
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        cx=w/2f;cy=h/2f-design.dp(12);radius=min(w.toFloat(),h-design.dp(48).toFloat())*.43f
        lines=normalized.map{(_,it)->floatArrayOf(cx+it[0]*radius,cy+it[1]*radius,cx+it[2]*radius,cy+it[3]*radius,it[4],it[5])}.toTypedArray()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        paint.pathEffect=null;paint.style=Paint.Style.FILL;paint.color=colors.accentSurface
        canvas.drawCircle(cx,cy,radius,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=design.dp(1).toFloat();paint.color=colors.line
        canvas.drawCircle(cx,cy,radius,paint);canvas.drawCircle(cx,cy,radius*.5f,paint)
        canvas.drawLine(cx-radius,cy,cx+radius,cy,paint);canvas.drawLine(cx,cy-radius,cx,cy+radius,paint)
        paint.style=Paint.Style.FILL;paint.color=colors.surface;canvas.drawCircle(cx,cy,design.dp(8).toFloat(),paint)
        paint.color=colors.accent;canvas.drawCircle(cx,cy,design.dp(4).toFloat(),paint);paint.style=Paint.Style.STROKE
        paint.strokeCap=Paint.Cap.ROUND
        val now=System.currentTimeMillis()
        for((index,line) in lines.withIndex()) {
            paint.color=when(line[4]){0f->colors.accent;2f->colors.warning;else->colors.muted}
            paint.alpha=(255*(if(normalized[index].first==0L)0.18 else trailOpacity(normalized[index].first,now))).toInt()
            paint.strokeWidth=design.dp(if(line[4]==2f)7 else 5).toFloat();paint.pathEffect=if(line[5]==1f)dash else null
            canvas.drawLine(line[0],line[1],line[2],line[3],paint)
        }
        paint.pathEffect=null;paint.style=Paint.Style.FILL
        paint.color=colors.muted;paint.textSize=22*resources.displayMetrics.scaledDensity;paint.textAlign=Paint.Align.CENTER
        canvas.drawText("수색 중심",cx,height-design.dp(8).toFloat(),paint)
        if(lines.isNotEmpty() && isShown)postInvalidateDelayed(60000)
    }
}

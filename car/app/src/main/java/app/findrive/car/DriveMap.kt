package app.findrive.car

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.*
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

internal fun driveInk(design:CarDesign,opacity:Double):Int {
    val blue=if(design.night)Color.rgb(127,186,255) else Color.rgb(32,85,178)
    val background=design.colors.accentSurface
    fun mix(f:Int,b:Int)=(f*opacity+b*(1-opacity)).roundToInt()
    return Color.rgb(mix(Color.red(blue),Color.red(background)),mix(Color.green(blue),Color.green(background)),mix(Color.blue(blue),Color.blue(background)))
}

/** A local north-up plot of the routing API's road shapes and timestamped location pairs. */
@SuppressLint("ViewConstructor") // CarScreen constructs the view with the selected area and road geometry.
internal class DriveMap(context:Context,private val design:CarDesign,private val area:DriveArea,
    roads:List<DriveRoad>,private val selected:String?,traces:List<DriveEdge>,private val heat:List<HeatRing> = emptyList()):View(context) {
    private val c=design.colors
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash=DashPathEffect(floatArrayOf(design.dp(8).toFloat(),design.dp(5).toFloat()),0f)
    private var cx=0f; private var cy=0f; private var radius=0f
    private fun x(p:DriveCoord)=(area.center.east(p)/area.radius).toFloat()
    private fun y(p:DriveCoord)=(-area.center.north(p)/area.radius).toFloat()
    private val shapes=roads.sortedBy{it.id==selected}.map { road-> road.id to Path().apply { road.points.forEachIndexed{i,p->if(i==0)moveTo(x(p),y(p)) else lineTo(x(p),y(p))} } }
    private val paths=shapes.map{it.first to Path()}
    private val drive=traces.sortedBy{it.at}
    private val normalized=drive.map{floatArrayOf(x(it.from),y(it.from),x(it.to),y(it.to))}
    private var lines=emptyList<FloatArray>()
    private val clip=Path()
    private val heatPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeCap=Paint.Cap.BUTT}
    private val peak=heat.maxOfOrNull{it.density.maxOrNull()?:0.0}?:0.0
    private val amber=if(design.night)Color.rgb(247,179,75) else Color.rgb(204,122,19)
    private val bounds=RectF()
    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription="북쪽이 위인 수색 구간도. 반경 ${area.radius}미터, 선택한 코스는 초록 선입니다. 주황색이 진할수록 우선순위가 높습니다. 파란 선은 실제 통과 시각에 따라 옅어집니다."
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        cx=w/2f;cy=h/2f;radius=min(w,h)*0.44f
        val m=Matrix().apply{setScale(radius,radius);postTranslate(cx,cy)}
        shapes.forEachIndexed{i,(_,path)->path.transform(m,paths[i].second)}
        lines=normalized.map{floatArrayOf(cx+it[0]*radius,cy+it[1]*radius,cx+it[2]*radius,cy+it[3]*radius)}
        clip.reset();clip.addCircle(cx,cy,radius,Path.Direction.CW)
    }
    override fun onDraw(canvas:Canvas) {
        val now=System.currentTimeMillis()
        paint.style=Paint.Style.FILL;paint.color=c.accentSurface;paint.pathEffect=null
        canvas.drawCircle(cx,cy,radius,paint)
        if(peak>0)for(band in heat){
            val r=(radius*(band.inner+band.outer)/2).toFloat()
            bounds.set(cx-r,cy-r,cx+r,cy+r)
            heatPaint.strokeWidth=(radius*(band.outer-band.inner)).toFloat()
            val sweep=360f/band.density.size
            for((index,density) in band.density.withIndex()){
                heatPaint.color=amber;heatPaint.alpha=(165*kotlin.math.sqrt(density/peak)).roundToInt()
                canvas.drawArc(bounds,-(index+1)*sweep,sweep,false,heatPaint)
            }
        }
        paint.style=Paint.Style.STROKE;paint.strokeWidth=design.dp(1).toFloat();paint.color=c.line
        canvas.drawCircle(cx,cy,radius,paint);canvas.drawCircle(cx,cy,radius/2,paint)
        canvas.save();canvas.clipPath(clip);paint.strokeCap=Paint.Cap.ROUND
        for ((id,path) in paths) {
            paint.pathEffect=if(id==selected)null else dash;paint.color=if(id==selected)c.accent else c.muted;paint.strokeWidth=design.dp(if(id==selected)5 else 2).toFloat()
            canvas.drawPath(path,paint)
        }
        paint.pathEffect=null;paint.strokeWidth=design.dp(5).toFloat()
        // Use opaque blended ink. Repeated old passes must not stack up to the colour of a fresh pass.
        for((i,line) in lines.withIndex()) {
            val a=trailOpacity(drive[i].at,now)
            if(a==0.0)continue
            paint.color=driveInk(design,a)
            canvas.drawLine(line[0],line[1],line[2],line[3],paint)
        }
        canvas.restore();paint.style=Paint.Style.FILL;paint.color=c.ink
        canvas.drawCircle(cx,cy,design.dp(3).toFloat(),paint)
        if (lines.isNotEmpty() && isShown) postInvalidateDelayed(60_000)
    }
}

package app.findrive.car

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.*
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Pleos neutral surfaces, Asta Sans, and FINDRIVE semantic accents. */
data class CarPalette(
    val background: Int, val surface: Int, val subtle: Int, val ink: Int,
    val muted: Int, val line: Int, val accent: Int, val onAccent: Int,
    val accentSurface: Int, val warning: Int, val disabled: Int,
) {
    companion object {
        private fun c(hex: String) = hex.removePrefix("#").toLong(16).toInt() or (0xFF shl 24)
        fun forNight(night: Boolean) = if (night) CarPalette(
            c("#1D1E20"), c("#303236"), c("#25272A"), c("#FAFAFA"),
            c("#C2C7CD"), c("#50555C"), c("#87DDB1"), c("#10251B"),
            c("#263E33"), c("#FFCF87"), c("#41454B"),
        ) else CarPalette(
            c("#F6F7F9"), Color.WHITE, c("#ECEFF2"), c("#141618"),
            c("#535B65"), c("#D4D9DF"), c("#176348"), Color.WHITE,
            c("#E5F1EA"), c("#865200"), c("#DDE1E5"),
        )
    }
}

enum class CarGlyph { SEARCH, LINK, LOCATION, NAVIGATE, REFRESH, BACK, CHEVRON, SHIELD, FLAG, CHECK, LOCK, PAW }

/** Static line icons; paths and paint are allocated once. */
class CarIcon(private val glyph: CarGlyph, color: Int, private val size: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color=color; style=Paint.Style.STROKE; strokeWidth=1.8f
        strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND
    }
    private val path = Path().apply {
        when(glyph) {
            CarGlyph.NAVIGATE -> {moveTo(21f,3f);lineTo(15f,21f);lineTo(10f,14f);lineTo(3f,9f);close()}
            CarGlyph.BACK -> {moveTo(14f,5f);lineTo(7f,12f);lineTo(14f,19f);moveTo(7f,12f);lineTo(21f,12f)}
            CarGlyph.CHEVRON -> {moveTo(9f,5f);lineTo(16f,12f);lineTo(9f,19f)}
            CarGlyph.SHIELD -> {moveTo(12f,2f);lineTo(21f,6f);lineTo(20f,14f);quadTo(18f,20f,12f,22f);quadTo(6f,20f,4f,14f);lineTo(3f,6f);close();moveTo(8f,12f);lineTo(11f,15f);lineTo(16f,9f)}
            CarGlyph.FLAG -> {moveTo(5f,22f);lineTo(5f,3f);lineTo(12f,3f);lineTo(14f,5f);lineTo(21f,5f);lineTo(19f,13f);lineTo(12f,13f);lineTo(10f,11f);lineTo(5f,11f)}
            CarGlyph.CHECK -> {moveTo(4f,12f);lineTo(10f,18f);lineTo(20f,6f)}
            CarGlyph.REFRESH -> {moveTo(20f,3f);lineTo(20f,9f);lineTo(14f,9f)}
            else -> Unit
        }
    }
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    override fun draw(canvas: Canvas) {
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        pen.style=Paint.Style.STROKE
        when(glyph) {
            CarGlyph.SEARCH -> {canvas.drawCircle(10.5f,10.5f,6.5f,pen);canvas.drawLine(15.5f,15.5f,21f,21f,pen)}
            CarGlyph.LOCATION -> {canvas.drawCircle(12f,12f,7f,pen);canvas.drawCircle(12f,12f,2.5f,pen);canvas.drawLine(12f,1f,12f,5f,pen);canvas.drawLine(12f,19f,12f,23f,pen);canvas.drawLine(1f,12f,5f,12f,pen);canvas.drawLine(19f,12f,23f,12f,pen)}
            CarGlyph.LINK -> {canvas.save();canvas.rotate(-40f,12f,12f);canvas.drawRoundRect(2f,8f,14f,16f,4f,4f,pen);canvas.drawRoundRect(10f,8f,22f,16f,4f,4f,pen);canvas.restore()}
            CarGlyph.REFRESH -> {canvas.drawArc(4f,4f,20f,20f,35f,300f,false,pen);canvas.drawPath(path,pen)}
            CarGlyph.LOCK -> {canvas.drawRoundRect(5f,10f,19f,21f,2f,2f,pen);canvas.drawArc(8f,2f,16f,16f,180f,180f,false,pen);canvas.drawLine(12f,14f,12f,17f,pen)}
            CarGlyph.PAW -> {pen.style=Paint.Style.FILL;canvas.drawOval(5f,11f,19f,22f,pen);canvas.drawOval(1f,8f,6f,14f,pen);canvas.drawOval(6f,2f,11f,9f,pen);canvas.drawOval(13f,2f,18f,9f,pen);canvas.drawOval(18f,8f,23f,14f,pen)}
            else -> canvas.drawPath(path,pen)
        }
        canvas.restore()
    }
    override fun setAlpha(alpha:Int){pen.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:ColorFilter?){pen.colorFilter=filter;invalidateSelf()}
    @Deprecated("Drawable opacity") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

class CarDesign(val context: Context) {
    val night=context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
    val colors=CarPalette.forNight(night)
    private val font=context.resources.getFont(R.font.asta_sans)
    val regular:Typeface=Typeface.create(font,400,false)
    val strong:Typeface=Typeface.create(font,700,false)
    fun dp(value:Int)=(value*context.resources.displayMetrics.density+.5f).toInt()
    fun shape(color:Int,radius:Int=24,stroke:Int?=null)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(radius).toFloat();if(stroke!=null)setStroke(dp(2),stroke)
    }
    fun ripple(color:Int,radius:Int=24,stroke:Int?=null):Drawable=RippleDrawable(
        ColorStateList.valueOf(if(night)0x26FFFFFF else 0x18000000),shape(color,radius,stroke),shape(Color.WHITE,radius),
    )
    // Full explanatory/consent text must remain readable at large system font sizes.
    // Compact list labels opt into an explicit line limit at the call site.
    fun text(value:String,size:Int=26,bold:Boolean=false,color:Int=colors.ink,lines:Int=Int.MAX_VALUE)=TextView(context).apply {
        text=value;textSize=size.toFloat();typeface=if(bold)strong else regular;fontVariationSettings=if(bold)"'wght' 700" else "'wght' 400";setTextColor(color)
        includeFontPadding=false;setLineSpacing(dp(5).toFloat(),1f);maxLines=lines;ellipsize=android.text.TextUtils.TruncateAt.END
    }
    fun column(padding:Int=0)=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(padding),dp(padding),dp(padding),dp(padding))
    }
    fun row()=LinearLayout(context).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    fun button(label:String,primary:Boolean=false,glyph:CarGlyph?=null)=Button(context).apply {
        text=label;textSize=26f;typeface=strong;fontVariationSettings="'wght' 700";isAllCaps=false;minWidth=0;minimumWidth=0
        minHeight=dp(88);minimumHeight=dp(88);maxLines=2;includeFontPadding=false;gravity=Gravity.CENTER
        setPadding(dp(24),dp(16),dp(24),dp(16));stateListAnimator=null
        val foreground=if(primary)colors.onAccent else colors.ink
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(colors.muted,foreground)))
        background=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(colors.disabled))
            addState(intArrayOf(),ripple(if(primary)colors.accent else colors.subtle))
        }
        glyph?.let{setCompoundDrawablesRelativeWithIntrinsicBounds(CarIcon(it,foreground,dp(28)),null,null,null);compoundDrawablePadding=dp(12);compoundDrawableTintList=textColors}
        isFocusable=true
    }
    fun space(height:Int)=View(context).apply{layoutParams=LinearLayout.LayoutParams(1,dp(height));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
}

package app.findrive.car

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class CarContrastTest {
    private fun luminance(color:Int):Double {
        fun channel(shift:Int):Double {
            val value=((color shr shift) and 255)/255.0
            return if(value<=.04045)value/12.92 else ((value+.055)/1.055).pow(2.4)
        }
        return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
    }
    @Test fun textAndMeaningfulSymbolsMeetPleosContrastInBothThemes() {
        for(night in listOf(false,true)) {
            val p=CarPalette.forNight(night)
            val combinations=listOf(
                p.ink to p.surface,p.ink to p.background,p.muted to p.surface,
                p.muted to p.subtle,p.muted to p.disabled,p.accent to p.accentSurface,
                p.onAccent to p.accent,p.warning to p.surface,p.warning to p.accentSurface,
                p.muted to p.accentSurface,
            )
            for((foreground,background) in combinations) {
                val a=luminance(foreground);val b=luminance(background)
                val contrast=(maxOf(a,b)+.05)/(minOf(a,b)+.05)
                assertTrue("night="+night+" contrast="+contrast,contrast>=4.5)
            }
        }
    }
}

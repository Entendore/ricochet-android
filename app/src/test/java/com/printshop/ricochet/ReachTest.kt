package com.printshop.ricochet

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReachTest {
    /** vmax²/2g must keep every legal target inside the parabola of safety
     *  on every aspect ratio — this is exactly what vmax = 1.15·max(W,H)
     *  broke on square-ish windows (the original "puzzle 1 is impossible"). */
    private fun inEnvelope(m: Metrics, tx: Float, ty: Float): Boolean {
        val apex = m.vmax * m.vmax / (2f * m.g)
        val d = abs(tx - m.bowX)
        val maxRise = apex - m.g * d * d / (2f * m.vmax * m.vmax)
        return m.bowY - ty <= maxRise
    }

    @Test fun `every legal target band is reachable on all aspect ratios`() {
        for (aspect in floatArrayOf(0.45f, 0.66f, 1f, 1.78f, 2.4f)) {
            val h = 1600f; val w = h * aspect
            val m = Metrics(w, h)
            var x = 0.30f * w
            while (x <= 0.84f * w) {
                var y = 0.16f * h
                while (y <= 0.40f * h) {
                    assertTrue(
                        "unreachable (${x.toInt()},${y.toInt()}) at aspect $aspect",
                        inEnvelope(m, x, y))
                    y += 0.04f * h
                }
                x += 0.06f * w
            }
        }
    }

    @Test fun `tiny phone screen still reaches puzzle one`() {
        val m = Metrics(320f, 480f)
        assertTrue(inEnvelope(m, .74f * 320f, .26f * 480f))
    }
}

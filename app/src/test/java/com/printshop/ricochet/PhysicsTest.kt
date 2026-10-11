package com.printshop.ricochet

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class PhysicsTest {
    private fun mm(w: Int, h: Int) = Metrics(w.toFloat(), h.toFloat())
    private fun arrow(x: Float, y: Float, vx: Float, vy: Float, pierce: Int = 0): Arrow {
        val a = Arrow(); a.x = x; a.y = y; a.vx = vx; a.vy = vy; a.pierce = pierce
        return a
    }

    @Test fun `slow wall hit sticks instead of bouncing`() {
        val m = mm(1000, 1800)
        val w = World(m)
        // inside the wall band; imp = 200 < 150*K = 234 → stick
        val a = arrow(500f, m.H - 20f, 0f, 200f)
        val evs = ArrayList<Ev>()
        assertTrue(w.resolve(a, false, 1f / 240f, evs))
        assertTrue(evs.any { it is Ev.Stick })
        assertFalse(evs.any { it is Ev.Bounce })
    }

    @Test fun `fast wall bounce reflects with 078 restitution`() {
        val m = mm(1000, 1800)
        val w = World(m)
        val a = arrow(m.W - 20f, 900f, 1500f, 0f)
        val evs = ArrayList<Ev>()
        assertFalse(w.resolve(a, false, 1f / 240f, evs))
        assertEquals(-1500f * 0.78f, a.vx, 0.5f)
        assertEquals(1, a.bounces)
        assertEquals("wall", (evs.first { it is Ev.Bounce } as Ev.Bounce).type)
    }

    @Test fun `bumper reflects and reboosts into the tuned band`() {
        val m = mm(1000, 1800)
        val w = World(m)
        w.obs.add(RObs().apply { t = "bump"; x = 500f; y = 900f; r = 100f })
        // overlapping the bumper: d = 90 < AR + r = 107
        val a = arrow(500f, 810f, 0f, 800f)
        val evs = ArrayList<Ev>()
        assertFalse(w.resolve(a, false, 1f / 240f, evs))
        val sp = hypot(a.vx, a.vy)
        assertEquals(800f * 1.1f + 240f * m.K, sp, 1f)      // the reboost formula
        assertTrue(sp >= 620f * m.K && sp <= m.vmax * 0.93f)
        assertEquals(1, a.bounces)
        assertEquals("bump", (evs.first { it is Ev.Bounce } as Ev.Bounce).type)
    }

    @Test fun `crate shatters and bleeds ten percent speed`() {
        val m = mm(1000, 1800)
        val w = World(m)
        val o = RObs().apply { t = "crate"; x = 500f; y = 900f; w = 80f; h = 80f }
        w.obs.add(o)
        // overlapping the crate face: 3px above y=860, AR = 7 → pen = 4
        val a = arrow(500f, 857f, 0f, 1000f)
        val evs = ArrayList<Ev>()
        assertFalse(w.resolve(a, false, 1f / 240f, evs))    // punch-through, not a stop
        assertTrue(o.dead)
        assertEquals(900f, hypot(a.vx, a.vy), 0.5f)         // ×0.9
        assertEquals(0, a.bounces)
        assertTrue(evs.any { it is Ev.Crate })
        // a dead crate never re-triggers
        val evs2 = ArrayList<Ev>()
        assertFalse(w.resolve(a, false, 1f / 240f, evs2))
        assertFalse(evs2.any { it is Ev.Crate })
    }

    @Test fun `piercing arrow phases a plank and spends exactly one charge`() {
        val m = mm(1000, 1800)
        val w = World(m)
        w.obs.add(RObs().apply { t = "plank"; x = 500f; y = 900f; w = 200f; h = 60f })
        val a = arrow(500f, 900f, 800f, 0f, pierce = 2)
        val evs = ArrayList<Ev>()
        assertFalse(w.resolve(a, false, 1f / 240f, evs))
        assertEquals(1, a.pierce)
        assertEquals(1, a.pierced)
        assertEquals(800f, a.vx, 0.01f)                     // no deflection
        assertTrue(evs.any { it is Ev.Slice })
        // ghost flight phases silently — no live events
        val g = arrow(500f, 900f, 800f, 0f, pierce = 2)
        val evs2 = ArrayList<Ev>()
        w.resolve(g, true, 1f / 240f, evs2)
        assertTrue(evs2.isEmpty())
        assertEquals(1, g.pierce)
    }

    @Test fun `portal teleports preserving velocity then cools down`() {
        val m = mm(1000, 1800)
        val w = World(m)
        // exit end kept INSIDE the wall band so the teleport isn't
        // immediately eaten by a wall bounce
        w.obs.add(RObs().apply {
            t = "portal"; ax = 300f; ay = 900f; bx = 700f; by = 400f; r = 120f
        })
        val a = arrow(300f, 900f, 1000f, 0f)
        val evs = ArrayList<Ev>()
        assertFalse(w.resolve(a, false, 1f / 240f, evs))
        assertEquals(700f + 120f + m.AR + 3f, a.x, 1f)
        assertEquals(400f, a.y, 1f)
        assertEquals(1000f * 0.96f, a.vx, 0.5f)
        assertEquals(0.35f, a.pcd, 1e-4f)
        assertTrue(a.ported)
        val x0 = a.x
        w.resolve(a, false, 1f / 240f, ArrayList())         // cooldown blocks reuse
        assertEquals(x0, a.x, 1f)
        val g = arrow(300f, 900f, 1000f, 0f)
        w.resolve(g, true, 1f / 240f, ArrayList())
        assertFalse(g.ported)                               // ghost doesn't flag
    }

    @Test fun `wind zone adds force proportional to gravity`() {
        val m = mm(1000, 1800)
        val w = World(m)
        w.obs.add(RObs().apply {
            t = "wind"; x = 400f; y = 800f; w = 300f; h = 300f; kx = 1f
        })
        val a = arrow(500f, 900f, 500f, 0f)
        val h = 1f / 240f
        w.resolve(a, false, h, ArrayList())
        assertEquals(500f + m.g * h, a.vx, 0.01f)
    }

    @Test fun `gravity well pulls toward its core with falloff`() {
        val m = mm(1000, 1800)
        val w = World(m)
        w.obs.add(RObs().apply { t = "well"; x = 500f; y = 900f; f = 3f; R = 200f })
        val a = arrow(500f, 800f, 0f, 0f)                   // d = 100 → k = 3g·(1−½)
        val h = 1f / 240f
        val evs = ArrayList<Ev>()
        w.resolve(a, false, h, evs)
        assertEquals(3f * m.g * 0.5f * h, a.vy, 0.01f)
        assertTrue(a.welled)
    }
}

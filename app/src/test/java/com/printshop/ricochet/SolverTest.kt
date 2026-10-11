package com.printshop.ricochet

import org.junit.Assert.*
import org.junit.Test

class SolverTest {
    private val m = Metrics(1080f, 1920f)

    private fun def(tx: Float, ty: Float, obs: List<RObs> = emptyList()): PDef {
        val d = PDef(); d.tx = tx; d.ty = ty; d.arrows = 3; d.par = 1
        d.obs.addAll(obs)
        return d
    }

    @Test fun `empty room is solvable`() {
        assertTrue(Solver.paths(def(.74f, .26f), m) > 0)
    }

    @Test fun `full-height wall makes the far side unreachable`() {
        val wall = RObs().apply { t = "plank"; x = .5f; y = .5f; w = .035f; h = 1f }
        assertEquals(0, Solver.paths(def(.8f, .25f, listOf(wall)), m))
    }

    @Test fun `repair ladder rescues an unsolvable puzzle`() {
        val wall = RObs().apply { t = "plank"; x = .5f; y = .5f; w = .035f; h = 1f }
        val fixed = Rng.seeded(42) { Solver.repair(def(.8f, .25f, listOf(wall)), m) }
        assertTrue(Solver.paths(fixed, m) > 0)
    }

    @Test fun `generated depths 1 to 8 all verify with legal coins`() {
        for (d in 1..8) {
            val c = Rng.seeded(1000 + d) { Solver.gen(d, m) }
            assertTrue("depth $d unsolvable", c.paths > 0)
            assertTrue("depth $d coin count", c.coins.size in 1..8)
            for (cn in c.coins) {
                assertTrue("depth $d coin x", cn.x in .14f..(.9f))
                assertTrue("depth $d coin y", cn.y in .12f..(.8f))
            }
        }
    }

    @Test fun `daily seed reproduces the identical puzzle`() {
        val a = Rng.seeded(20240115) { Solver.gen(7, m) }
        val b = Rng.seeded(20240115) { Solver.gen(7, m) }
        assertEquals(a.tx, b.tx, 1e-5f)
        assertEquals(a.ty, b.ty, 1e-5f)
        assertEquals(a.obs.size, b.obs.size)
        assertEquals(a.coins.size, b.coins.size)
        for (i in a.obs.indices) assertEquals(a.obs[i].t, b.obs[i].t)
    }

    @Test fun `tour puzzles verify on this screen - representative subset`() {
        for (i in intArrayOf(0, 4, 9, 14, 19))
            assertTrue("tour Nº${i + 1} failed", Solver.paths(Courses.ALL[i], m) > 0)
    }
}

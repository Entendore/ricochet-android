package com.printshop.ricochet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CodecTest {
    private fun eq(a: Float, b: Float) = abs(a - b) < 1e-4f
    private inline fun fails(block: () -> Unit): Boolean =
        try { block(); false } catch (e: Exception) { true }

    private fun fullDef(): PDef {
        val d = PDef(); d.name = "ROUND TRIP"; d.par = 3; d.arrows = 7
        d.tx = .8f; d.ty = .3f; d.tmx = .12f; d.tsp = 1.1f
        d.coins.add(Coin(.2f, .5f)); d.coins.add(Coin(.4f, .6f))
        d.obs.add(RObs().apply { t = "plank"; x = .3f; y = .5f; w = .2f; h = .035f; a = .6f })
        d.obs.add(RObs().apply { t = "crate"; x = .4f; y = .4f; w = .055f; h = .055f; a = -.2f })
        d.obs.add(RObs().apply { t = "bump"; x = .5f; y = .5f; r = .07f })
        d.obs.add(RObs().apply { t = "spin"; x = .45f; y = .35f; len = .22f; sp = -1.8f; ph = 1f })
        d.obs.add(RObs().apply { t = "move"; x = .5f; y = .6f; w = .3f; axis = "y"; range = .14f; sp = 1.2f; ph = .5f })
        d.obs.add(RObs().apply { t = "wind"; x = .3f; y = .2f; w = .16f; h = .3f; kx = .8f; ky = .2f })
        d.obs.add(RObs().apply { t = "well"; x = .55f; y = .7f; f = 2.8f; R = .3f })
        d.obs.add(RObs().apply { t = "portal"; ax = .25f; ay = .7f; bx = .8f; by = .3f; r = .08f })
        return d
    }

    @Test fun `share code round-trips every obstacle type`() {
        val d = fullDef()
        val b = decodeDef(encodeDef(d))
        assertEquals(d.name, b.name)
        assertEquals(d.par, b.par)
        assertEquals(d.arrows, b.arrows)
        assertTrue(eq(d.tx, b.tx)); assertTrue(eq(d.ty, b.ty))
        assertTrue(eq(d.tmx, b.tmx)); assertTrue(eq(d.tsp, b.tsp))
        assertEquals(d.coins.size, b.coins.size)
        assertTrue(eq(d.coins[0].x, b.coins[0].x))
        assertEquals(d.obs.size, b.obs.size)
        for (i in d.obs.indices) {
            val a = d.obs[i]; val c = b.obs[i]
            assertEquals("type $i", a.t, c.t)
            assertEquals("axis $i", a.axis, c.axis)
            assertTrue(eq(a.x, c.x)); assertTrue(eq(a.y, c.y)); assertTrue(eq(a.w, c.w))
            assertTrue(eq(a.h, c.h)); assertTrue(eq(a.a, c.a)); assertTrue(eq(a.r, c.r))
            assertTrue(eq(a.len, c.len)); assertTrue(eq(a.sp, c.sp)); assertTrue(eq(a.ph, c.ph))
            assertTrue(eq(a.range, c.range)); assertTrue(eq(a.kx, c.kx)); assertTrue(eq(a.ky, c.ky))
            assertTrue(eq(a.f, c.f)); assertTrue(eq(a.R, c.R))
            assertTrue(eq(a.ax, c.ax)); assertTrue(eq(a.ay, c.ay))
            assertTrue(eq(a.bx, c.bx)); assertTrue(eq(a.by, c.by))
        }
    }

    @Test fun `codes interchange with the web build schema`() {
        // a hand-built def in the web build's exact JSON shape must import
        val j = JSONObject()
            .put("name", "WEB NATIVE").put("par", 2).put("arrows", 3)
            .put("target", JSONObject().put("x", .7).put("y", .3).put("mx", 0).put("sp", 0))
            .put("coins", JSONArray().put(JSONObject().put("x", .4).put("y", .5)))
            .put("obs", JSONArray().put(
                JSONObject().put("t", "move").put("x", .5).put("y", .5)
                    .put("w", .3).put("h", .035).put("ax", "y")
                    .put("range", .12).put("sp", 1.2).put("ph", 0)))
        val d = decodeDef("R1" + java.util.Base64.getEncoder()
            .encodeToString(j.toString().toByteArray(Charsets.UTF_8)))
        assertEquals("WEB NATIVE", d.name)
        assertEquals("y", d.obs[0].axis)
        assertTrue(eq(.12f, d.obs[0].range))
    }

    @Test fun `garbage codes are rejected`() {
        assertTrue(fails { decodeDef("not a code") })
        assertTrue(fails { decodeDef("R1!!!!") })
        assertTrue(fails { decodeDef("") })
    }

    @Test fun `hostile imports are clamped and filtered`() {
        val coins = JSONArray()
        for (i in 0 until 12) coins.put(JSONObject().put("x", .5).put("y", .5))
        val j = JSONObject()
            .put("name", "x".repeat(40)).put("par", 99).put("arrows", 999)
            .put("target", JSONObject().put("x", 9).put("y", 9).put("mx", 5).put("sp", 99))
            .put("coins", coins)
            .put("obs", JSONArray()
                .put(JSONObject().put("t", "laser").put("x", .5))            // unknown → dropped
                .put(JSONObject().put("t", "bump").put("x", .5).put("y", .5).put("r", .9)))
        val d = decodeDef("R1" + java.util.Base64.getEncoder()
            .encodeToString(j.toString().toByteArray(Charsets.UTF_8)))
        assertEquals(9, d.arrows)                 // clamped 1..9
        assertEquals(5, d.par)                    // clamped 1..5
        assertEquals(16, d.name.length)           // truncated
        assertEquals(.95f, d.tx, 1e-4f)           // target clamped
        assertEquals(.88f, d.ty, 1e-4f)
        assertEquals(0f, d.tmx, 1e-4f)            // mx clamped to 0..0.25 → 5 → 0.25? no: 0.25
        assertEquals(.25f, d.tmx, 1e-4f)
        assertEquals(8, d.coins.size)             // capped at 8
        assertEquals(1, d.obs.size)               // laser dropped
        assertEquals("bump", d.obs[0].t)
        assertEquals(.12f, d.obs[0].r, 1e-4f)     // radius clamped
    }
}

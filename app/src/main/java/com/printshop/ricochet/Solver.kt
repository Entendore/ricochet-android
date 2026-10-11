package com.printshop.ricochet

import kotlin.math.*

/**
 * The shot solver. Simulates candidate launches through the EXACT ghost
 * physics (World.resolve with ghost=true) so a verified path is a path a
 * player can actually fly. Movers/spinners/moving targets are sampled at
 * several phase offsets; portal routes get seeded aim angles.
 *
 * Extracted from the view so it runs under plain JVM unit tests and the
 * game/editor/hint systems all share one implementation.
 */
object Solver {

    /** Analytic ballistic angles in CANVAS coordinates (y grows down). */
    private fun directAngles(m: Metrics, dxx0: Float, dyc: Float, v: Float): FloatArray {
        var dxx = dxx0
        val left = dxx < 0f
        dxx = abs(dxx)
        if (dxx < 6f) dxx = 6f
        val v2 = v * v
        val A = m.g * dxx * dxx / (2f * v2)
        val disc = dxx * dxx - 4f * A * (A - dyc)
        if (disc <= 0f) return FloatArray(0)
        val s = sqrt(disc)
        val PI = PI.toFloat()
        val t1 = atan2((-dxx + s), 2f * A)
        val t2 = atan2((-dxx - s), 2f * A)
        return if (left) floatArrayOf(PI - t1, PI - t2) else floatArrayOf(t1, t2)
    }

    /** Distinct verified shot solutions for this def on this screen, capped at 3. */
    fun paths(def: PDef, m: Metrics): Int {
        val w = buildLayout(def, m)
        val tg = w.tg
        val base = atan2(tg.y - m.bowY, tg.x - m.bowX)
        val pseeds = ArrayList<Float>()
        for (o in w.obs) if (o.t == "portal") {
            pseeds.add(atan2(o.y - m.bowY, o.x - m.bowX))
            pseeds.add(atan2(o.by - m.bowY, o.bx - m.bowX))
        }
        fun simShot(dx: Float, dy: Float, spd: Float, t0: Float): Boolean {
            val FR = 1f / 160f
            val hitR2 = (tg.r * 0.92f).pow(2)
            val a = Arrow()
            a.x = m.bowX + dx * 50f * m.K
            a.y = m.bowY + dy * 50f * m.K
            a.vx = dx * spd; a.vy = dy * spd
            for (i in 0 until 480) {
                a.pcd = max(a.pcd - FR, 0f)
                w.setObsTime(t0 + (i + 1) * FR)
                val sp = hypot(a.vx, a.vy)
                val n = ceil(sp * FR / 6f).toInt().coerceIn(1, 4)
                val h = FR / n
                for (k in 0 until n) {
                    a.vy += w.m.g * h; a.x += a.vx * h; a.y += a.vy * h
                    if (w.resolve(a, true, h, null)) return false
                    val ex = a.x - tg.x; val ey = a.y - tg.y
                    if (ex * ex + ey * ey < hitR2) return true
                }
            }
            return false
        }
        fun scan(powers: FloatArray, offs: FloatArray, jits: FloatArray,
                 ringN: Int, fanN: Int, fanStep: Float): Int {
            var hits = 0
            val PI = PI.toFloat(); val TAU = 2f * PI
            for (t0 in offs) {
                w.setObsTime(t0)
                for (pf in powers) {
                    val v = pf * m.vmax
                    val angles = ArrayList<Float>(ringN + 2 * fanN + 40)
                    for (i in 0 until ringN) angles.add(-PI + (i + .5f) * (TAU / ringN))
                    for (k in -fanN..fanN) angles.add(base + k * fanStep)
                    for (da in directAngles(m, tg.x - m.bowX, tg.y - m.bowY, v))
                        for (j in jits) angles.add(da + j)
                    for (ps in pseeds) for (j in jits) angles.add(ps + j)
                    for (a0 in angles)
                        if (simShot(cos(a0), sin(a0), v, t0)) {
                            hits++
                            if (hits >= 3) return hits
                        }
                }
            }
            return hits
        }
        var p = scan(floatArrayOf(.55f, .72f, .88f, 1f),
            floatArrayOf(0f, .55f, 1.1f, 1.65f),
            floatArrayOf(0f, .07f, -.07f, .16f, -.16f), 14, 4, .14f)
        if (p == 0) p = scan(floatArrayOf(.5f, .58f, .66f, .76f, .86f, 1f),
            floatArrayOf(.28f, .83f, 1.38f, 1.93f, 2.48f),
            floatArrayOf(0f, .04f, -.04f, .09f, -.09f, .15f, -.15f), 24, 7, .05f)
        return p
    }

    /** Verify, or auto-repair: nudge target toward a provably reachable
     *  spot → thin obstacles → guaranteed-reachable empty fallback. */
    fun ensure(def: PDef, m: Metrics): PDef {
        val n = paths(def, m)
        if (n > 0) { def.paths = n; return def }
        return repair(def, m)
    }

    fun repair(def0: PDef, m: Metrics): PDef {
        var d = def0.cpy()
        d.paths = 0
        val (sx, sy) = safeTargetSpot(m)
        for (i in 0 until 28) {
            if (paths(d, m) > 0) return d
            if (d.obs.isNotEmpty() && i % 2 == 1) d.obs.removeAt(ri(d.obs.size))
            d.tx = lerp(d.tx, sx, .3f)
            d.ty = lerp(d.ty, sy, .35f)
            if (d.tmx != 0f) { d.tmx *= .75f; if (d.tmx < .03f) d.tmx = 0f }
        }
        val fb = PDef()
        fb.name = d.name; fb.par = 1; fb.arrows = 3
        fb.tx = sx; fb.ty = sy; fb.paths = 1
        return fb
    }

    /** Generator + verify loop: thin → reroll → guaranteed fallback,
     *  then seed coins along the verified corridor. */
    fun gen(depth: Int, m: Metrics): PDef {
        var def = rollInfinite(depth)
        for (attempt in 0 until 24) {
            val v = paths(def, m)
            if (v > 0) {
                def.paths = v
                def.coins.addAll(coinCorridor(def, 3 + depth % 3))
                return def
            }
            if (def.obs.size > 1 && attempt < 10) def.obs.removeAt(ri(def.obs.size))
            else def = rollInfinite(depth)
        }
        val fb = PDef()
        fb.name = "ENDLESS"; fb.par = 2; fb.arrows = 4
        val (sx, sy) = safeTargetSpot(m)
        fb.tx = sx; fb.ty = sy; fb.paths = 1
        return fb
    }

    /**
     * The chalk line: first verified path at the CURRENT obstacle phase.
     * Runs against the live world; restores its phase before returning
     * (the game loop re-applies simTime every frame anyway, but we leave
     * things exactly as we found them).
     */
    fun hintPath(w: World, bx: Float, by: Float, t0: Float): List<FloatArray>? {
        val m = w.m; val tg = w.tg
        val base = atan2(tg.y - by, tg.x - bx)
        val dirs = ArrayList<Pair<Float, Float>>()
        val PI = PI.toFloat()
        for (pf in floatArrayOf(.55f, .72f, .88f, 1f)) {
            val v = pf * m.vmax
            for (da in directAngles(m, tg.x - bx, tg.y - by, v))
                for (j in floatArrayOf(0f, .05f, -.05f, .12f, -.12f)) dirs.add(da + j to v)
            for (k in -5..5) dirs.add(base + k * .12f to v)
            for (i in 0 until 12) dirs.add(-PI + (i + .5f) * (2f * PI / 12f) to v)
        }
        for (o in w.obs) if (o.t == "portal") {
            dirs.add(atan2(o.y - by, o.x - bx) to m.vmax)
            dirs.add(atan2(o.by - by, o.bx - bx) to m.vmax)
        }
        try {
            for ((ang, v) in dirs) {
                simPath(w, bx, by, cos(ang), sin(ang), v, t0)?.let { return it }
            }
            return null
        } finally {
            w.setObsTime(t0)
        }
    }

    private fun simPath(w: World, bx: Float, by: Float, dx: Float, dy: Float,
                        spd: Float, t0: Float): ArrayList<FloatArray>? {
        val m = w.m
        val FR = 1f / 160f
        val tg = w.tg
        val hitR2 = (tg.r * .92f).pow(2)
        val a = Arrow()
        a.x = bx + dx * 50f * m.K; a.y = by + dy * 50f * m.K
        a.vx = dx * spd; a.vy = dy * spd
        val pts = ArrayList<FloatArray>()
        pts.add(floatArrayOf(a.x, a.y))
        for (i in 0 until 480) {
            a.pcd = max(a.pcd - FR, 0f)
            w.setObsTime(t0 + (i + 1) * FR)
            val sp = hypot(a.vx, a.vy)
            val n = ceil(sp * FR / 6f).toInt().coerceIn(1, 4)
            val h = FR / n
            for (k in 0 until n) {
                a.vy += m.g * h; a.x += a.vx * h; a.y += a.vy * h
                if (w.resolve(a, true, h, null)) return null
                val ex = a.x - tg.x; val ey = a.y - tg.y
                if (ex * ex + ey * ey < hitR2) { pts.add(floatArrayOf(a.x, a.y)); return pts }
            }
            if (i % 4 == 0 && pts.size < 200) pts.add(floatArrayOf(a.x, a.y))
        }
        return null
    }
}

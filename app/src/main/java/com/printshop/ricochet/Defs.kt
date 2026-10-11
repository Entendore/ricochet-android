package com.printshop.ricochet

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

/* ---------- RNG indirection: the daily puzzle swaps in a seeded PRNG ---------- */
object Rng {
    var r: Random = Random.Default
    fun <T> seeded(seed: Int, block: () -> T): T {
        val old = r
        r = Random(seed)
        return try { block() } finally { r = old }
    }
}
fun rf(): Float = Rng.r.nextFloat()
fun rf(a: Float, b: Float): Float = rf() * (b - a) + a
fun ri(n: Int): Int = (rf() * n).toInt()
fun clamp(v: Float, a: Float, b: Float) = if (v < a) a else if (v > b) b else v
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/* ---------- data model ---------- */
class Coin(var x: Float, var y: Float)

class RObs {
    var t = ""
    var x = 0f; var y = 0f; var w = 0f; var h = 0f; var a = 0f
    var r = 0f                      // bumper radius / portal radius
    var len = 0f; var sp = 0f; var ph = 0f
    var axis = "x"; var range = 0f  // mover
    var kx = 0f; var ky = 0f        // wind
    var f = 0f; var R = 0f          // well
    var ax = 0f; var ay = 0f        // portal first end (normalized)
    var bx = 0f; var by = 0f        // portal second end (normalized)
    // runtime
    var dead = false; var cd = 0f; var pulse = 0f
    var bx0 = 0f; var by0 = 0f; var ang = 0f; var th = 0f
    fun cpy(): RObs { val o = RObs(); o.copyFrom(this); return o }
    fun copyFrom(o: RObs) {
        t = o.t; x = o.x; y = o.y; w = o.w; h = o.h; a = o.a; r = o.r
        len = o.len; sp = o.sp; ph = o.ph; axis = o.axis; range = o.range
        kx = o.kx; ky = o.ky; f = o.f; R = o.R
        ax = o.ax; ay = o.ay; bx = o.bx; by = o.by
        dead = false; cd = 0f; pulse = 0f
        bx0 = o.bx0; by0 = o.by0; ang = o.ang; th = o.th
    }
}

class PDef {
    var name = ""; var par = 2; var arrows = 3
    var tx = .5f; var ty = .4f; var tmx = 0f; var tsp = 0f
    val coins = ArrayList<Coin>()
    val obs = ArrayList<RObs>()
    var paths = 0
    fun cpy(): PDef {
        val d = PDef(); d.name = name; d.par = par; d.arrows = arrows
        d.tx = tx; d.ty = ty; d.tmx = tmx; d.tsp = tsp; d.paths = paths
        coins.forEach { d.coins.add(Coin(it.x, it.y)) }
        obs.forEach { d.obs.add(it.cpy()) }
        return d
    }
}

class Arrow {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var ang = 0f
    var bounces = 0; var age = 0f; var tt = 0f
    var slowed = false; var gone = false
    var chain = 0; var pierce = 0; var pierced = 0
    var ported = false; var welled = false; var pcd = 0f; var inW = false
    var simStop = false
}

class RTarget {
    var bx = 0f; var by = 0f; var x = 0f; var y = 0f
    var r = 40f; var mx = 0f; var sp = 0f; var pulse = 0f
}

/* ---------- physics events (live game turns these into fx/sfx;
   the solver runs the same code with ev = null, zero overhead) ---------- */
sealed class Ev {
    object Wind : Ev()
    object Well : Ev()
    data class Portal(val ax: Float, val ay: Float, val bx: Float, val by: Float) : Ev()
    data class Stick(val x: Float, val y: Float, val nx: Float, val ny: Float) : Ev()
    data class Bounce(val type: String, val x: Float, val y: Float, val imp: Float,
                      val nx: Float = 0f, val ny: Float = 0f) : Ev()
    data class Crate(val x: Float, val y: Float) : Ev()
    data class Slice(val x: Float, val y: Float) : Ev()
}

class Metrics(val W: Float, val H: Float) {
    val U = min(W, H)
    val K = U / 640f
    val g = 1.7f * U
    val vmax = 1.25f * hypot(W, H)   // diagonal-scaled: whole field inside reach
    val AR = 4.5f * max(K, 0.8f)
    val bowX = max(W * 0.1f, 80f * K)
    val bowY = H * 0.84f
}

class World(val m: Metrics) {
    val obs = ArrayList<RObs>()
    val tg = RTarget()

    fun setObsTime(t: Float) {
        for (o in obs) {
            if (o.t == "move") {
                val s = sin(t * o.sp + o.ph) * o.range
                if (o.axis == "x") o.x = o.bx0 + s else o.y = o.by0 + s
            } else if (o.t == "spin") o.ang = t * o.sp + o.ph
        }
        if (tg.sp != 0f) tg.x = tg.bx + sin(t * tg.sp) * tg.mx
    }

    private fun circleOBB(px: Float, py: Float, cr: Float, o: RObs): FloatArray? {
        val ca = cos(o.ang); val sa = sin(o.ang)
        val dx = px - o.x; val dy = py - o.y
        val lx = dx * ca + dy * sa; val ly = -dx * sa + dy * ca
        val qx = lx.coerceIn(-o.w / 2, o.w / 2); val qy = ly.coerceIn(-o.h / 2, o.h / 2)
        val ddx = lx - qx; val ddy = ly - qy; val d2 = ddx * ddx + ddy * ddy
        if (d2 > cr * cr) return null
        var nx: Float; var ny: Float; var pen: Float
        if (d2 > 1e-9f) { val d = sqrt(d2); nx = ddx / d; ny = ddy / d; pen = cr - d }
        else {
            val ox = o.w / 2 - abs(lx); val oy = o.h / 2 - abs(ly)
            if (ox < oy) { nx = if (lx < 0) -1f else 1f; ny = 0f; pen = cr + ox }
            else { nx = 0f; ny = if (ly < 0) -1f else 1f; pen = cr + oy }
        }
        return floatArrayOf(nx * ca - ny * sa, nx * sa + ny * ca, pen,
                            o.x + qx * ca - qy * sa, o.y + qx * sa + qy * ca)
    }

    private fun circleSeg(px: Float, py: Float, cr: Float,
                          axx: Float, ayy: Float, bxx: Float, byy: Float,
                          rad: Float): FloatArray? {
        val abx = bxx - axx; val aby = byy - ayy
        val L2 = abx * abx + aby * aby
        var t = if (L2 > 1e-9f) ((px - axx) * abx + (py - ayy) * aby) / L2 else 0f
        t = t.coerceIn(0f, 1f)
        val qx = axx + abx * t; val qy = ayy + aby * t
        val dx = px - qx; val dy = py - qy
        val d2 = dx * dx + dy * dy; val R = cr + rad
        if (d2 >= R * R) return null
        val d = sqrt(d2)
        var nx = 0f; var ny = -1f; var pen = R
        if (d > 1e-6f) { nx = dx / d; ny = dy / d; pen = R - d }
        return floatArrayOf(nx, ny, pen, qx, qy)
    }

    /** Returns true when the arrow must stop (stuck live / died in sim). */
    fun resolve(a: Arrow, ghost: Boolean, h: Float, ev: ArrayList<Ev>?): Boolean {
        val IN = 16f * max(m.K, 0.8f)
        // --- wind zones ---
        var inAny = false
        for (o in obs) {
            if (o.t != "wind") continue
            if (a.x > o.x && a.x < o.x + o.w && a.y > o.y && a.y < o.y + o.h) {
                inAny = true
                a.vx += o.kx * m.g * h; a.vy += o.ky * m.g * h
                if (!ghost && !a.inW) { a.inW = true; ev?.add(Ev.Wind) }
            }
        }
        if (!inAny) a.inW = false
        // --- gravity wells: (1−d/R) falloff, zero at the core —
        //     fast arrows slingshot naturally around them ---
        for (o in obs) {
            if (o.t != "well") continue
            val dx = o.x - a.x; val dy = o.y - a.y
            val d = hypot(dx, dy)
            if (d < o.R && d > 4f) {
                val k = o.f * m.g * (1f - d / o.R)
                a.vx += dx / d * k * h; a.vy += dy / d * k * h
                if (!ghost && k * h > m.g * h * 0.2f && !a.welled) {
                    a.welled = true; ev?.add(Ev.Well)
                }
            }
        }
        // --- portals: velocity preserved, 0.35s cooldown ---
        if (a.pcd <= 0f) {
            for (o in obs) {
                if (o.t != "portal") continue
                val dA = hypot(a.x - o.x, a.y - o.y)
                val dB = hypot(a.x - o.bx, a.y - o.by)
                var tx = Float.NaN; var ty = Float.NaN
                if (dA < o.r) { tx = o.bx; ty = o.by }
                else if (dB < o.r) { tx = o.x; ty = o.y }
                if (tx.isNaN()) continue
                val sp = hypot(a.vx, a.vy).coerceAtLeast(0.001f)
                val nx2 = a.vx / sp; val ny2 = a.vy / sp
                ev?.add(Ev.Portal(o.x, o.y, o.bx, o.by))
                a.x = tx + nx2 * (o.r + m.AR + 3f)
                a.y = ty + ny2 * (o.r + m.AR + 3f)
                a.pcd = 0.35f; a.vx *= 0.96f; a.vy *= 0.96f
                if (!ghost) a.ported = true
                break
            }
        }
        // --- walls (0.78 restitution; slow hits stick) ---
        var wev: Ev.Bounce? = null
        if (a.x < IN + m.AR) {
            a.x = IN + m.AR
            if (a.vx < 0f) { wev = Ev.Bounce("wall", IN, a.y, -a.vx, 1f, 0f); a.vx = -a.vx * 0.78f }
        } else if (a.x > m.W - IN - m.AR) {
            a.x = m.W - IN - m.AR
            if (a.vx > 0f) { wev = Ev.Bounce("wall", m.W - IN, a.y, a.vx, -1f, 0f); a.vx = -a.vx * 0.78f }
        }
        if (a.y < IN + m.AR) {
            a.y = IN + m.AR
            if (a.vy < 0f) {
                if (wev == null) wev = Ev.Bounce("wall", a.x, IN, -a.vy, 0f, 1f)
                a.vy = -a.vy * 0.78f
            }
        } else if (a.y > m.H - IN - m.AR) {
            a.y = m.H - IN - m.AR
            if (a.vy > 0f) {
                if (wev == null) wev = Ev.Bounce("wall", a.x, m.H - IN, a.vy, 0f, -1f)
                a.vy = -a.vy * 0.78f
            }
        }
        if (wev != null) {
            val post = hypot(a.vx, a.vy)
            if (ghost) {
                if (wev.imp < 150f * m.K) { a.simStop = true; return true }
                a.bounces++
            } else {
                if (wev.imp < 150f * m.K || post < 85f * m.K) {
                    ev?.add(Ev.Stick(wev.x, wev.y, wev.nx, wev.ny)); return true
                }
                a.bounces++; ev?.add(wev)
            }
        }
        // --- obstacles ---
        val pi = a.pierce
        for (o in obs) {
            var c: FloatArray? = null
            var type = ""
            var svx = 0f; var svy = 0f
            var qx = a.x; var qy = a.y
            when (o.t) {
                "plank", "move" -> {
                    c = circleOBB(a.x, a.y, m.AR, o); type = "plank"
                    if (c != null) { qx = c[3]; qy = c[4] }
                }
                "bump" -> {
                    val dx = a.x - o.x; val dy = a.y - o.y
                    val d = hypot(dx, dy).coerceAtLeast(0.0001f)
                    val rr = m.AR + o.r
                    if (d < rr) {
                        val nx2 = dx / d; val ny2 = dy / d
                        c = floatArrayOf(nx2, ny2, rr - d); type = "bump"
                        qx = o.x + nx2 * o.r; qy = o.y + ny2 * o.r
                    }
                }
                "spin" -> {
                    val ca = cos(o.ang); val sa = sin(o.ang)
                    c = circleSeg(a.x, a.y, m.AR,
                        o.x - ca * o.len, o.y - sa * o.len,
                        o.x + ca * o.len, o.y + sa * o.len, o.th * 0.5f)
                    type = "spin"
                    if (c != null) {
                        qx = c[3]; qy = c[4]
                        val rx = qx - o.x; val ry = qy - o.y
                        svx = -o.sp * ry; svy = o.sp * rx
                    }
                }
                "crate" -> {
                    // crates shatter on any contact: arrow punches through
                    // with a 10% speed penalty — ghost & real physics agree
                    if (!o.dead) {
                        c = circleOBB(a.x, a.y, m.AR, o)
                        if (c != null) {
                            o.dead = true; a.vx *= 0.9f; a.vy *= 0.9f
                            if (!ghost) ev?.add(Ev.Crate(c[3], c[4]))
                        }
                    }
                    continue
                }
                else -> continue
            }
            if (c == null) continue
            // piercing arrows phase through planks & blades
            if (pi > 0 && (type == "plank" || type == "spin")) {
                a.pierce = pi - 1
                if (!ghost) { a.pierced++; ev?.add(Ev.Slice(qx, qy)) }
                continue
            }
            a.x += c[0] * c[2]; a.y += c[1] * c[2]
            val rvx = a.vx - svx; val rvy = a.vy - svy
            val vn = rvx * c[0] + rvy * c[1]
            if (vn >= 0f) continue
            val imp = -vn
            if (type == "bump") {
                a.vx -= 2f * vn * c[0]; a.vy -= 2f * vn * c[1]
                val sp = hypot(a.vx, a.vy).coerceAtLeast(0.001f)
                val nsp = (sp * 1.1f + 240f * m.K).coerceIn(620f * m.K, m.vmax * 0.93f)
                a.vx = a.vx / sp * nsp; a.vy = a.vy / sp * nsp
                if (ghost) a.bounces++
                else if (o.cd <= 0f) {
                    o.cd = 0.07f; o.pulse = 1f; a.bounces++
                    ev?.add(Ev.Bounce("bump", qx, qy, imp, c[0], c[1]))
                }
            } else {
                val e = 0.76f
                a.vx = rvx - (1f + e) * vn * c[0] + svx * 0.6f
                a.vy = rvy - (1f + e) * vn * c[1] + svy * 0.6f
                val post = hypot(a.vx, a.vy)
                if (ghost) {
                    if (imp < 150f * m.K) { a.simStop = true; return true }
                    a.bounces++
                } else {
                    if (imp < 150f * m.K || post < 85f * m.K) {
                        ev?.add(Ev.Stick(qx, qy, c[0], c[1])); return true
                    }
                    a.bounces++; ev?.add(Ev.Bounce(type, qx, qy, imp, c[0], c[1]))
                }
            }
            val sp3 = hypot(a.vx, a.vy)
            if (sp3 > m.vmax * 1.05f) {
                a.vx *= m.vmax * 1.05f / sp3; a.vy *= m.vmax * 1.05f / sp3
            }
        }
        return false
    }
}

/* ---------- layout builder: normalized def → world (game + solver share it) ---------- */
fun buildLayout(def: PDef, m: Metrics): World {
    val w = World(m)
    for (o in def.obs) {
        val c = o.cpy()
        when (c.t) {
            "plank"  -> { c.x = o.x * m.W; c.y = o.y * m.H; c.w = o.w * m.U; c.h = o.h * m.U }
            "crate"  -> { c.x = o.x * m.W; c.y = o.y * m.H; c.w = o.w * m.U; c.h = o.h * m.U; c.dead = false }
            "move"   -> { c.bx0 = o.x * m.W; c.by0 = o.y * m.H; c.x = c.bx0; c.y = c.by0
                          c.w = o.w * m.U; c.h = o.h * m.U; c.range = o.range * m.U }
            "spin"   -> { c.x = o.x * m.W; c.y = o.y * m.H; c.len = o.len * m.U
                          c.th = 15f * m.K; c.ang = o.ph }
            "bump"   -> { c.x = o.x * m.W; c.y = o.y * m.H; c.r = o.r * m.U }
            "wind"   -> { c.x = o.x * m.W; c.y = o.y * m.H; c.w = o.w * m.W; c.h = o.h * m.H }
            "well"   -> { c.x = o.x * m.W; c.y = o.y * m.H; c.R = o.R * m.U }
            "portal" -> { c.x = o.ax * m.W; c.y = o.ay * m.H
                          c.bx = o.bx * m.W; c.by = o.by * m.H; c.r = o.r * m.U }
        }
        w.obs.add(c)
    }
    w.tg.bx = def.tx * m.W; w.tg.by = def.ty * m.H
    w.tg.x = w.tg.bx; w.tg.y = w.tg.by
    w.tg.r = clamp(0.115f * m.U, 36f * m.K, 62f * m.K)
    w.tg.mx = def.tmx * m.U; w.tg.sp = def.tsp
    return w
}

/* ---------- guaranteed-reachable target spot, from the reach envelope ---------- */
fun safeTargetSpot(m: Metrics): Pair<Float, Float> {
    val apex = m.vmax * m.vmax / (2f * m.g)
    val rise = min(apex * 0.45f, 0.62f * m.H)
    val x = 0.55f * m.W
    val d = abs(x - m.bowX)
    val maxRise = apex - m.g * d * d / (2f * m.vmax * m.vmax)
    val y = max(0.2f * m.H, m.bowY - min(rise, maxRise * 0.8f))
    return x / m.W to y / m.H
}

/* ---------- obstacle builders (file scope — Courses + generator share them) ---------- */
private fun o(t: String, vararg p: Pair<String, Float>): RObs = RObs().apply {
    this.t = t
    for ((k, v) in p) when (k) {
        "x" -> x = v; "y" -> y = v; "w" -> w = v; "h" -> h = v; "a" -> a = v; "r" -> r = v
        "len" -> len = v; "sp" -> sp = v; "ph" -> ph = v; "range" -> range = v
        "kx" -> kx = v; "ky" -> ky = v; "f" -> f = v; "R" -> R = v
        "ax" -> ax = v; "ay" -> ay = v; "bx" -> bx = v; "by" -> by = v
    }
}
private fun pl(x: Float, y: Float, w: Float, h: Float, a: Float = 0f) =
    o("plank", "x" to x, "y" to y, "w" to w, "h" to h, "a" to a)
private fun cr(x: Float, y: Float, w: Float, a: Float = 0f) =
    o("crate", "x" to x, "y" to y, "w" to w, "h" to w, "a" to a)
private fun bu(x: Float, y: Float, r: Float) =
    o("bump", "x" to x, "y" to y, "r" to r)
private fun spn(x: Float, y: Float, len: Float, sp: Float) =
    o("spin", "x" to x, "y" to y, "len" to len, "sp" to sp)
private fun mv(x: Float, y: Float, w: Float, ax: String, range: Float, sp: Float) =
    o("move", "x" to x, "y" to y, "w" to w, "range" to range, "sp" to sp).apply { this.axis = ax }
private fun wd(x: Float, y: Float, w: Float, h: Float, kx: Float, ky: Float) =
    o("wind", "x" to x, "y" to y, "w" to w, "h" to h, "kx" to kx, "ky" to ky)
private fun wl(x: Float, y: Float, f: Float, R: Float) =
    o("well", "x" to x, "y" to y, "f" to f, "R" to R)
private fun po(ax: Float, ay: Float, bx: Float, by: Float) =
    o("portal", "ax" to ax, "ay" to ay, "bx" to bx, "by" to by, "r" to .07f)
private fun cn(x: Float, y: Float) = Coin(x, y)
private fun pdef(name: String, par: Int, arrows: Int, tx: Float, ty: Float,
                 coins: List<Coin>, obs: List<RObs>, tmx: Float = 0f, tsp: Float = 0f): PDef {
    val d = PDef(); d.name = name; d.par = par; d.arrows = arrows
    d.tx = tx; d.ty = ty; d.tmx = tmx; d.tsp = tsp
    d.coins.addAll(coins); d.obs.addAll(obs); return d
}

/* ================= THE TOUR — 20 handcrafted puzzles =================
   ACT I (Nº01–10): one new idea per puzzle — arc, wall, bumpers,
   shelf, spinner, mover, mandatory bank, wind, portal, gauntlet.
   ACT II (Nº11–20): escalates each idea, then combines everything.  */
object Courses {
    val ALL: List<PDef> = listOf(
        /* ---- ACT I ---- */
        pdef("FIRST FLIGHT", 1, 3, .74f, .26f,
            listOf(cn(.4f, .6f), cn(.52f, .46f), cn(.63f, .36f)), emptyList()),
        pdef("OVER THE WALL", 1, 3, .78f, .24f,
            listOf(cn(.44f, .18f), cn(.56f, .24f), cn(.68f, .3f)),
            listOf(pl(.46f, .52f, .035f, .52f))),
        pdef("PINBALL", 2, 3, .82f, .2f,
            listOf(cn(.5f, .34f), cn(.58f, .42f), cn(.44f, .44f)),
            listOf(bu(.5f, .5f, .075f), bu(.66f, .32f, .055f), bu(.36f, .3f, .055f))),
        pdef("THE SHELF", 2, 3, .8f, .6f,
            listOf(cn(.4f, .5f), cn(.5f, .55f), cn(.58f, .6f)),
            listOf(pl(.72f, .33f, .44f, .035f), bu(.45f, .78f, .06f))),
        pdef("WINDMILL", 2, 3, .84f, .2f,
            listOf(cn(.55f, .2f), cn(.72f, .45f), cn(.4f, .5f)),
            listOf(spn(.55f, .45f, .28f, 1.5f), bu(.3f, .62f, .05f))),
        pdef("ELEVATOR", 2, 3, .82f, .22f,
            listOf(cn(.62f, .38f), cn(.62f, .6f)),
            listOf(mv(.5f, .5f, .34f, "y", .15f, 1.2f), pl(.2f, .4f, .035f, .34f),
                   bu(.66f, .52f, .06f))),
        pdef("BILLIARDS", 2, 4, .14f, .2f,
            listOf(cn(.4f, .35f), cn(.55f, .55f), cn(.3f, .45f)),
            listOf(bu(.45f, .5f, .07f), bu(.62f, .35f, .05f), bu(.6f, .68f, .05f),
                   pl(.82f, .55f, .035f, .4f))),
        pdef("CROSSDRAFT", 2, 3, .8f, .16f,
            listOf(cn(.46f, .5f), cn(.45f, .38f), cn(.47f, .27f)),
            listOf(wd(.38f, .2f, .17f, .56f, 0f, -.95f),
                   bu(.63f, .62f, .05f), bu(.66f, .34f, .045f))),
        pdef("WORMHOLE", 2, 3, .87f, .16f,
            listOf(cn(.6f, .42f), cn(.66f, .36f)),
            listOf(po(.3f, .68f, .72f, .32f), pl(.86f, .5f, .035f, .42f),
                   bu(.52f, .55f, .05f))),
        pdef("GAUNTLET", 3, 5, .84f, .2f,
            listOf(cn(.3f, .55f), cn(.68f, .3f), cn(.45f, .8f)),
            listOf(spn(.5f, .3f, .26f, 2.1f), mv(.5f, .62f, .3f, "x", .16f, 1.4f),
                   bu(.24f, .42f, .055f), bu(.68f, .78f, .055f), pl(.86f, .52f, .035f, .3f))),
        /* ---- ACT II ---- */
        pdef("MOVING TARGET", 2, 3, .5f, .22f,
            listOf(cn(.4f, .36f), cn(.6f, .36f)),
            listOf(bu(.3f, .5f, .06f), bu(.7f, .5f, .06f)), tmx = .18f, tsp = 1.1f),
        pdef("TWIN MILLS", 2, 4, .5f, .16f,
            listOf(cn(.5f, .35f), cn(.42f, .6f), cn(.6f, .6f)),
            listOf(spn(.32f, .42f, .24f, 1.8f), spn(.68f, .42f, .24f, -1.8f),
                   bu(.5f, .7f, .06f))),
        pdef("GOLD RUSH", 2, 3, .84f, .46f,
            listOf(cn(.3f, .62f), cn(.38f, .52f), cn(.47f, .45f),
                   cn(.56f, .42f), cn(.66f, .43f), cn(.75f, .48f)),
            listOf(pl(.62f, .66f, .4f, .035f), bu(.44f, .78f, .06f), bu(.7f, .3f, .05f))),
        pdef("THE WINDOW", 2, 3, .78f, .32f,
            listOf(cn(.42f, .35f), cn(.6f, .22f), cn(.72f, .3f)),
            listOf(pl(.42f, .14f, .035f, .28f), pl(.42f, .71f, .035f, .56f),
                   bu(.24f, .55f, .05f))),
        pdef("CROSSFIRE", 3, 4, .5f, .14f,
            listOf(cn(.28f, .45f), cn(.72f, .45f), cn(.5f, .35f)),
            listOf(wd(.16f, .28f, .2f, .5f, .85f, 0f), wd(.64f, .28f, .2f, .5f, -.85f, 0f),
                   wl(.5f, .8f, 2.2f, .28f), spn(.5f, .58f, .2f, 1.6f)),
            tmx = .14f, tsp = 1f),
        pdef("DOUBLE MOVE", 2, 3, .82f, .62f,
            listOf(cn(.55f, .68f), cn(.68f, .8f)),
            listOf(pl(.72f, .38f, .4f, .035f), mv(.45f, .5f, .3f, "y", .12f, 1.2f),
                   cr(.82f, .44f, .055f, .2f), bu(.35f, .8f, .06f))),
        pdef("UPDRAFT", 2, 3, .84f, .16f,
            listOf(cn(.51f, .5f), cn(.51f, .34f), cn(.51f, .2f)),
            listOf(pl(.64f, .64f, .035f, .66f), wd(.44f, .14f, .15f, .6f, 0f, -1.1f),
                   bu(.26f, .55f, .05f))),
        pdef("SWAP", 2, 3, .87f, .16f,
            listOf(cn(.28f, .5f), cn(.6f, .4f), cn(.8f, .42f)),
            listOf(pl(.68f, .55f, .035f, .62f), po(.28f, .72f, .84f, .3f),
                   spn(.46f, .45f, .2f, 1.8f))),
        pdef("BOOT CAMP", 3, 4, .8f, .18f,
            listOf(cn(.3f, .6f), cn(.5f, .35f), cn(.66f, .5f)),
            listOf(mv(.5f, .6f, .3f, "x", .15f, 1.3f), wd(.62f, .2f, .14f, .34f, -.8f, 0f),
                   bu(.36f, .44f, .055f), bu(.7f, .66f, .05f))),
        pdef("THE EXAM", 4, 6, .58f, .15f,
            listOf(cn(.22f, .3f), cn(.64f, .3f), cn(.78f, .2f)),
            listOf(pl(.3f, .58f, .035f, .46f), spn(.48f, .4f, .2f, 2.2f),
                   wl(.52f, .78f, 2.5f, .28f), cr(.58f, .33f, .06f, -.15f),
                   mv(.66f, .62f, .26f, "y", .12f, 1.4f), po(.14f, .42f, .8f, .34f),
                   bu(.22f, .78f, .05f), bu(.86f, .6f, .05f)),
            tmx = .12f, tsp = 1.15f)
    )
}

/* ================= INFINITE — procedural generator =================
   Difficulty is a pure function of depth. All randomness flows
   through rf()/ri(), so the daily puzzle's seeded PRNG reproduces
   layouts exactly. Placement uses rejection sampling against a
   zone list so nothing spawns on the target or the bow.           */
fun rollInfinite(d: Int): PDef {
    val def = PDef(); def.name = "ENDLESS"
    def.par = if (d >= 8) 3 else 2
    def.arrows = if (d < 5 || d % 5 == 0) 4 else 3
    def.tx = rf(.3f, .84f); def.ty = rf(.16f, .4f)
    if (d >= 3 && d % 3 == 0) {
        def.tmx = rf(.08f, .14f)
        def.tsp = rf(.8f, 1.1f + min(.5f, d * .04f))
    }
    val zones = ArrayList<Triple<Float, Float, Float>>()
    zones.add(Triple(def.tx, def.ty, .2f))      // keep clear of target
    zones.add(Triple(.1f, .84f, .14f))          // …and of the bow
    fun place(rad: Float): Pair<Float, Float>? {
        for (i in 0 until 50) {
            val x = rf(.2f, .8f); val y = rf(.24f, .72f)
            if (zones.all { hypot(x - it.first, y - it.second) > it.third + rad })
                return x to y
        }
        return null
    }
    fun take(x: Float, y: Float, r: Float) { zones.add(Triple(x, y, r)) }

    // wind zones — from depth 3, strength creeps up with depth
    if (d >= 3 && rf() < min(.5f, .3f + d * .02f)) {
        val horiz = rf() < .5f
        val ww = if (horiz) rf(.18f, .26f) else .14f
        val wh = if (horiz) .14f else rf(.2f, .3f)
        val wx = clamp(rf(.26f, .58f), .16f, .84f - ww)
        val wy = clamp(rf(.24f, .5f), .18f, .72f - wh)
        val k = rf(.55f, .9f) + min(.35f, d * .02f)
        def.obs.add(wd(wx, wy, ww, wh,
            if (horiz) (if (rf() < .5f) 1f else -1f) * k else 0f,
            if (horiz) 0f else (if (rf() < .5f) 1f else -1f) * k))
        take(wx + ww / 2f, wy + wh / 2f, .16f)
    }
    // portal pairs — from depth 6
    if (d >= 6 && rf() < min(.45f, .25f + d * .02f)) {
        val pa = rf(.18f, .36f) to rf(.55f, .75f)
        val pb = rf(.58f, .82f) to rf(.16f, .38f)
        if (listOf(pa, pb).all { p ->
                zones.all { hypot(p.first - it.first, p.second - it.second) > it.third + .1f }
            }) {
            def.obs.add(po(pa.first, pa.second, pb.first, pb.second))
            take(pa.first, pa.second, .11f); take(pb.first, pb.second, .11f)
        }
    }
    // gravity wells — from depth 7
    if (d >= 7 && rf() < .35f) place(.17f)?.let { p ->
        def.obs.add(wl(p.first, p.second, rf(2.2f, 3.2f), rf(.26f, .34f)))
        take(p.first, p.second, .3f)
    }
    // bumpers
    val nb = min(2 + (d % 3) + d / 6, 5)
    for (i in 0 until nb) place(.09f)?.let { p ->
        def.obs.add(bu(p.first, p.second, rf(.05f, .075f)))
        take(p.first, p.second, .12f)
    }
    // spinners — none before depth 2, twins possible from depth 9
    val nspin = if (d < 2) 0 else (if (d >= 9 && rf() < .5f) 2 else 1)
    for (i in 0 until nspin) place(.12f)?.let { p ->
        def.obs.add(spn(p.first, p.second, rf(.2f, .28f),
            (if (rf() < .5f) -1f else 1f) * (1.3f + min(1.7f, d * .11f))))
    }
    // planks
    val np = min(1 + d / 5, 3)
    val angles = floatArrayOf(0f, (Math.PI / 2.0).toFloat(), .6f, -.6f)
    for (i in 0 until np) place(.12f)?.let { p ->
        def.obs.add(pl(p.first, p.second, rf(.18f, .3f), .035f, angles[ri(4)]))
    }
    // crates — shootable walls that open routes, from depth 4
    if (d >= 4 && rf() < .45f) place(.1f)?.let { p ->
        def.obs.add(cr(p.first, p.second, rf(.05f, .07f), rf(-.3f, .3f)))
        take(p.first, p.second, .1f)
    }
    // movers — from depth 5
    if (d >= 5 && rf() < .55f) place(.12f)?.let { p ->
        def.obs.add(mv(p.first, p.second, .3f, if (rf() < .5f) "x" else "y",
            rf(.1f, .16f), rf(.9f, 1.5f)))
    }
    return def
}

/** False when a coin would overlap an obstacle / sit inside a well or wind zone. */
fun clearOfObs(def: PDef, px: Float, py: Float, margin: Float): Boolean {
    for (o in def.obs) {
        when (o.t) {
            "plank", "move" -> if (abs(px - o.x) < o.w / 2f + margin &&
                                   abs(py - o.y) < o.h / 2f + margin) return false
            "bump"  -> if (hypot(px - o.x, py - o.y) < o.r + margin) return false
            "spin"  -> if (hypot(px - o.x, py - o.y) < o.len + margin) return false
            "crate" -> if (hypot(px - o.x, py - o.y) < hypot(o.w, o.h) / 2f + margin) return false
            "well"  -> if (hypot(px - o.x, py - o.y) < o.R * .85f + margin) return true
            "portal"-> if (hypot(px - o.ax, py - o.ay) < o.r + margin ||
                           hypot(px - o.bx, py - o.by) < o.r + margin) return false
            "wind"  -> if (px > o.x - margin && px < o.x + o.w + margin &&
                           py > o.y - margin && py < o.y + o.h + margin) return true
        }
    }
    return true
}

/** Coins scattered along the bow→target corridor, clear of obstacles. */
fun coinCorridor(def: PDef, n: Int): List<Coin> {
    val pts = ArrayList<Coin>()
    var i = 0
    while (i < n * 4 && pts.size < n) {
        i++
        val f = .25f + .6f * rf()
        val px = lerp(.1f, def.tx, f) + (rf() - .5f) * .16f
        val py = lerp(.84f, def.ty, f) + (rf() - .5f) * .22f
        if (px < .14f || px > .9f || py < .12f || py > .8f) continue
        if (!clearOfObs(def, px, py, .055f)) continue
        if (pts.any { hypot(it.x - px, it.y - py) < .09f }) continue
        pts.add(Coin(px, py))
    }
    return pts
}

/* ================= JSON codec + R1 share codes =================
   Schema is IDENTICAL to the web build's encodeDef/JSON.stringify
   output, so puzzle codes move freely between the two:
     {"name","par","arrows",
      "target":{"x","y","mx","sp"},
      "coins":[{"x","y"}…],
      "obs":[{"t":…type-specific keys…}]}
   Movers carry "ax" as the string axis; portals carry numeric
   ax/ay/bx/by — matching the web def objects field for field.  */
fun defToJson(d: PDef): JSONObject {
    val j = JSONObject()
    j.put("name", d.name)
    j.put("par", d.par)
    j.put("arrows", d.arrows)
    j.put("target", JSONObject()
        .put("x", d.tx.toDouble()).put("y", d.ty.toDouble())
        .put("mx", d.tmx.toDouble()).put("sp", d.tsp.toDouble()))
    val cs = JSONArray()
    for (c in d.coins)
        cs.put(JSONObject().put("x", c.x.toDouble()).put("y", c.y.toDouble()))
    j.put("coins", cs)
    val os = JSONArray()
    for (o in d.obs) {
        val jo = JSONObject().put("t", o.t)
        when (o.t) {
            "plank", "crate" -> jo
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("w", o.w.toDouble()).put("h", o.h.toDouble())
                .put("a", o.a.toDouble())
            "bump" -> jo
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("r", o.r.toDouble())
            "spin" -> jo
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("len", o.len.toDouble()).put("sp", o.sp.toDouble())
                .put("ph", o.ph.toDouble())
            "move" -> jo
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("w", o.w.toDouble()).put("h", o.h.toDouble())
                .put("ax", o.axis).put("range", o.range.toDouble())
                .put("sp", o.sp.toDouble()).put("ph", o.ph.toDouble())
            "wind" -> jo
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("w", o.w.toDouble()).put("h", o.h.toDouble())
                .put("kx", o.kx.toDouble()).put("ky", o.ky.toDouble())
            "well" -> jo
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("f", o.f.toDouble()).put("R", o.R.toDouble())
            "portal" -> jo
                .put("ax", o.ax.toDouble()).put("ay", o.ay.toDouble())
                .put("bx", o.bx.toDouble()).put("by", o.by.toDouble())
                .put("r", o.r.toDouble())
        }
        os.put(jo)
    }
    j.put("obs", os)
    return j
}

fun defFromJson(j: JSONObject): PDef {
    val d = PDef()
    d.name = j.optString("name", "IMPORT").take(16)
    d.par = j.optInt("par", 2).coerceIn(1, 5)
    d.arrows = j.optInt("arrows", 3).coerceIn(1, 9)
    val t = j.optJSONObject("target") ?: JSONObject()
    d.tx = clamp(t.optDouble("x", .5).toFloat(), .05f, .95f)
    d.ty = clamp(t.optDouble("y", .4).toFloat(), .1f, .88f)
    d.tmx = clamp(t.optDouble("mx", 0.0).toFloat(), 0f, .25f)
    d.tsp = clamp(t.optDouble("sp", 0.0).toFloat(), 0f, 2.5f)
    val cs = j.optJSONArray("coins") ?: JSONArray()
    for (i in 0 until min(cs.length(), 8)) {
        val c = cs.optJSONObject(i) ?: continue
        d.coins.add(Coin(
            clamp(c.optDouble("x", .5).toFloat(), .02f, .98f),
            clamp(c.optDouble("y", .5).toFloat(), .02f, .98f)))
    }
    val ok = setOf("plank", "crate", "bump", "spin", "move", "wind", "well", "portal")
    val os = j.optJSONArray("obs") ?: JSONArray()
    fun g(o: JSONObject, k: String, dv: Float) = o.optDouble(k, dv.toDouble()).toFloat()
    for (i in 0 until min(os.length(), 24)) {
        val jo = os.optJSONObject(i) ?: continue
        val tt = jo.optString("t", "")
        if (tt !in ok) continue
        val o = RObs(); o.t = tt
        when (tt) {
            "plank", "crate" -> {
                o.x = g(jo, "x", .5f); o.y = g(jo, "y", .5f)
                o.w = clamp(g(jo, "w", .2f), .02f, .5f)
                o.h = clamp(g(jo, "h", .03f), .008f, .1f)
                o.a = g(jo, "a", 0f)
            }
            "bump" -> {
                o.x = g(jo, "x", .5f); o.y = g(jo, "y", .5f)
                o.r = clamp(g(jo, "r", .06f), .02f, .12f)
            }
            "spin" -> {
                o.x = g(jo, "x", .5f); o.y = g(jo, "y", .5f)
                o.len = clamp(g(jo, "len", .2f), .08f, .4f)
                o.sp = clamp(g(jo, "sp", 1.5f), -4f, 4f)
                o.ph = g(jo, "ph", 0f)
            }
            "move" -> {
                o.x = g(jo, "x", .5f); o.y = g(jo, "y", .5f)
                o.w = clamp(g(jo, "w", .28f), .1f, .5f)
                o.h = .035f
                o.axis = if (jo.optString("ax", "x") == "y") "y" else "x"
                o.range = clamp(g(jo, "range", .12f), .02f, .3f)
                o.sp = clamp(g(jo, "sp", 1.2f), .3f, 3f)
                o.ph = g(jo, "ph", 0f)
            }
            "wind" -> {
                o.x = g(jo, "x", .4f); o.y = g(jo, "y", .3f)
                o.w = clamp(g(jo, "w", .16f), .04f, .5f)
                o.h = clamp(g(jo, "h", .2f), .04f, .7f)
                o.kx = clamp(g(jo, "kx", 0f), -1.6f, 1.6f)
                o.ky = clamp(g(jo, "ky", -.9f), -1.6f, 1.6f)
            }
            "well" -> {
                o.x = g(jo, "x", .5f); o.y = g(jo, "y", .5f)
                o.f = clamp(g(jo, "f", 2.6f), 1f, 4.5f)
                o.R = clamp(g(jo, "R", .26f), .1f, .45f)
            }
            "portal" -> {
                o.ax = clamp(g(jo, "ax", .3f), .02f, .98f)
                o.ay = clamp(g(jo, "ay", .6f), .02f, .98f)
                o.bx = clamp(g(jo, "bx", .7f), .02f, .98f)
                o.by = clamp(g(jo, "by", .3f), .02f, .98f)
                o.r = clamp(g(jo, "r", .07f), .04f, .12f)
            }
        }
        d.obs.add(o)
    }
    return d
}

fun encodeDef(d: PDef): String =
    "R1" + java.util.Base64.getEncoder()
        .encodeToString(defToJson(d).toString().toByteArray(Charsets.UTF_8))

fun decodeDef(s0: String): PDef {
    val s = s0.trim()
    require(s.startsWith("R1")) { "bad header" }
    val bytes = java.util.Base64.getDecoder().decode(s.substring(2))
    return defFromJson(JSONObject(String(bytes, Charsets.UTF_8)))
}

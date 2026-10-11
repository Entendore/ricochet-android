package com.printshop.ricochet

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Choreographer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class GameView(ctx: Context) : View(ctx) {

    /* ================= palette & type ================= */
    private val PAPER = 0xFFF2EDE3.toInt()
    private val INK   = 0xFF191612.toInt()
    private val ACC   = 0xFFE2482B.toInt()
    private val BLUE  = 0xFF2B3FBF.toInt()
    private val GOLD  = 0xFFD99E13.toInt()
    private val GOLDD = 0xFFA6740A.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tfMono = Typeface.MONOSPACE
    private val tfBig  = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)

    private var M = Metrics(1f, 1f)
    private fun SC() = max(M.K, 0.8f)
    private fun pad2(n: Int) = n.toString().padStart(2, '0')

    /* ================= persistence ================= */
    private val prefs = ctx.getSharedPreferences("ric", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private fun Ps(k: String, v: String) = prefs.edit().putString(k, v).apply()
    private fun Pi(k: String, v: Int)    = prefs.edit().putInt(k, v).apply()
    private fun Pf(k: String, v: Float)  = prefs.edit().putFloat(k, v).apply()

    /* ================= core state ================= */
    private var mode = "menu"            // menu | play | clear | fail | editor
    private var gameMode = "tour"        // tour | inf | daily | custom
    private var paused = false
    private var courseNo = 1
    private var courseDef: PDef = Courses.ALL[0]
    private var world = World(Metrics(1f, 1f))
    private var par = 1
    private var arrowsLeft = 0
    private var shotsUsed = 0
    private var courseName = ""
    private var bestChain = 0

    class CoinR(var x: Float, var y: Float, var r: Float, var ph: Float, var got: Boolean)
    private val coins = ArrayList<CoinR>()

    class Splat(var x: Float, var y: Float, val pts: List<FloatArray>, val acc: Boolean)
    private val splats = ArrayList<Splat>()

    class StuckA(var x: Float, var y: Float, var a: Float, var t: Float)
    private val stuck = ArrayList<StuckA>()

    class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var gy: Float,
                var age: Float, var life: Float, var kind: Int, var rot: Float,
                var vr: Float, var col: Int, var s: Float)
    private val sparks = ArrayList<Spark>()

    class Ring(var x: Float, var y: Float, var r0: Float, var r1: Float,
               var age: Float, var life: Float, var col: Int, var lw: Float)
    private val rings = ArrayList<Ring>()

    class FloatT(var x: Float, var y: Float, var txt: String, var col: Int,
                 var size: Float, var age: Float,
                 var life: Float = .95f, var vy: Float = -42f * max(1f, 1f))
    private val floats = ArrayList<FloatT>()

    class Trail(var x: Float, var y: Float, var age: Float, var life: Float, var col: Int)
    private val trailDots = ArrayList<Trail>()

    private val ghostPath = ArrayList<FloatArray>()
    private var ghostNo = -1
    private var ghostTick = 0
    private var hintPts = ArrayList<FloatArray>()
    private var hintT = 0f

    private var score = 0
    private var best = 0
    private var savedLevel = 1
    private var tourStars = IntArray(20)
    private var infBest = 0
    private var calm = false
    private val ach = LinkedHashSet<String>()

    private var time = 0f
    private var simTime = 0f
    private var shake = 0f
    private var flash = 0f
    private var punch = 0f
    private var timeScale = 1f
    private var slowmoT = 0f
    private var zoom = 1f
    private var fx = 0f
    private var fy = 0f
    private var clearT = 0f
    private var winShown = false
    private var winShownAt = 0f
    private var loseShown = false
    private var failT = 0f
    private var newBest = false
    private var menuAutoT = 1.2f
    private var charge = 0f
    private val CHARGE_MAX = 4
    private var tagT = 0f
    private var tagHead = ""
    private var tagSub = ""
    private var resetArm = false
    private var resetArmT = 0f

    class ClearData(var shots: Int, var bank: Int, var chain: Int, var pts: Int,
                    var spared: Int, var bonus: Int, var stars: Int,
                    var db: Int = 0, var dailyBest: Boolean = false)
    private var clearData: ClearData? = null

    class Bow(var x: Float = 0f, var y: Float = 0f, var ang: Float = -0.7f,
              var pull: Float = 0f, var vib: Float = 0f)
    private val bow = Bow()
    class Aim(var on: Boolean = false, var sx: Float = 0f, var sy: Float = 0f,
              var dx: Float = .7f, var dy: Float = -.7f, var p: Float = 0f)
    private val aim = Aim()
    private var pid = -1
    private val arrows = ArrayList<Arrow>()

    /* ================= studio log & stamps ================= */
    class Stats(var shots: Int = 0, var hits: Int = 0, var banks: Int = 0,
                var coins: Int = 0, var crates: Int = 0, var portals: Int = 0,
                var pierces: Int = 0, var hints: Int = 0, var dailys: Int = 0)
    private var stats = Stats()

    private val ACH = listOf(
        arrayOf("bank",    "FIRST BANK",    "Finish a shot off a bounce"),
        arrayOf("bank3",   "TRIPLE BANK",   "Clear with 3+ banks"),
        arrayOf("chain3",  "FULL STRING",   "Chain 3 coins in one flight"),
        arrayOf("arm",     "ARMORER",       "Charge the piercing arrow"),
        arrayOf("breach",  "BREACH",        "Pierce through to the bullseye"),
        arrayOf("demo",    "DEMOLITION",    "Shatter a crate"),
        arrayOf("worm",    "WORMHOLED",     "Score through a portal"),
        arrayOf("sling",   "SLINGSHOT",     "Curve a winner through a well"),
        arrayOf("grad",    "GRADUATE",      "Complete the 20-puzzle tour"),
        arrayOf("depth10", "SPELUNKER",     "Reach depth 10 in INFINITE"),
        arrayOf("sharp",   "SHARPSHOOTER",  "Perfect 5 puzzles (3★)"),
        arrayOf("daily",   "REGULAR",       "Clear a puzzle of the day"),
        arrayOf("maker",   "GAME WRIGHT",   "Playtest-clear a workshop build"))

    class ToastQ(val n: String, val d: String)
    private val toastQ = ArrayDeque<ToastQ>()
    private var toastOn = false
    private var toastT = 0f
    private var toastName = ""
    private var toastDesc = ""

    /* ================= immediate-mode UI registry =================
       Buttons register themselves during onDraw (Part 6) into this
       list; touch hit-tests against the freshest frame.            */
    class UiB(val r: RectF, val act: () -> Unit)
    private val ui = ArrayList<UiB>()
    private var uiPress: UiB? = null

    /* overlay flags (drawn & wired in Part 6) */
    private var showMap = false
    private var showStats = false
    private var showSlots = false
    private var showShare = false
    private var shareCode = ""

    /* ================= workshop editor state ================= */
    val ED_TOOLS = listOf("sel", "tgt", "coin", "plank", "crate", "bump",
        "spin", "move", "wind", "well", "port", "erase")
    val ED_LBL = mapOf("sel" to "SELECT", "tgt" to "TARGET", "coin" to "COIN",
        "plank" to "PLANK", "crate" to "CRATE", "bump" to "BUMPER",
        "spin" to "SPINNER", "move" to "MOVER", "wind" to "WIND",
        "well" to "WELL", "port" to "PORTAL", "erase" to "ERASE")

    class EdState {
        var def: PDef? = null
        var tool = "sel"
        var selK = ""          // "" | target | coin | obs
        var selI = -1
        var dragging = false
        var dragEnd = 'a'      // portal end being dragged
        var dragOx = 0f; var dragOy = 0f
        var pendX = 0f; var pendY = 0f
        var pending = false    // first portal end placed, awaiting second
        var verify = "—"
        var verifyOk = true
        val undo = ArrayDeque<PDef>()
    }
    private val ed = EdState()
    private var edVerifyJob: Runnable? = null
    private val slots = ArrayList<Pair<String, PDef>?>(8)

    /* ================= grain (built on size change) ================= */
    private var grainPaint: Paint? = null

    /* ================= init ================= */
    init {
        best = prefs.getInt("ric_best", 0)
        savedLevel = prefs.getInt("ric_level", 1).coerceIn(1, 20)
        infBest = prefs.getInt("ric_infbest", 0)
        calm = prefs.getString("ric_calm", "0") == "1"
        Synth.muted = prefs.getString("ric_muted", "0") == "1"
        prefs.getString("ric_tour", null)?.let { s ->
            val a = s.split(",").mapNotNull { it.toIntOrNull() }
            for (i in 0 until 20) tourStars[i] = a.getOrElse(i) { 0 }
        }
        prefs.getString("ric_ach", null)?.let {
            ach.addAll(it.split(",").filter { t -> t.isNotBlank() })
        }
        prefs.getString("ric_stats", null)?.let { s ->
            runCatching {
                val j = JSONObject(s)
                stats = Stats(j.optInt("shots"), j.optInt("hits"), j.optInt("banks"),
                    j.optInt("coins"), j.optInt("crates"), j.optInt("portals"),
                    j.optInt("pierces"), j.optInt("hints"), j.optInt("dailys"))
            }
        }
        for (i in 0 until 8) slots.add(null)
        prefs.getString("ric_slots", null)?.let { s ->
            runCatching {
                val arr = JSONArray(s)
                for (i in 0 until min(arr.length(), 8)) {
                    val oj = arr.optJSONObject(i) ?: continue
                    slots[i] = oj.optString("n") to defFromJson(oj.getJSONObject("d"))
                }
            }
        }
        menuInit()
        // boot audit: verify the whole tour on this screen, off the UI thread
        handler.postDelayed({
            Thread {
                var fails = 0
                for (c in Courses.ALL) {
                    val n = if (M.W > 1f) Solver.paths(c, M) else 0
                    if (n > 0) c.paths = n else fails++
                }
                android.util.Log.i("RICOCHET",
                    if (fails == 0) "solver · all 20 tour puzzles verified on this screen"
                    else "solver · $fails/20 tour puzzles would be auto-repaired on this screen")
            }.start()
        }, 1200)
    }

    /* ================= lifecycle ================= */
    fun onBackground() {
        if (mode == "play" && !paused) { paused = true; Sfx.ui() }
    }

    fun onBack() {
        when {
            showShare -> showShare = false
            showSlots -> showSlots = false
            showStats -> showStats = false
            showMap   -> showMap = false
            mode == "editor" -> when {
                ed.pending            -> { ed.pending = false; Sfx.tick2() }
                ed.selK.isNotEmpty()  -> { ed.selK = ""; ed.selI = -1; Sfx.tick2() }
                else -> goMenu()
            }
            mode == "play" -> togglePause()
            paused -> togglePause()
            mode == "clear" || mode == "fail" -> goMenu()
            else -> (context as? Activity)?.finish()
        }
    }

    /* ================= loop ================= */
    private var lastFrame = 0L
    private var running = false
    private val choreo = Choreographer.getInstance()
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            val dt = if (lastFrame == 0L) .016f
                     else ((now - lastFrame) / 1e9f).coerceAtMost(.05f)
            lastFrame = now
            update(dt)
            invalidate()
            if (running) choreo.postFrameCallback(this)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        lastFrame = 0L
        choreo.postFrameCallback(frameCb)
    }
    override fun onDetachedFromWindow() {
        running = false
        choreo.removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        M = Metrics(w.toFloat(), h.toFloat())
        bow.x = M.bowX; bow.y = M.bowY
        splats.clear()
        // paper grain tile
        runCatching {
            val bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ALPHA_8)
            val px = IntArray(96 * 96)
            val r = java.util.Random()
            for (i in px.indices) px[i] = r.nextInt(30)
            bm.setPixels(px, 0, 96, 0, 0, 96, 96)
            grainPaint = Paint().apply {
                shader = BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                color = INK
            }
        }
        if (mode == "editor" && ed.def != null) edSync() else layoutCourse()
        // resize re-verification: a new viewport can change what's reachable
        handler.removeCallbacks(resizeVerify)
        handler.postDelayed(resizeVerify, 400)
    }
    private val resizeVerify = Runnable {
        if (mode == "menu" || mode == "editor" || M.W <= 1f) return@Runnable
        Thread {
            val ok = Solver.paths(courseDef, M) > 0
            if (!ok) {
                val fixed = Solver.repair(courseDef, M)
                handler.post {
                    if (mode != "menu" && mode != "editor") {
                        courseDef = fixed; layoutCourse()
                    }
                }
            }
        }.start()
    }

    /* ================= daily ================= */
    private fun dailySeed(): Int {
        val d = java.time.LocalDate.now()
        return d.year * 10000 + d.monthValue * 100 + d.dayOfMonth
    }
    private fun dailyLabel(): String {
        val mo = arrayOf("JAN","FEB","MAR","APR","MAY","JUN",
                         "JUL","AUG","SEP","OCT","NOV","DEC")
        val d = java.time.LocalDate.now()
        return "${mo[d.monthValue - 1]} ${pad2(d.dayOfMonth)}"
    }

    /* ================= course setup ================= */
    private fun layoutCourse() {
        world = buildLayout(courseDef, M)
        bow.x = M.bowX; bow.y = M.bowY
        coins.clear()
        for (c in courseDef.coins)
            coins.add(CoinR(c.x * M.W, c.y * M.H,
                clamp(.02f * M.U, 10f * M.K, 15f * M.K), rf(0f, 6.2832f), false))
    }

    private fun setCourse(no: Int) {
        courseNo = no
        paused = false
        hintPts.clear(); hintT = 0f
        val raw = when (gameMode) {
            "tour"   -> Courses.ALL[no - 1]
            "daily"  -> Rng.seeded(dailySeed()) { Solver.gen(7, M) }
            "custom" -> ed.def!!.cpy()
            else     -> Solver.gen(no, M)          // gen verifies before returning
        }
        courseDef = if (gameMode == "custom") raw else Solver.ensure(raw, M)
        layoutCourse()
        arrowsLeft = courseDef.arrows
        par = courseDef.par
        courseName = if (gameMode == "tour") courseDef.name else "ENDLESS"
        shotsUsed = 0; bestChain = 0
        arrows.clear(); stuck.clear(); trailDots.clear(); splats.clear()
        aim.on = false; pid = -1
        if (courseNo != ghostNo) { ghostPath.clear(); ghostNo = courseNo }
        ghostTick = 0
        showTag()
    }

    private fun showTag() {
        val feats = ArrayList<String>()
        var hasP = false; var hasW = false; var hasC = false; var hasG = false
        for (o in courseDef.obs) {
            if (o.t == "portal") hasP = true
            if (o.t == "wind")   hasW = true
            if (o.t == "crate")  hasC = true
            if (o.t == "well")   hasG = true
        }
        if (hasP) feats.add("PORTALS")
        if (hasW) feats.add("WIND")
        if (hasG) feats.add("WELLS")
        if (hasC) feats.add("CRATES")
        if (courseDef.coins.isNotEmpty()) feats.add("COINS ×${courseDef.coins.size}")
        tagHead = when (gameMode) {
            "tour"   -> courseName.uppercase()
            "daily"  -> "PUZZLE OF THE DAY — ${dailyLabel()}"
            "custom" -> "WORKSHOP PLAYTEST"
            else     -> "INFINITE — DEPTH ${pad2(courseNo)}"
        }
        tagSub = "PAR $par · $arrowsLeft ARROWS" +
            (if (feats.isEmpty()) "" else " · ${feats.joinToString(" · ")}")
        tagT = 2f
    }

    /* ================= fx helpers ================= */
    private fun mkSpark(x: Float, y: Float, n: Int, col: Int, spd: Float) {
        for (i in 0 until n) {
            val a = rf(0f, 6.2832f); val s = rf(spd * .4f, spd)
            sparks.add(Spark(x, y, cos(a) * s, sin(a) * s - 60f * SC(), 600f * SC(),
                0f, rf(.3f, .6f), ri(3), rf(0f, 6.28f), rf(-8f, 8f),
                col, rf(2f, 4f) * max(M.K, .8f)))
        }
    }
    private fun puff(x: Float, y: Float) = mkSpark(x, y, 6, INK, 120f)
    private fun confetti(x: Float, y: Float) {
        val cols = intArrayOf(INK, ACC, BLUE, GOLD)
        for (i in 0 until 30) {
            val a = rf(0f, 6.2832f); val s = rf(140f, 460f) * max(M.K, .8f)
            sparks.add(Spark(x, y, cos(a) * s, sin(a) * s - 120f, 520f * max(M.K, .8f),
                0f, rf(.5f, 1f), ri(3), rf(0f, 6.28f), rf(-10f, 10f),
                cols[ri(4)], rf(2.5f, 5f) * max(M.K, .8f)))
        }
    }
    private fun addSplat(x: Float, y: Float, acc: Boolean) {
        val pts = ArrayList<FloatArray>()
        val n = 3 + ri(3)
        for (i in 0 until n) pts.add(floatArrayOf(rf(-9f, 9f), rf(-9f, 9f), rf(2.5f, 6.5f)))
        splats.add(Splat(x, y, pts, acc))
        if (splats.size > 70) splats.removeAt(0)
    }
    private fun edFloat(txt: String) {
        floats.add(FloatT(M.W / 2f, M.H * .32f, txt, ACC, 12f * SC()))
    }
    private fun vib(ms: Long) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31)
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager).defaultVibrator
            else
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(ms, 90))
        } catch (_: Exception) {}
    }

    /* ================= event dispatch =================
       World.resolve emits physics events; live mode turns them into
       sound, particles, score, stats and stamps. The solver passes
       ev = null and gets identical physics with zero fx overhead.  */
    private val evs = ArrayList<Ev>()
    private fun handleEv(e: Ev, a: Arrow) {
        when (e) {
            is Ev.Stick -> {
                stuck.add(StuckA(e.x - e.nx * 2f, e.y - e.ny * 2f,
                    atan2(-e.ny, -e.nx), 0f))
                if (stuck.size > 16) stuck.removeAt(0)
                addSplat(e.x, e.y, false)
                a.gone = true
                Sfx.thunk(); mkSpark(e.x, e.y, 3, INK, 60f)
            }
            is Ev.Bounce -> {
                val s = (e.imp / (M.vmax * .45f)).coerceIn(.15f, 1f)
                mkSpark(e.x, e.y, 3 + (4 * s).toInt(),
                    if (e.type == "bump") ACC else INK, 90f + 260f * s)
                rings.add(Ring(e.x, e.y, 3f, 10f + 30f * s, 0f, .32f,
                    if (e.type == "bump") ACC else INK, 2f))
                shake = max(shake, min(4f, e.imp / 750f))
                stats.banks++; saveStats()
                when (e.type) {
                    "bump"  -> { score += 25
                                 floats.add(FloatT(e.x, e.y - 16f * SC(), "+25", ACC, 12f))
                                 Sfx.ding() }
                    "plank" -> { score += 10
                                 floats.add(FloatT(e.x, e.y - 14f * SC(), "+10", INK, 10f))
                                 Sfx.wood(s) }
                    "spin"  -> { score += 10; Sfx.clank() }
                    else    -> Sfx.wall(s)
                }
                if (a.bounces == 3)
                    floats.add(FloatT(a.x, a.y - 30f, "RICOCHET ×3", ACC, 12f))
            }
            is Ev.Crate -> {
                mkSpark(e.x, e.y, 10, INK, 220f)
                mkSpark(e.x, e.y, 6, ACC, 150f)
                rings.add(Ring(e.x, e.y, 4f, 36f, 0f, .4f, INK, 2.5f))
                score += 40; stats.crates++; saveStats()
                floats.add(FloatT(e.x, e.y - 16f, "+40 SMASH", INK, 12f))
                shake = max(shake, 3.5f)
                Sfx.crate(); award("demo")
            }
            is Ev.Portal -> {
                for (pt in listOf(e.ax to e.ay, e.bx to e.by))
                    for (i in 0 until 8) {
                        val a2 = rf(0f, 6.2832f); val s = rf(60f, 220f)
                        sparks.add(Spark(pt.first, pt.second, cos(a2) * s, sin(a2) * s,
                            0f, 0f, rf(.25f, .5f), 2, 0f, 0f, ACC,
                            rf(1.8f, 3.4f) * max(M.K, .8f)))
                    }
                rings.add(Ring(e.ax, e.ay, 4f, 34f, 0f, .35f, ACC, 2f))
                rings.add(Ring(e.bx, e.by, 4f, 34f, 0f, .35f, ACC, 2f))
                Sfx.portal(); stats.portals++; saveStats()
                punch = max(punch, .35f)
            }
            is Ev.Slice -> {
                addSplat(e.x, e.y, true)
                mkSpark(e.x, e.y, 4, ACC, 120f)
                score += 15; stats.pierces++; saveStats()
                floats.add(FloatT(e.x, e.y - 14f, "+15", ACC, 10f))
                Sfx.slice()
            }
            Ev.Wind -> Sfx.wind()
            Ev.Well -> Sfx.well()
        }
    }

    /* ================= persistence of stats / ach / tour ================= */
    private fun saveStats() {
        Ps("ric_stats", JSONObject()
            .put("shots", stats.shots).put("hits", stats.hits)
            .put("banks", stats.banks).put("coins", stats.coins)
            .put("crates", stats.crates).put("portals", stats.portals)
            .put("pierces", stats.pierces).put("hints", stats.hints)
            .put("dailys", stats.dailys).toString())
    }
    private fun saveAch()  = Ps("ric_ach", ach.joinToString(","))
    private fun saveTour() = Ps("ric_tour", tourStars.joinToString(","))
    private fun tourStarSum() = tourStars.sum()

    private fun award(id: String) {
        if (id in ach) return
        ach.add(id); saveAch()
        val d = ACH.firstOrNull { it[0] == id } ?: return
        toastQ.add(ToastQ(d[1], d[2]))
    }
    private fun pumpToast() {
        if (toastOn || toastQ.isEmpty()) return
        toastOn = true
        val d = toastQ.removeFirst()
        toastName = d.n; toastDesc = d.d; toastT = 2.3f
        Sfx.stamp()
    }

    /* ================= gameplay ================= */
    private fun specialReady() = charge >= CHARGE_MAX

    private fun fire() {
        if (mode != "play" || arrowsLeft <= 0 || arrows.size >= 6) return
        arrowsLeft--; shotsUsed++; stats.shots++; saveStats()
        var pr = 0
        if (charge >= CHARGE_MAX) { pr = 3; charge = 0f }
        val spd = lerp(.45f, 1f, aim.p.pow(.9f)) * M.vmax
        val a = Arrow()
        a.x = bow.x + aim.dx * 50f * M.K
        a.y = bow.y + aim.dy * 50f * M.K
        a.vx = aim.dx * spd; a.vy = aim.dy * spd
        a.ang = atan2(aim.dy, aim.dx)
        a.pierce = pr
        arrows.add(a)
        bow.vib = 1f
        Sfx.release(aim.p)
        if (pr != 0) floats.add(FloatT(bow.x, bow.y - 52f, "PIERCE AWAY", ACC, 12f))
    }

    private fun collectCoins(a: Arrow) {
        for (c in coins) {
            if (c.got) continue
            val dx = a.x - c.x; val dy = a.y - c.y
            val rr = c.r + M.AR + 7f * SC()
            if (dx * dx + dy * dy > rr * rr) continue
            c.got = true
            a.chain++
            if (a.chain > bestChain) bestChain = a.chain
            if (a.chain == 3) award("chain3")
            val v = 30 * a.chain
            score += v; stats.coins++; saveStats()
            val wasReady = specialReady()
            charge = min(CHARGE_MAX.toFloat(), charge + 1f)
            if (!wasReady && specialReady()) { Sfx.charge(); award("arm") }
            floats.add(FloatT(c.x, c.y - 14f, "+$v", GOLDD, 12f))
            if (a.chain > 1)
                floats.add(FloatT(c.x, c.y + 16f, "CHAIN ×${a.chain}", GOLDD, 10f))
            mkSpark(c.x, c.y, 6, GOLD, 140f)
            Sfx.coin(a.chain)
            if (a.chain >= 3) punch = max(punch, .22f)
        }
    }

    private fun hitTarget(a: Arrow) {
        a.gone = true
        for (o in arrows) if (!o.gone) { o.gone = true; puff(o.x, o.y) }
        val b = a.bounces
        val pb = if (a.pierce > 0) 75 else 0
        val pts = 150 + 100 * b + pb
        score += pts; stats.hits++; saveStats()
        if (b >= 1) award("bank")
        if (b >= 3) award("bank3")
        if (a.pierced > 0) award("breach")
        if (a.ported) award("worm")
        if (a.welled) award("sling")
        val cd = ClearData(shotsUsed, b, bestChain, pts, arrowsLeft, arrowsLeft * 150,
            if (shotsUsed == 1) 3 else if (shotsUsed <= par) 2 else 1)
        score += cd.bonus
        when (gameMode) {
            "inf" -> {
                val db = 25 * courseNo
                score += db; cd.db = db
                if (courseNo > infBest) { infBest = courseNo; Pi("ric_infbest", infBest) }
                if (courseNo >= 10) award("depth10")
            }
            "daily" -> {
                val seed = dailySeed()
                val prev = prefs.getString("ric_daily", null)?.let {
                    runCatching { JSONObject(it) }.getOrNull()
                }
                if (prev == null || prev.optInt("seed") != seed || score > prev.optInt("score")) {
                    Ps("ric_daily", JSONObject().put("seed", seed)
                        .put("score", score).toString())
                    cd.dailyBest = true
                }
                award("daily"); stats.dailys++; saveStats()
            }
            "custom" -> award("maker")
            else -> {
                savedLevel = max(savedLevel, min(courseNo + 1, 20))
                Pi("ric_level", savedLevel)
                if (courseNo >= 20) award("grad")
                if (tourStars[courseNo - 1] < cd.stars) {
                    tourStars[courseNo - 1] = cd.stars
                    saveTour()
                }
                if (tourStars.count { it == 3 } >= 5) award("sharp")
            }
        }
        if (score > best) { best = score; newBest = true; Pi("ric_best", best) }
        clearData = cd
        world.tg.pulse = 1f; punch = 1f; flash = .35f; shake = 6f
        rings.add(Ring(world.tg.x, world.tg.y, world.tg.r * .4f, world.tg.r * 2.6f,
            0f, .6f, ACC, 3f))
        confetti(world.tg.x, world.tg.y)
        floats.add(FloatT(world.tg.x, world.tg.y - world.tg.r - 24f, "BULLSEYE +$pts", INK, 15f))
        if (b >= 1) floats.add(FloatT(world.tg.x, world.tg.y + world.tg.r + 26f,
            "BANK SHOT ×$b", ACC, 13f))
        if (pb > 0) floats.add(FloatT(world.tg.x, world.tg.y + world.tg.r + 44f,
            "PIERCING +75", ACC, 11f))
        if (cd.db > 0) floats.add(FloatT(world.tg.x, world.tg.y + world.tg.r + 62f,
            "DEPTH BONUS +${cd.db}", GOLDD, 11f))
        if (cd.dailyBest) floats.add(FloatT(world.tg.x, world.tg.y - world.tg.r - 44f,
            "NEW DAILY BEST", GOLDD, 12f))
        Sfx.target(b); vib(40)
        slowmoT = min(slowmoT, .15f)
        mode = "clear"; clearT = 0f; winShown = false
        aim.on = false; pid = -1
    }

    private fun angLerp(a: Float, b: Float, t: Float): Float {
        var d = (b - a) % (2f * Math.PI.toFloat())
        if (d > Math.PI.toFloat()) d -= 2f * Math.PI.toFloat()
        if (d < -Math.PI.toFloat()) d += 2f * Math.PI.toFloat()
        return a + d * t
    }

    private fun stepArrows(sdt: Float, isMenu: Boolean) {
        for (a in arrows) {
            if (a.gone) continue
            a.age += sdt
            a.pcd = max(0f, a.pcd - sdt)
            val sp = hypot(a.vx, a.vy)
            val steps = ceil(sp * sdt / 6f).toInt().coerceIn(1, 12)
            val h = sdt / steps
            for (i in 0 until steps) {
                a.vy += M.g * h; a.x += a.vx * h; a.y += a.vy * h
                evs.clear()
                val died = world.resolve(a, false, h, evs)
                for (e in evs) handleEv(e, a)
                if (a.gone) break
                if (!isMenu) {
                    if (hypot(a.x - world.tg.x, a.y - world.tg.y) < world.tg.r * .92f) {
                        hitTarget(a); break
                    }
                    collectCoins(a)
                }
                if (a.age > 9f) { a.gone = true; puff(a.x, a.y); break }
                if (died) break
            }
            if (a.gone) continue
            a.ang = angLerp(a.ang, atan2(a.vy, a.vx), 1f - exp(-18f * sdt))
            a.tt += sdt
            if (a.tt > .03f) {
                a.tt = 0f
                trailDots.add(Trail(a.x, a.y, 0f, 2.4f, if (a.chain >= 3) GOLD else INK))
                if (trailDots.size > 420) trailDots.removeAt(0)
                if (!isMenu) {
                    ghostTick = (ghostTick + 1) % 3
                    if (ghostTick == 0 && ghostPath.size < 420)
                        ghostPath.add(floatArrayOf(a.x, a.y))
                }
            }
        }
        arrows.removeAll { it.gone }
    }

    private fun menuFire() {
        val ang = rf(-2.6f, -.6f)
        val dx = cos(ang); val dy = sin(ang)
        val spd = rf(.55f, .95f) * M.vmax
        val a = Arrow()
        a.x = bow.x + dx * 50f * M.K; a.y = bow.y + dy * 50f * M.K
        a.vx = dx * spd; a.vy = dy * spd; a.ang = ang
        arrows.add(a)
        bow.vib = 1f
    }

    class SimResult(val pts: ArrayList<FloatArray>, val hit: Boolean,
                    val ex: Float, val ey: Float)

    /** Aim preview: honest ghost physics with the pierce state armed. */
    private fun simulate(dx: Float, dy: Float, spd: Float): SimResult {
        val a = Arrow()
        a.x = bow.x + dx * 50f * M.K; a.y = bow.y + dy * 50f * M.K
        a.vx = dx * spd; a.vy = dy * spd
        a.pierce = if (specialReady()) 3 else 0
        val pts = ArrayList<FloatArray>()
        val h = 1f / 240f
        var t = 0f
        var hit = false
        var ex = a.x; var ey = a.y
        for (i in 0 until 300) {
            a.pcd = max(0f, a.pcd - h)
            a.vy += M.g * h; a.x += a.vx * h; a.y += a.vy * h
            ex = a.x; ey = a.y
            if (world.resolve(a, true, h, null)) break
            if (hypot(a.x - world.tg.x, a.y - world.tg.y) < world.tg.r * .92f) {
                hit = true; break
            }
            t += h
            if (t > 1.15f || pts.size > 40) break
            if (i % 5 == 0) pts.add(floatArrayOf(a.x, a.y))
        }
        return SimResult(pts, hit, ex, ey)
    }

    private fun showHint() {
        if (mode != "play") return
        val pth = Solver.hintPath(world, bow.x, bow.y, simTime)
        if (pth != null) {
            hintPts = ArrayList(pth); hintT = 6f
            stats.hints++; saveStats(); Sfx.chalk()
        } else {
            floats.add(FloatT(M.W / 2f, M.H * .45f, "NO LINE FOUND", INK, 12f * SC()))
        }
    }

    /* ================= mode actions ================= */
    private fun resetFx() {
        slowmoT = 0f; timeScale = 1f; zoom = 1f; punch = 0f; shake = 0f; flash = 0f
        sparks.clear(); rings.clear(); floats.clear()
    }
    private fun closeOverlays() {
        showMap = false; showStats = false; showSlots = false; showShare = false
        ui.clear(); uiPress = null
    }
    private fun startTour(from: Int = 0) {
        gameMode = "tour"; closeOverlays()
        score = 0; charge = 0f; newBest = false; resetFx()
        setCourse(if (from > 0) from.coerceIn(1, 20) else savedLevel.coerceIn(1, 20))
        mode = "play"; Sfx.ui()
    }
    private fun startInfinite(keep: Boolean) {
        gameMode = "inf"; closeOverlays()
        if (!keep) { score = 0; charge = 0f }
        newBest = false; resetFx()
        setCourse(1); mode = "play"; Sfx.ui()
    }
    private fun startDaily() {
        gameMode = "daily"; closeOverlays()
        score = 0; charge = 0f; newBest = false; resetFx()
        setCourse(1); mode = "play"; Sfx.ui()
    }
    private fun startCustom() {
        gameMode = "custom"; closeOverlays()
        score = 0; charge = 0f; newBest = false; resetFx()
        splats.clear(); ghostPath.clear()
        setCourse(1); mode = "play"; Sfx.ui()
    }
    private fun menuInit() {
        courseDef = Courses.ALL[4]      // WINDMILL, as the living backdrop
        courseNo = 5; courseName = "WINDMILL"
        layoutCourse()
        arrows.clear(); stuck.clear(); trailDots.clear(); arrowsLeft = 0
        ghostPath.clear(); ghostNo = -1; hintPts.clear(); hintT = 0f
    }
    private fun goMenu() {
        closeOverlays()
        paused = false; mode = "menu"
        resetFx()
        menuInit()
    }
    private fun togglePause() {
        if (mode != "play") return
        paused = !paused
        if (paused) Sfx.ui()
    }

    /* ================= workshop — interaction ================= */
    private fun starterDef(): PDef {
        val d = PDef()
        d.name = "MY PUZZLE"; d.par = 2; d.arrows = 3
        d.tx = .72f; d.ty = .28f
        d.coins.add(Coin(.38f, .6f)); d.coins.add(Coin(.5f, .45f))
        d.obs.add(RObs().apply {
            t = "plank"; x = .46f; y = .55f; w = .03f; h = .4f
        })
        return d
    }
    private fun edSync() {
        courseDef = ed.def!!
        layoutCourse()
        scheduleVerify()
    }
    private fun scheduleVerify() {
        edVerifyJob?.let { handler.removeCallbacks(it) }
        val job = Runnable {
            val d = ed.def ?: return@Runnable
            val n = if (M.W > 1f) Solver.paths(d, M) else 0
            ed.verifyOk = n > 0
            ed.verify = if (n > 0)
                "✓ SOLVER: ${if (n >= 3) "3+" else "$n"} PATH${if (n == 1) "" else "S"}"
            else "✗ UNSOLVABLE — REACH THE TARGET"
        }
        edVerifyJob = job
        handler.postDelayed(job, 260)
    }
    private fun pushUndo() {
        ed.def?.let {
            ed.undo.addLast(it.cpy())
            if (ed.undo.size > 50) ed.undo.removeFirst()
        }
    }
    private fun undoEd() {
        val d = ed.undo.removeLastOrNull() ?: return
        ed.def = d
        ed.selK = ""; ed.selI = -1; ed.pending = false
        edSync(); Sfx.tick2()
    }
    private fun selectTool(t: String) {
        ed.tool = t
        if (t != "port") ed.pending = false
        Sfx.tick2()
    }
    private fun edSelObj(): Any? {
        val d = ed.def ?: return null
        return when (ed.selK) {
            "target" -> d
            "coin"   -> d.coins.getOrNull(ed.selI)
            "obs"    -> d.obs.getOrNull(ed.selI)
            else     -> null
        }
    }
    private fun edHit(px: Float, py: Float): Pair<String, Int>? {
        val d = ed.def ?: return null
        var best: Pair<String, Int>? = null
        var bd = Float.MAX_VALUE
        fun consider(k: String, i: Int, cx: Float, cy: Float, r: Float) {
            val dist = hypot(px - cx, py - cy)
            if (dist < r && dist < bd) { bd = dist; best = k to i }
        }
        consider("target", 0, d.tx * M.W, d.ty * M.H,
            clamp(.115f * M.U, 36f * M.K, 62f * M.K) + 10f)
        d.coins.forEachIndexed { i, c -> consider("coin", i, c.x * M.W, c.y * M.H, 22f * SC()) }
        for (i in d.obs.indices.reversed()) {
            val o = d.obs[i]
            when (o.t) {
                "plank", "crate" -> consider("obs", i, o.x * M.W, o.y * M.H,
                    max(o.w, o.h) * M.U / 2f + 12f * SC())
                "bump" -> consider("obs", i, o.x * M.W, o.y * M.H, o.r * M.U + 10f * SC())
                "spin" -> consider("obs", i, o.x * M.W, o.y * M.H, o.len * M.U + 10f * SC())
                "move" -> consider("obs", i, o.x * M.W, o.y * M.H, o.w * M.U / 2f + 12f * SC())
                "well" -> consider("obs", i, o.x * M.W, o.y * M.H, o.R * M.U + 8f * SC())
                "portal" -> {
                    consider("obs", i, o.ax * M.W, o.ay * M.H, o.r * M.U + 10f * SC())
                    consider("obs", i, o.bx * M.W, o.by * M.H, o.r * M.U + 10f * SC())
                }
            }
        }
        if (best == null) {          // wind rects: large → lowest hit priority
            for (i in d.obs.indices.reversed()) {
                val o = d.obs[i]
                if (o.t != "wind") continue
                if (px > o.x * M.W - 8f && px < o.x * M.W + o.w * M.W + 8f &&
                    py > o.y * M.H - 8f && py < o.y * M.H + o.h * M.H + 8f) {
                    best = "obs" to i; break
                }
            }
        }
        return best
    }
    private fun mkOb(tool: String, nx: Float, ny: Float): RObs? {
        val o = RObs()
        when (tool) {
            "plank" -> { o.t = "plank"; o.x = nx; o.y = ny; o.w = .2f;  o.h = .032f }
            "crate" -> { o.t = "crate"; o.x = nx; o.y = ny; o.w = .055f; o.h = .055f }
            "bump"  -> { o.t = "bump";  o.x = nx; o.y = ny; o.r = .06f }
            "spin"  -> { o.t = "spin";  o.x = nx; o.y = ny; o.len = .22f; o.sp = 1.6f }
            "move"  -> { o.t = "move";  o.x = nx; o.y = ny; o.w = .28f; o.h = .032f
                         o.axis = "x"; o.range = .12f; o.sp = 1.2f }
            "wind"  -> { o.t = "wind";  o.x = nx - .08f; o.y = ny - .11f
                         o.w = .16f; o.h = .22f; o.ky = -.9f }
            "well"  -> { o.t = "well";  o.x = nx; o.y = ny; o.f = 2.6f; o.R = .26f }
            else -> return null
        }
        return o
    }
    private fun edPlace(nx: Float, ny: Float) {
        val d = ed.def ?: return
        if (hypot(nx * M.W - bow.x, ny * M.H - bow.y) < 100f * SC()) {
            edFloat("TOO CLOSE TO THE BOW"); return
        }
        when (ed.tool) {
            "tgt" -> {
                pushUndo(); d.tx = nx; d.ty = ny
                ed.selK = "target"; ed.selI = 0; Sfx.place()
            }
            "coin" -> {
                if (d.coins.size >= 8) { edFloat("COIN LIMIT (8)"); return }
                pushUndo(); d.coins.add(Coin(nx, ny))
                ed.selK = "coin"; ed.selI = d.coins.size - 1; Sfx.place()
            }
            "port" -> {
                if (!ed.pending) {
                    ed.pending = true; ed.pendX = nx; ed.pendY = ny
                    edFloat("NOW TAP THE SECOND END"); return
                }
                if (d.obs.size >= 24) {
                    edFloat("OBSTACLE LIMIT (24)"); ed.pending = false; return
                }
                pushUndo()
                val o = RObs()
                o.t = "portal"; o.ax = ed.pendX; o.ay = ed.pendY
                o.bx = nx; o.by = ny; o.r = .07f
                d.obs.add(o)
                ed.pending = false
                ed.selK = "obs"; ed.selI = d.obs.size - 1
                Sfx.portal()
            }
            "erase" -> {}
            else -> {
                if (d.obs.size >= 24) { edFloat("OBSTACLE LIMIT (24)"); return }
                val o = mkOb(ed.tool, nx, ny) ?: return
                pushUndo(); d.obs.add(o)
                ed.selK = "obs"; ed.selI = d.obs.size - 1; Sfx.place()
            }
        }
        edSync()
    }
    private fun edErase(nx: Float, ny: Float) {
        val h = edHit(nx * M.W, ny * M.H)
        if (h == null || h.first == "target") { edFloat("NOTHING TO ERASE"); return }
        val d = ed.def ?: return
        pushUndo()
        if (h.first == "coin") d.coins.removeAt(h.second)
        else d.obs.removeAt(h.second)
        ed.selK = ""; ed.selI = -1
        edSync(); Sfx.thunk()
    }
    private fun edDown(e: MotionEvent) {
        val nx = (e.x / M.W).coerceIn(.04f, .96f)
        val ny = (e.y / M.H).coerceIn(.09f, .9f)
        if (ed.tool == "erase") { edErase(nx, ny); return }
        val h = edHit(e.x, e.y)
        if (h != null && ed.tool !in setOf("tgt", "coin", "port")) {
            val (k, i) = h
            ed.selK = k; ed.selI = i
            pushUndo()
            ed.dragging = true
            val d = ed.def!!
            when (k) {
                "target" -> { ed.dragOx = d.tx - e.x / M.W; ed.dragOy = d.ty - e.y / M.H }
                "coin" -> {
                    val c = d.coins[i]
                    ed.dragOx = c.x - e.x / M.W; ed.dragOy = c.y - e.y / M.H
                }
                "obs" -> {
                    val o = d.obs[i]
                    if (o.t == "portal") {
                        val dA = hypot(e.x - o.ax * M.W, e.y - o.ay * M.H)
                        ed.dragEnd =
                            if (dA <= hypot(o.bx * M.W - e.x, o.by * M.H - e.y)) 'a' else 'b'
                        val ix = if (ed.dragEnd == 'a') o.ax else o.bx
                        val iy = if (ed.dragEnd == 'a') o.ay else o.by
                        ed.dragOx = ix - e.x / M.W; ed.dragOy = iy - e.y / M.H
                    } else {
                        ed.dragOx = o.x - e.x / M.W; ed.dragOy = o.y - e.y / M.H
                    }
                }
            }
            return
        }
        edPlace(nx, ny)
    }
    private fun edDragMove(e: MotionEvent) {
        if (!ed.dragging) return
        val d = ed.def ?: return
        val nx = (e.x / M.W + ed.dragOx).coerceIn(.05f, .95f)
        val ny = (e.y / M.H + ed.dragOy).coerceIn(.1f, .88f)
        when (ed.selK) {
            "target" -> { d.tx = nx; d.ty = ny }
            "coin"   -> d.coins.getOrNull(ed.selI)?.let { it.x = nx; it.y = ny }
            "obs"    -> d.obs.getOrNull(ed.selI)?.let { o ->
                if (o.t == "portal") {
                    if (ed.dragEnd == 'a') { o.ax = nx; o.ay = ny }
                    else { o.bx = nx; o.by = ny }
                } else { o.x = nx; o.y = ny }
            }
        }
        edSync()
    }
    /** Property-bar actions, invoked from the chrome in Part 6. */
    private fun edAct(act: String) {
        val d = ed.def ?: return
        val it0 = edSelObj() ?: return
        if (act == "del") {
            pushUndo()
            if (ed.selK == "coin") d.coins.removeAt(ed.selI)
            else if (ed.selK == "obs") d.obs.removeAt(ed.selI)
            ed.selK = ""; ed.selI = -1
            edSync(); return
        }
        val o = it0 as? RObs ?: return
        pushUndo()
        when (act) {
            "rl" -> o.a -= Math.PI.toFloat() / 12f
            "rr" -> o.a += Math.PI.toFloat() / 12f
            "big" -> when (o.t) {
                "plank"  -> o.w = clamp(o.w + .03f, .08f, .46f)
                "crate"  -> { o.w = clamp(o.w + .01f, .03f, .09f); o.h = o.w }
                "bump"   -> o.r = clamp(o.r + .008f, .03f, .09f)
                "spin"   -> o.len = clamp(o.len + .02f, .1f, .32f)
                "move"   -> o.w = clamp(o.w + .03f, .14f, .4f)
                "well"   -> o.R = clamp(o.R + .02f, .14f, .4f)
                "portal" -> o.r = clamp(o.r + .008f, .05f, .1f)
            }
            "sml" -> when (o.t) {
                "plank"  -> o.w = clamp(o.w - .03f, .08f, .46f)
                "crate"  -> { o.w = clamp(o.w - .01f, .03f, .09f); o.h = o.w }
                "bump"   -> o.r = clamp(o.r - .008f, .03f, .09f)
                "spin"   -> o.len = clamp(o.len - .02f, .1f, .32f)
                "move"   -> o.w = clamp(o.w - .03f, .14f, .4f)
                "well"   -> o.R = clamp(o.R - .02f, .14f, .4f)
                "portal" -> o.r = clamp(o.r - .008f, .05f, .1f)
            }
            "fst" -> when (o.t) {
                "spin" -> o.sp = clamp(o.sp + if (o.sp < 0f) .2f else -.2f, -3.4f, 3.4f)
                "move" -> o.sp = clamp(o.sp + .15f, .5f, 2.2f)
                "well" -> o.f = clamp(o.f + .3f, 1.2f, 4f)
                "wind" -> {
                    val m = hypot(o.kx, o.ky).coerceAtLeast(.9f)
                    val nm = clamp(m + .15f, .3f, 1.4f)
                    o.kx = o.kx / m * nm; o.ky = o.ky / m * nm
                }
            }
            "slw" -> when (o.t) {
                "spin" -> o.sp = clamp(o.sp - if (o.sp < 0f) -.2f else .2f, -3.4f, 3.4f)
                "move" -> o.sp = clamp(o.sp - .15f, .5f, 2.2f)
                "well" -> o.f = clamp(o.f - .3f, 1.2f, 4f)
                "wind" -> {
                    val m = hypot(o.kx, o.ky).coerceAtLeast(.9f)
                    val nm = clamp(m - .15f, .3f, 1.4f)
                    o.kx = o.kx / m * nm; o.ky = o.ky / m * nm
                }
            }
            "rev"  -> o.sp *= -1f
            "ax"   -> o.axis = if (o.axis == "x") "y" else "x"
            "dir"  -> {
                val a2 = atan2(o.ky, o.kx) + Math.PI.toFloat() / 4f
                o.kx = cos(a2) * .9f; o.ky = sin(a2) * .9f
            }
            "rng"  -> o.range = clamp(o.range + .02f, .04f, .22f)
            "rng-" -> o.range = clamp(o.range - .02f, .04f, .22f)
        }
        edSync()
    }
    private fun openEditor() {
        closeOverlays()
        paused = false
        mode = "editor"
        if (ed.def == null) ed.def = starterDef()
        splats.clear(); ghostPath.clear(); hintPts.clear(); hintT = 0f
        arrows.clear(); stuck.clear(); trailDots.clear(); charge = 0f
        resetFx()
        edSync()
        Sfx.ui()
    }
    private fun saveSlots() {
        val arr = JSONArray()
        for (s in slots) {
            if (s == null) arr.put(JSONObject.NULL)
            else arr.put(JSONObject().put("n", s.first).put("d", defToJson(s.second)))
        }
        Ps("ric_slots", arr.toString())
    }
    private fun copyToClip(label: String, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {}
    }
    private fun importDialog() {
        handler.post {
            val input = EditText(context)
            input.hint = "PASTE A PUZZLE CODE"
            input.typeface = tfMono
            input.textSize = 12f
            val wrap = FrameLayout(context).apply {
                setPadding(48, 24, 48, 0); addView(input)
            }
            AlertDialog.Builder(context)
                .setTitle("IMPORT PUZZLE")
                .setView(wrap)
                .setPositiveButton("LOAD") { _, _ ->
                    runCatching {
                        val d = decodeDef(input.text.toString())
                        pushUndo()
                        ed.def = d
                        ed.selK = ""; ed.selI = -1; ed.pending = false
                        edSync(); Sfx.place()
                    }.onFailure { edFloat("INVALID CODE") }
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }
    }

    /* ================= update ================= */
    private fun update(dt: Float) {
        // frame-driven UI timers run even while paused
        if (toastT > 0f) {
            toastT -= dt
            if (toastT <= 0f) {
                toastOn = false
                handler.postDelayed({ pumpToast() }, 420)
            }
        }
        if (resetArm) {
            resetArmT -= dt
            if (resetArmT <= 0f) resetArm = false
        }
        if (paused) return

        time += dt
        if (hintT > 0f) hintT -= dt
        if (tagT > 0f) tagT -= dt

        val want = if (slowmoT > 0f) .3f else 1f
        timeScale += (want - timeScale) * (1f - exp(-10f * dt))
        if (slowmoT > 0f) slowmoT -= dt
        val sdt = dt * timeScale
        simTime += sdt

        punch *= exp(-4f * dt)
        zoom += (((if (slowmoT > 0f) 1.13f else 1f) + punch * .1f) - zoom) *
                (1f - exp(-7f * dt))
        val zx = if (slowmoT > .05f || punch > .4f) world.tg.x else M.W / 2f
        val zy = if (slowmoT > .05f || punch > .4f) world.tg.y else M.H * .42f
        fx += (zx - fx) * (1f - exp(-5f * dt))
        fy += (zy - fy) * (1f - exp(-5f * dt))
        shake *= exp(-7f * dt); if (shake < .02f) shake = 0f
        flash *= exp(-5f * dt)
        if (calm) { shake = 0f; flash = 0f }

        bow.pull += ((if (aim.on) aim.p else 0f) - bow.pull) * (1f - exp(-16f * dt))
        val ta = if (aim.on && aim.p > .03f) atan2(aim.dy, aim.dx)
                 else -.7f + sin(time * 1.2f) * .05f
        bow.ang = angLerp(bow.ang, ta, 1f - exp(-12f * dt))
        bow.vib *= exp(-5f * dt)

        // live obstacle animation — editor mode shows movers/spinners too
        for (o in world.obs) {
            if (o.t == "move") {
                val s = sin(simTime * o.sp + o.ph) * o.range
                if (o.axis == "x") o.x = o.bx0 + s else o.y = o.by0 + s
            } else if (o.t == "spin") o.ang = simTime * o.sp + o.ph
            else if (o.t == "bump") {
                if (o.cd > 0f) o.cd -= sdt
                o.pulse *= exp(-5f * sdt)
            }
        }
        if (world.tg.sp != 0f)
            world.tg.x = world.tg.bx + sin(simTime * world.tg.sp) * world.tg.mx
        world.tg.pulse *= exp(-4f * sdt)

        if (mode == "play" || mode == "menu") {
            stepArrows(sdt, mode == "menu")
            if (mode == "menu") {
                menuAutoT -= dt
                if (menuAutoT <= 0f) { menuAutoT = rf(1.8f, 3.2f); menuFire() }
            } else {
                // trick-shot slow-motion: bounced arrow homing on the target
                for (a in arrows) {
                    if (a.slowed || a.bounces < 1) continue
                    val sp = hypot(a.vx, a.vy)
                    if (sp < 260f * M.K) continue
                    val dx = world.tg.x - a.x; val dy = world.tg.y - a.y
                    val d = hypot(dx, dy)
                    if (d > .5f * max(M.W, M.H)) continue
                    if ((a.vx * dx + a.vy * dy) / (sp * d) < .5f) continue
                    val closest = abs(dx * (a.vy / sp) - dy * (a.vx / sp))
                    if (closest < world.tg.r * 1.6f && d / sp < .6f) {
                        a.slowed = true
                        slowmoT = max(slowmoT, .6f)
                        Sfx.slow()
                    }
                }
                if (arrowsLeft <= 0 && arrows.isEmpty()) {
                    mode = "fail"; failT = 0f; loseShown = false
                    aim.on = false; pid = -1
                    if (score > best) {
                        best = score; newBest = true; Pi("ric_best", best)
                    } else newBest = false
                    Sfx.empty()
                }
            }
        } else if (mode == "clear") {
            clearT += dt
            if (!winShown && clearT > 1.05f) {
                winShown = true; winShownAt = time
                Sfx.fanfare()
            }
        } else if (mode == "fail") {
            failT += dt
            if (!loseShown && failT > .7f) loseShown = true
        }

        val si = sparks.iterator()
        while (si.hasNext()) {
            val s = si.next()
            s.age += sdt
            s.x += s.vx * sdt; s.y += s.vy * sdt
            s.vy += s.gy * sdt; s.rot += s.vr * sdt
            if (s.age > s.life) si.remove()
        }
        val ri2 = rings.iterator()
        while (ri2.hasNext()) { val r = ri2.next(); r.age += sdt; if (r.age > r.life) ri2.remove() }
        val fi = floats.iterator()
        while (fi.hasNext()) { val f = fi.next(); f.age += sdt; f.y += f.vy * sdt
            if (f.age > f.life) fi.remove() }
        val ti = trailDots.iterator()
        while (ti.hasNext()) { val d = ti.next(); d.age += sdt; if (d.age > d.life) ti.remove() }
        for (s in stuck) s.t += dt
    }

    /* ================= input ================= */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val b = hitUi(e.x, e.y)
                if (b != null) { uiPress = b; return true }
                if (mode == "editor") { edDown(e); return true }
                if (mode == "play" && !paused && pid == -1 && arrowsLeft > 0) {
                    pid = e.pointerId
                    aim.on = true
                    aim.sx = e.x; aim.sy = e.y
                    aim.dx = .7f; aim.dy = -.7f; aim.p = 0f
                    Sfx.draw()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (uiPress != null) return true
                if (mode == "editor") { edDragMove(e); return true }
                if (aim.on && e.pointerId == pid) {
                    val dx = aim.sx - e.x; val dy = aim.sy - e.y
                    val pl = hypot(dx, dy)
                    if (pl > 10f) { aim.dx = dx / pl; aim.dy = dy / pl }
                    val p2 = (pl / (.32f * M.U)).coerceIn(0f, 1f)
                    if ((p2 * 4f).toInt() != (aim.p * 4f).toInt()) Sfx.tick2()
                    aim.p = p2
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val b = uiPress
                uiPress = null
                if (b != null) {
                    if (b.r.contains(e.x, e.y)) b.act()
                    return true
                }
                if (mode == "editor") { ed.dragging = false; return true }
                if (aim.on && e.pointerId == pid) {
                    aim.on = false; pid = -1
                    if (e.actionMasked == MotionEvent.ACTION_UP && aim.p >= .06f) fire()
                }
            }
        }
        return true
    }
    private fun hitUi(x: Float, y: Float): UiB? {
        for (i in ui.indices.reversed()) if (ui[i].r.contains(x, y)) return ui[i]
        return null
    }

    /* ================= draw helpers ================= */
    private fun tpx(size: Float, bold: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (bold) tfBig else tfMono
        textSize = size * max(M.K, .85f)
        color = INK
    }
    private fun drawTextC(c: Canvas, pt: Paint, txt: String, cx: Float, cy: Float) {
        c.drawText(txt, cx, cy - (pt.ascent() + pt.descent()) / 2f, pt)
    }
    private fun dim(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = PAPER; p.alpha = 226
        c.drawRect(0f, 0f, M.W, M.H, p)
        p.alpha = 255
    }
    private fun card(c: Canvas, w: Float, h: Float): RectF {
        val cx = M.W / 2f; val cy = M.H / 2f
        val o = 9f * SC()
        p.style = Paint.Style.FILL; p.color = INK
        c.drawRoundRect(cx - w / 2 + o, cy - h / 2 + o, cx + w / 2 + o, cy + h / 2 + o, 4f, 4f, p)
        p.color = PAPER
        c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 4f, 4f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = INK
        c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, 4f, 4f, p)
        p.style = Paint.Style.FILL
        return RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }
    private fun kicker(c: Canvas, txt: String, cx: Float, cy: Float) {
        val k = tpx(11f, false)
        k.alpha = 140; k.letterSpacing = .3f; k.textAlign = Paint.Align.CENTER
        c.drawText(txt, cx, cy, k)
        k.letterSpacing = 0f; k.alpha = 255; k.textAlign = Paint.Align.LEFT
    }
    private fun btn(c: Canvas, x: Float, y: Float, w: Float, h: Float, label: String,
                    style: String, act: () -> Unit,
                    on: Boolean = false, warn: Boolean = false) {
        val r = RectF(x, y, x + w, y + h)
        p.style = Paint.Style.FILL
        val fill = when {
            style == "chip-ok" -> GOLD
            style == "chip-bad" -> ACC
            style == "acc" -> INK
            style == "tool" && on -> INK
            else -> PAPER
        }
        p.color = fill
        if (style == "tool") c.drawRect(r, p) else c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (style == "cta") 2.5f else 2f
        p.color = when {
            warn -> ACC
            style == "acc" -> INK
            style == "tool" && on -> INK
            else -> INK
        }
        if (style == "tool") c.drawRect(r, p) else c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.style = Paint.Style.FILL
        if (label.isNotEmpty()) {
            val tp = tpx(if (style == "cta") 13f else 10f, true)
            tp.color = if (fill == INK && style != "chip-bad") PAPER
                       else if (style == "chip-bad") PAPER else INK
            if (style == "tool" && on) tp.color = PAPER
            tp.textAlign = Paint.Align.CENTER
            c.drawText(label, x + w / 2, y + h / 2 - (tp.ascent() + tp.descent()) / 2f, tp)
            tp.textAlign = Paint.Align.LEFT
        }
        ui.add(UiB(r, act))
    }
    private fun hudBtn(c: Canvas, cx: Float, cy: Float, kind: String, act: () -> Unit) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = INK
        c.drawCircle(cx, cy, 19f, p)
        p.style = Paint.Style.FILL
        when (kind) {
            "pause" -> { c.drawRect(cx - 5f, cy - 7f, cx - 1.5f, cy + 7f, p)
                         c.drawRect(cx + 1.5f, cy - 7f, cx + 5f, cy + 7f, p) }
            "hint" -> { val t = tpx(13f, true); t.textAlign = Paint.Align.CENTER
                        c.drawText("H", cx, cy - (t.ascent() + t.descent()) / 2f, t)
                        t.textAlign = Paint.Align.LEFT }
            "mute-on" -> { val t = tpx(14f, true); t.textAlign = Paint.Align.CENTER
                           c.drawText("♪", cx, cy - (t.ascent() + t.descent()) / 2f, t)
                           t.textAlign = Paint.Align.LEFT }
            else -> { val t = tpx(13f, true); t.textAlign = Paint.Align.CENTER
                      c.drawText("×", cx, cy - (t.ascent() + t.descent()) / 2f, t)
                      t.textAlign = Paint.Align.LEFT }
        }
        ui.add(UiB(RectF(cx - 26f, cy - 26f, cx + 26f, cy + 26f), act))
    }
    private fun starPath(cx: Float, cy: Float, r: Float): Path {
        val path = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * .45f
            val a = -Math.PI.toFloat() / 2f + i * Math.PI.toFloat() / 5f
            val x = cx + cos(a) * rr; val y = cy + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); return path
    }

    /* ================= onDraw ================= */
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        ui.clear()
        c.drawColor(PAPER)
        grainPaint?.let {
            it.alpha = 26
            c.drawRect(0f, 0f, M.W, M.H, it)
            it.alpha = 255
        }
        c.save()
        if (shake > .1f) c.translate(rf(-shake, shake), rf(-shake, shake))
        drawSheet(c)
        c.save()
        c.translate(fx, fy); c.scale(zoom, zoom); c.translate(-fx, -fy)
        if (mode == "editor") drawEditorDecor(c)
        drawSplats(c); drawGhost(c); drawHint(c)
        drawWind(c); drawWells(c); drawPortals(c); drawObstacles(c)
        drawCoins(c); drawTarget(c); drawStuck(c); drawTrails(c); drawArrows(c)
        drawBow(c); drawPreview(c); drawFX(c)
        c.restore(); c.restore()
        drawHUD(c)
        if (mode == "editor") drawEditorChrome(c)
        drawQuiver(c); drawCharge(c); drawGauge(c)
        if (mode == "menu") drawMenu(c)
        else if (paused) drawPause(c)
        else if (mode == "clear" && winShown) drawWin(c)
        else if (mode == "fail" && loseShown) drawLose(c)
        if (showMap) drawMap(c)
        if (showStats) drawStats(c)
        if (showSlots) drawSlots(c)
        if (showShare) drawShare(c)
        drawToast(c)
        if (flash > .01f) {
            p.style = Paint.Style.FILL; p.color = ACC; p.alpha = (flash * 51).toInt()
            c.drawRect(0f, 0f, M.W, M.H, p); p.alpha = 255
        }
    }

    /* ================= sheet ================= */
    private fun drawSheet(c: Canvas) {
        p.style = Paint.Style.STROKE; p.color = INK
        p.strokeWidth = 1.5f; p.alpha = 40
        c.drawRect(12.5f, 12.5f, M.W - 12.5f, M.H - 12.5f, p)
        p.strokeWidth = 1f; p.alpha = 76
        for ((xx, yy) in listOf(26f to 26f, M.W - 26f to 26f,
                                26f to M.H - 26f, M.W - 26f to M.H - 26f)) {
            c.drawLine(xx - 5f, yy, xx + 5f, yy, p)
            c.drawLine(xx, yy - 5f, xx, yy + 5f, p)
        }
        p.alpha = 255
        val ch = when { mode == "menu" -> "R"; mode == "editor" -> "W"; else -> pad2(courseNo) }
        val np = tpx(M.U * .5f, true)
        np.color = INK; np.alpha = 13
        drawTextC(c, np, ch, M.W / 2f, M.H * .44f)
        val fp = tpx(10f, false)
        fp.color = INK; fp.alpha = 110
        fp.textAlign = Paint.Align.RIGHT
        val foot = when {
            mode == "menu" -> "RICOCHET · SHEET Nº8 · THE WORKSHOP"
            mode == "editor" -> "WORKSHOP · ${ed.verify}"
            gameMode == "custom" -> "WORKSHOP BUILD · PLAYTEST"
            else -> {
                val head = when (gameMode) {
                    "tour" -> "PUZZLE Nº${pad2(courseNo)} · ${courseName.uppercase()}"
                    "daily" -> "DAILY · ${dailyLabel()}"
                    else -> "INFINITE · DEPTH ${pad2(courseNo)}"
                }
                val vp = courseDef.paths.coerceAtMost(3)
                head + " — " + if (vp > 0)
                    "${if (vp >= 3) "3+" else vp} SOLVER-VERIFIED PATH${if (vp == 1) "" else "S"}"
                else "BANK SHOTS PAY"
            }
        }
        c.drawText(foot, M.W - 24f, M.H - 13f, fp)
        fp.textAlign = Paint.Align.LEFT; fp.alpha = 255
        if (mode == "play" && gameMode == "tour" && courseNo == 1 &&
            shotsUsed == 0 && !aim.on) {
            val al = (.3f + .25f * sin(time * 3.2f)).coerceIn(0f, 1f)
            val hp = tpx(11f, true)
            hp.textAlign = Paint.Align.CENTER; hp.alpha = (al * 255).toInt()
            c.drawText("TOUCH & DRAG BACK — RELEASE TO FIRE", M.W / 2f, bow.y + 56f, hp)
            hp.alpha = 255; hp.textAlign = Paint.Align.LEFT
        }
        if (tagT > 0f) {
            val al = min(1f, tagT / .4f)
            val t1 = tpx(38f * max(M.K, .9f), true)
            t1.textAlign = Paint.Align.CENTER; t1.alpha = (al * 255).toInt()
            drawTextC(c, t1, "Nº${pad2(courseNo)}", M.W / 2f, 96f)
            val t2 = tpx(10f, false)
            t2.textAlign = Paint.Align.CENTER; t2.alpha = (al * 150).toInt()
            c.drawText("$tagHead — $tagSub", M.W / 2f, 126f, t2)
            t1.alpha = 255; t2.alpha = 255
            t1.textAlign = Paint.Align.LEFT; t2.textAlign = Paint.Align.LEFT
        }
    }

    /* ================= editor decor & chrome ================= */
    private fun drawEditorDecor(c: Canvas) {
        p.style = Paint.Style.FILL; p.color = INK; p.alpha = 18
        var yy = 76f
        while (yy < M.H - 46f) {
            var xx = 44f
            while (xx < M.W - 30f) { c.drawRect(xx, yy, xx + 1.6f, yy + 1.6f, p); xx += 44f }
            yy += 44f
        }
        p.alpha = 255
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
        p.color = ACC; p.alpha = 115
        p.pathEffect = DashPathEffect(floatArrayOf(4f * SC(), 6f * SC()), 0f)
        c.drawCircle(bow.x, bow.y, 100f * SC(), p)
        p.pathEffect = null; p.alpha = 255
        if (ed.pending) {
            p.pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f); p.strokeWidth = 2f
            c.drawCircle(ed.pendX * M.W, ed.pendY * M.H, 14f, p)
            p.pathEffect = null
        }
        val d = ed.def ?: return
        if (ed.selK == "obs" && d.obs.getOrNull(ed.selI)?.t == "portal") {
            val o = d.obs[ed.selI]
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f)
            p.strokeWidth = 2f; p.color = ACC
            c.drawCircle(o.ax * M.W, o.ay * M.H, o.r * M.U + 8f, p)
            c.drawCircle(o.bx * M.W, o.by * M.H, o.r * M.U + 8f, p)
            p.pathEffect = null
            return
        }
        val ring: RectF? = when (ed.selK) {
            "target" -> RectF(d.tx * M.W - 60f, d.ty * M.H - 60f,
                              d.tx * M.W + 60f, d.ty * M.H + 60f)
            "coin" -> d.coins.getOrNull(ed.selI)?.let {
                RectF(it.x * M.W - 20f, it.y * M.H - 20f,
                      it.x * M.W + 20f, it.y * M.H + 20f) }
            "obs" -> d.obs.getOrNull(ed.selI)?.let { o ->
                when (o.t) {
                    "wind" -> RectF(o.x * M.W - 6f, o.y * M.H - 6f,
                        o.x * M.W + o.w * M.W + 6f, o.y * M.H + o.h * M.H + 6f)
                    "spin", "well" -> {
                        val r = (if (o.t == "spin") o.len else o.R) * M.U + 10f
                        RectF(o.x * M.W - r, o.y * M.H - r, o.x * M.W + r, o.y * M.H + r) }
                    else -> {
                        val r = max(o.w, o.h) * M.U / 2f + 12f
                        RectF(o.x * M.W - r, o.y * M.H - r, o.x * M.W + r, o.y * M.H + r) }
                } }
            else -> null
        }
        if (ring != null) {
            p.pathEffect = DashPathEffect(floatArrayOf(4f, 5f), 0f)
            p.strokeWidth = 2f; p.color = ACC
            c.drawRect(ring, p)
            p.pathEffect = null
        }
    }

    private fun drawEditorChrome(c: Canvas) {
        val h = 30f * max(M.K, .9f)
        val lab = tpx(9.5f, true)
        fun wid(t: String) = lab.measureText(t) + 24f
        var x = 16f; val y = 60f
        btn(c, x, y, wid(ed.verify), h, ed.verify,
            if (ed.verifyOk) "chip-ok" else "chip-bad", {}); x += wid(ed.verify) + 7f
        btn(c, x, y, wid("▶ TEST"), h, "▶ TEST", "acc") { startCustom() }
        x += wid("▶ TEST") + 7f
        btn(c, x, y, wid("UNDO"), h, "UNDO", "chip") { undoEd() }; x += wid("UNDO") + 7f
        btn(c, x, y, h * .85f, h, "−", "chip") {
            pushUndo(); ed.def!!.arrows = (ed.def!!.arrows - 1).coerceIn(1, 9); edSync()
        }; x += h * .85f + 3f
        val an = "${ed.def!!.arrows} ARROWS"
        btn(c, x, y, wid(an), h, an, "chip", {}); x += wid(an) + 3f
        btn(c, x, y, h * .85f, h, "+", "chip") {
            pushUndo(); ed.def!!.arrows = (ed.def!!.arrows + 1).coerceIn(1, 9); edSync()
        }; x += h * .85f + 9f
        for ((lbl, act) in arrayOf<Pair<String, () -> Unit>>(
            "SLOTS" to { showSlots = true },
            "SHARE" to { shareCode = encodeDef(ed.def!!); showShare = true },
            "CLEAR" to { pushUndo(); ed.def = starterDef()
                         ed.selK = ""; ed.selI = -1; ed.pending = false
                         edSync(); Sfx.thunk() },
            "EXIT" to { goMenu() })) {
            btn(c, x, y, wid(lbl), h, lbl, "chip") { act() }; x += wid(lbl) + 7f
        }
        // tool row (wraps on narrow screens)
        val tw = 47f * max(M.K, .85f); val th = 46f * max(M.K, .85f)
        val perRow = if (M.W < (tw + 6f) * 8f) 6 else 12
        val rows = if (ED_TOOLS.size <= perRow) 1 else 2
        val ty0 = M.H - th * rows - 12f * rows - (if (rows == 2) 46f else 0f)
        for ((idx, t) in ED_TOOLS.withIndex()) {
            val row = idx / perRow
            val inRow = if (row == 0) min(perRow, ED_TOOLS.size) else ED_TOOLS.size - perRow
            val rowN = idx % perRow
            val rw = inRow * (tw + 6f) - 6f
            val tx = M.W / 2f - rw / 2f + rowN * (tw + 6f)
            val ty = ty0 + row * (th + 10f)
            btn(c, tx, ty, tw, th, "", "tool", { selectTool(t) }, on = ed.tool == t)
            drawToolIcon(c, t, tx + tw / 2f, ty + th / 2f - 5f, ed.tool == t)
            val lp = tpx(8f, true)
            lp.textAlign = Paint.Align.CENTER
            lp.color = if (ed.tool == t) PAPER else INK
            c.drawText(ED_LBL[t]!!, tx + tw / 2f, ty + th - 7f, lp)
            lp.textAlign = Paint.Align.LEFT
        }
        drawProps(c, ty0 - 38f)
    }

    private fun drawToolIcon(c: Canvas, t: String, cx: Float, cy: Float, on: Boolean) {
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.8f
        p.color = if (on) PAPER else INK
        val r = 7f
        when (t) {
            "sel" -> { val path = Path()
                path.moveTo(cx - 5f, cy - 6f); path.lineTo(cx + 6f, cy - 1f)
                path.lineTo(cx, cy + 1f); path.lineTo(cx - 1f, cy + 6f)
                path.close(); c.drawPath(path, p) }
            "tgt" -> { c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r * .5f, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx, cy, 1.6f, p)
                p.style = Paint.Style.STROKE }
            "coin" -> { c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, r * .5f, p) }
            "plank" -> c.drawRoundRect(cx - r - 2f, cy - 2.5f, cx + r + 2f, cy + 2.5f, 2f, 2f, p)
            "crate" -> { c.drawRect(cx - r, cy - r, cx + r, cy + r, p)
                c.drawLine(cx - r, cy - r, cx + r, cy + r, p)
                c.drawLine(cx + r, cy - r, cx - r, cy + r, p) }
            "bump" -> { c.drawCircle(cx, cy, r, p); c.drawCircle(cx, cy, 3f, p) }
            "spin" -> { c.drawLine(cx - r - 2f, cy, cx + r + 2f, cy, p)
                c.drawCircle(cx, cy, 2.5f, p) }
            "move" -> { c.drawRoundRect(cx - r, cy - 2.5f, cx + 3f, cy + 2.5f, 2f, 2f, p)
                p.pathEffect = DashPathEffect(floatArrayOf(2f, 3f), 0f)
                c.drawLine(cx + 6f, cy - 6f, cx + 6f, cy + 6f, p); p.pathEffect = null }
            "wind" -> { for (i in 0..2) {
                    val yy = cy - 5f + i * 5f
                    val len = if (i == 1) 13f else 9f
                    c.drawLine(cx - 7f, yy, cx - 7f + len, yy, p)
                    if (i == 1) { c.drawLine(cx - 7f + len, yy, cx - 3f + len, yy - 3f, p)
                        c.drawLine(cx - 7f + len, yy, cx - 3f + len, yy + 3f, p) } } }
            "well" -> { p.pathEffect = DashPathEffect(floatArrayOf(3f, 4f), 0f)
                c.drawCircle(cx, cy, r, p); p.pathEffect = null
                c.drawCircle(cx, cy, 3.5f, p) }
            "port" -> { c.drawCircle(cx - 5f, cy, 4f, p); c.drawCircle(cx + 5f, cy, 4f, p) }
            "erase" -> { c.drawLine(cx - 6f, cy + 6f, cx + 6f, cy + 6f, p)
                c.drawLine(cx - 4f, cy + 3f, cx + 2f, cy - 5f, p)
                c.drawLine(cx + 2f, cy - 5f, cx + 5f, cy - 2f, p)
                c.drawLine(cx + 5f, cy - 2f, cx - 1f, cy + 3f, p) }
        }
        p.style = Paint.Style.FILL
    }

    private fun drawProps(c: Canvas, y: Float) {
        val it0 = edSelObj()
        data class Prop(val l: String, val a: String, val w: Boolean)
        val bar = when {
            ed.pending -> listOf(Prop("PLACING PORTAL — TAP THE SECOND END", "", false))
            it0 == null || ed.selK.isEmpty() ->
                listOf(Prop("PICK A TOOL BELOW — TAP THE SHEET TO PLACE", "", false))
            ed.selK == "target" -> listOf(Prop("TARGET — DRAG TO MOVE", "", false))
            ed.selK == "coin" -> listOf(Prop("✕ REMOVE", "del", true))
            else -> {
                val o = it0 as RObs
                when (o.t) {
                    "plank" -> listOf(Prop("⟲", "rl"), Prop("⟳", "rr"),
                        Prop("LEN +", "big"), Prop("LEN −", "sml"), Prop("✕", "del", true))
                    "crate" -> listOf(Prop("⟲", "rl"), Prop("⟳", "rr"),
                        Prop("SIZE +", "big"), Prop("SIZE −", "sml"), Prop("✕", "del", true))
                    "bump" -> listOf(Prop("R +", "big"), Prop("R −", "sml"),
                        Prop("✕", "del", true))
                    "spin" -> listOf(Prop("LEN +", "big"), Prop("LEN −", "sml"),
                        Prop("SPD +", "fst"), Prop("SPD −", "slw"),
                        Prop("REVERSE", "rev"), Prop("✕", "del", true))
                    "move" -> listOf(Prop("AXIS: ${o.axis.uppercase()}", "ax"),
                        Prop("RANGE +", "rng"), Prop("RANGE −", "rng-"),
                        Prop("SPD +", "fst"), Prop("SPD −", "slw"), Prop("✕", "del", true))
                    "wind" -> listOf(Prop("ROTATE DIR", "dir"), Prop("FORCE +", "fst"),
                        Prop("FORCE −", "slw"), Prop("✕", "del", true))
                    "well" -> listOf(Prop("R +", "big"), Prop("R −", "sml"),
                        Prop("PULL +", "fst"), Prop("PULL −", "slw"), Prop("✕", "del", true))
                    "portal" -> listOf(Prop("R +", "big"), Prop("R −", "sml"),
                        Prop("✕ PAIR", "del", true))
                    else -> emptyList()
                }
            }
        }
        val pw = tpx(9f, true)
        var total = 0f
        for (q in bar) total += pw.measureText(q.l) + 22f + 5f
        total -= 5f
        var x = M.W / 2f - total / 2f
        val h = 26f
        for (q in bar) {
            if (q.a.isEmpty()) {
                val mp = tpx(9f, false); mp.alpha = 128
                drawTextC(c, mp, q.l, x + (pw.measureText(q.l) + 22f) / 2f, y + h / 2f)
                mp.alpha = 255
                x += pw.measureText(q.l) + 22f + 5f
                continue
            }
            val w = pw.measureText(q.l) + 22f
            btn(c, x, y, w, h, q.l, "prop", { edAct(q.a) }, warn = q.w)
            x += w + 5f
        }
    }

    /* ================= scene ================= */
    private fun drawSplats(c: Canvas) {
        p.style = Paint.Style.FILL
        for (s in splats) {
            p.color = if (s.acc) ACC else INK
            p.alpha = if (s.acc) 51 else 23
            for (q in s.pts) c.drawCircle(s.x + q[0], s.y + q[1], q[2], p)
        }
        p.alpha = 255
    }
    private fun drawGhost(c: Canvas) {
        p.style = Paint.Style.FILL
        p.color = INK; p.alpha = 28
        for (q in ghostPath) c.drawCircle(q[0], q[1], 1.7f, p)
        p.alpha = 255
    }
    private fun drawHint(c: Canvas) {
        if (hintT <= 0f || hintPts.size < 2) return
        val al = min(1f, hintT / 1.2f) * .75f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        p.color = ACC; p.alpha = (al * 255).toInt()
        p.pathEffect = DashPathEffect(floatArrayOf(2f, 9f), -time * 40f)
        val path = Path()
        path.moveTo(hintPts[0][0], hintPts[0][1])
        for (i in 1 until hintPts.size) path.lineTo(hintPts[i][0], hintPts[i][1])
        c.drawPath(path, p)
        p.pathEffect = null
        val e = hintPts.last()
        c.drawCircle(e[0], e[1], 7f, p)
        p.alpha = 255
    }
    private fun drawWind(c: Canvas) {
        for (o in world.obs) {
            if (o.t != "wind") continue
            p.style = Paint.Style.FILL; p.color = BLUE; p.alpha = 12
            c.drawRect(o.x, o.y, o.x + o.w, o.y + o.h, p)
            c.save(); c.clipRect(o.x, o.y, o.x + o.w, o.y + o.h)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.alpha = 23
            var d = -o.h
            while (d < o.w + o.h) {
                c.drawLine(o.x + d, o.y, o.x + d + o.h, o.y + o.h, p); d += 14f
            }
            val ang = atan2(o.ky, o.kx); val L = hypot(o.w, o.h)
            p.strokeWidth = 2f
            for (i in 0 until 3) {
                val ph = (time * .22f + i / 3f) % 1f
                val off = (ph - .5f) * L * .8f
                val x = o.x + o.w / 2f + cos(ang) * off
                val y = o.y + o.h / 2f + sin(ang) * off
                p.alpha = (sin(ph * Math.PI).toFloat() * 140).toInt().coerceIn(0, 255)
                c.save(); c.translate(x, y); c.rotate(ang * 180f / Math.PI.toFloat())
                c.drawLine(-5f, -5f, 3f, 0f, p); c.drawLine(-5f, 5f, 3f, 0f, p)
                c.restore()
            }
            p.alpha = 128; p.strokeWidth = 1.4f
            p.pathEffect = DashPathEffect(floatArrayOf(3f, 6f), 0f)
            c.drawRect(o.x, o.y, o.x + o.w, o.y + o.h, p)
            p.pathEffect = null; p.alpha = 255
            c.restore()
        }
    }
    private fun drawWells(c: Canvas) {
        for (o in world.obs) {
            if (o.t != "well") continue
            c.save(); c.translate(o.x, o.y)
            p.style = Paint.Style.FILL; p.color = INK; p.alpha = 13
            c.drawCircle(0f, 0f, o.R, p); p.alpha = 255
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f; p.alpha = 90
            p.pathEffect = DashPathEffect(floatArrayOf(3f, 8f), 0f)
            c.drawCircle(0f, 0f, o.R, p)
            p.pathEffect = null; p.alpha = 255
            val rot = time * 1.1f
            p.strokeWidth = 2.2f; p.color = INK
            for (i in 0 until 3) {
                val rr = o.R * (.32f + i * .22f)
                val a0 = rot * (if (i % 2 == 1) -1.3f else 1f) + i * 2.1f
                c.drawArc(-rr, -rr, rr, rr,
                    a0 * 180f / Math.PI.toFloat(), 66f, false, p)
            }
            p.style = Paint.Style.FILL; c.drawCircle(0f, 0f, 5f, p)
            p.style = Paint.Style.STROKE; p.color = ACC; p.strokeWidth = 2f
            c.drawCircle(0f, 0f, 9f, p)
            c.restore(); p.style = Paint.Style.FILL
        }
    }
    private fun drawPortalEnd(c: Canvas, x: Float, y: Float, r: Float, dir: Float) {
        c.save(); c.translate(x, y)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f; p.color = INK
        c.drawCircle(0f, 0f, r, p)
        p.color = ACC; p.strokeWidth = 2.2f
        p.pathEffect = DashPathEffect(floatArrayOf(6f, 7f), -time * 26f * dir)
        c.drawCircle(0f, 0f, r - 5f, p)
        p.pathEffect = null
        val sw = time * 2.4f * dir * 180f / Math.PI.toFloat()
        p.strokeWidth = 1.8f
        c.drawArc(-r * .45f, -r * .45f, r * .45f, r * .45f, sw, 86f, false, p)
        c.drawArc(-r * .45f, -r * .45f, r * .45f, r * .45f, sw + 180f, 86f, false, p)
        p.style = Paint.Style.FILL; p.color = ACC
        c.drawCircle(0f, 0f, 2.6f, p)
        c.restore(); p.style = Paint.Style.FILL
    }
    private fun drawPortals(c: Canvas) {
        for (o in world.obs) {
            if (o.t != "portal") continue
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
            p.color = INK; p.alpha = 31
            p.pathEffect = DashPathEffect(floatArrayOf(2f, 7f), 0f)
            c.drawLine(o.x, o.y, o.bx, o.by, p)
            p.pathEffect = null; p.alpha = 255
            drawPortalEnd(c, o.x, o.y, o.r, 1f)
            drawPortalEnd(c, o.bx, o.by, o.r, -1f)
            p.style = Paint.Style.FILL
        }
    }
    private fun drawCoins(c: Canvas) {
        for (cn in coins) {
            if (cn.got) continue
            val bob = sin(time * 2f + cn.ph) * 3f
            val sq = .4f + .6f * abs(cos(time * 1.6f + cn.ph))
            c.save(); c.translate(cn.x, cn.y + bob); c.scale(sq, 1f)
            p.style = Paint.Style.FILL; p.color = GOLD
            c.drawCircle(0f, 0f, cn.r, p)
            p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 2.4f
            c.drawCircle(0f, 0f, cn.r, p)
            c.drawCircle(0f, 0f, cn.r * .55f, p)
            p.style = Paint.Style.FILL
            c.restore()
        }
    }
    private fun drawObstacles(c: Canvas) {
        for (o in world.obs) {
            if (o.t == "wind" || o.t == "portal" || o.t == "well") continue
            if (o.t == "move") {
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
                p.color = INK; p.alpha = 38
                p.pathEffect = DashPathEffect(floatArrayOf(2f, 7f), 0f)
                if (o.axis == "x") c.drawLine(o.bx0 - o.range - o.w / 2f, o.by0,
                    o.bx0 + o.range + o.w / 2f, o.by0, p)
                else c.drawLine(o.bx0, o.by0 - o.range - o.h / 2f,
                    o.bx0, o.by0 + o.range + o.h / 2f, p)
                p.pathEffect = null; p.alpha = 255
            }
            when (o.t) {
                "spin" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
                    p.color = INK; p.alpha = 36
                    p.pathEffect = DashPathEffect(floatArrayOf(2f, 8f), 0f)
                    c.drawCircle(o.x, o.y, o.len, p)
                    p.pathEffect = null; p.alpha = 255
                    val ca = cos(o.ang); val sa = sin(o.ang)
                    p.strokeWidth = o.th; p.color = INK
                    c.drawLine(o.x - ca * o.len, o.y - sa * o.len,
                        o.x + ca * o.len, o.y + sa * o.len, p)
                    p.style = Paint.Style.FILL; p.color = ACC
                    c.drawCircle(o.x + ca * o.len, o.y + sa * o.len, 4f, p)
                    c.drawCircle(o.x - ca * o.len, o.y - sa * o.len, 4f, p)
                    p.color = PAPER; c.drawCircle(o.x, o.y, 6f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = INK
                    c.drawCircle(o.x, o.y, 6f, p)
                    p.style = Paint.Style.FILL
                }
                "bump" -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
                    p.color = INK; p.alpha = 36
                    p.pathEffect = DashPathEffect(floatArrayOf(2f, 7f), 0f)
                    c.drawCircle(o.x, o.y, o.r + 9f, p)
                    p.pathEffect = null; p.alpha = 255
                    p.style = Paint.Style.FILL; p.color = PAPER
                    c.drawCircle(o.x, o.y, o.r, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3.2f; p.color = INK
                    c.drawCircle(o.x, o.y, o.r, p)
                    p.color = ACC; p.strokeWidth = 2.4f
                    c.drawCircle(o.x, o.y, o.r * .62f, p)
                    p.style = Paint.Style.FILL
                    c.drawCircle(o.x, o.y, o.r * .22f, p)
                    if (o.pulse > .02f) {
                        p.style = Paint.Style.STROKE; p.color = ACC; p.strokeWidth = 3f
                        p.alpha = (o.pulse * 255).toInt()
                        c.drawCircle(o.x, o.y, o.r * (1f + (1f - o.pulse) * .5f), p)
                        p.alpha = 255; p.style = Paint.Style.FILL
                    }
                }
                "crate" -> {
                    c.save(); c.translate(o.x, o.y)
                    c.rotate(o.a * 180f / Math.PI.toFloat())
                    val w2 = o.w / 2f; val h2 = o.h / 2f
                    if (o.dead) {
                        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f
                        p.color = INK; p.alpha = 46
                        p.pathEffect = DashPathEffect(floatArrayOf(3f, 4f), 0f)
                        c.drawRect(-w2, -h2, w2, h2, p)
                        p.pathEffect = null; p.alpha = 255
                    } else {
                        p.style = Paint.Style.FILL; p.color = PAPER
                        c.drawRect(-w2, -h2, w2, h2, p)
                        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = INK
                        c.drawRect(-w2, -h2, w2, h2, p)
                        p.strokeWidth = 1.8f
                        c.drawLine(-w2, -h2, w2, h2, p)
                        c.drawLine(w2, -h2, -w2, h2, p)
                        p.style = Paint.Style.FILL
                        for ((sx, sy) in listOf(-1f to -1f, 1f to -1f,
                                                -1f to 1f, 1f to 1f))
                            c.drawCircle(sx * (w2 - 4f), sy * (h2 - 4f), 1.8f, p)
                    }
                    c.restore()
                }
                else -> { // plank / mover
                    c.save(); c.translate(o.x, o.y)
                    c.rotate(o.a * 180f / Math.PI.toFloat())
                    val w2 = o.w / 2f; val h2 = o.h / 2f
                    p.style = Paint.Style.FILL; p.color = PAPER
                    c.drawRoundRect(-w2, -h2, w2, h2, 4f, 4f, p)
                    c.save(); c.clipRect(-w2, -h2, w2, h2)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
                    p.color = INK; p.alpha = 71
                    var xx = -w2 - o.h
                    while (xx < w2 + o.h) {
                        c.drawLine(xx, -h2 - 3f, xx + o.h, h2 + 3f, p); xx += 9f
                    }
                    p.alpha = 255; c.restore()
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = INK
                    c.drawRoundRect(-w2, -h2, w2, h2, 4f, 4f, p)
                    p.style = Paint.Style.FILL
                    p.color = if (o.t == "move") ACC else INK
                    c.drawCircle(-w2 + 7f, 0f, 2.2f, p)
                    c.drawCircle(w2 - 7f, 0f, 2.2f, p)
                    c.restore()
                }
            }
        }
    }
    private fun drawTarget(c: Canvas) {
        val t = world.tg
        if (t.mx != 0f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
            p.color = INK; p.alpha = 51
            p.pathEffect = DashPathEffect(floatArrayOf(2f, 7f), 0f)
            c.drawLine(t.bx - t.mx - t.r, t.by, t.bx + t.mx + t.r, t.by, p)
            p.pathEffect = null; p.alpha = 255
        }
        c.save(); c.translate(t.x, t.y)
        val pr = 1f + t.pulse * .15f; c.scale(pr, pr)
        val R = t.r
        p.style = Paint.Style.FILL; p.color = PAPER
        c.drawCircle(0f, 0f, R, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = INK
        c.drawCircle(0f, 0f, R, p)
        p.style = Paint.Style.FILL; p.color = ACC
        c.drawCircle(0f, 0f, R * .72f, p)
        p.color = PAPER; c.drawCircle(0f, 0f, R * .45f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = INK
        c.drawCircle(0f, 0f, R * .45f, p)
        p.style = Paint.Style.FILL; p.color = ACC
        c.drawCircle(0f, 0f, R * .16f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = INK
        for (i in 0 until 4) {
            val a2 = i * Math.PI.toFloat() / 2f + Math.PI.toFloat() / 4f
            c.drawLine(cos(a2) * (R + 4f), sin(a2) * (R + 4f),
                cos(a2) * (R + 11f), sin(a2) * (R + 11f), p)
        }
        p.style = Paint.Style.FILL
        c.restore()
    }
    private fun drawArrowBody(c: Canvas, L: Float, col: Int) {
        val s = SC()
        p.color = col
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f * s
        c.drawLine(-L, 0f, -8f * s, 0f, p)
        p.style = Paint.Style.FILL
        val path = Path()
        path.moveTo(0f, 0f); path.lineTo(-10f * s, -5f * s); path.lineTo(-10f * s, 5f * s)
        path.close(); c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * s
        c.drawLine(-L + 2f * s, -1f, -L + 11f * s, -8f * s, p)
        c.drawLine(-L + 2f * s, 1f, -L + 11f * s, 8f * s, p)
        p.style = Paint.Style.FILL
    }
    private fun drawStuck(c: Canvas) {
        val s = SC()
        for (st in stuck) {
            val wob = sin(st.t * 34f) * .14f * exp(-st.t * 2.4f)
            c.save(); c.translate(st.x, st.y)
            c.rotate((st.a + wob) * 180f / Math.PI.toFloat())
            p.alpha = 140
            drawArrowBody(c, 32f * s, INK)
            p.alpha = 255
            c.restore()
        }
    }
    private fun drawArrows(c: Canvas) {
        val s = SC()
        for (a in arrows) {
            val sp = hypot(a.vx, a.vy)
            val col = if (a.pierce > 0 || slowmoT > 0f) ACC else INK
            c.save(); c.translate(a.x, a.y)
            c.rotate(a.ang * 180f / Math.PI.toFloat())
            if (sp > 400f) {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3f * s
                p.color = col; p.alpha = 38
                c.drawLine(-10f * s, 0f, -10f * s - min(sp * .04f, 60f), 0f, p)
                p.alpha = 255
            }
            drawArrowBody(c, 34f * s, col)
            c.restore()
        }
    }
    private fun drawTrails(c: Canvas) {
        val s = SC()
        p.style = Paint.Style.FILL
        for (d in trailDots) {
            p.alpha = ((1f - d.age / d.life) * 77).toInt().coerceIn(0, 255)
            p.color = d.col
            c.drawCircle(d.x, d.y, 2f * s, p)
        }
        p.alpha = 255
    }
    private fun drawBow(c: Canvas) {
        c.save(); c.translate(bow.x, bow.y)
        c.rotate(bow.ang * 180f / Math.PI.toFloat())
        val s = SC(); val Rb = 40f * s; val cx0 = -14f * s
        val tipX = cx0 + Rb * cos(.98f); val tipY = Rb * sin(.98f)
        var nockX = tipX - 6f * s - 26f * s * bow.pull
        if (bow.vib > .02f) nockX = tipX - 6f * s + sin(time * 70f) * 7f * s * bow.vib
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = INK
        c.drawLine(tipX, -tipY, nockX, 0f, p)
        c.drawLine(tipX, tipY, nockX, 0f, p)
        p.strokeWidth = 4.2f
        c.drawArc(cx0 - Rb, -Rb, cx0 + Rb, Rb, -56f, 112f, false, p)
        p.color = ACC; p.strokeWidth = 6f
        c.drawLine(cx0 + Rb, -8f * s, cx0 + Rb, 8f * s, p)
        if (mode == "play" && arrowsLeft > 0 && bow.vib < .5f) {
            c.save(); c.translate(nockX + 44f * s, 0f)
            drawArrowBody(c, 44f * s, if (specialReady()) ACC else INK)
            c.restore()
        }
        p.style = Paint.Style.FILL
        c.restore()
    }
    private fun drawGauge(c: Canvas) {
        if (mode != "play" || !aim.on || aim.p <= .03f) return
        val s = SC()
        val w = 64f * s; val h = 7f * s
        val gx = bow.x - w / 2f; val gy = bow.y - 74f * s
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f
        p.color = INK; p.alpha = 140
        c.drawRect(gx, gy, gx + w, gy + h, p); p.alpha = 255
        p.style = Paint.Style.FILL
        p.color = if (specialReady()) ACC else INK
        c.drawRect(gx + 2f, gy + 2f, gx + 2f + (w - 4f) * aim.p, gy + h - 2f, p)
        val tp = tpx(8f, true)
        tp.textAlign = Paint.Align.CENTER; tp.alpha = 128
        c.drawText("PULL", bow.x, gy - 4f, tp)
        tp.alpha = 255; tp.textAlign = Paint.Align.LEFT
    }
    private fun drawPreview(c: Canvas) {
        if (!(aim.on && aim.p > .05f && mode == "play")) return
        val s = SC()
        val spd = lerp(.45f, 1f, aim.p.pow(.9f)) * M.vmax
        val sim = simulate(aim.dx, aim.dy, spd)
        val col = if (specialReady()) ACC else INK
        p.style = Paint.Style.FILL
        for ((i, q) in sim.pts.withIndex()) {
            val al = 1f - i.toFloat() / sim.pts.size
            p.alpha = ((.15f + .5f * al) * 255).toInt()
            p.color = col
            c.drawCircle(q[0], q[1], 2.4f * s * (.6f + .4f * al), p)
        }
        p.alpha = 255
        if (sim.hit) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = col
            c.drawCircle(sim.ex, sim.ey, 8f * s, p)
            p.style = Paint.Style.FILL
        }
    }
    private fun drawFX(c: Canvas) {
        for (sp in sparks) {
            val al = (1f - sp.age / sp.life).coerceIn(0f, 1f)
            p.alpha = (al * 255).toInt(); p.color = sp.col
            when (sp.kind) {
                0 -> { c.save(); c.translate(sp.x, sp.y)
                    c.rotate(sp.rot * 180f / Math.PI.toFloat())
                    p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                    c.drawLine(-sp.s, 0f, sp.s, 0f, p); c.restore() }
                1 -> { c.save(); c.translate(sp.x, sp.y)
                    c.rotate(sp.rot * 180f / Math.PI.toFloat())
                    val path = Path()
                    path.moveTo(sp.s, 0f); path.lineTo(-sp.s * .7f, sp.s * .75f)
                    path.lineTo(-sp.s * .7f, -sp.s * .75f); path.close()
                    c.drawPath(path, p); c.restore() }
                else -> c.drawCircle(sp.x, sp.y, sp.s * .6f, p)
            }
        }
        p.style = Paint.Style.FILL
        for (r in rings) {
            val q = (r.age / r.life).coerceIn(0f, 1f)
            p.alpha = ((1f - q) * 140).toInt().coerceIn(0, 255)
            p.style = Paint.Style.STROKE; p.color = r.col
            p.strokeWidth = r.lw * (1f - q) + .5f
            c.drawCircle(r.x, r.y, lerp(r.r0, r.r1, 1f - (1f - q).pow(3f)), p)
        }
        p.style = Paint.Style.FILL; p.alpha = 255
        for (f in floats) {
            val q = (f.age / f.life).coerceIn(0f, 1f)
            p.alpha = ((1f - q * q) * 255).toInt()
            val fp = tpx(f.size, true); fp.color = f.col
            fp.textAlign = Paint.Align.CENTER
            c.drawText(f.txt, f.x, f.y, fp)
            fp.textAlign = Paint.Align.LEFT
        }
        p.alpha = 255
    }
    private fun drawQuiver(c: Canvas) {
        if (mode != "play") return
        if (arrowsLeft <= 0) {
            val al = (.5f + .4f * sin(time * 5f)).coerceIn(0f, 1f)
            val qp = tpx(11f, true)
            qp.color = ACC; qp.alpha = (al * 255).toInt()
            c.drawText("LAST ARROW AWAY", 28f, M.H - 28f, qp)
            qp.alpha = 255
            return
        }
        val n = min(arrowsLeft, 8)
        for (i in 0 until n) miniArrow(c, 30f + i * 22f, M.H - 32f)
        if (arrowsLeft > 8) {
            val qp = tpx(11f, true); qp.alpha = 217
            c.drawText("+${arrowsLeft - 8}", 30f + n * 22f + 4f, M.H - 28f, qp)
            qp.alpha = 255
        }
    }
    private fun miniArrow(c: Canvas, x: Float, y: Float) {
        c.save(); c.translate(x, y)
        p.color = INK; p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        c.drawLine(-8f, 0f, 5f, 0f, p)
        val path = Path()
        path.moveTo(10f, 0f); path.lineTo(4f, -3.6f); path.lineTo(4f, 3.6f); path.close()
        c.drawPath(path, p)
        c.drawLine(-1f, 0f, -5f, -4f, p)
        c.drawLine(-1f, 0f, -5f, 4f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
    private fun drawCharge(c: Canvas) {
        if (mode != "play") return
        val gx = 28f; val gy = M.H - 56f
        if (specialReady()) {
            val al = (.75f + .25f * sin(time * 5f)).coerceIn(0f, 1f)
            c.save(); c.translate(gx + 8f, gy); c.rotate(45f)
            p.style = Paint.Style.FILL; p.color = ACC; p.alpha = (al * 255).toInt()
            c.drawRect(-6f, -6f, 6f, 6f, p); p.alpha = 255
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.color = INK
            c.drawRect(-6f, -6f, 6f, 6f, p)
            c.restore()
            val tp = tpx(9f, true); tp.color = ACC
            c.drawText("PIERCE ARMED", gx + 24f, gy + 3f, tp)
        } else {
            for (i in 0 until CHARGE_MAX) {
                p.style = Paint.Style.FILL
                if (i < charge) { p.color = GOLD; c.drawCircle(gx + i * 14f, gy, 4.2f, p) }
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f
                p.color = INK; p.alpha = 102
                c.drawCircle(gx + i * 14f, gy, 4.2f, p); p.alpha = 255
                p.style = Paint.Style.FILL
            }
        }
    }

    /* ================= HUD ================= */
    private fun drawHUD(c: Canvas) {
        val wp = tpx(21f, true)
        c.drawText("RICOCHET®", 22f, 34f, wp)
        val cp = tpx(10f, false); cp.alpha = 140
        c.drawText(when {
            mode == "menu" -> "BANK-SHOT ARCHERY"
            mode == "editor" -> "WORKSHOP"
            gameMode == "daily" -> "DAILY PUZZLE"
            gameMode == "custom" -> "WORKSHOP · PLAYTEST"
            gameMode == "tour" -> "TOUR Nº${pad2(courseNo)}"
            else -> "INFINITE · DEPTH ${pad2(courseNo)}"
        }, 22f, 52f, cp)
        cp.alpha = 255
        val sp = tpx(11f, false); sp.alpha = 115
        c.drawText("SCORE", M.W - 190f, 30f, sp)
        c.drawText("BEST", M.W - 120f, 30f, sp)
        sp.alpha = 255; sp.typeface = tfBig
        c.drawText("$score", M.W - 190f, 48f, sp)
        c.drawText("${max(best, score)}", M.W - 120f, 48f, sp)
        if (mode == "play" && !paused)
            hudBtn(c, M.W - 52f, 40f, "hint") { showHint() }
        if (mode == "play")
            hudBtn(c, M.W - 100f, 40f, "pause") { togglePause() }
        hudBtn(c, M.W - 148f, 40f, if (Synth.muted) "mute-off" else "mute-on") {
            Synth.muted = !Synth.muted
            Ps("ric_muted", if (Synth.muted) "1" else "0")
        }
    }

    /* ================= overlays ================= */
    private fun drawMenu(c: Canvas) {
        dim(c)
        kicker(c, "A BANK-SHOT ARCHERY PUZZLE GAME", M.W / 2f, M.H * .075f)
        val hp = tpx(min(M.W * .13f, 60f * max(M.K, 1f)), true)
        hp.textAlign = Paint.Align.CENTER
        c.drawText("RICOCHET", M.W / 2f, M.H * .155f, hp)
        hp.textAlign = Paint.Align.LEFT
        val rp = tpx(12f, false); rp.alpha = 190
        rp.textAlign = Paint.Align.CENTER
        val rules = listOf(
            "Drag back, release, bank into the target.",
            "Coins arm a piercing arrow · wells bend · wind pushes ·",
            "portals fold · crates shatter. Build your own in the workshop.")
        var ry = M.H * .205f
        for (lineTxt in rules) { c.drawText(lineTxt, M.W / 2f, ry, rp); ry += 17f }
        rp.alpha = 255; rp.textAlign = Paint.Align.LEFT
        // mode cards
        val cw = min(210f * max(M.K, .9f), M.W * .46f)
        val chh = 148f * max(M.K, .9f)
        val stacked = M.W < cw * 2f + 48f
        val cy0 = M.H * .30f
        val cleared = tourStars.count { it > 0 }
        for (idx in 0..1) {
            val x0 = if (stacked) M.W / 2f - cw / 2f
                     else (if (idx == 0) M.W / 2f - cw - 10f else M.W / 2f + 10f)
            val y0 = if (stacked) cy0 + idx * (chh + 14f) else cy0
            p.style = Paint.Style.FILL; p.color = INK
            c.drawRoundRect(x0 + 5f, y0 + 5f, x0 + cw + 5f, y0 + chh + 5f, 4f, 4f, p)
            p.color = PAPER
            c.drawRoundRect(x0, y0, x0 + cw, y0 + chh, 4f, 4f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
            c.drawRoundRect(x0, y0, x0 + cw, y0 + chh, 4f, 4f, p)
            p.style = Paint.Style.FILL
            val tp = tpx(21f, true); tp.textAlign = Paint.Align.CENTER
            c.drawText(if (idx == 0) "THE TOUR" else "INFINITE",
                x0 + cw / 2f, y0 + 26f, tp)
            val sp2 = tpx(9f, false); sp2.alpha = 140
            sp2.textAlign = Paint.Align.CENTER
            if (idx == 0) {
                c.drawText(when {
                    cleared == 0 -> "20 HANDCRAFTED PUZZLES"
                    cleared < 20 -> "${tourStarSum()}/60 STARS COLLECTED"
                    else -> "ALL 60 STARS — FLAWLESS"
                }, x0 + cw / 2f, y0 + 44f, sp2)
                val pipS = 11f * max(M.K, .75f)
                for (i in 0 until 20) {
                    val row = i / 10; val col2 = i % 10
                    val px = x0 + cw / 2f - (10 * (pipS + 5f) - 5f) / 2f +
                             col2 * (pipS + 5f)
                    val py = y0 + 56f + row * (pipS + 5f)
                    c.save(); c.translate(px + pipS / 2f, py + pipS / 2f); c.rotate(45f)
                    if (tourStars[i] > 0) {
                        p.style = Paint.Style.FILL
                        p.color = if (tourStars[i] >= 3) GOLD else ACC
                        c.drawRect(-pipS / 2f, -pipS / 2f, pipS / 2f, pipS / 2f, p)
                    }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = INK
                    p.alpha = if (tourStars[i] > 0) 255 else 66
                    c.drawRect(-pipS / 2f, -pipS / 2f, pipS / 2f, pipS / 2f, p)
                    p.alpha = 255; p.style = Paint.Style.FILL
                    c.restore()
                }
                val gp = tpx(11f, true); gp.color = ACC; gp.textAlign = Paint.Align.CENTER
                c.drawText(if (cleared >= 20) "REPLAY TOUR"
                           else "BEGIN — Nº${pad2(savedLevel.coerceIn(1, 20))}",
                    x0 + cw / 2f, y0 + chh - 14f, gp)
                gp.textAlign = Paint.Align.LEFT
                ui.add(UiB(RectF(x0, y0, x0 + cw, y0 + chh)) { startTour(0) })
            } else {
                c.drawText(if (infBest > 0) "BEST DEPTH — ${pad2(infBest)}"
                           else "ENDLESS · SOLVER-VERIFIED",
                    x0 + cw / 2f, y0 + 44f, sp2)
                for ((k, th) in listOf(5, 10, 20).withIndex()) {
                    val px = x0 + cw / 2f - 28f + k * 19f; val py = y0 + 62f
                    c.save(); c.translate(px + 5.5f, py + 5.5f); c.rotate(45f)
                    if (infBest >= th) {
                        p.style = Paint.Style.FILL; p.color = ACC
                        c.drawRect(-5.5f, -5.5f, 5.5f, 5.5f, p)
                    }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = INK
                    c.drawRect(-5.5f, -5.5f, 5.5f, 5.5f, p)
                    p.style = Paint.Style.FILL
                    c.restore()
                }
                val gp = tpx(11f, true); gp.color = ACC; gp.textAlign = Paint.Align.CENTER
                c.drawText("DESCEND — DEPTH 01", x0 + cw / 2f, y0 + chh - 14f, gp)
                gp.textAlign = Paint.Align.LEFT
                ui.add(UiB(RectF(x0, y0, x0 + cw, y0 + chh)) { startInfinite(false) })
            }
            sp2.alpha = 255; sp2.textAlign = Paint.Align.LEFT
            tp.textAlign = Paint.Align.LEFT
        }
        // pill row (flows onto a second line when narrow)
        val pills = listOf(
            "PUZZLE OF THE DAY — ${dailyLabel()}" to { startDaily() },
            "WORKSHOP" to { openEditor() },
            "PUZZLE INDEX" to { showMap = true },
            "STUDIO LOG" to { showStats = true })
        val pw = tpx(10f, true)
        val pwds = pills.map { pw.measureText(it.first) + 30f }
        val maxY = if (stacked) cy0 + 2 * chh + 22f else cy0 + chh + 22f
        var px = M.W / 2f
        var py = maxY
        for ((i, pill) in pills.withIndex()) {
            val w = pwds[i]
            if (px + w / 2f > M.W - 12f) { px = M.W / 2f; py += 44f }
            btn(c, px - w / 2f, py, w, 36f, pill.first, "pill") { pill.second() }
            px += w / 2f + 10f
        }
        val bl = tpx(11f, false); bl.alpha = 128; bl.textAlign = Paint.Align.CENTER
        c.drawText("BEST — $best   ·   STARS — ${tourStarSum()}" +
                   "   ·   STAMPS — ${ach.size}/${ACH.size}",
            M.W / 2f, py + 62f, bl)
        bl.alpha = 255; bl.textAlign = Paint.Align.LEFT
        btn(c, M.W / 2f - 80f, py + 74f, 160f, 28f,
            if (resetArm) "TAP AGAIN TO CONFIRM" else "RESET PROGRESS", "prop",
            warn = true) {
            if (resetArm) {
                Pi("ric_level", 1); Pi("ric_best", 0); Pi("ric_infbest", 0)
                Ps("ric_ach", ""); Ps("ric_stats", JSONObject().toString())
                for (i in 0 until 20) tourStars[i] = 0
                saveTour()
                best = 0; savedLevel = 1; infBest = 0; ach.clear()
                stats = Stats(); resetArm = false
            } else { resetArm = true; resetArmT = 2.5f }
        }
    }

    private fun drawMap(c: Canvas) {
        dim(c)
        val cw = min(M.W * .92f, 440f * max(M.K, .9f))
        val chh = min(M.H * .8f, 350f * max(M.K, .9f))
        val r = card(c, cw, chh)
        kicker(c, "THE TOUR — PUZZLE INDEX", r.centerX(), r.top + 26f)
        val cols = 5
        val tw = (cw - 60f - (cols - 1) * 9f) / cols
        val th = tw * 1.05f
        for (i in 0 until 20) {
            val row = i / cols; val col = i % cols
            val x = r.left + 30f + col * (tw + 9f)
            val y = r.top + 42f + row * (th + 9f)
            val unlocked = (i + 1) <= savedLevel
            p.style = Paint.Style.FILL; p.color = PAPER
            c.drawRect(x, y, x + tw, y + th, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = INK
            p.alpha = if (unlocked) 255 else 64
            c.drawRect(x, y, x + tw, y + th, p)
            val np = tpx(15f, true); np.textAlign = Paint.Align.CENTER
            np.alpha = p.alpha
            c.drawText(pad2(i + 1), x + tw / 2f, y + th / 2f + 2f, np)
            np.textAlign = Paint.Align.LEFT; np.alpha = 255
            for (k in 0 until 3) {
                p.style = Paint.Style.FILL
                p.color = if (k < tourStars[i]) GOLD else INK
                p.alpha = if (k < tourStars[i]) 255 else 77
                c.drawCircle(x + tw / 2f - 8f + k * 8f, y + th - 8f, 2.5f, p)
            }
            p.alpha = 255; p.style = Paint.Style.FILL
            if (unlocked) ui.add(UiB(RectF(x, y, x + tw, y + th)) {
                showMap = false; startTour(i + 1)
            })
        }
        val fp = tpx(10f, false); fp.alpha = 140; fp.textAlign = Paint.Align.CENTER
        c.drawText("${tourStarSum()} / 60 STARS — CLEAR A PUZZLE TO UNLOCK THE NEXT",
            r.centerX(), r.bottom - 44f, fp)
        fp.alpha = 255; fp.textAlign = Paint.Align.LEFT
        btn(c, r.centerX() - 60f, r.bottom - 38f, 120f, 30f, "CLOSE", "sec") {
            showMap = false
        }
    }

    private fun drawStats(c: Canvas) {
        dim(c)
        val r = card(c, min(M.W * .92f, 420f * M.K), 400f * M.K)
        kicker(c, "STUDIO LOG — LIFETIME", r.centerX(), r.top + 28f)
        val rows = listOf("ARROWS LOOSED" to stats.shots, "BULLSEYES" to stats.hits,
            "BANKS" to stats.banks, "COINS TAKEN" to stats.coins,
            "PLANKS PIERCED" to stats.pierces, "CRATES SMASHED" to stats.crates,
            "PORTALS USED" to stats.portals, "CHALK LINES" to stats.hints,
            "BEST DEPTH" to infBest, "DAILY CLEARS" to stats.dailys)
        var y = r.top + 54f
        for ((i, rr) in rows.withIndex()) {
            val tp = tpx(11.5f, i == rows.lastIndex)
            c.drawText(rr.first, r.left + 34f, y, tp)
            tp.textAlign = Paint.Align.RIGHT
            if (i == rows.lastIndex) tp.color = ACC
            c.drawText("${rr.second}", r.right - 34f, y, tp)
            tp.textAlign = Paint.Align.LEFT; tp.color = INK
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = INK; p.alpha = 64
            p.pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
            c.drawLine(r.left + 30f, y + 7f, r.right - 30f, y + 7f, p)
            p.pathEffect = null; p.alpha = 255
            y += 26f * max(M.K, .85f)
        }
        btn(c, r.centerX() - 60f, r.bottom - 40f, 120f, 32f, "CLOSE", "sec") {
            showStats = false
        }
    }

    private fun drawSlots(c: Canvas) {
        dim(c)
        val r = card(c, min(M.W * .92f, 440f * M.K), 420f * M.K)
        kicker(c, "WORKSHOP — BUILD SLOTS", r.centerX(), r.top + 26f)
        var y = r.top + 46f
        val rowH = min(30f, (r.height() - 130f) / 8f - 6f)
        for (i in 0 until 8) {
            val s = slots[i]
            p.style = Paint.Style.FILL; p.color = PAPER
            c.drawRect(r.left + 22f, y, r.right - 22f, y + rowH, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = INK
            c.drawRect(r.left + 22f, y, r.right - 22f, y + rowH, p)
            p.style = Paint.Style.FILL
            val bp = tpx(10f, false); bp.alpha = 115
            c.drawText(pad2(i + 1), r.left + 30f, y + rowH / 2f + 4f, bp); bp.alpha = 255
            c.drawText(s?.let { "${it.first} · ${it.second.obs.size} obj" }
                       ?: "— empty —",
                r.left + 58f, y + rowH / 2f + 4f, bp)
            if (s != null) {
                val bw = 52f; val bh = rowH - 8f
                btn(c, r.right - 24f - bw * 3f - 12f, y + 4f, bw, bh, "LOAD", "prop") {
                    pushUndo(); ed.def = s.second.cpy()
                    ed.selK = ""; ed.selI = -1; ed.pending = false
                    edSync(); showSlots = false; Sfx.ui()
                }
                btn(c, r.right - 24f - bw * 2f - 8f, y + 4f, bw, bh, "SHARE", "prop") {
                    shareCode = encodeDef(s.second); showSlots = false; showShare = true
                }
                btn(c, r.right - 24f - bw, y + 4f, bw, bh, "✕", "prop", warn = true) {
                    slots[i] = null; saveSlots(); Sfx.thunk()
                }
            }
            y += rowH + 6f
        }
        btn(c, r.centerX() - 154f, r.bottom - 44f, 144f, 32f, "SAVE CURRENT", "sec") {
            val idx = slots.indexOfFirst { it == null }
            if (idx < 0) edFloat("SLOTS FULL — DELETE ONE OR SHARE")
            else {
                slots[idx] = ed.def!!.name.ifEmpty { "BUILD ${idx + 1}" } to ed.def!!.cpy()
                saveSlots(); Sfx.place()
            }
        }
        btn(c, r.centerX() + 10f, r.bottom - 44f, 144f, 32f, "IMPORT A CODE", "sec") {
            importDialog()
        }
        btn(c, r.centerX() - 60f, r.bottom - 86f, 120f, 28f, "CLOSE", "sec") {
            showSlots = false
        }
    }

    private fun drawShare(c: Canvas) {
        dim(c)
        val r = card(c, min(M.W * .92f, 440f * M.K), 320f * M.K)
        kicker(c, "PUZZLE CODE — COPY TO SHARE, PASTE TO IMPORT",
            r.centerX(), r.top + 26f)
        val tp = tpx(10f, false)
        var line = ""; var y = r.top + 52f
        for (chch in shareCode) {
            line += chch
            if (tp.measureText(line) > r.width() - 68f) {
                c.drawText(line, r.left + 34f, y, tp); y += 15f; line = ""
            }
        }
        if (line.isNotEmpty()) { c.drawText(line, r.left + 34f, y, tp); y += 15f }
        btn(c, r.centerX() - 160f, r.bottom - 92f, 150f, 32f, "COPY", "sec") {
            copyToClip("RICOCHET PUZZLE", shareCode)
            floats.add(FloatT(M.W / 2f, M.H * .3f, "COPIED ✓", INK, 12f))
        }
        btn(c, r.centerX() + 10f, r.bottom - 92f, 150f, 32f, "IMPORT ANOTHER", "sec") {
            importDialog()
        }
        btn(c, r.centerX() - 60f, r.bottom - 46f, 120f, 30f, "CLOSE", "sec") {
            showShare = false
        }
    }

    private fun drawPause(c: Canvas) {
        dim(c)
        val r = card(c, min(M.W * .88f, 340f * M.K), min(M.H * .86f, 430f * M.K))
        kicker(c, "INTERMISSION", r.centerX(), r.top + 34f)
        val hp = tpx(38f, true); hp.textAlign = Paint.Align.CENTER
        c.drawText("PAUSED", r.centerX(), r.top + 80f, hp)
        hp.textAlign = Paint.Align.LEFT
        var y = r.top + 108f
        btn(c, r.centerX() - 100f, y, 200f, 40f, "RESUME", "cta") { togglePause() }
        y += 52f
        btn(c, r.centerX() - 100f, y, 200f, 34f, "SHOW A LINE (HINT)", "sec") {
            paused = false; showHint()
        }; y += 42f
        btn(c, r.centerX() - 100f, y, 200f, 34f, "RESTART PUZZLE", "sec") {
            paused = false; setCourse(courseNo); mode = "play"; Sfx.ui()
        }; y += 42f
        btn(c, r.centerX() - 100f, y, 200f, 34f,
            if (gameMode == "custom") "BACK TO WORKSHOP" else "PUZZLE INDEX", "sec") {
            paused = false
            if (gameMode == "custom") openEditor()
            else { goMenu(); showMap = true }
        }; y += 42f
        btn(c, r.centerX() - 100f, y, 200f, 34f,
            "MOTION: ${if (calm) "CALM" else "FULL"}", "sec") {
            calm = !calm; Ps("ric_calm", if (calm) "1" else "0")
        }; y += 42f
        btn(c, r.centerX() - 100f, y, 200f, 34f, "QUIT TO MENU", "sec") { goMenu() }
    }

    private fun drawWin(c: Canvas) {
        dim(c)
        val cd = clearData ?: return
        val tourDone = gameMode == "tour" && courseNo >= 20
        val r = card(c, min(M.W * .88f, 380f * M.K), 440f * M.K)
        if (newBest) {
            c.save(); c.translate(r.right - 8f, r.top + 6f); c.rotate(7f)
            p.style = Paint.Style.FILL; p.color = ACC
            c.drawRect(-52f, -14f, 52f, 14f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = INK
            c.drawRect(-52f, -14f, 52f, 14f, p)
            p.style = Paint.Style.FILL
            val bp = tpx(10f, true); bp.color = PAPER; bp.textAlign = Paint.Align.CENTER
            c.drawText("NEW BEST", 0f, 4f, bp); bp.textAlign = Paint.Align.LEFT
            c.restore()
        }
        kicker(c, when {
            tourDone -> "THE TOUR — COMPLETE"
            gameMode == "daily" -> "PUZZLE OF THE DAY — CLEAR"
            gameMode == "custom" -> "PLAYTEST — CLEAR"
            else -> "PUZZLE Nº${pad2(courseNo)} — CLEAR"
        }, r.centerX(), r.top + 30f)
        val hp = tpx(34f, true); hp.textAlign = Paint.Align.CENTER
        c.drawText(when {
            gameMode == "custom" -> "IT WORKS!"
            tourDone -> "TWENTY FOR TWENTY"
            cd.bank >= 2 -> "TRICK SHOT"
            else -> "BULLSEYE"
        }, r.centerX(), r.top + 74f, hp)
        hp.textAlign = Paint.Align.LEFT
        for (i in 0 until 3) {
            val t = ((time - winShownAt) - i * .12f).coerceIn(0f, .45f) / .45f
            val sc = if (t <= 0f) .01f
                     else (1f + 2.70158f * (t - 1f).pow(3f) +
                           1.70158f * (t - 1f).pow(2f)).coerceAtLeast(.01f)
            val on = i < cd.stars
            p.style = Paint.Style.FILL
            p.color = if (on) ACC else INK
            p.alpha = if (on) 255 else 46
            c.save()
            c.translate(r.centerX() - 52f + i * 52f, r.top + 118f)
            c.scale(sc, sc)
            c.drawPath(starPath(0f, 0f, 17f), p)
            c.restore(); p.alpha = 255
        }
        var y = r.top + 162f
        val rows = listOf(
            "SHOTS USED" to "${cd.shots}",
            "BANK BONUS" to (if (cd.bank > 0) "×${cd.bank} · +${cd.bank * 100}" else "—"),
            "COIN CHAIN" to "×${cd.chain}",
            "ARROWS SPARED" to "+${cd.bonus}",
            "SCORE" to "+${cd.pts + cd.bonus + cd.db}",
            "TOTAL" to "$score")
        for ((i, rr) in rows.withIndex()) {
            val tp = tpx(11.5f, i >= rows.size - 2)
            c.drawText(rr.first, r.left + 38f, y, tp)
            tp.textAlign = Paint.Align.RIGHT
            if (i == rows.lastIndex) tp.color = ACC
            c.drawText(rr.second, r.right - 38f, y, tp)
            tp.textAlign = Paint.Align.LEFT; tp.color = INK
            p.style = Paint.Style.STROKE; p.color = INK
            p.strokeWidth = if (i >= rows.size - 2) 2f else 1f
            p.alpha = if (i >= rows.size - 2) 255 else 64
            if (i < rows.size - 1) p.pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
            c.drawLine(r.left + 34f, y + 7f, r.right - 34f, y + 7f, p)
            p.pathEffect = null; p.alpha = 255; p.style = Paint.Style.FILL
            y += 25f * max(M.K, .85f)
        }
        btn(c, r.centerX() - 110f, r.bottom - 48f, 220f, 38f, when {
            gameMode == "custom" -> "BACK TO WORKSHOP"
            tourDone -> "ENTER INFINITE"
            gameMode == "daily" -> "BACK TO MENU"
            gameMode == "tour" -> "NEXT PUZZLE"
            else -> "DESCEND — Nº${pad2(courseNo + 1)}"
        }, "cta") {
            winShown = false
            slowmoT = 0f; punch = 0f
            when {
                gameMode == "custom" -> openEditor()
                gameMode == "daily" -> goMenu()
                tourDone -> startInfinite(true)
                else -> { setCourse(courseNo + 1); mode = "play"; Sfx.ui() }
            }
        }
    }

    private fun drawLose(c: Canvas) {
        dim(c)
        val isInf = gameMode == "inf"
        val isCus = gameMode == "custom"
        val r = card(c, min(M.W * .88f, 380f * M.K), 400f * M.K)
        kicker(c, when {
            isCus -> "PLAYTEST — DRY"
            isInf -> "THE PIT CLAIMS YOUR QUIVER"
            else -> "OUT OF ARROWS"
        }, r.centerX(), r.top + 30f)
        val hp = tpx(30f, true); hp.textAlign = Paint.Align.CENTER
        c.drawText(when {
            isCus -> "NEEDS TUNING"
            isInf -> "RUN OVER"
            else -> "QUIVER EMPTY"
        }, r.centerX(), r.top + 72f, hp)
        hp.textAlign = Paint.Align.LEFT
        val rp = tpx(12f, false); rp.alpha = 190; rp.textAlign = Paint.Align.CENTER
        val msg = when {
            isCus -> listOf("The build held out. Check the solver chip,",
                            "loosen a lane — or accept it as a hard one.")
            isInf -> listOf("Depth ${pad2(courseNo)} is where the arrows ran dry.",
                            "Every run, new puzzles.")
            gameMode == "daily" -> listOf("Today's puzzle holds. Retry — the layout is",
                                          "the same for everyone, all day.")
            else -> listOf("Your misses are inked on the sheet. Bank off",
                           "the walls — or ask for a chalked line.")
        }
        var yy = r.top + 102f
        for (lineTxt in msg) { c.drawText(lineTxt, r.centerX(), yy, rp); yy += 17f }
        rp.alpha = 255; rp.textAlign = Paint.Align.LEFT
        var gy = yy + 12f
        val grid = mutableListOf<Pair<String, String>>(
            (if (isInf) "RUN SCORE" else "SCORE") to "$score",
            when {
                isInf -> "DEPTH" to pad2(courseNo)
                gameMode == "daily" -> "ATTEMPT" to pad2(courseNo)
                else -> "PUZZLE" to pad2(courseNo)
            })
        if (isInf) grid.add("BEST DEPTH" to (if (infBest > 0) pad2(infBest) else "—"))
        val gw = (r.width() - 60f) / grid.size
        for ((i, gpair) in grid.withIndex()) {
            val gx = r.left + 30f + i * gw + gw / 2f
            val kp2 = tpx(10f, false); kp2.alpha = 115; kp2.textAlign = Paint.Align.CENTER
            c.drawText(gpair.first, gx, gy + 10f, kp2); kp2.alpha = 255
            val vp2 = tpx(26f, true)
            if (i == 0) vp2.color = ACC
            vp2.textAlign = Paint.Align.CENTER
            c.drawText(gpair.second, gx, gy + 42f, vp2)
            vp2.textAlign = Paint.Align.LEFT; vp2.color = INK
        }
        gy += 64f
        btn(c, r.centerX() - 110f, gy, 220f, 38f, when {
            isCus -> "RETRY TEST"
            isInf -> "NEW RUN"
            gameMode == "daily" -> "RETRY DAILY"
            else -> "RETRY PUZZLE"
        }, "cta") {
            loseShown = false
            slowmoT = 0f; punch = 0f
            if (isInf || isCus) { score = 0; charge = 0f; setCourse(1) }
            else if (gameMode == "daily") setCourse(1)
            else setCourse(courseNo)
            mode = "play"; Sfx.ui()
        }
        if (isCus) btn(c, r.centerX() - 110f, gy + 46f, 220f, 30f,
            "BACK TO WORKSHOP", "sec") { loseShown = false; openEditor() }
        if (isInf) btn(c, r.centerX() - 110f, gy + 46f, 220f, 30f, "COPY RESULT", "sec") {
            copyToClip("RICOCHET",
                "RICOCHET — INFINITE\nDepth ${pad2(courseNo)} · $score PTS · " +
                "Best depth ${pad2(infBest)}\n${dailyLabel()} ${java.time.LocalDate.now().year}")
            floats.add(FloatT(M.W / 2f, M.H * .3f, "COPIED ✓", INK, 12f))
        }
    }

    private fun drawToast(c: Canvas) {
        if (!toastOn || toastT <= 0f) return
        val t = ((2.3f - toastT) / .35f).coerceIn(0f, 1f)
        val slide = (1f - t) * 40f * SC()
        val w = 224f * max(M.K, .9f); val h = 64f * max(M.K, .9f)
        val x = M.W - w - 20f + slide; val y = 74f
        c.save()
        c.translate(x + w / 2f, y + h / 2f); c.rotate(3f)
        c.translate(-(x + w / 2f), -(y + h / 2f))
        p.style = Paint.Style.FILL; p.color = INK
        c.drawRoundRect(x + 5f, y + 5f, x + w + 5f, y + h + 5f, 4f, 4f, p)
        p.color = PAPER
        c.drawRoundRect(x, y, x + w, y + h, 4f, 4f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f; p.color = INK
        c.drawRoundRect(x, y, x + w, y + h, 4f, 4f, p)
        p.style = Paint.Style.FILL
        val kp2 = tpx(8.5f, true)
        kp2.color = ACC; kp2.letterSpacing = .26f
        c.drawText("STAMP EARNED", x + 16f, y + 18f, kp2)
        kp2.letterSpacing = 0f
        val np2 = tpx(16f, true)
        c.drawText(toastName, x + 16f, y + 38f, np2)
        val dp2 = tpx(9.5f, false); dp2.alpha = 140
        c.drawText(toastDesc, x + 16f, y + 54f, dp2)
        dp2.alpha = 255
        c.restore()
    }
}

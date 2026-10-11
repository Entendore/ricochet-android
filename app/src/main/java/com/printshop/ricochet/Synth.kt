package com.printshop.ricochet

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

object Synth {
    const val SR = 44100
    var muted = false
    private val handler = Handler(Looper.getMainLooper())
    private val TAU = (2.0 * Math.PI).toFloat()
    private val live = AtomicInteger(0)

    fun tone(freq: Float, dur: Float, type: String, vol: Float,
             slideTo: Float = 0f, delay: Float = 0f) {
        if (muted) return
        val n = (dur * SR).toInt().coerceAtLeast(1)
        val buf = ShortArray(n)
        val f0 = freq.coerceAtLeast(20f)
        val f1 = (if (slideTo > 0f) slideTo else freq).coerceAtLeast(20f)
        val atk = (0.008f * SR).toInt().coerceIn(1, n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val f = f0 * (f1 / f0).pow(t / dur)
            phase += TAU * f / SR
            if (phase > TAU) phase -= TAU
            val pph = phase / TAU
            val wv = when (type) {
                "square" -> if (pph < 0.5f) 1f else -1f
                "saw"    -> 2f * pph - 1f
                "sine"   -> sin(phase)
                else -> if (pph < 0.25f) 4f * pph
                        else if (pph < 0.75f) 2f - 4f * pph
                        else 4f * pph - 4f        // triangle
            }
            var env = if (i < atk) i.toFloat() / atk
                      else ((n - i).toFloat() / (n - atk)).pow(1.6f)
            env = env.coerceIn(0f, 1f)
            buf[i] = (wv * env * vol * 0.5f * 32767f).toInt().toShort()
        }
        play(buf, delay)
    }

    fun noise(dur: Float, freq: Float, vol: Float, kind: String, delay: Float = 0f) {
        if (muted) return
        val n = (dur * SR).toInt().coerceAtLeast(1)
        val buf = ShortArray(n)
        val fc = freq.coerceAtLeast(40f)
        val a1 = 1f - exp(-TAU * fc * 1.4f / SR)
        val a2 = 1f - exp(-TAU * fc / SR)
        var l1 = 0f; var l2 = 0f; var l3 = 0f
        val atk = (0.004f * SR).toInt().coerceIn(1, n)
        for (i in 0 until n) {
            val x = Math.random().toFloat() * 2f - 1f
            l1 += a1 * (x - l1)
            l2 += a1 * (l1 - l2)
            l3 += a2 * (l1 - l3)
            val y = when (kind) {
                "lowpass"  -> l3
                "highpass" -> x - l1
                else       -> l2 - l3           // bandpass-ish
            }
            var env = if (i < atk) i.toFloat() / atk
                      else ((n - i).toFloat() / (n - atk)).pow(1.4f)
            env = env.coerceIn(0f, 1f)
            buf[i] = (y * env * vol * 0.5f * 32767f).toInt().toShort()
        }
        play(buf, delay)
    }

    private fun play(buf: ShortArray, delay: Float) {
        if (live.get() >= 24) return
        var at: AudioTrack? = null
        try {
            at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(buf.size * 2)
                .build()
            at.write(buf, 0, buf.size)
            live.incrementAndGet()
            if (delay > 0f) handler.postDelayed({ start(at, buf) }, (delay * 1000).toLong())
            else start(at, buf)
        } catch (_: Exception) {
            try { at?.release() } catch (_: Exception) {}
        }
    }

    private fun start(at: AudioTrack, buf: ShortArray) {
        try {
            at.play()
            handler.postDelayed({
                try { at.stop(); at.release() } catch (_: Exception) {}
                live.decrementAndGet()
            }, buf.size * 1000L / SR + 150)
        } catch (_: Exception) {
            try { at.release() } catch (_: Exception) {}
            live.decrementAndGet()
        }
    }
}

/** Same recipes as the browser sfx object — identical timing & pitches. */
object Sfx {
    private val rnd = java.util.Random()
    private fun fr(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    fun ui()      = Synth.tone(660f, .06f, "tri", .08f)
    fun draw()    = Synth.tone(190f, .09f, "saw", .03f, 150f)
    fun tick2()   = Synth.tone(880f, .03f, "square", .03f)
    fun release(p: Float) {
        Synth.tone(150f + 120f * p, .12f, "saw", .16f, 60f)
        Synth.noise(.08f, 1800f, .08f, "highpass")
    }
    fun wall(v: Float) {
        Synth.noise(.07f, 300f, .08f + .2f * v, "lowpass")
        Synth.tone(90f, .06f, "sine", .08f + .12f * v)
    }
    fun wood(v: Float) {
        Synth.noise(.06f, 700f + 500f * v, .1f + .16f * v, "band")
        Synth.tone(240f, .05f, "tri", .08f)
    }
    fun clank() {
        Synth.tone(320f, .06f, "square", .06f, 240f)
        Synth.noise(.04f, 2400f, .05f, "highpass")
    }
    fun ding() {
        val f = fr(700f, 900f)
        Synth.tone(f, .2f, "sine", .14f); Synth.tone(f * 2f, .12f, "sine", .05f)
    }
    fun thunk() { Synth.noise(.05f, 240f, .14f, "lowpass"); Synth.tone(120f, .06f, "sine", .1f, 80f) }
    fun slow()  = Synth.tone(620f, .4f, "sine", .1f, 140f)
    fun coin(ch: Int) {
        val steps = floatArrayOf(0f, 4f, 7f, 12f, 16f, 19f, 24f, 28f)
        val st = steps[(ch - 1).coerceIn(0, 7)]
        val f = 660f * Math.pow(2.0, st / 12.0).toFloat()
        Synth.tone(f, .16f, "tri", .14f); Synth.tone(f * 2f, .09f, "sine", .05f)
    }
    fun charge() { Synth.tone(880f, .1f, "tri", .12f); Synth.tone(1320f, .16f, "tri", .1f, delay = .08f) }
    fun portal() { Synth.tone(220f, .28f, "sine", .12f, 880f); Synth.noise(.2f, 1800f, .05f, "highpass") }
    fun slice()  { Synth.noise(.07f, 2600f, .09f, "highpass"); Synth.tone(760f, .06f, "square", .05f, 320f) }
    fun wind()   = Synth.noise(.3f, 500f, .06f, "lowpass")
    fun well()   = Synth.tone(150f, .5f, "sine", .07f, 90f)
    fun crate() {
        Synth.noise(.1f, 420f, .2f, "band"); Synth.noise(.07f, 1500f, .12f, "highpass")
        Synth.tone(150f, .12f, "square", .1f, 55f)
    }
    fun stamp()  { Synth.tone(520f, .07f, "square", .09f); Synth.tone(784f, .12f, "tri", .12f, delay = .07f) }
    fun chalk()  { Synth.tone(1200f, .05f, "square", .05f); Synth.tone(900f, .09f, "tri", .07f, delay = .05f) }
    fun place()  { Synth.tone(500f, .05f, "square", .07f); Synth.tone(740f, .07f, "tri", .08f, delay = .04f) }
    fun target(b: Int) {
        floatArrayOf(523f, 659f, 784f, 1046f).forEachIndexed { i, f ->
            Synth.tone(f, .14f, "tri", .16f, delay = i * .055f)
        }
        if (b >= 1) Synth.tone(1318f, .25f, "sine", .1f, delay = .25f)
        Synth.noise(.3f, 6000f, .04f, "highpass", .05f)
    }
    fun fanfare() {
        floatArrayOf(392f, 494f, 587f, 784f).forEachIndexed { i, f ->
            Synth.tone(f, .16f, "tri", .13f, delay = i * .09f)
        }
    }
    fun empty() {
        Synth.tone(240f, .25f, "saw", .1f, 170f)
        Synth.tone(180f, .3f, "saw", .08f, 120f, .12f)
    }
}

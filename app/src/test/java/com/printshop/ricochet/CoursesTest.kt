package com.printshop.ricochet

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CoursesTest {
    private val okTypes = setOf(
        "plank", "crate", "bump", "spin", "move", "wind", "well", "portal")

    @Test fun `tour holds twenty unique sane puzzles`() {
        assertEquals(20, Courses.ALL.size)
        assertEquals(20, Courses.ALL.map { it.name }.toSet().size)
        for ((i, d) in Courses.ALL.withIndex()) {
            assertTrue("par Nº${i + 1}", d.par in 1..4)
            assertTrue("arrows Nº${i + 1}", d.arrows in 1..9)
            assertTrue("target bounds Nº${i + 1}",
                d.tx in .05f..(.95f) && d.ty in .1f..(.88f))
            assertTrue("coins Nº${i + 1}", d.coins.size <= 8)
            assertTrue("moving target Nº${i + 1}", d.tmx == 0f || d.tsp != 0f)
            for (ob in d.obs) {
                assertTrue("type ${ob.t} Nº${i + 1}", ob.t in okTypes)
                if (ob.t == "spin")
                    assertTrue("spinner speed Nº${i + 1}", abs(ob.sp) in .1f..3.4f)
                if (ob.t == "portal")
                    assertTrue("portal radius Nº${i + 1}", ob.r in .04f..(.12f))
            }
        }
    }

    @Test fun `curriculum shape - openers teach, finale examines`() {
        assertTrue(Courses.ALL[0].obs.isEmpty())          // FIRST FLIGHT: pure arc
        assertEquals("GAUNTLET", Courses.ALL[9].name)     // Act I finale
        assertEquals("THE EXAM", Courses.ALL[19].name)    // par-4 exam
        assertEquals(4, Courses.ALL[19].par)
        assertTrue(Courses.ALL[19].obs.size >= 6)
        // Act II escalates: every Act II puzzle uses at least 2 obstacle types
        for (i in 10 until 20)
            assertTrue("Act II variety Nº${i + 1}",
                Courses.ALL[i].obs.map { it.t }.toSet().size >= 2)
    }
}

package io.github.ramziag.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val sec = 1_000_000_000L
    private val now = 3600 * sec

    /** A fix [northM] metres north of Springfield's rounded spot, [ageS] seconds old. */
    private fun fix(acc: Float, ageS: Long, northM: Double = 0.0) = Fix(39.80 + northM / 111_195, -89.64, acc, now - ageS * sec)

    @Test
    fun round2() {
        assertEquals(39.8, Geo.round2(39.80172), 0.0)
        assertEquals(-89.64, Geo.round2(-89.64371), 0.0)
        assertEquals(139.69, Geo.round2(139.69171), 0.0)
        assertEquals("0.0", Geo.round2(-0.004).toString()) // never "-0.0"
        assertEquals(-0.01, Geo.round2(-0.006), 0.0)
    }

    @Test
    fun distance() {
        assertEquals(0.0, Geo.distanceM(39.8, -89.64, 39.8, -89.64), 1e-6)
        assertEquals(1112.0, Geo.distanceM(39.80, -89.64, 39.81, -89.64), 1.0)
        assertEquals(854.0, Geo.distanceM(39.80, -89.64, 39.80, -89.63), 1.0)
        assertEquals(6_930_000.0, Geo.distanceM(39.80172, -89.64371, 48.85341, 2.3488), 20_000.0)
    }

    @Test
    fun coords() {
        assertEquals("39.80° N, 89.64° W", Geo.coords(39.80172, -89.64371))
        assertEquals("33.87° S, 151.21° E", Geo.coords(-33.8688, 151.2093))
        assertEquals("0.00° N, 0.00° E", Geo.coords(-0.001, -0.004))
    }

    @Test
    fun pickBestPrecise() {
        val sharp = fix(30f, 60)
        val rough = fix(500f, 10)
        assertSame(sharp, Geo.pickBest(listOf(rough, sharp), now, precise = true))
        assertTrue(Geo.isFresh(sharp, now, true))
        // Not good enough to stop: the newest one is shown while locating.
        val old = fix(20f, 3 * 60)
        assertSame(rough, Geo.pickBest(listOf(old, rough), now, precise = true))
        assertFalse(Geo.isFresh(rough, now, true))
        assertFalse(Geo.isFresh(old, now, true))
        assertNull(Geo.pickBest(listOf(fix(5f, 31 * 60)), now, precise = true))
        assertNull(Geo.pickBest(emptyList(), now, precise = true))
    }

    @Test
    fun pickBestApproximate() {
        val coarse = fix(2000f, 8 * 60)
        assertTrue(Geo.isFresh(coarse, now, false))
        assertFalse(Geo.isFresh(fix(2000f, 11 * 60), now, false))
        assertSame(coarse, Geo.pickBest(listOf(fix(2000f, 20 * 60), coarse), now, precise = false))
        // A clock that ran backwards counts as brand new, not as negative age.
        assertTrue(Geo.isFresh(Fix(39.8, -89.64, 50f, now + 5 * sec), now, true))
    }

    @Test
    fun goodLiveFix() {
        assertTrue(Geo.isGood(fix(100f, 0), precise = true))
        assertFalse(Geo.isGood(fix(101f, 0), precise = true))
        assertTrue(Geo.isGood(fix(3000f, 0), precise = false))
    }

    @Test
    fun shouldMoveUsesAtLeastOneKilometre() {
        assertFalse(Geo.shouldMove(39.80, -89.64, fix(20f, 0, northM = 500.0)))
        assertFalse(Geo.shouldMove(39.80, -89.64, fix(20f, 0, northM = 990.0)))
        assertTrue(Geo.shouldMove(39.80, -89.64, fix(20f, 0, northM = 1500.0)))
        assertFalse(Geo.shouldMove(39.80, -89.64, fix(2000f, 0, northM = 1500.0)))
        assertTrue(Geo.shouldMove(39.80, -89.64, fix(2000f, 0, northM = 2500.0)))
    }
}

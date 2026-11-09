package com.rassul.pdfreader.core.geometry

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightColorMatrixTest {
    private val m = NightColorMatrix.create()

    private fun near(exp: IntArray, act: IntArray) {
        for (i in exp.indices) assertEquals("канал $i: ${act.toList()}", exp[i].toFloat(), act[i].toFloat(), 1f)
    }

    @Test fun shapeAndReferenceMatrix() {
        val ref = floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
        assertArrayEquals(ref, m, 1e-3f)
    }

    // Эталон physics-math-reviewer (±1)
    @Test fun referenceColors() {
        near(intArrayOf(0, 0, 0, 255), NightColorMatrix.apply(m, 255, 255, 255))
        near(intArrayOf(255, 255, 255, 255), NightColorMatrix.apply(m, 0, 0, 0))
        near(intArrayOf(255, 146, 146, 255), NightColorMatrix.apply(m, 255, 0, 0))
        near(intArrayOf(127, 127, 127, 255), NightColorMatrix.apply(m, 128, 128, 128))
        near(intArrayOf(218, 218, 255, 255), NightColorMatrix.apply(m, 0, 0, 255))
        near(intArrayOf(37, 37, 0, 255), NightColorMatrix.apply(m, 255, 255, 0))
    }

    @Test fun hueIsPreserved() {
        val r = NightColorMatrix.apply(m, 0, 0, 139)
        assertTrue(r[2] > r[0] && r[2] > r[1])
        val red = NightColorMatrix.apply(m, 200, 30, 30)
        assertTrue(red[0] > red[1] && red[0] > red[2])
    }

    @Test fun luminanceIsInverted() {
        fun y(c: IntArray) = 0.213f * c[0] + 0.715f * c[1] + 0.072f * c[2]
        val src = intArrayOf(40, 120, 200)
        val out = NightColorMatrix.apply(m, src[0], src[1], src[2])
        assertEquals(255f - y(src), y(out), 1.5f)
    }

    @Test fun blackLevel() {
        val soft = NightColorMatrix.create(0.1f)
        assertEquals(26f, NightColorMatrix.apply(soft, 255, 255, 255)[0].toFloat(), 1f)
        assertArrayEquals(intArrayOf(255, 255, 255, 255), NightColorMatrix.apply(soft, 0, 0, 0))
    }

    @Test fun alphaKept() { assertEquals(77, NightColorMatrix.apply(m, 10, 20, 30, 77)[3]) }
}

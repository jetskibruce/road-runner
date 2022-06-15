package com.acmerobotics.roadrunner

import org.junit.jupiter.api.Test
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.assertEquals

infix fun Vector2.det(other: Vector2) = x * other.y - y * other.x

fun Position2.free() = Vector2(x, y)

private fun approxLength(
    p1: Position2, p2: Position2, p3: Position2): Double {
    val chord = (p3 - p1).norm()

    val v1 = p2 - p1
    val v2 = p2 - p3
    val det = 4.0 * (v1 det v2)

    return if (abs(det) < 1e-6) {
        chord
    } else {
        val x1 = p1.free().sqrNorm()
        val x2 = p2.free().sqrNorm()
        val x3 = p3.free().sqrNorm()

        val y1 = x2 - x1
        val y2 = x2 - x3

        val center = Position2(
            (y1 * v2.y - y2 * v1.y) / det, (y2 * v1.x - y1 * v2.x) / det)
        val radius = (p1 - center).norm()
        2.0 * radius * asin(chord / (2.0 * radius))
    }
}

fun Position2Dual<Internal>.curvature(): Double {
    val (_, dx, d2x) = x.values
    val (_, dy, d2y) = y.values
    val derivNorm = sqrt(dx * dx + dy * dy)
    return abs(d2x * dy - dx * d2y) / (derivNorm * derivNorm * derivNorm)
}

class ArcApproxArcCurve2(
    private val curve: PositionPath<Internal>,
    private val maxDeltaK: Double = 0.01,
    private val maxSegmentLength: Double = 0.25,
    private val maxDepth: Int = 30,
) {
    private data class Samples(
        val length: Double,
        // first: s, second: t
        val values: List<Pair<Double, Double>>,
    ) {
        init {
            if (values.size < 2) {
                throw IllegalArgumentException("must have at least two samples")
            }
        }

        operator fun plus(other: Samples) = Samples(
            length + other.length,
            values.dropLast(1) + other.values
        )
    }

    private fun adaptiveSample(): Samples {
        fun helper(
            sLo: Double,
            tLo: Double,
            tHi: Double,
            pLo: Position2Dual<Internal>,
            pHi: Position2Dual<Internal>,
            depth: Int,
        ): Samples {
            val tMid = 0.5 * (tLo + tHi)
            val pMid = curve[tMid, 3]

            val deltaK = abs(pLo.curvature() - pHi.curvature())
            val length = approxLength(pLo.value(), pMid.value(), pHi.value())

            return if (depth < maxDepth && (deltaK > maxDeltaK || length > maxSegmentLength)) {
                val loSamples = helper(sLo, tLo, tMid, pLo, pMid, depth + 1)
                // loSamples.length is more accurate than length
                val sMid = sLo + loSamples.length
                val hiSamples = helper(sMid, tMid, tHi, pMid, pHi, depth + 1)
                loSamples + hiSamples
            } else {
                Samples(
                    length, listOf(
                        Pair(sLo, tLo),
                        Pair(sLo + length, tHi)
                    )
                )
            }
        }

        return helper(0.0, 0.0, 1.0, curve[0.0, 3], curve[1.0, 3], 0)
    }

    private val samples: List<Pair<Double, Double>>
    val length: Double

    init {
        val (length, values) = adaptiveSample()
        this.length = length
        this.samples = values
    }

    fun reparam(s: Double): Double {
        val result = samples.binarySearch { (sMid, _) -> sMid.compareTo(s) }
        return when {
            result >= 0 -> samples[result].let { (_, t) -> t }
            else -> {
                val insIndex = -(result + 1)
                when {
                    insIndex == 0 -> 0.0
                    insIndex >= samples.size -> 1.0
                    else -> {
                        val (sLo, tLo) = samples[insIndex - 1]
                        val (sHi, tHi) = samples[insIndex]
                        lerp(s, sLo, sHi, tLo, tHi)
                    }
                }
            }
        }
    }
}

class CurvesTest {
    @Test
    fun testArcLengthReparam() {
        val spline =
            QuinticSpline2(
                QuinticSpline1(
                    DualNum(doubleArrayOf(0.0, 10.0, 30.0)),
                    DualNum(doubleArrayOf(20.0, 30.0, 0.0)),
                ),
                QuinticSpline1(
                    DualNum(doubleArrayOf(0.0, 15.0, 10.0)),
                    DualNum(doubleArrayOf(20.0, 20.0, 0.0)),
                ),
            )

        val curveExp = ArcApproxArcCurve2(spline)
        val curveActual = ArcCurve2(spline)

        range(0.0, min(curveExp.length, curveActual.length), 100)
            .forEach {
                assertEquals(curveExp.reparam(it), curveActual.reparam(it), 1e-2)
            }
    }

    @Test
    fun testSplineInterpolation() {
        val r = Random.Default
        repeat(100) {
            val begin = DualNum<Internal>(doubleArrayOf(r.nextDouble(), r.nextDouble(), r.nextDouble()))
            val end = DualNum<Internal>(doubleArrayOf(r.nextDouble(), r.nextDouble(), r.nextDouble()))

            val spline = QuinticSpline1(begin, end)

            val splineBegin = spline[0.0, 3]
            assertEquals(begin[0], splineBegin[0], 1e-6)
            assertEquals(begin[1], splineBegin[1], 1e-6)
            assertEquals(begin[2], splineBegin[2], 1e-6)

            val splineEnd = spline[1.0, 3]
            assertEquals(end[0], splineEnd[0], 1e-6)
            assertEquals(end[1], splineEnd[1], 1e-6)
            assertEquals(end[2], splineEnd[2], 1e-6)
        }
    }
}

package com.jmstudios.redmoon.tv
import org.junit.Assert.*
import org.junit.Test
class QuantisedFadeTest {
    @Test fun fullLengthTestStartsAtActualAndSkipsMissedSteps() {
        assertEquals(66, QuantisedFade.value(66, 61, 888000, 0))
        assertEquals(64, QuantisedFade.value(66, 61, 888000, 444000))
        assertEquals(61, QuantisedFade.value(66, 61, 888000, 999999))
        assertTrue(QuantisedFade.nextDelay(66, 61, 888000, 0) > 80000)
        assertEquals(61, QuantisedFade.value(66, 61, 0, 0))
    }
}

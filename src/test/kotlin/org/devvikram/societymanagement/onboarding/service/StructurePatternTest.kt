package org.devvikram.societymanagement.onboarding.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class StructurePatternTest {

    @Test
    fun `floor and two-digit flat numbering`() {
        val numbers = StructurePattern.flatNumbers(WingPattern("A", floorsFrom = 1, floorsTo = 12, flatsPerFloor = 4))
        assertEquals(48, numbers.size)
        assertEquals(listOf("101", "102", "103", "104", "201"), numbers.take(5))
        assertEquals("1204", numbers.last())
    }

    @Test
    fun `other numberings, skipped flats and extra units`() {
        assertEquals(
            listOf("B-1-1", "B-1-2", "B-2-2"),
            StructurePattern.flatNumbers(WingPattern("B", 1, 2, 2, numbering = "{wing}-{floor}-{n}", skip = listOf("B-2-1"))),
        )
        assertEquals(
            listOf("001", "002", "101", "102", "Shop 1", "Shop 2"),
            StructurePattern.flatNumbers(WingPattern("C", 0, 1, 2, extraUnits = listOf("Shop 1", " Shop 2 ", ""))),
        )
        // A row house society: one unit per "floor", numbered by floor alone.
        assertEquals(listOf("1", "2", "3"), StructurePattern.flatNumbers(WingPattern("Villas", 1, 3, 1, numbering = "{floor}")))
        // Only shops.
        assertEquals(listOf("S1"), StructurePattern.flatNumbers(WingPattern("Market", 0, 0, 0, extraUnits = listOf("S1"))))
    }

    @Test
    fun `numberings that would repeat a flat number are refused`() {
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern("A", 1, 3, 4, numbering = "{floor}")) }
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern("A", 1, 3, 1, numbering = "{nn}")) }
        // Floor 1 flat 11 and floor 11 flat 1 both give 111.
        val repeat = assertThrows<IllegalArgumentException> {
            StructurePattern.flatNumbers(WingPattern("A", 1, 11, 11, numbering = "{floor}{n}"))
        }
        assert("111" in repeat.message!!)
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern("A", 1, 1, 2, numbering = "{floor}", extraUnits = listOf("101"))) }
    }

    @Test
    fun `bad ranges and requests are refused`() {
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern(" ", 1, 2, 2)) }
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern("A", 5, 2, 2)) }
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern("A", 1, 2, 51)) }
        assertThrows<IllegalArgumentException> { StructurePattern.flatNumbers(WingPattern("A", 1, 1, 1, skip = listOf("101"))) }
        assertThrows<IllegalArgumentException> { StructurePattern.plan(emptyList()) }
        assertThrows<IllegalArgumentException> { StructurePattern.plan(listOf(WingPattern("A", 1, 2, 2), WingPattern("a ", 3, 4, 2))) }
        assertThrows<IllegalArgumentException> {
            StructurePattern.plan((1..3).map { WingPattern("W$it", 0, 200, 10, numbering = "{floor}-{nn}") })
        }
    }
}

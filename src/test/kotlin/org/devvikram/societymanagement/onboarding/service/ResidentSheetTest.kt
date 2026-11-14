package org.devvikram.societymanagement.onboarding.service

import org.devvikram.societymanagement.user.model.ResidentType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResidentSheetTest {

    @Test
    fun `columns are guessed from common header names`() {
        assertEquals(
            SheetColumns(flat = 2, name = 3, phone = 4, wing = 1, type = 5),
            ResidentSheet.guess(listOf("Wing", "Flat No.", "Resident Name", "Mobile Number", "Owner/Tenant")),
        )
        assertEquals(
            SheetColumns(flat = 1, name = 2, phone = 3),
            ResidentSheet.guess(listOf("Unit", "Name", "Phone")),
        )
        // "Building name" is the wing, not the resident's name; "Resident type" is the type.
        assertEquals(
            SheetColumns(flat = 2, name = 3, phone = 5, wing = 1, type = 4),
            ResidentSheet.guess(listOf("Building name", "Flat", "Member name", "Resident type", "Contact")),
        )
        assertNull(ResidentSheet.guess(listOf("A", "B", "C")))
    }

    @Test
    fun `rows skip the header and blank lines and keep file line numbers`() {
        val records = listOf(
            listOf("Flat", "Name", "Phone"),
            listOf("A-101", " Meera ", "9876543210"),
            listOf("", "", ""),
            listOf("A-102", "", "98765 43211", "extra"),
        )
        val rows = ResidentSheet.rows(records, SheetColumns(1, 2, 3))
        assertEquals(listOf(2, 4), rows.map { it.row })
        assertEquals("Meera", rows[0].name)
        assertNull(rows[1].name)
        assertThrows<IllegalArgumentException> { ResidentSheet.rows(listOf(listOf("Flat", "Name", "Phone")), SheetColumns(1, 2, 3)) }
    }

    @Test
    fun `phone numbers are normalised to the account form`() {
        assertEquals("9876543210", ResidentSheet.phone("98765 43210"))
        assertEquals("9876543210", ResidentSheet.phone("+91 98765-43210"))
        assertEquals("9876543210", ResidentSheet.phone("919876543210"))
        assertEquals("9876543210", ResidentSheet.phone("09876543210"))
        assertEquals("+14155550123", ResidentSheet.phone("+1 (415) 555-0123"))
        assertNull(ResidentSheet.phone("9.87654321E+09"))
        assertNull(ResidentSheet.phone("12345"))
        assertNull(ResidentSheet.phone(" "))
    }

    @Test
    fun `resident types in plain words`() {
        assertEquals(ResidentType.OWNER, ResidentSheet.residentType(null))
        assertEquals(ResidentType.OWNER, ResidentSheet.residentType("Owner"))
        assertEquals(ResidentType.TENANT, ResidentSheet.residentType("tenant "))
        assertEquals(ResidentType.TENANT, ResidentSheet.residentType("Rented"))
        assertEquals(ResidentType.FAMILY, ResidentSheet.residentType("Family member"))
        assertNull(ResidentSheet.residentType("caretaker"))
    }

    @Test
    fun `flat cells may carry the wing`() {
        assertEquals("A" to "101", ResidentSheet.splitFlat("A-101"))
        assertEquals("Tower B" to "1204", ResidentSheet.splitFlat("Tower B / 1204"))
        assertEquals(null to "101", ResidentSheet.splitFlat(" 101 "))
    }
}

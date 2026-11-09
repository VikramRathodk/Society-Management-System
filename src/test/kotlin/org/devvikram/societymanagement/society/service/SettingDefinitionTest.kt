package org.devvikram.societymanagement.society.service

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingDefinitionTest {

    private fun definition(type: SettingType, default: String, min: Long? = null, max: Long? = null,
                           maxLength: Int? = null, options: List<String> = emptyList()) =
        SettingDefinition("test", SettingGroup.MAINTENANCE, "Test", "A test setting", type, default, min, max, maxLength, options)

    @Test
    fun `booleans take only JSON booleans`() {
        val d = definition(SettingType.BOOLEAN, "false")
        assertEquals("true", d.normalize(true))
        assertEquals(true, d.typed("true"))
        assertFailsWith<IllegalArgumentException> { d.normalize("true") }
        assertFailsWith<IllegalArgumentException> { d.normalize(1) }
    }

    @Test
    fun `integers take whole numbers within their range`() {
        val d = definition(SettingType.INTEGER, "7", min = 1, max = 30)
        assertEquals("1", d.normalize(1))
        assertEquals("30", d.normalize(30L))
        assertEquals(7L, d.typed("7"))
        assertFailsWith<IllegalArgumentException> { d.normalize(0) }
        assertFailsWith<IllegalArgumentException> { d.normalize(31) }
        assertFailsWith<IllegalArgumentException> { d.normalize(2.5) }
        assertFailsWith<IllegalArgumentException> { d.normalize("7") }
        assertFailsWith<IllegalArgumentException> { d.normalize(BigInteger.TWO.pow(70)) }
    }

    @Test
    fun `text is trimmed and length-limited`() {
        val d = definition(SettingType.TEXT, "", maxLength = 5)
        assertEquals("abc", d.normalize("  abc "))
        assertFailsWith<IllegalArgumentException> { d.normalize("abcdef") }
        assertFailsWith<IllegalArgumentException> { d.normalize(5) }
    }

    @Test
    fun `choices take one of their options`() {
        val d = definition(SettingType.CHOICE, "A", options = listOf("A", "B"))
        assertEquals("B", d.normalize("B"))
        assertFailsWith<IllegalArgumentException> { d.normalize("C") }
    }

    @Test
    fun `a definition with an invalid default is rejected`() {
        assertFailsWith<IllegalArgumentException> { definition(SettingType.INTEGER, "50", min = 1, max = 30) }
        assertFailsWith<IllegalArgumentException> { definition(SettingType.CHOICE, "A") }
    }

    @Test
    fun `the catalog is consistent`() {
        assertTrue(SocietySettingCatalog.all.isNotEmpty())
        assertEquals(SocietySettingCatalog.all.size, SocietySettingCatalog.all.map { it.key }.distinct().size)
        assertEquals("false", SocietySettingCatalog.get(SocietySettingKeys.TENANTS_SEE_MAINTENANCE).defaultValue)
    }
}

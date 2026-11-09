package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.SystemAccounts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultChartTest {
    private val all: List<DefaultChart.Node> = DefaultChart.roots.values.flatMap { flatten(it) }

    private fun flatten(node: DefaultChart.Node): List<DefaultChart.Node> = listOf(node) + node.children.flatMap(::flatten)

    @Test
    fun `codes and names are unique (names ignoring case), as the database requires`() {
        assertEquals(all.size, all.map { it.code }.toSet().size)
        assertEquals(all.size, all.map { it.name.lowercase() }.toSet().size)
    }

    @Test
    fun `every type has a root group and every system account exists once`() {
        assertEquals(AccountType.entries.toSet(), DefaultChart.roots.keys)
        assertTrue(DefaultChart.roots.values.all { it.group })
        val keys = all.mapNotNull { it.systemKey }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.containsAll(listOf(
            SystemAccounts.CASH_AND_BANK, SystemAccounts.BANK_ACCOUNTS, SystemAccounts.CASH, SystemAccounts.MEMBER_DUES,
            SystemAccounts.MEMBER_ADVANCE, SystemAccounts.MAINTENANCE_INCOME, SystemAccounts.LATE_FEE_INCOME,
            SystemAccounts.WRITE_OFF, SystemAccounts.GENERAL_FUND,
        )))
    }

    @Test
    fun `only groups have children`() {
        assertTrue(all.filter { it.children.isNotEmpty() }.all { it.group })
    }
}

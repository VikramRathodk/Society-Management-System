package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AccountType
import org.devvikram.societymanagement.finance.model.SystemAccounts

// The chart a society starts with. Codes follow 1xxx assets, 2xxx liabilities, 3xxx funds, 4xxx income,
// 5xxx expenses (a convention, not a rule). Societies rename, add, move and deactivate freely afterwards;
// only roots and system accounts are fixed.
object DefaultChart {
    data class Node(
        val code: String,
        val name: String,
        val group: Boolean,
        val systemKey: String? = null,
        val children: List<Node> = emptyList(),
    )

    private fun group(code: String, name: String, systemKey: String? = null, vararg children: Node) =
        Node(code, name, group = true, systemKey = systemKey, children = children.toList())

    private fun ledger(code: String, name: String, systemKey: String? = null) = Node(code, name, group = false, systemKey = systemKey)

    // One root group per account type.
    val roots: Map<AccountType, Node> = linkedMapOf(
        AccountType.ASSET to group(
            "1000", "Assets", null,
            group(
                "1100", "Cash & bank", SystemAccounts.CASH_AND_BANK,
                ledger("1110", "Cash in hand", SystemAccounts.CASH),
                group("1120", "Bank accounts", SystemAccounts.BANK_ACCOUNTS),
            ),
            group(
                "1200", "Receivables", null,
                ledger("1210", "Maintenance receivable from members", SystemAccounts.MEMBER_DUES),
                ledger("1220", "Other receivables"),
            ),
            group(
                "1300", "Deposits & prepaid", null,
                ledger("1310", "Electricity & utility deposits"),
                ledger("1320", "Prepaid expenses"),
            ),
            group(
                "1400", "Investments", null,
                ledger("1410", "Fixed deposits"),
            ),
        ),
        AccountType.LIABILITY to group(
            "2000", "Liabilities", null,
            group(
                "2100", "Members", null,
                ledger("2110", "Advance maintenance from members", SystemAccounts.MEMBER_ADVANCE),
                ledger("2120", "Members' deposits"),
            ),
            group(
                "2200", "Payables", null,
                ledger("2210", "Outstanding expenses"),
                ledger("2220", "Sundry creditors"),
            ),
        ),
        AccountType.FUND to group(
            "3000", "Funds", null,
            ledger("3100", "General fund", SystemAccounts.GENERAL_FUND),
            ledger("3200", "Sinking fund"),
            ledger("3300", "Repair fund"),
        ),
        AccountType.INCOME to group(
            "4000", "Income", null,
            group(
                "4100", "Maintenance", null,
                ledger("4110", "Maintenance charges", SystemAccounts.MAINTENANCE_INCOME),
                ledger("4120", "Interest / late charges", SystemAccounts.LATE_FEE_INCOME),
            ),
            group(
                "4200", "Other income", null,
                ledger("4210", "Hall & clubhouse rent"),
                ledger("4220", "Interest on bank & deposits"),
                ledger("4230", "Donations"),
                ledger("4240", "Transfer fees & other income"),
            ),
        ),
        AccountType.EXPENSE to group(
            "5000", "Expenses", null,
            group(
                "5100", "Utilities", null,
                ledger("5110", "Electricity"),
                ledger("5120", "Water"),
            ),
            group(
                "5200", "Services", null,
                ledger("5210", "Security"),
                ledger("5220", "Housekeeping"),
                ledger("5230", "Lift maintenance (AMC)"),
                ledger("5240", "Gardening"),
            ),
            group(
                "5300", "Repairs & maintenance", null,
                ledger("5310", "Building repairs"),
                ledger("5320", "Plumbing & electrical repairs"),
            ),
            group(
                "5400", "Administration", null,
                ledger("5410", "Salaries & wages"),
                ledger("5420", "Printing & stationery"),
                ledger("5430", "Audit fees"),
                ledger("5440", "Bank charges"),
                ledger("5450", "Property tax & municipal charges"),
                ledger("5460", "Insurance"),
            ),
            ledger("5900", "Maintenance waived", SystemAccounts.WRITE_OFF),
        ),
    )
}

package org.devvikram.societymanagement.onboarding.service

import org.devvikram.societymanagement.user.model.ResidentType

// Which columns (1-based) hold what. Wing and type are optional: without a wing column the flat cell may
// say "A-101", and a missing type means owner.
data class SheetColumns(val flat: Int, val name: Int, val phone: Int, val wing: Int? = null, val type: Int? = null) {
    fun validate() {
        val all = listOfNotNull(flat, name, phone, wing, type)
        require(all.all { it in 1..50 }) { "Columns are numbered 1 to 50" }
        require(all.size == all.toSet().size) { "Each column can be used for one thing only" }
    }
}

// A line of the sheet as written (cells trimmed, blanks as null). `row` is the file's line number.
data class SheetRow(val row: Int, val wing: String?, val flat: String?, val name: String?, val phone: String?, val type: String?)

// Pure rules for reading a residents sheet, unit-tested on their own. The first line is the header.
object ResidentSheet {
    const val MAX_ROWS = 2000

    private val FLAT = listOf("flatno", "flatnumber", "flat", "unit", "apartment", "door", "houseno", "roomno")
    private val WING = listOf("wing", "building", "tower", "block", "bldg")
    private val NAME = listOf("name", "resident", "member", "occupant")
    private val PHONE = listOf("mobile", "phone", "contact", "whatsapp", "cell")
    private val TYPE = listOf("type", "ownertenant", "ownership", "occupancy", "relation", "category", "status")

    // Matches the header's names; null when flat, name and phone can't all be found.
    fun guess(header: List<String>): SheetColumns? {
        val keys = header.map { it.lowercase().filter(Char::isLetter) }
        val taken = mutableSetOf<Int>()
        fun find(words: List<String>): Int? {
            for (word in words) {
                val index = keys.indices.firstOrNull { it !in taken && keys[it].contains(word) } ?: continue
                taken += index
                return index + 1
            }
            return null
        }
        // Most specific first: "Wing name" is the wing and "Owner/Tenant" the type, not the name.
        val phone = find(PHONE)
        val wing = find(WING)
        val type = find(TYPE)
        val flat = find(FLAT)
        val name = find(NAME)
        return if (flat != null && name != null && phone != null) SheetColumns(flat, name, phone, wing, type) else null
    }

    fun rows(records: List<List<String>>, columns: SheetColumns): List<SheetRow> {
        fun cell(cells: List<String>, column: Int?) = column?.let { cells.getOrNull(it - 1)?.trim()?.ifEmpty { null } }
        val rows = records.withIndex().drop(1)
            .filter { (_, cells) -> cells.any { it.isNotBlank() } }
            .map { (index, cells) ->
                SheetRow(
                    row = index + 1,
                    wing = cell(cells, columns.wing),
                    flat = cell(cells, columns.flat),
                    name = cell(cells, columns.name),
                    phone = cell(cells, columns.phone),
                    type = cell(cells, columns.type),
                )
            }
        require(rows.isNotEmpty()) { "The sheet has no residents below its header" }
        require(rows.size <= MAX_ROWS) { "At most $MAX_ROWS residents in one sheet" }
        return rows
    }

    // Indian numbers are kept as their 10 digits (as accounts are), so "+91 98765-43210", "919876543210"
    // and "09876543210" all match the account 9876543210. Other numbers keep a leading + and 10–14 digits.
    fun phone(text: String?): String? {
        val raw = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.any { it.isLetter() }) return null // e.g. 9.87654321E+09 from a spreadsheet
        val plus = raw.startsWith("+")
        val digits = raw.filter(Char::isDigit)
        return when {
            digits.length == 10 && !plus -> digits
            digits.length == 12 && digits.startsWith("91") -> digits.substring(2)
            digits.length == 11 && digits.startsWith("0") && !plus -> digits.substring(1)
            digits.length in 10..14 && plus -> "+$digits"
            else -> null
        }
    }

    // Blank means owner; null when the text isn't one we know.
    fun residentType(text: String?): ResidentType? {
        val key = text?.lowercase()?.filter(Char::isLetter) ?: return ResidentType.OWNER
        return when (key) {
            "", "owner", "o", "self", "landlord", "own" -> ResidentType.OWNER
            "tenant", "t", "rent", "rented", "renter", "lessee" -> ResidentType.TENANT
            "family", "f", "familymember", "member", "spouse", "son", "daughter", "father", "mother", "wife", "husband" -> ResidentType.FAMILY
            else -> null
        }
    }

    // "A-101", "A/101", "A 101" → wing A, flat 101. A flat on its own has no wing.
    fun splitFlat(text: String): Pair<String?, String> {
        val match = Regex("""^\s*(.+?)\s*[-/ ]\s*(\S+)\s*$""").find(text) ?: return null to text.trim()
        return match.groupValues[1] to match.groupValues[2]
    }
}

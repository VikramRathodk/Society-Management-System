package org.devvikram.societymanagement.society.service

import java.math.BigInteger

enum class SettingType { BOOLEAN, INTEGER, TEXT, CHOICE }

// Sections of the settings page, in display order.
enum class SettingGroup(val label: String) {
    MAINTENANCE("Maintenance"),
    MEMBERSHIP("Membership & invitations"),
    FINANCE("Finance & accounts"),
    POLICIES("Rules & policies"),
    GOVERNANCE("Meetings & governance"),
}

// What one setting is. Values are stored as text: "true"/"false", a whole number, or the string itself.
data class SettingDefinition(
    val key: String,
    val group: SettingGroup,
    val label: String,
    val description: String,
    val type: SettingType,
    val defaultValue: String,
    val min: Long? = null,
    val max: Long? = null,
    val maxLength: Int? = null,
    val options: List<String> = emptyList(),
) {
    init {
        require(type != SettingType.CHOICE || options.isNotEmpty()) { "Setting $key needs options" }
        require(normalize(typed(defaultValue)) == defaultValue) { "Setting $key has an invalid default" }
    }

    // The stored form of a value from a JSON request body; IllegalArgumentException (400) if it doesn't fit.
    fun normalize(raw: Any): String = when (type) {
        SettingType.BOOLEAN -> (raw as? Boolean ?: invalid("true or false")).toString()
        SettingType.INTEGER -> {
            val number = when (raw) {
                is Int -> raw.toLong()
                is Long -> raw
                is BigInteger -> if (raw.bitLength() < 64) raw.toLong() else invalid("a whole number")
                else -> invalid("a whole number")
            }
            if (min != null && number < min || max != null && number > max) invalid(rangeText())
            number.toString()
        }
        SettingType.TEXT -> {
            val text = (raw as? String ?: invalid("text")).trim()
            if (maxLength != null && text.length > maxLength) invalid("at most $maxLength characters")
            text
        }
        SettingType.CHOICE -> {
            val choice = raw as? String ?: invalid("one of ${options.joinToString()}")
            if (choice !in options) invalid("one of ${options.joinToString()}")
            choice
        }
    }

    // A stored value as the JSON type the client sees.
    fun typed(stored: String): Any = when (type) {
        SettingType.BOOLEAN -> stored.toBooleanStrict()
        SettingType.INTEGER -> stored.toLong()
        SettingType.TEXT, SettingType.CHOICE -> stored
    }

    private fun rangeText() = when {
        min != null && max != null -> "a whole number from $min to $max"
        min != null -> "a whole number of at least $min"
        else -> "a whole number of at most $max"
    }

    private fun invalid(expected: String): Nothing =
        throw IllegalArgumentException("Setting $key must be $expected")
}

object SocietySettingKeys {
    const val TENANTS_SEE_MAINTENANCE = "tenantsSeeMaintenance"
    const val ACCEPT_ADVANCE_PAYMENTS = "acceptAdvancePayments"
    const val INVITE_EXPIRY_DAYS = "inviteExpiryDays"
    const val RESIDENTS_CAN_INVITE = "residentsCanInvite"
    const val RESIDENT_INVITES_NEED_APPROVAL = "residentInvitesNeedApproval"
    const val MAX_RESIDENTS_PER_FLAT = "maxResidentsPerFlat"
    const val FINANCIAL_YEAR_START_MONTH = "financialYearStartMonth"
    const val FINANCE_APPROVAL_ABOVE = "financeApprovalAbove"
    const val RESIDENTS_SEE_FINANCE_SUMMARY = "residentsSeeFinanceSummary"
    const val RECONCILIATION_MATCH_DAYS = "reconciliationMatchDays"
    const val POLICY_APPROVAL_REQUIRED = "policyApprovalRequired"
    const val POLICY_SELF_APPROVAL_ALLOWED = "policySelfApprovalAllowed"
    const val POLICY_REMINDER_DAYS = "policyReminderDays"
    const val AGM_NOTICE_DAYS = "agmNoticeDays"
    const val SGM_NOTICE_DAYS = "sgmNoticeDays"
    const val COMMITTEE_NOTICE_DAYS = "committeeNoticeDays"
    const val GENERAL_QUORUM_PERCENT = "generalQuorumPercent"
    const val GENERAL_QUORUM_CAP = "generalQuorumCap"
    const val COMMITTEE_QUORUM_PERCENT = "committeeQuorumPercent"
    const val MEETING_REMINDER_HOURS = "meetingReminderHours"
}

// Every society setting. To add one: add a definition here (and a key constant if code reads it).
// The settings API and page pick it up; societies get the default until they change it.
object SocietySettingCatalog {
    val all: List<SettingDefinition> = listOf(
        SettingDefinition(
            key = SocietySettingKeys.TENANTS_SEE_MAINTENANCE,
            group = SettingGroup.MAINTENANCE,
            label = "Tenants see maintenance bills",
            description = "Off: only a flat's owner and family see its bills, payments and dues, and get bill " +
                "notifications. On: tenants do too. Committee members and above always see every flat.",
            type = SettingType.BOOLEAN,
            defaultValue = "false",
        ),
        SettingDefinition(
            key = SocietySettingKeys.ACCEPT_ADVANCE_PAYMENTS,
            group = SettingGroup.MAINTENANCE,
            label = "Accept advance payments",
            description = "On: a payment can be more than the flat owes; the rest is kept as the flat's credit " +
                "and pays its next bills when they are issued. Off: payments above what is owed are refused.",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
        ),
        SettingDefinition(
            key = SocietySettingKeys.INVITE_EXPIRY_DAYS,
            group = SettingGroup.MEMBERSHIP,
            label = "Invitation link expiry (days)",
            description = "How long an invitation link and its QR code work. Resending an invitation " +
                "starts the period again.",
            type = SettingType.INTEGER,
            defaultValue = "7",
            min = 1,
            max = 30,
        ),
        SettingDefinition(
            key = SocietySettingKeys.RESIDENTS_CAN_INVITE,
            group = SettingGroup.MEMBERSHIP,
            label = "Residents can invite to their own flat",
            description = "Owners invite family members and tenants; tenants invite other tenants of their " +
                "household. Off: only the committee and above invite people.",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
        ),
        SettingDefinition(
            key = SocietySettingKeys.RESIDENT_INVITES_NEED_APPROVAL,
            group = SettingGroup.MEMBERSHIP,
            label = "Resident invitations need committee approval",
            description = "On: an invitation sent by a resident works only after a committee member approves " +
                "it. Off: it works straight away.",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
        ),
        SettingDefinition(
            key = SocietySettingKeys.MAX_RESIDENTS_PER_FLAT,
            group = SettingGroup.MEMBERSHIP,
            label = "Most residents per flat",
            description = "Residents of a flat plus its open invitations, for any invitation to that flat. " +
                "0 means no limit.",
            type = SettingType.INTEGER,
            defaultValue = "10",
            min = 0,
            max = 100,
        ),
        SettingDefinition(
            key = SocietySettingKeys.FINANCIAL_YEAR_START_MONTH,
            group = SettingGroup.FINANCE,
            label = "Financial year starts in month",
            description = "1 = January … 12 = December. 4 (April) gives the usual April–March year. " +
                "Voucher numbers restart every financial year.",
            type = SettingType.INTEGER,
            defaultValue = "4",
            min = 1,
            max = 12,
        ),
        SettingDefinition(
            key = SocietySettingKeys.FINANCE_APPROVAL_ABOVE,
            group = SettingGroup.FINANCE,
            label = "Approval needed above (rupees)",
            description = "Expenses, journal entries and refunds above this amount count only after a second " +
                "person approves them. 0 means no approval is needed.",
            type = SettingType.INTEGER,
            defaultValue = "0",
            min = 0,
            max = 1_000_000_000,
        ),
        SettingDefinition(
            key = SocietySettingKeys.RESIDENTS_SEE_FINANCE_SUMMARY,
            group = SettingGroup.FINANCE,
            label = "Residents see the finance summary",
            description = "On: residents see monthly income and expense totals and the cash and bank balance. " +
                "They never see individual entries.",
            type = SettingType.BOOLEAN,
            defaultValue = "false",
        ),
        SettingDefinition(
            key = SocietySettingKeys.RECONCILIATION_MATCH_DAYS,
            group = SettingGroup.FINANCE,
            label = "Bank matching window (days)",
            description = "When reconciling a bank statement, an entry in the books is matched to a bank line of " +
                "the same amount up to this many days apart. 0 means the same day only.",
            type = SettingType.INTEGER,
            defaultValue = "3",
            min = 0,
            max = 15,
        ),
        SettingDefinition(
            key = SocietySettingKeys.POLICY_APPROVAL_REQUIRED,
            group = SettingGroup.POLICIES,
            label = "Policies need approval",
            description = "On: a policy or a change to one is published only after the chairman, secretary or " +
                "an admin approves it. Off: its author publishes it directly.",
            type = SettingType.BOOLEAN,
            defaultValue = "true",
        ),
        SettingDefinition(
            key = SocietySettingKeys.POLICY_SELF_APPROVAL_ALLOWED,
            group = SettingGroup.POLICIES,
            label = "The chairman may approve their own policies",
            description = "The secretary and above always approve and publish their own policies. Off: a policy the " +
                "chairman drafts waits for the secretary or an admin. On (for small committees): the chairman " +
                "approves their own too.",
            type = SettingType.BOOLEAN,
            defaultValue = "false",
        ),
        SettingDefinition(
            key = SocietySettingKeys.POLICY_REMINDER_DAYS,
            group = SettingGroup.POLICIES,
            label = "Days between acknowledgement reminders",
            description = "A flat that hasn't acknowledged a policy is reminded again only after this many days.",
            type = SettingType.INTEGER,
            defaultValue = "7",
            min = 1,
            max = 60,
        ),
        SettingDefinition(
            key = SocietySettingKeys.AGM_NOTICE_DAYS,
            group = SettingGroup.GOVERNANCE,
            label = "AGM notice (days)",
            description = "The notice of an annual general meeting goes out at least this many days before it. " +
                "Sending it later needs a reason, which is kept with the meeting.",
            type = SettingType.INTEGER,
            defaultValue = "14",
            min = 0,
            max = 60,
        ),
        SettingDefinition(
            key = SocietySettingKeys.SGM_NOTICE_DAYS,
            group = SettingGroup.GOVERNANCE,
            label = "Special general meeting notice (days)",
            description = "The shortest notice for a special general meeting; later needs a reason.",
            type = SettingType.INTEGER,
            defaultValue = "7",
            min = 0,
            max = 60,
        ),
        SettingDefinition(
            key = SocietySettingKeys.COMMITTEE_NOTICE_DAYS,
            group = SettingGroup.GOVERNANCE,
            label = "Committee meeting notice (days)",
            description = "The shortest notice for a managing committee meeting; later needs a reason.",
            type = SettingType.INTEGER,
            defaultValue = "2",
            min = 0,
            max = 30,
        ),
        SettingDefinition(
            key = SocietySettingKeys.GENERAL_QUORUM_PERCENT,
            group = SettingGroup.GOVERNANCE,
            label = "General meeting quorum (% of member flats)",
            description = "A general meeting has quorum when this share of member flats is present, or the " +
                "number below, whichever is less.",
            type = SettingType.INTEGER,
            defaultValue = "67",
            min = 1,
            max = 100,
        ),
        SettingDefinition(
            key = SocietySettingKeys.GENERAL_QUORUM_CAP,
            group = SettingGroup.GOVERNANCE,
            label = "General meeting quorum (at most, flats)",
            description = "Quorum never needs more member flats than this. 0 means no cap: only the percentage counts.",
            type = SettingType.INTEGER,
            defaultValue = "20",
            min = 0,
            max = 10_000,
        ),
        SettingDefinition(
            key = SocietySettingKeys.COMMITTEE_QUORUM_PERCENT,
            group = SettingGroup.GOVERNANCE,
            label = "Committee meeting quorum (% of members)",
            description = "A managing committee meeting has quorum when at least this share of its members " +
                "is present (51 = more than half).",
            type = SettingType.INTEGER,
            defaultValue = "51",
            min = 1,
            max = 100,
        ),
        SettingDefinition(
            key = SocietySettingKeys.MEETING_REMINDER_HOURS,
            group = SettingGroup.GOVERNANCE,
            label = "Meeting reminder (hours before)",
            description = "Invitees get a reminder this many hours before a meeting. 0 means no reminder.",
            type = SettingType.INTEGER,
            defaultValue = "24",
            min = 0,
            max = 168,
        ),
    )

    private val byKey = all.associateBy { it.key }

    init {
        require(byKey.size == all.size) { "Duplicate society setting keys" }
    }

    fun find(key: String): SettingDefinition? = byKey[key]

    fun get(key: String): SettingDefinition = byKey[key] ?: throw IllegalArgumentException("Unknown setting: $key")
}

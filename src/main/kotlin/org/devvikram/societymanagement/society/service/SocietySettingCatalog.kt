package org.devvikram.societymanagement.society.service

import java.math.BigInteger

enum class SettingType { BOOLEAN, INTEGER, TEXT, CHOICE }

// Sections of the settings page, in display order.
enum class SettingGroup(val label: String) {
    MAINTENANCE("Maintenance"),
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
    )

    private val byKey = all.associateBy { it.key }

    init {
        require(byKey.size == all.size) { "Duplicate society setting keys" }
    }

    fun find(key: String): SettingDefinition? = byKey[key]

    fun get(key: String): SettingDefinition = byKey[key] ?: throw IllegalArgumentException("Unknown setting: $key")
}

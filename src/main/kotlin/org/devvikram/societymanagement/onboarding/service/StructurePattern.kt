package org.devvikram.societymanagement.onboarding.service

// One wing described as a pattern: floors from–to, flats per floor and how they are numbered. Tokens in
// `numbering`: {floor} (floor number), {nn} (flat on the floor, two digits), {n} (flat on the floor) and
// {wing}. "{floor}{nn}" gives 101, 102 … 1204. `skip` leaves numbers out (e.g. no 13th floor, a merged
// flat); `extraUnits` adds named units such as ground-floor shops.
data class WingPattern(
    val wing: String,
    val floorsFrom: Int,
    val floorsTo: Int,
    val flatsPerFloor: Int,
    val numbering: String = DEFAULT_NUMBERING,
    val skip: List<String> = emptyList(),
    val extraUnits: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_NUMBERING = "{floor}{nn}"
    }
}

// Pure: turns patterns into flat numbers, so the numbering rules are unit-tested without a database.
object StructurePattern {
    const val MAX_FLATS = 5000
    private val TOKEN = Regex("""\{(floor|nn|n|wing)}""")

    fun flatNumbers(pattern: WingPattern): List<String> {
        val wing = pattern.wing.trim()
        require(wing.isNotEmpty()) { "Every wing needs a name" }
        require(wing.length <= 50) { "Wing name $wing is too long (50 characters at most)" }
        require(pattern.floorsFrom in 0..200 && pattern.floorsTo in 0..200) { "Wing $wing: floors are numbered 0 to 200" }
        require(pattern.floorsFrom <= pattern.floorsTo) { "Wing $wing: the first floor must not be above the last" }
        require(pattern.flatsPerFloor in 0..50) { "Wing $wing: 0 to 50 flats per floor" }
        val numbering = pattern.numbering.trim().ifEmpty { WingPattern.DEFAULT_NUMBERING }
        require(TOKEN.findAll(numbering).any { it.groupValues[1] in setOf("nn", "n") } || pattern.flatsPerFloor <= 1) {
            "Wing $wing: the numbering needs {nn} or {n} to tell the flats on a floor apart"
        }
        require(TOKEN.findAll(numbering).any { it.groupValues[1] == "floor" } || pattern.floorsFrom == pattern.floorsTo) {
            "Wing $wing: the numbering needs {floor} to tell the floors apart"
        }
        val leaveOut = pattern.skip.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val numbers = mutableListOf<String>()
        if (pattern.flatsPerFloor > 0) {
            for (floor in pattern.floorsFrom..pattern.floorsTo) {
                for (n in 1..pattern.flatsPerFloor) {
                    val number = TOKEN.replace(numbering) {
                        when (it.groupValues[1]) {
                            "floor" -> floor.toString()
                            "nn" -> n.toString().padStart(2, '0')
                            "n" -> n.toString()
                            else -> wing
                        }
                    }.trim()
                    if (number !in leaveOut) numbers += number
                }
            }
        }
        numbers += pattern.extraUnits.map { it.trim() }.filter { it.isNotEmpty() && it !in leaveOut }

        require(numbers.isNotEmpty()) { "Wing $wing has no flats" }
        require(numbers.all { it.length <= 20 }) { "Wing $wing: flat numbers are at most 20 characters" }
        val repeated = numbers.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(repeated.isEmpty()) { "Wing $wing: the numbering gives ${repeated.take(5).joinToString()} more than once" }
        return numbers
    }

    // Every wing's numbers; wings named twice in one request are refused.
    fun plan(patterns: List<WingPattern>): Map<String, List<String>> {
        require(patterns.isNotEmpty()) { "Describe at least one wing" }
        val names = patterns.map { it.wing.trim().lowercase() }
        require(names.size == names.toSet().size) { "Each wing may appear only once" }
        val plan = patterns.associate { it.wing.trim() to flatNumbers(it) }
        require(plan.values.sumOf { it.size } <= MAX_FLATS) { "At most $MAX_FLATS flats at a time" }
        return plan
    }
}

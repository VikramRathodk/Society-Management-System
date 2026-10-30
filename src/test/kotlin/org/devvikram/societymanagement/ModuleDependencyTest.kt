package org.devvikram.societymanagement

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// Guards the modular-monolith boundaries. Each top-level package under the root is a module and may
// use only the modules listed for it here. Adding a module or a dependency means editing this map,
// which keeps the dependency graph a deliberate decision (and acyclic). Reads source references, so
// it needs no extra library and no database.
class ModuleDependencyTest {

    private val allowed: Map<String, Set<String>> = mapOf(
        "common" to emptySet(),
        "notification" to emptySet(),
        "authorization" to setOf("common"),
        "society" to setOf("common", "authorization"),
        "user" to setOf("common", "authorization", "society", "notification"),
        "auth" to setOf("common", "authorization", "user"),
        "visitor" to setOf("common", "authorization", "society", "user", "notification"),
        "communication" to setOf("common", "authorization", "society", "user", "notification"),
        "complaint" to setOf("common", "authorization", "society", "user", "notification"),
    )

    private val root = "org.devvikram.societymanagement"
    private val sourceRoot = File("src/main/kotlin/" + root.replace('.', '/'))
    private val reference = Regex("""\b${Regex.escape(root)}\.([a-z][a-z0-9_]*)\.""")

    @Test
    fun `modules only use the modules they are allowed to`() {
        assertTrue(sourceRoot.isDirectory, "Source root not found: ${sourceRoot.absolutePath}")
        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val relative = file.relativeTo(sourceRoot).invariantSeparatorsPath
            if ('/' !in relative) return@forEach // the application class at the root
            val module = relative.substringBefore('/')
            val permitted = allowed[module] ?: run {
                violations += "$relative: module '$module' is not declared in ModuleDependencyTest"
                return@forEach
            }
            file.readLines().filterNot { it.startsWith("package ") }.forEach { line ->
                reference.findAll(line).map { it.groupValues[1] }
                    .filter { it != module && it !in permitted }
                    .forEach { violations += "$relative: '$module' must not use '$it'" }
            }
        }
        if (violations.isNotEmpty()) fail(violations.distinct().joinToString("\n"))
    }

    @Test
    fun `declared module dependencies have no cycles`() {
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(module: String, path: List<String>) {
            if (module in done) return
            if (!visiting.add(module)) fail("Cycle: ${(path + module).joinToString(" -> ")}")
            allowed[module].orEmpty().forEach { visit(it, path + module) }
            visiting.remove(module)
            done.add(module)
        }
        allowed.keys.forEach { visit(it, emptyList()) }
    }
}

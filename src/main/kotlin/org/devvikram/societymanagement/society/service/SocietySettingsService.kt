package org.devvikram.societymanagement.society.service

import org.devvikram.societymanagement.society.model.SocietySetting
import org.devvikram.societymanagement.society.repository.SocietySettingRepository
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant

// A setting as one society has it: its own value, or the default (customized = false).
data class SettingValue(
    val definition: SettingDefinition,
    val value: String,
    val customized: Boolean,
    val updatedAt: Instant?,
    val updatedBy: Long?,
)

// Reads and changes per-society settings. Other modules read single values through boolean()/integer()/
// text(), which are cached per society, since they run on hot paths like listing bills.
@Service
class SocietySettingsService(
    private val repository: SocietySettingRepository,
    private val societyService: SocietyService,
    cacheManager: CacheManager,
) {
    private val cache = cacheManager.getCache(CACHE) ?: error("Cache '$CACHE' is not configured")

    // Every setting in catalog order, with the society's value or the default.
    fun settingsOf(societyId: Long): List<SettingValue> {
        societyService.getById(societyId)
        val stored = repository.findBySociety(societyId).associateBy { it.key }
        return SocietySettingCatalog.all.map { definition ->
            stored[definition.key]
                ?.let { SettingValue(definition, it.value, true, it.updatedAt, it.updatedBy) }
                ?: SettingValue(definition, definition.defaultValue, false, null, null)
        }
    }

    fun boolean(societyId: Long, key: String): Boolean = valueOf(societyId, key, SettingType.BOOLEAN).toBooleanStrict()

    fun integer(societyId: Long, key: String): Long = valueOf(societyId, key, SettingType.INTEGER).toLong()

    fun text(societyId: Long, key: String): String = valueOf(societyId, key, SettingType.TEXT, SettingType.CHOICE)

    // Applies only the given keys: a value sets it, null puts it back to the default. All or nothing:
    // one invalid or unknown key rejects the whole change.
    @Transactional
    fun update(societyId: Long, changes: Map<String, Any?>, userId: Long) {
        societyService.getById(societyId)
        require(changes.isNotEmpty()) { "No settings to change" }
        val unknown = changes.keys.filter { SocietySettingCatalog.find(it) == null }
        require(unknown.isEmpty()) { "Unknown settings: ${unknown.joinToString()}" }
        val values = changes.mapValues { (key, raw) -> raw?.let { SocietySettingCatalog.get(key).normalize(it) } }

        val stored = repository.findBySociety(societyId).associateBy { it.key }
        val now = Instant.now()
        for ((key, value) in values) {
            val existing = stored[key]
            when {
                value == null -> if (existing != null) repository.delete(societyId, key)
                existing == null -> repository.save(SocietySetting(societyId, key, value, now, userId))
                existing.value != value -> {
                    existing.value = value
                    existing.updatedAt = now
                    existing.updatedBy = userId
                    repository.save(existing)
                }
            }
        }
        evict(societyId)
    }

    private fun valueOf(societyId: Long, key: String, vararg types: SettingType): String {
        val definition = SocietySettingCatalog.get(key)
        check(definition.type in types) { "Setting $key is ${definition.type}, not ${types.joinToString()}" }
        return overrides(societyId)[key] ?: definition.defaultValue
    }

    private fun overrides(societyId: Long): Map<String, String> =
        cache.get(societyId) { repository.findBySociety(societyId).associate { it.key to it.value } }!!

    // Now, so this transaction reads its own change, and again after commit, so a read that cached the old
    // values while the change was still uncommitted doesn't keep them.
    private fun evict(societyId: Long) {
        cache.evict(societyId)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = cache.evict(societyId)
            })
        }
    }

    companion object {
        const val CACHE = "societySettings"
    }
}

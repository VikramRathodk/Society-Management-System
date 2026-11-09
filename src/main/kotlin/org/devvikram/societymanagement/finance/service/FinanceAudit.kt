package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.finance.model.AuditAction
import org.devvikram.societymanagement.finance.model.AuditEntityType
import org.devvikram.societymanagement.finance.model.FinanceAuditEntry
import org.devvikram.societymanagement.finance.repository.AuditRepository
import org.devvikram.societymanagement.finance.repository.BooksRepository
import org.devvikram.societymanagement.society.service.SettingGroup
import org.devvikram.societymanagement.society.service.SocietySettingCatalog
import org.devvikram.societymanagement.society.service.SocietySettingKeys
import org.devvikram.societymanagement.society.service.SocietySettingsChanged
import org.devvikram.societymanagement.user.repository.UserRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

data class AuditPage(val entries: List<FinanceAuditEntry>, val names: Map<Long, String>)

// Writes the finance audit log. Callers pass the changed fields only; values must never be secrets.
// Always joins the caller's transaction, so the entry commits or rolls back with the change.
@Service
class FinanceAudit(
    private val auditRepository: AuditRepository,
    private val userRepository: UserRepository,
    private val jsonMapper: JsonMapper,
) {
    // Newest first; `names` maps each performer's id to their name.
    fun search(societyId: Long, entityType: AuditEntityType?, entityId: Long?, page: Int, size: Int): AuditPage {
        val entries = auditRepository.search(societyId, entityType, entityId, page, size)
        val names = userRepository.findAllById(entries.map { it.performedBy }.toSet()).associate { it.id!! to it.name }
        return AuditPage(entries, names)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        societyId: Long,
        entityType: AuditEntityType,
        entityId: Long?,
        action: AuditAction,
        userId: Long,
        oldValues: Map<String, Any?>? = null,
        newValues: Map<String, Any?>? = null,
        reason: String? = null,
    ) {
        auditRepository.add(
            FinanceAuditEntry(
                societyId = societyId,
                entityType = entityType,
                entityId = entityId,
                action = action,
                oldValues = oldValues?.let(jsonMapper::writeValueAsString),
                newValues = newValues?.let(jsonMapper::writeValueAsString),
                reason = reason,
                performedBy = userId,
            )
        )
    }
}

// Finance settings live in the general society settings; their changes are audited here, in the same
// transaction (a plain @EventListener runs inside the publisher's transaction). The financial year can't
// move once the books are started: voucher numbers and every report period depend on it. Throwing here
// rolls the whole settings change back (400).
@Component
class FinanceSettingsListener(
    private val audit: FinanceAudit,
    private val booksRepository: BooksRepository,
) {
    @EventListener
    fun on(event: SocietySettingsChanged) {
        val finance = event.changes.filter { SocietySettingCatalog.find(it.key)?.group == SettingGroup.FINANCE }
        if (finance.isEmpty()) return
        require(finance.none { it.key == SocietySettingKeys.FINANCIAL_YEAR_START_MONTH } ||
            booksRepository.findBySocietyId(event.societyId) == null) {
            "The financial year can't be changed after the books are started"
        }
        audit.record(
            societyId = event.societyId,
            entityType = AuditEntityType.SETTING,
            entityId = null,
            action = AuditAction.UPDATE,
            userId = event.userId,
            oldValues = finance.associate { it.key to it.oldValue },
            newValues = finance.associate { it.key to it.newValue },
        )
    }
}

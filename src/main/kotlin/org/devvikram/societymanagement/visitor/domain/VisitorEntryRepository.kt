package org.devvikram.societymanagement.visitor.domain

import org.devvikram.societymanagement.user.domain.AppUser
import java.time.Instant

// flatIds == null means every flat in the society; an empty set matches nothing.
data class VisitorSearch(
    val societyId: Long,
    val flatIds: Set<Long>? = null,
    val status: VisitorStatus? = null,
    val page: Int = 0,
    val size: Int = 20,
)

// Reads return entries with society, flat, loggedBy and respondedBy loaded (open-in-view is off).
// Status changes are conditional single-statement updates — they apply only if the entry is still
// in the expected state and return whether they did, so two residents responding at once can't
// both win and a gate action can't skip a step.
interface VisitorEntryRepository {
    fun save(entry: VisitorEntry): VisitorEntry
    fun findById(id: Long): VisitorEntry?
    fun search(search: VisitorSearch): List<VisitorEntry>
    fun respond(id: Long, decision: VisitorStatus, responder: AppUser, asOverride: Boolean, at: Instant): Boolean
    fun markEntered(id: Long, at: Instant): Boolean
    fun markExited(id: Long, at: Instant): Boolean
    fun delete(entry: VisitorEntry)
}

package org.devvikram.societymanagement.visitor.application

import org.devvikram.societymanagement.common.ConflictException
import org.devvikram.societymanagement.common.storage.ImageFormat
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.society.domain.FlatRepository
import org.devvikram.societymanagement.user.domain.UserRepository
import org.devvikram.societymanagement.visitor.domain.VisitorEntry
import org.devvikram.societymanagement.visitor.domain.VisitorEntryRepository
import org.devvikram.societymanagement.visitor.domain.VisitorSearch
import org.devvikram.societymanagement.visitor.domain.VisitorStatus
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class LogVisitorCommand(
    val flatId: Long,
    val visitorName: String,
    val visitorPhone: String? = null,
    val purpose: String? = null,
    val photo: ByteArray? = null,
)

// Visitor lifecycle and its invariants. *Who* may act (guard of the society, resident of the flat, override)
// is decided by the caller (VisitorController), not here. Write methods return ids, not entities:
// callers re-read through getById so the response has every association loaded.
@Service
class VisitorService(
    private val visitorEntryRepository: VisitorEntryRepository,
    private val flatRepository: FlatRepository,
    private val userRepository: UserRepository,
    private val photoStorage: PhotoStorage,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun logVisitor(command: LogVisitorCommand, guardId: Long): Long {
        val flat = flatRepository.findById(command.flatId)
            ?: throw NoSuchElementException("Flat ${command.flatId} not found")
        val guard = userRepository.findById(guardId) ?: throw NoSuchElementException("User $guardId not found")
        val entry = visitorEntryRepository.save(
            VisitorEntry(
                society = flat.wing.society,
                flat = flat,
                visitorName = command.visitorName,
                visitorPhone = command.visitorPhone,
                purpose = command.purpose,
                photoKey = command.photo?.let(::storePhoto),
                loggedBy = guard,
            )
        )
        events.publishEvent(
            VisitorLogged(entry.id!!, flat.id!!, flat.flatNumber, entry.visitorName, entry.purpose)
        )
        return entry.id!!
    }

    // First response wins: the conditional update applies only while the entry is still PENDING.
    // asOverride: the responder is a society authority deciding on the flat's behalf, not a resident.
    @Transactional
    fun respond(id: Long, decision: VisitorStatus, responderId: Long, asOverride: Boolean = false) {
        require(decision == VisitorStatus.APPROVED || decision == VisitorStatus.REJECTED) {
            "A visitor can only be approved or rejected"
        }
        val responder = userRepository.findById(responderId)
            ?: throw NoSuchElementException("User $responderId not found")
        if (!visitorEntryRepository.respond(id, decision, responder, asOverride, Instant.now())) {
            val current = getById(id)
            throw ConflictException(
                current.respondedBy?.let { "Already responded by ${it.name}" + if (current.respondedAsOverride) " (override)" else "" }
                    ?: "Visitor is ${current.status}, not awaiting a response"
            )
        }
        val entry = getById(id)
        events.publishEvent(
            VisitorResponded(
                entry.id!!, entry.loggedBy.id!!, entry.flat.id!!, entry.visitorName, entry.flat.flatNumber,
                decision, responder.name, asOverride,
            )
        )
    }

    @Transactional
    fun markEntered(id: Long) {
        if (!visitorEntryRepository.markEntered(id, Instant.now())) {
            throw ConflictException("Visitor must be APPROVED to enter; it is ${getById(id).status}")
        }
    }

    @Transactional
    fun markExited(id: Long) {
        if (!visitorEntryRepository.markExited(id, Instant.now())) {
            throw ConflictException("Visitor must have ENTERED to exit; it is ${getById(id).status}")
        }
    }

    @Transactional
    fun delete(id: Long) {
        val entry = getById(id)
        visitorEntryRepository.delete(entry)
        entry.photoKey?.let(photoStorage::delete)
    }

    fun getById(id: Long): VisitorEntry =
        visitorEntryRepository.findById(id) ?: throw NoSuchElementException("Visitor $id not found")

    fun search(search: VisitorSearch): List<VisitorEntry> = visitorEntryRepository.search(search)

    fun getPhoto(id: Long): Photo =
        getById(id).photoKey?.let(photoStorage::load) ?: throw NoSuchElementException("Visitor $id has no photo")

    private fun storePhoto(data: ByteArray): String {
        require(data.isNotEmpty()) { "Photo is empty" }
        require(data.size <= ImageFormat.MAX_BYTES) { "Photo must be at most ${ImageFormat.MAX_BYTES / (1024 * 1024)}MB" }
        val contentType = requireNotNull(ImageFormat.detect(data)) { "Photo must be a JPEG, PNG or WebP image" }
        return photoStorage.save(data, contentType)
    }
}

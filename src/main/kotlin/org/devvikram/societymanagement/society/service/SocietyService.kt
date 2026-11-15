package org.devvikram.societymanagement.society.service

import org.devvikram.societymanagement.common.exception.ConflictException
import org.devvikram.societymanagement.common.storage.ImageFormat
import org.devvikram.societymanagement.common.storage.Photo
import org.devvikram.societymanagement.common.storage.PhotoStorage
import org.devvikram.societymanagement.society.model.Society
import org.devvikram.societymanagement.society.model.SocietySource
import org.devvikram.societymanagement.society.model.SocietyStatus
import org.devvikram.societymanagement.society.repository.ComplexRepository
import org.devvikram.societymanagement.society.repository.SocietyRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

// The society's own details. Blank strings clear a field; null leaves it unchanged.
data class SocietyProfileCommand(
    val name: String? = null,
    val address: String? = null,
    val registrationNumber: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val contactPhone: String? = null,
    val contactEmail: String? = null,
    val notes: String? = null,
)

@Service
class SocietyService(
    private val societyRepository: SocietyRepository,
    private val complexRepository: ComplexRepository,
    private val photoStorage: PhotoStorage,
) {
    // Callers that build a society for immediate use (seeding, tests) get a LIVE one by default; the API
    // and onboarding ask for SETUP.
    @Transactional
    fun createSociety(
        name: String,
        address: String? = null,
        complexId: Long? = null,
        status: SocietyStatus = SocietyStatus.LIVE,
        source: SocietySource = SocietySource.PLATFORM,
    ): Society {
        val complex = complexId?.let {
            complexRepository.findById(it) ?: throw NoSuchElementException("Complex $it not found")
        }
        return societyRepository.save(
            Society(name = name, address = address, complex = complex, status = status, source = source)
        )
    }

    fun getById(id: Long): Society =
        societyRepository.findById(id) ?: throw NoSuchElementException("Society $id not found")

    fun getAll(): List<Society> = societyRepository.findAll()

    @Transactional
    fun updateProfile(id: Long, command: SocietyProfileCommand): Society {
        val society = getById(id)
        command.name?.let {
            require(it.isNotBlank()) { "The society needs a name" }
            society.name = it.trim()
        }
        command.address?.let { society.address = it.trim().ifEmpty { null } }
        command.registrationNumber?.let { society.registrationNumber = it.trim().ifEmpty { null } }
        command.city?.let { society.city = it.trim().ifEmpty { null } }
        command.state?.let { society.state = it.trim().ifEmpty { null } }
        command.pincode?.let { society.pincode = it.trim().ifEmpty { null } }
        command.contactPhone?.let { society.contactPhone = it.trim().ifEmpty { null } }
        command.contactEmail?.let { society.contactEmail = it.trim().ifEmpty { null } }
        command.notes?.let { society.notes = it.trim().ifEmpty { null } }
        return societyRepository.save(society)
    }

    // One-way: a society in SETUP becomes LIVE once its profile is complete.
    @Transactional
    fun goLive(id: Long, userId: Long, now: Instant): Society {
        val society = getById(id)
        if (!society.isSetup) throw ConflictException("${society.name} is already live")
        val missing = society.missingProfile()
        require(missing.isEmpty()) { "Complete the society profile first (missing: ${missing.joinToString()})" }
        society.status = SocietyStatus.LIVE
        society.wentLiveAt = now
        society.wentLiveBy = userId
        return societyRepository.save(society)
    }

    // ---- Logo ----

    @Transactional
    fun setLogo(id: Long, data: ByteArray): Society {
        val society = getById(id)
        require(data.isNotEmpty()) { "The file is empty" }
        require(data.size <= LOGO_MAX_BYTES) { "A logo can be at most 1 MB" }
        val contentType = ImageFormat.detect(data) ?: throw IllegalArgumentException("A logo must be a JPEG, PNG or WebP image")
        val old = society.logoKey
        society.logoKey = photoStorage.save(data, contentType)
        societyRepository.save(society)
        old?.let(photoStorage::delete)
        return society
    }

    fun logo(id: Long): Photo? = getById(id).logoKey?.let(photoStorage::load)

    @Transactional
    fun removeLogo(id: Long): Society {
        val society = getById(id)
        val old = society.logoKey ?: return society
        society.logoKey = null
        societyRepository.save(society)
        photoStorage.delete(old)
        return society
    }

    // Wings, flats and role assignments go with it (ON DELETE CASCADE). Anything with history —
    // visitors, notices, complaints, staff, parking — blocks the delete instead of being lost.
    @Transactional
    fun delete(id: Long) {
        val society = getById(id)
        try {
            societyRepository.delete(society)
        } catch (e: DataIntegrityViolationException) {
            throw ConflictException(
                "Society ${society.name} still has visitors, notices, complaints, maintenance bills, finance books, policies, staff or parking slots and cannot be deleted"
            )
        }
    }

    companion object {
        const val LOGO_MAX_BYTES = 1024 * 1024
    }
}

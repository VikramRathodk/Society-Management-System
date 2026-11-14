package org.devvikram.societymanagement.onboarding.repository

import org.devvikram.societymanagement.onboarding.model.ApplicationStatus
import org.devvikram.societymanagement.onboarding.model.SocietyApplication
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SocietyApplicationRepositoryImpl(
    private val jpaRepository: SocietyApplicationJpaRepository,
) : SocietyApplicationRepository {
    override fun save(application: SocietyApplication): SocietyApplication = jpaRepository.save(application)

    override fun findById(id: Long): SocietyApplication? = jpaRepository.findById(id).orElse(null)

    override fun findByIdAndPhone(id: Long, phone: String): SocietyApplication? =
        jpaRepository.findByIdAndApplicantPhone(id, phone)

    override fun hasPending(phone: String): Boolean =
        jpaRepository.existsByApplicantPhoneAndStatus(phone, ApplicationStatus.PENDING)

    override fun countFromIpSince(ip: String, since: Instant): Long =
        jpaRepository.countByCreatedIpAndCreatedAtAfter(ip, since)

    override fun list(status: ApplicationStatus?): List<SocietyApplication> =
        if (status == null) jpaRepository.findAllByOrderByCreatedAtDesc()
        else jpaRepository.findByStatusOrderByCreatedAtDesc(status)
}

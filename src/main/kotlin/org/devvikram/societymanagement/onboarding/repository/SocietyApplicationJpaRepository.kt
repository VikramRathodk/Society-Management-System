package org.devvikram.societymanagement.onboarding.repository

import org.devvikram.societymanagement.onboarding.model.ApplicationStatus
import org.devvikram.societymanagement.onboarding.model.SocietyApplication
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface SocietyApplicationJpaRepository : JpaRepository<SocietyApplication, Long> {
    fun existsByApplicantPhoneAndStatus(phone: String, status: ApplicationStatus): Boolean
    fun countByCreatedIpAndCreatedAtAfter(ip: String, after: Instant): Long
    fun findByStatusOrderByCreatedAtDesc(status: ApplicationStatus): List<SocietyApplication>
    fun findAllByOrderByCreatedAtDesc(): List<SocietyApplication>
    fun findByIdAndApplicantPhone(id: Long, phone: String): SocietyApplication?
}

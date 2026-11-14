package org.devvikram.societymanagement.onboarding.repository

import org.devvikram.societymanagement.onboarding.model.ApplicationStatus
import org.devvikram.societymanagement.onboarding.model.SocietyApplication
import java.time.Instant

interface SocietyApplicationRepository {
    fun save(application: SocietyApplication): SocietyApplication
    fun findById(id: Long): SocietyApplication?
    fun findByIdAndPhone(id: Long, phone: String): SocietyApplication?
    fun hasPending(phone: String): Boolean
    fun countFromIpSince(ip: String, since: Instant): Long
    // Newest first; status null = all.
    fun list(status: ApplicationStatus?): List<SocietyApplication>
}

package org.devvikram.societymanagement.onboarding.repository

import org.devvikram.societymanagement.onboarding.model.ResidentImport

interface ResidentImportRepository {
    fun save(residentImport: ResidentImport): ResidentImport
    fun delete(residentImport: ResidentImport)
    // Writes pending changes now, e.g. removing the old open sheet before a new one takes its place.
    fun flush()
    fun findOpen(societyId: Long): ResidentImport?
}

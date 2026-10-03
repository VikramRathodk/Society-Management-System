package org.devvikram.societymanagement.security.permission

import org.devvikram.societymanagement.user.domain.Role
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase

// Exercises the JPQL query against the real V2__seed_permissions.sql data (permission/role_permission
// tables are Flyway-managed, not created by @DataJpaTest) — matches the pattern used by the other
// *RepositoryTest classes, which run against the real local Postgres.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RolePermissionJpaRepositoryTest {

    @Autowired
    private lateinit var rolePermissionRepository: RolePermissionJpaRepository

    @Test
    fun `admin has full user account management permissions`() {
        val codes = rolePermissionRepository.findPermissionCodesByRole(Role.ADMIN)

        assertTrue(codes.contains("USER.ACCOUNT.CREATE"))
        assertTrue(codes.contains("USER.ACCOUNT.VIEW"))
        assertTrue(codes.contains("USER.ACCOUNT.UPDATE"))
        assertTrue(codes.contains("USER.ACCOUNT.DELETE"))
    }

    @Test
    fun `resident cannot create user accounts`() {
        val codes = rolePermissionRepository.findPermissionCodesByRole(Role.RESIDENT)

        assertFalse(codes.contains("USER.ACCOUNT.CREATE"))
        assertTrue(codes.contains("COMPLAINT.TICKET.CREATE"))
    }

    @Test
    fun `guard is scoped to visitor entry logging, not approval`() {
        val codes = rolePermissionRepository.findPermissionCodesByRole(Role.GUARD)

        assertTrue(codes.contains("VISITOR.ENTRY.CREATE"))
        assertFalse(codes.contains("VISITOR.APPROVAL.APPROVE"))
    }
}

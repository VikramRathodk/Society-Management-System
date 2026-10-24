package org.devvikram.societymanagement.security

import org.devvikram.societymanagement.security.role.RoleRepositoryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import

// Resolves the role hierarchy against the real V3__dynamic_roles.sql seed data (role/role_inheritance/
// role_permission are Flyway-managed) — same real-local-Postgres pattern as the *RepositoryTest classes.
// No caching here: @DataJpaTest doesn't enable it, so every call hits the database.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PermissionService::class, RoleRepositoryImpl::class)
class PermissionServiceTest {

    @Autowired
    private lateinit var permissionService: PermissionService

    @Test
    fun `secretary has full user account management permissions`() {
        val codes = permissionService.getPermissionsForRole("SECRETARY")

        assertTrue(codes.contains("USER.ACCOUNT.CREATE"))
        assertTrue(codes.contains("USER.ACCOUNT.VIEW"))
        assertTrue(codes.contains("USER.ACCOUNT.UPDATE"))
        assertTrue(codes.contains("USER.ACCOUNT.DELETE"))
    }

    @Test
    fun `admin inherits everything secretary has`() {
        val admin = permissionService.getPermissionsForRole("ADMIN")

        assertTrue(admin.containsAll(permissionService.getPermissionsForRole("SECRETARY")))
    }

    @Test
    fun `resident cannot create user accounts`() {
        val codes = permissionService.getPermissionsForRole("RESIDENT")

        assertFalse(codes.contains("USER.ACCOUNT.CREATE"))
        assertTrue(codes.contains("COMPLAINT.TICKET.CREATE"))
    }

    @Test
    fun `guard is scoped to visitor entry logging, not approval`() {
        val codes = permissionService.getPermissionsForRole("GUARD")

        assertTrue(codes.contains("VISITOR.ENTRY.CREATE"))
        assertFalse(codes.contains("VISITOR.APPROVAL.APPROVE"))
    }

    @Test
    fun `committee inherits resident permissions but not guard ones`() {
        val codes = permissionService.getPermissionsForRole("COMMITTEE")

        assertTrue(codes.contains("COMPLAINT.TICKET.CREATE"))
        assertTrue(codes.contains("NOTICE.POST.CREATE"))
        assertFalse(codes.contains("VISITOR.ENTRY.CREATE"))
    }

    @Test
    fun `super admin gets every permission`() {
        val codes = permissionService.getPermissionsForRole("SUPER_ADMIN")

        assertTrue(codes.contains("SYSTEM.ROLE.CREATE"))
        assertTrue(codes.containsAll(permissionService.getPermissionsForRole("ADMIN")))
    }

    @Test
    fun `unknown role has no permissions`() {
        assertEquals(emptySet<String>(), permissionService.getPermissionsForRole("NO_SUCH_ROLE"))
    }

    @Test
    fun `subordinates are the roles strictly below`() {
        assertEquals(
            setOf("SECRETARY", "COMMITTEE", "GUARD", "RESIDENT"),
            permissionService.getSubordinateRoles("ADMIN"),
        )
        assertEquals(emptySet<String>(), permissionService.getSubordinateRoles("RESIDENT"))

        val belowSuperAdmin = permissionService.getSubordinateRoles("SUPER_ADMIN")
        assertTrue(belowSuperAdmin.contains("ADMIN"))
        assertFalse(belowSuperAdmin.contains("SUPER_ADMIN"))
    }
}

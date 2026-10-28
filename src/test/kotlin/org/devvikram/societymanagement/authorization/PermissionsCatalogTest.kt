package org.devvikram.societymanagement.authorization

import org.devvikram.societymanagement.authorization.repository.PermissionJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import java.lang.reflect.Modifier

// Guards against typos and missing migrations: every code in `Permissions` must be a seeded permission.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PermissionsCatalogTest {

    @Autowired
    private lateinit var permissionJpaRepository: PermissionJpaRepository

    @Test
    fun `every permission constant exists in the permission table`() {
        val constants = Permissions::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }

        val missing = constants - permissionJpaRepository.findAllCodes()

        assertEquals(emptyList<String>(), missing)
    }
}

package org.devvikram.societymanagement.finance.controller

import org.devvikram.societymanagement.authorization.Access
import org.devvikram.societymanagement.authorization.AccessService
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Component

// The books are society-wide: a permission only counts from a society role (or a platform grant), never
// from a flat-tied role, even a custom one that happens to hold a finance code.
@Component
class FinanceGuard(private val accessService: AccessService) {
    fun require(code: String, societyId: Long): Access = accessService.current().also {
        if (!it.canSocietyWide(code, societyId)) throw AccessDeniedException("Missing permission: $code for this society")
    }
}

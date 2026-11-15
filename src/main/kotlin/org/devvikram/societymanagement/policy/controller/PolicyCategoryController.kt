package org.devvikram.societymanagement.policy.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.policy.dto.CreateCategoryRequest
import org.devvikram.societymanagement.policy.dto.PolicyCategoryResponse
import org.devvikram.societymanagement.policy.dto.UpdateCategoryRequest
import org.devvikram.societymanagement.policy.service.PolicyCategoryService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Everyone in the society reads the categories (hidden ones included, for older policies); SECRETARY and
// above manage them, like the rest of the society's settings.
@RestController
class PolicyCategoryController(
    private val categoryService: PolicyCategoryService,
    private val accessService: AccessService,
) {
    @GetMapping("/api/societies/{societyId}/policy-categories")
    @RequiresPermission(Permissions.POLICY_RULE_VIEW)
    fun list(@PathVariable societyId: Long): List<PolicyCategoryResponse> {
        accessService.current().requireIn(Permissions.POLICY_RULE_VIEW, societyId)
        return categoryService.list(societyId).map(PolicyCategoryResponse::from)
    }

    @PostMapping("/api/societies/{societyId}/policy-categories")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun create(@PathVariable societyId: Long, @Valid @RequestBody request: CreateCategoryRequest): PolicyCategoryResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, societyId)
        return PolicyCategoryResponse.from(categoryService.get(categoryService.create(societyId, request.name!!)))
    }

    @PatchMapping("/api/policy-categories/{id}")
    @RequiresPermission(Permissions.SOCIETY_SETTINGS_UPDATE)
    fun update(@PathVariable id: Long, @RequestBody request: UpdateCategoryRequest): PolicyCategoryResponse {
        accessService.current().requireIn(Permissions.SOCIETY_SETTINGS_UPDATE, categoryService.get(id).societyId)
        categoryService.update(id, request.name, request.position, request.hidden)
        return PolicyCategoryResponse.from(categoryService.get(id))
    }
}

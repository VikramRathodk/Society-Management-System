package org.devvikram.societymanagement.notice.controller

import jakarta.validation.Valid
import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.authorization.RequiresPermission
import org.devvikram.societymanagement.notice.dto.CreateNoticeRequest
import org.devvikram.societymanagement.notice.dto.NoticeResponse
import org.devvikram.societymanagement.notice.dto.UpdateNoticeRequest
import org.devvikram.societymanagement.notice.model.Notice
import org.devvikram.societymanagement.notice.service.NoticeService
import org.devvikram.societymanagement.notice.service.PostNoticeCommand
import org.devvikram.societymanagement.notice.service.UpdateNoticeCommand
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Who may do what with notices (all within one society):
//  - everyone with a role in the society reads its notice board;
//  - COMMITTEE and above post, and edit/delete their own notices;
//  - holders of NOTICE.POST.MODERATE (SECRETARY and above) edit/delete anyone's.
@RestController
class NoticeController(
    private val noticeService: NoticeService,
    private val accessService: AccessService,
) {

    @PostMapping("/api/societies/{societyId}/notices")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.NOTICE_POST_CREATE)
    fun post(@PathVariable societyId: Long, @Valid @RequestBody request: CreateNoticeRequest): NoticeResponse {
        val access = accessService.current()
        access.requireIn(Permissions.NOTICE_POST_CREATE, societyId)
        val id = noticeService.post(PostNoticeCommand(societyId, request.title, request.description), access.userId)
        return NoticeResponse.from(noticeService.getById(id))
    }

    // The society's notice board, newest first.
    @GetMapping("/api/societies/{societyId}/notices")
    @RequiresPermission(Permissions.NOTICE_POST_VIEW)
    fun list(
        @PathVariable societyId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): List<NoticeResponse> {
        accessService.current().requireIn(Permissions.NOTICE_POST_VIEW, societyId)
        return noticeService.listBySociety(societyId, page.coerceAtLeast(0), size.coerceIn(1, 100))
            .map(NoticeResponse::from)
    }

    @GetMapping("/api/notices/{id}")
    @RequiresPermission(Permissions.NOTICE_POST_VIEW)
    fun getById(@PathVariable id: Long): NoticeResponse {
        val notice = noticeService.getById(id)
        accessService.current().requireIn(Permissions.NOTICE_POST_VIEW, notice.society.id!!)
        return NoticeResponse.from(notice)
    }

    @PatchMapping("/api/notices/{id}")
    @RequiresPermission(Permissions.NOTICE_POST_UPDATE)
    fun update(@PathVariable id: Long, @Valid @RequestBody request: UpdateNoticeRequest): NoticeResponse {
        val notice = actionable(id, Permissions.NOTICE_POST_UPDATE, "You can only edit your own notices")
        noticeService.update(
            id,
            UpdateNoticeCommand(title = request.title, description = request.description),
            accessService.current().userId,
        )
        return NoticeResponse.from(noticeService.getById(notice.id!!))
    }

    @DeleteMapping("/api/notices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.NOTICE_POST_DELETE)
    fun delete(@PathVariable id: Long) {
        actionable(id, Permissions.NOTICE_POST_DELETE, "You can only delete your own notices")
        noticeService.delete(id)
    }

    // The permission in the notice's society, and either authorship or the moderation permission.
    private fun actionable(id: Long, permission: String, notOwnMessage: String): Notice {
        val access = accessService.current()
        val notice = noticeService.getById(id)
        val societyId = notice.society.id!!
        access.requireIn(permission, societyId)
        if (notice.postedBy.id != access.userId && !access.can(Permissions.NOTICE_POST_MODERATE, societyId)) {
            throw AccessDeniedException(notOwnMessage)
        }
        return notice
    }
}

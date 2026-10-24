package org.devvikram.societymanagement.common

import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.BindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.NOT_FOUND, ex.message, request)

    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class)
    fun handleBadRequest(ex: RuntimeException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.BAD_REQUEST, ex.message, request)

    // Unique-index races the service-level checks can't catch (e.g. the same role assigned twice concurrently).
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleConflict(ex: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.CONFLICT, "Request conflicts with existing data", request)

    @ExceptionHandler(ConflictException::class)
    fun handleStateConflict(ex: ConflictException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.CONFLICT, ex.message, request)

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleTooLarge(ex: MaxUploadSizeExceededException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.CONTENT_TOO_LARGE, "Upload exceeds the size limit", request)

    // BindException also covers MethodArgumentNotValidException and form (@ModelAttribute) binding errors.
    @ExceptionHandler(BindException::class)
    fun handleValidation(ex: BindException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val message = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return respond(HttpStatus.BAD_REQUEST, message, request)
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleBadCredentials(ex: BadCredentialsException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.UNAUTHORIZED, "Invalid phone or password", request)

    // Login with the right credentials on a deactivated account: say so, rather than the generic 401 below.
    @ExceptionHandler(DisabledException::class)
    fun handleDisabled(ex: DisabledException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.FORBIDDEN, "Account is deactivated", request)

    // Catch-all for the rest: missing/invalid token (via SecurityConfig's entry point), etc.
    @ExceptionHandler(AuthenticationException::class)
    fun handleUnauthenticated(ex: AuthenticationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.UNAUTHORIZED, "Authentication required", request)

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        respond(HttpStatus.FORBIDDEN, ex.message, request)

    private fun respond(status: HttpStatus, message: String?, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(status).body(
            ErrorResponse(status = status.value(), error = status.reasonPhrase, message = message, path = request.requestURI)
        )
}

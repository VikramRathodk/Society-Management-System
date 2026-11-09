package org.devvikram.societymanagement.finance.service

import org.devvikram.societymanagement.authorization.AccessService
import org.devvikram.societymanagement.authorization.Permissions
import org.devvikram.societymanagement.notification.PushNotifier
import org.devvikram.societymanagement.user.repository.UserRepository
import org.devvikram.societymanagement.user.repository.UserRoleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal

// Events carry everything the message needs, so listeners never touch lazy associations. Messages name
// the voucher and amount only: approvers may see vouchers anyway, and the maker made it.
data class VoucherNeedsApproval(
    val voucherId: Long,
    val societyId: Long,
    val number: String,
    val amount: BigDecimal,
    val payee: String?,
    val makerId: Long,
    val makerName: String,
)

data class VoucherDecided(
    val voucherId: Long,
    val makerId: Long,
    val number: String,
    val approved: Boolean,
    val reason: String?,
)

// After commit, best-effort: a failed push is logged, never surfaced to whoever acted.
@Component
class FinanceNotificationListener(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val accessService: AccessService,
    private val pushNotifier: PushNotifier,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // To everyone who may approve vouchers in that society, except its maker (who can't approve it).
    @TransactionalEventListener
    fun onNeedsApproval(event: VoucherNeedsApproval) {
        try {
            val roles = userRoleRepository.findByUserIds(userRoleRepository.findUserIdsBySocietyIds(listOf(event.societyId)))
            val approverIds = roles.groupBy { it.user.id!! }
                .filter { (userId, userRoles) ->
                    userId != event.makerId && accessService.accessFor(userId, userRoles.map { it.toGrant() })
                        .canSocietyWide(Permissions.FINANCE_VOUCHER_APPROVE, event.societyId)
                }
                .keys
            val tokens = userRepository.findAllById(approverIds).filter { it.isActive }.mapNotNull { it.fcmToken }.distinct()
            if (tokens.isEmpty()) return
            val to = event.payee?.let { " to $it" } ?: ""
            pushNotifier.send(
                tokens,
                title = "Expense to approve",
                body = "${event.makerName} recorded ${event.number}: ₹${event.amount.toPlainString()}$to",
                data = mapOf("type" to "FINANCE_VOUCHER_NEEDS_APPROVAL", "voucherId" to event.voucherId.toString()),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for voucher {} awaiting approval", event.voucherId, e)
        }
    }

    @TransactionalEventListener
    fun onDecided(event: VoucherDecided) {
        try {
            val token = userRepository.findById(event.makerId)?.takeIf { it.isActive }?.fcmToken ?: return
            pushNotifier.send(
                listOf(token),
                title = if (event.approved) "Entry approved" else "Entry not approved",
                body = if (event.approved) {
                    "${event.number} is approved and now in the books"
                } else {
                    "${event.number} was rejected" + (event.reason?.let { ": $it" } ?: "")
                },
                data = mapOf(
                    "type" to if (event.approved) "FINANCE_VOUCHER_APPROVED" else "FINANCE_VOUCHER_REJECTED",
                    "voucherId" to event.voucherId.toString(),
                ),
            )
        } catch (e: Exception) {
            log.warn("Push notification failed for decided voucher {}", event.voucherId, e)
        }
    }
}

package org.devvikram.societymanagement.authorization

// A check every society permission passes besides the user's roles (implemented by the subscription
// module): may throw PaymentRequiredException when the permission's module is outside the society's
// plan, and answers a reason when the permission is paused for now (paid work while suspended), else null.
// Platform grants never pass through it.
fun interface SocietyGate {
    fun check(societyId: Long, permission: String): String?
}

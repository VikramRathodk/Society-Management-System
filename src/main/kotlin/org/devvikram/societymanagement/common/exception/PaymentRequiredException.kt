package org.devvikram.societymanagement.common.exception

// The society's subscription doesn't allow this (a module outside its plan, or paid work while it is
// suspended); answered with 402 and a message saying what to do.
class PaymentRequiredException(message: String) : RuntimeException(message)

// Request attribute holding why a permission check was refused by the subscription (set by the
// authorization module), so a later "missing permission" in the same request answers 402 with that reason.
const val PAYMENT_REQUIRED_REASON = "org.devvikram.societymanagement.paymentRequiredReason"

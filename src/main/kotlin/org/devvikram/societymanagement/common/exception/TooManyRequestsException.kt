package org.devvikram.societymanagement.common.exception

// A caller has sent too many requests of one kind recently (answered with 429).
class TooManyRequestsException(message: String) : RuntimeException(message)

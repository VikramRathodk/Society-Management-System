package org.devvikram.societymanagement.common.exception

// The request was valid but the record is no longer in a state that allows it
// (e.g. a visitor another resident already responded to). Mapped to 409.
class ConflictException(message: String) : RuntimeException(message)

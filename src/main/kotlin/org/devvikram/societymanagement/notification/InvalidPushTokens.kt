package org.devvikram.societymanagement.notification

// Published when the push provider reports tokens that will never work again (app uninstalled,
// token rotated, token from another Firebase project). Whoever stores tokens should forget them.
data class InvalidPushTokens(val tokens: Set<String>)

package org.devvikram.societymanagement.notification

import com.google.firebase.messaging.BatchResponse
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.SendResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher

class FcmPushNotifierTest {

    private lateinit var messaging: FirebaseMessaging
    private val published = mutableListOf<Any>()
    private lateinit var notifier: FcmPushNotifier

    @BeforeEach
    fun setUp() {
        messaging = mock(FirebaseMessaging::class.java)
        published.clear()
        notifier = FcmPushNotifier(messaging, ApplicationEventPublisher { published += it }, "society_default")
    }

    private fun ok(): SendResponse = mock(SendResponse::class.java).also { `when`(it.isSuccessful).thenReturn(true) }

    private fun failed(code: MessagingErrorCode): SendResponse {
        val error = mock(FirebaseMessagingException::class.java)
        `when`(error.messagingErrorCode).thenReturn(code)
        return mock(SendResponse::class.java).also { `when`(it.exception).thenReturn(error) }
    }

    private fun batch(vararg responses: SendResponse): BatchResponse =
        mock(BatchResponse::class.java).also { `when`(it.responses).thenReturn(responses.toList()) }

    @Test
    fun `sends nothing when there are no usable tokens`() {
        notifier.send(listOf("", "  "), "title", "body")

        verify(messaging, never()).sendEachForMulticast(any())
    }

    @Test
    fun `splits large audiences into calls of at most 500 tokens`() {
        val empty = batch()
        `when`(messaging.sendEachForMulticast(any())).thenReturn(empty)

        notifier.send((1..1001).map { "t$it" }, "title", "body")

        verify(messaging, times(3)).sendEachForMulticast(any())
    }

    @Test
    fun `reports only permanently dead tokens`() {
        val response = batch(
            ok(),
            failed(MessagingErrorCode.UNREGISTERED),
            failed(MessagingErrorCode.INTERNAL),
            failed(MessagingErrorCode.INVALID_ARGUMENT),
            failed(MessagingErrorCode.SENDER_ID_MISMATCH),
        )
        `when`(messaging.sendEachForMulticast(any())).thenReturn(response)

        // The duplicate and blank tokens are dropped first, so responses line up with a..e.
        notifier.send(listOf("a", "b", "", "b", "c", "d", "e"), "title", "body")

        assertEquals(listOf(InvalidPushTokens(setOf("b", "e"))), published)
    }

    @Test
    fun `swallows delivery failures`() {
        `when`(messaging.sendEachForMulticast(any())).thenThrow(IllegalStateException("network down"))

        notifier.send(listOf("a"), "title", "body")

        assertTrue(published.isEmpty())
    }
}

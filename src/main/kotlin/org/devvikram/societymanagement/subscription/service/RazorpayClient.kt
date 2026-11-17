package org.devvikram.societymanagement.subscription.service

import jakarta.annotation.PostConstruct
import org.devvikram.societymanagement.common.exception.ConflictException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Razorpay keys (deployment settings: RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET).
// Test keys in dev; with `app.razorpay.mode=live` the backend refuses to start without all three.
@Component
class RazorpaySettings(
    @Value("\${app.razorpay.key-id:}") keyId: String,
    @Value("\${app.razorpay.key-secret:}") keySecret: String,
    @Value("\${app.razorpay.webhook-secret:}") webhookSecret: String,
    @Value("\${app.razorpay.mode:test}") val mode: String,
) {
    val keyId = keyId.trim()
    val keySecret = keySecret.trim()
    val webhookSecret = webhookSecret.trim()

    // Orders and checkout need the key pair; the webhook its own secret.
    val configured: Boolean get() = keyId.isNotEmpty() && keySecret.isNotEmpty()

    @PostConstruct
    fun validate() {
        if (mode == "live") {
            check(configured && webhookSecret.isNotEmpty()) {
                "app.razorpay.mode=live needs RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET"
            }
            check(keyId.startsWith("rzp_live_")) { "app.razorpay.mode=live needs a live key (rzp_live_…)" }
        } else if (configured) {
            LoggerFactory.getLogger(javaClass).info("Razorpay in test mode with key {}", keyId)
        }
    }

    // Razorpay's signatures: hex HMAC-SHA256. Checkout signs "orderId|paymentId" with the key secret; a
    // webhook signs its raw body with the webhook secret.
    fun checkoutSignatureValid(orderId: String, paymentId: String, signature: String): Boolean =
        keySecret.isNotEmpty() && same(hmac(keySecret, "$orderId|$paymentId"), signature)

    fun webhookSignatureValid(body: String, signature: String?): Boolean =
        webhookSecret.isNotEmpty() && signature != null && same(hmac(webhookSecret, body), signature)

    companion object {
        fun hmac(secret: String, data: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        // Constant time, so a forger learns nothing from how long a refusal takes.
        private fun same(expected: String, given: String) = MessageDigest.isEqual(expected.toByteArray(), given.trim().lowercase().toByteArray())
    }
}

// Razorpay's API, as far as billing needs it: creating an order. A port so tests use a fake.
interface RazorpayClient {
    // Answers Razorpay's order id.
    fun createOrder(amountPaise: Long, receipt: String, notes: Map<String, String>): String
}

@Component
class HttpRazorpayClient(
    private val settings: RazorpaySettings,
    builder: RestClient.Builder,
) : RazorpayClient {
    private val rest = builder.baseUrl("https://api.razorpay.com/v1").build()

    override fun createOrder(amountPaise: Long, receipt: String, notes: Map<String, String>): String {
        if (!settings.configured) throw ConflictException("Online payment isn't set up yet. Pay by bank transfer or UPI and tell the platform.")
        val response = rest.post()
            .uri("/orders")
            .headers { it.setBasicAuth(settings.keyId, settings.keySecret) }
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("amount" to amountPaise, "currency" to "INR", "receipt" to receipt.take(40), "notes" to notes))
            .retrieve()
            .body(Map::class.java)
        return response?.get("id") as? String ?: throw IllegalStateException("Razorpay answered without an order id")
    }
}

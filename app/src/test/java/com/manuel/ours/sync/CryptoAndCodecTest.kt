package com.manuel.ours.sync

import com.manuel.ours.data.sync.CryptoBox
import com.manuel.ours.data.sync.LogCodec
import com.manuel.ours.data.sync.SyncEvent
import com.manuel.ours.data.sync.SyncOp
import com.manuel.ours.data.sync.SyncPayload
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CryptoAndCodecTest {

    private val secret = "K7M2QP"
    private val householdId = "household-123"
    private val key = CryptoBox.deriveKey(secret, householdId)

    private fun sampleEvent(id: String, lamport: Long) = SyncEvent(
        eventId = id,
        txnId = "txn-$id",
        op = SyncOp.UPSERT,
        lamport = lamport,
        deviceId = "deviceA",
        ownerUid = "uid-1",
        wallClock = 1_720_000_000_000L,
        payload = SyncPayload(
            amountPaise = 45_000,
            type = "DEBIT",
            merchant = "swiggy@ybl",
            category = "FOOD",
            occurredAt = 1_720_000_000_000L,
            accountTail = "1234",
            refNo = "412345678901",
            bank = "HDFC Bank",
            note = null,
            splitType = "SHARED",
            source = "SMS",
            ownerName = "Manuel",
        ),
    )

    @Test
    fun `key derivation is deterministic for the same invite`() {
        val a = CryptoBox.deriveKey(secret, householdId)
        val b = CryptoBox.deriveKey(secret, householdId)
        assertThat(a).isEqualTo(b)
        assertThat(a).hasLength(32)
    }

    @Test
    fun `different households derive different keys from the same secret`() {
        val a = CryptoBox.deriveKey(secret, "household-1")
        val b = CryptoBox.deriveKey(secret, "household-2")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `encrypt then decrypt round trips`() {
        val plain = "Rs.450 at swiggy".toByteArray()
        val sealed = CryptoBox.encrypt(key, plain)
        assertThat(sealed).isNotEqualTo(plain)
        assertThat(CryptoBox.decrypt(key, sealed)).isEqualTo(plain)
    }

    @Test
    fun `same plaintext encrypts differently each time`() {
        val plain = "same input".toByteArray()
        val a = CryptoBox.encrypt(key, plain)
        val b = CryptoBox.encrypt(key, plain)
        // Random nonce per message — otherwise repeated amounts would be visible
        // to anyone holding the file.
        assertThat(a).isNotEqualTo(b)
        assertThat(CryptoBox.decrypt(key, a)).isEqualTo(CryptoBox.decrypt(key, b))
    }

    @Test
    fun `the wrong key cannot decrypt`() {
        val sealed = CryptoBox.encrypt(key, "secret".toByteArray())
        val wrongKey = CryptoBox.deriveKey("WRONG1", householdId)
        val failed = runCatching { CryptoBox.decrypt(wrongKey, sealed) }
        assertThat(failed.isFailure).isTrue()
    }

    @Test
    fun `tampering with the ciphertext is detected`() {
        val sealed = CryptoBox.encrypt(key, "Rs.450 at swiggy".toByteArray())
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        // GCM authenticates, so a flipped bit fails loudly rather than decrypting
        // to garbage that we would then try to parse.
        assertThat(runCatching { CryptoBox.decrypt(key, sealed) }.isFailure).isTrue()
    }

    @Test
    fun `log codec round trips a batch of events`() {
        val codec = LogCodec(key)
        val events = (1..10).map { sampleEvent("e$it", it.toLong()) }

        val encoded = codec.encodeLines(events)
        assertThat(encoded).doesNotContain("swiggy") // ciphertext, not plaintext

        assertThat(codec.decodeLines(encoded)).isEqualTo(events)
    }

    @Test
    fun `one corrupt line does not take down the whole log`() {
        val codec = LogCodec(key)
        val events = (1..5).map { sampleEvent("e$it", it.toLong()) }
        val lines = codec.encodeLines(events).lines().toMutableList()

        lines[2] = "!!!not-valid-base64!!!"
        val decoded = codec.decodeLines(lines.joinToString("\n"))

        // Per-line encryption means we lose exactly that one event, not the file.
        assertThat(decoded).hasSize(4)
        assertThat(decoded.map { it.eventId }).containsExactly("e1", "e2", "e4", "e5")
    }

    @Test
    fun `blank lines are skipped`() {
        val codec = LogCodec(key)
        val events = listOf(sampleEvent("e1", 1))
        val withBlanks = "\n\n" + codec.encodeLines(events) + "\n\n"
        assertThat(codec.decodeLines(withBlanks)).isEqualTo(events)
    }

    @Test
    fun `invite secret avoids visually ambiguous characters`() {
        repeat(200) {
            val code = CryptoBox.generateInviteSecret()
            assertThat(code).hasLength(6)
            assertThat(code).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}")
        }
    }

    @Test
    fun `raw sms travels in the payload, but only ever encrypted on this path`() {
        // Policy changed deliberately: the original bank message is now synced so a
        // wrong parse can be diagnosed by reading the Google Sheet. That message
        // carries the account tail and running balance, so on *this* transport it
        // must never appear in the clear.
        val fields = SyncPayload::class.java.declaredFields.map { it.name }
        assertThat(fields).contains("rawSms")

        val sensitive = "Debited Rs 151.00 from a/c X4657 Bal Rs 3469.55 -Federal Bank"
        val event = sampleEvent("e1", 1).let {
            it.copy(payload = it.payload!!.copy(rawSms = sensitive))
        }

        val encoded = LogCodec(key).encodeLines(listOf(event))

        assertThat(encoded).doesNotContain("X4657")
        assertThat(encoded).doesNotContain("3469.55")
        assertThat(encoded).doesNotContain("Federal Bank")
        // …and survives the round trip intact.
        assertThat(LogCodec(key).decodeLines(encoded).single().payload?.rawSms)
            .isEqualTo(sensitive)
    }
}

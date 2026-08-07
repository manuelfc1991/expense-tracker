package com.manuel.ours.core

import androidx.compose.runtime.saveable.SaverScope
import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.model.PaidFrom
import com.manuel.ours.ui.screens.home.paidFromSaverForTest
import org.junit.Test

/**
 * What `rememberSaveable` holds has to survive a Bundle.
 *
 * This is the defect that closed the app on the "+" button. `PaidFrom` is a sealed
 * interface — not Parcelable, not Serializable — and handing one to `rememberSaveable`
 * compiles, runs, and looks completely fine until Android asks for the state back:
 *
 *     java.lang.IllegalArgumentException: MutableState containing …
 *     cannot be saved using the current SaveableStateRegistry.
 *
 * The stack trace names Choreographer and no line of this app's code, which is why it
 * survived a build, a test run and three releases. The same mistake was sitting in the
 * Accounts panel holding an `AccountBalance`, waiting for somebody to rotate the phone
 * with the balance dialog open.
 *
 * So the check is on the *saved form*, against the types a Bundle actually takes.
 */
class SaveableStateTest {

    /** Always-allow scope: this test is about the value, not about who may save it. */
    private val scope = SaverScope { true }

    /**
     * What `DisposableSaveableStateRegistry` accepts on Android. Anything outside this
     * set throws at save time, wherever in the app it came from.
     */
    private fun bundleSafe(value: Any?): Boolean = when (value) {
        null, is String, is Boolean, is Byte, is Short, is Int, is Long,
        is Float, is Double, is Char,
        -> true
        is List<*> -> value.all(::bundleSafe)
        else -> false
    }

    private fun savedForm(paidFrom: PaidFrom): Any? =
        with(paidFromSaverForTest) { scope.save(paidFrom) }

    // ── the saved form must be Bundle-safe ───────────────────────────────────

    @Test
    fun `every PaidFrom saves to something a Bundle can hold`() {
        val all = listOf(
            PaidFrom.Cash,
            PaidFrom.Unknown,
            PaidFrom.Account(accountTail = "3062", bank = "Kerala Gramin Bank"),
            PaidFrom.Account(accountTail = null, bank = "Federal Bank"),
            PaidFrom.Account(accountTail = "8842", bank = null),
            PaidFrom.Account(accountTail = null, bank = null),
        )
        all.forEach { paidFrom ->
            assertThat(bundleSafe(savedForm(paidFrom))).isTrue()
        }
    }

    /** The guard above only means something if it rejects the thing that broke. */
    @Test
    fun `the check would have failed the value that crashed`() {
        assertThat(bundleSafe(PaidFrom.Cash)).isFalse()
        assertThat(bundleSafe(PaidFrom.Account("3062", "Kerala Gramin Bank"))).isFalse()
    }

    // ── and it has to come back as what went in ──────────────────────────────

    @Test
    fun `a PaidFrom survives the round trip`() {
        listOf(
            PaidFrom.Cash,
            PaidFrom.Unknown,
            PaidFrom.Account("3062", "Kerala Gramin Bank"),
            PaidFrom.Account(null, "Federal Bank"),
            PaidFrom.Account("8842", null),
        ).forEach { original ->
            val back = paidFromSaverForTest.restore(savedForm(original)!!)
            assertThat(back).isEqualTo(original)
        }
    }

    /**
     * Blank is how null is written down, so an Account of two nulls comes back as one.
     *
     * It stays an `Account` rather than collapsing to `Unknown`: the household picked an
     * account chip, and forgetting that because the account had no digits and no bank
     * name would silently change what they said.
     */
    @Test
    fun `an account with no digits and no bank still restores as an account`() {
        val back = paidFromSaverForTest.restore(savedForm(PaidFrom.Account(null, null))!!)
        assertThat(back).isEqualTo(PaidFrom.Account(null, null))
    }

    /** Garbage in the Bundle must not crash the sheet a second time. */
    @Test
    fun `an unreadable saved value restores as unknown`() {
        assertThat(paidFromSaverForTest.restore(listOf("nonsense", "", "")))
            .isEqualTo(PaidFrom.Unknown)
        assertThat(paidFromSaverForTest.restore(emptyList<String>()))
            .isEqualTo(PaidFrom.Unknown)
    }
}

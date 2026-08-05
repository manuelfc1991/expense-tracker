package com.manuel.ours.data.repo

import com.manuel.ours.data.db.BudgetDao
import com.manuel.ours.data.db.BudgetEntity
import com.manuel.ours.data.db.MemberDao
import com.manuel.ours.data.db.MemberEntity
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.sync.CryptoBox
import com.manuel.ours.domain.model.Budget
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Member
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseholdRepository @Inject constructor(
    private val memberDao: MemberDao,
    private val prefs: AppPrefs,
    private val transactionRepository: TransactionRepository,
) {
    fun observeMembers(): Flow<List<Member>> = memberDao.observeAll().map { list ->
        list.map { Member(it.uid, it.displayName, it.email, it.isSelf) }
    }

    /**
     * Creates the household. No network call, no account, no server — the household
     * is just a shared secret that the two phones agree on.
     */
    suspend fun createHousehold(uid: String, name: String, email: String): InviteBundle {
        // The device that creates the household is its owner: the only asymmetry
        // in an otherwise peer-to-peer design, and it exists only to decide whose
        // delete applies at once and whose waits for approval.
        prefs.setHouseholdOwner(true)
        val secret = CryptoBox.generateInviteSecret()
        val householdId = idForSecret(secret)

        prefs.setHousehold(householdId, secret)
        prefs.setSelf(uid, name, email)
        memberDao.upsert(MemberEntity(uid, name, email, isSelf = true))
        transactionRepository.adoptLocalTransactions()

        return InviteBundle(householdId, secret)
    }

    /** Joins via a scanned QR or a pasted invite payload. */
    /**
     * Brings a household created before the id was derived onto the derived id.
     *
     * Households made earlier hold a random UUID that only ever travelled inside the
     * QR. Someone joining such a household by *typing* the code now derives an id from
     * the secret and lands somewhere else — so the fix that made typed codes work would
     * leave existing households half-broken, working by QR and silently not by code.
     *
     * Every device sharing a secret derives the same id, so this converges: whoever
     * runs it ends up in the same place, whenever they run it. Safe because nothing
     * durable is keyed on the old value — the sheet does not use the household id at
     * all, and the id only salts the key for transports that have never yet carried
     * data for anyone.
     *
     * Returns true if it changed anything.
     */
    suspend fun migrateHouseholdIdIfLegacy(): Boolean {
        val snapshot = prefs.snapshot()
        val secret = snapshot.inviteSecret ?: return false
        val current = snapshot.householdId ?: return false
        val derived = idForSecret(secret)
        if (current == derived) return false
        prefs.setHousehold(derived, secret)
        return true
    }

    suspend fun joinHousehold(bundle: InviteBundle, uid: String, name: String, email: String) {
        prefs.setHouseholdOwner(false)
        prefs.setHousehold(bundle.householdId, bundle.inviteSecret)
        prefs.setSelf(uid, name, email)
        memberDao.upsert(MemberEntity(uid, name, email, isSelf = true))
        transactionRepository.adoptLocalTransactions()
    }

    suspend fun addMember(uid: String, name: String, email: String) {
        memberDao.upsert(MemberEntity(uid, name, email, isSelf = false))
    }

    suspend fun removeMember(uid: String) = memberDao.delete(uid)

    companion object {
        /**
         * The household id, derived from the invite secret rather than generated.
         *
         * It used to be a random UUID, which meant the two devices only agreed on it if
         * the QR carried it across. Someone joining by *typing* the code — the
         * documented fallback when a camera will not scan — got `householdId = code`
         * while the creator held a UUID, and nothing said so.
         *
         * That broke Bluetooth twice over. Nearby advertises on
         * `com.manuel.ours.sync.$householdId`, so the phones looked for each other on
         * different service ids; and [CryptoBox.deriveKey] salts with the household id,
         * so even meeting they would have derived different keys and failed to decrypt.
         *
         * Deriving it means both sides compute the same id from the one thing they
         * genuinely share. Hashed rather than used raw: the id is broadcast to every
         * nearby device, and the secret is what protects the household.
         */
        fun idForSecret(inviteSecret: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("ours:household:${inviteSecret.trim().uppercase()}".toByteArray())
            return digest.take(16).joinToString("") { "%02x".format(it) }
        }
    }

    data class InviteBundle(
        val householdId: String,
        val inviteSecret: String,
    ) {
        /** Compact payload for the QR code. */
        fun encode(): String = JSONObject()
            .put("h", householdId)
            .put("s", inviteSecret)
            .toString()

        companion object {
            fun decode(text: String): InviteBundle? = runCatching {
                val json = JSONObject(text)
                InviteBundle(
                    householdId = json.getString("h"),
                    inviteSecret = json.getString("s"),
                )
            }.getOrNull()
        }
    }
}

/**
 * The household's caps.
 *
 * Every write goes to two places: the `budgets` table, which is what the ruler, the
 * widget and the alerter read, and `shared_rules`, which is what actually crosses to the
 * other phone. The budget had been local-only — a ₹40,000 cap set on one phone was
 * invisible on the other, so a household with one budget in fact had two, and the
 * partner spent against a limit they could neither see nor be warned about. A shared cap
 * that only one person can see is not a shared cap.
 *
 * Written through rather than read through: the table stays the single source for
 * everything downstream, so none of it has to learn about sync, and an incoming rule is
 * folded back into the table by [RulesRepository.apply].
 */
@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val sharedRuleDao: com.manuel.ours.data.db.SharedRuleDao,
    private val prefs: AppPrefs,
) {
    fun observeBudgets(): Flow<List<Budget>> = budgetDao.observeAll().map { list ->
        list.map { entity ->
            Budget(
                category = if (entity.categoryKey == OVERALL) null
                else Category.fromNameOrOther(entity.categoryKey),
                limitPaise = entity.limitPaise,
            )
        }
    }

    fun observeOverall(): Flow<Long?> = budgetDao.observeAll().map { list ->
        list.firstOrNull { it.categoryKey == OVERALL }?.limitPaise
    }

    suspend fun setOverall(limitPaise: Long) {
        budgetDao.upsert(BudgetEntity(OVERALL, limitPaise))
        share(OVERALL, limitPaise)
    }

    suspend fun setCategoryBudget(category: Category, limitPaise: Long) {
        budgetDao.upsert(BudgetEntity(category.name, limitPaise))
        share(category.name, limitPaise)
    }

    suspend fun clear(category: Category?) {
        val key = category?.name ?: OVERALL
        budgetDao.delete(key)
        // Zero is the tombstone. Deleting the rule outright would leave the other phone
        // holding the old cap with nothing to tell it the household had dropped it —
        // there is no row to carry the news, so the removal would never travel.
        share(key, 0L)
    }

    private suspend fun share(key: String, limitPaise: Long) {
        sharedRuleDao.upsertAll(
            listOf(
                com.manuel.ours.data.db.SharedRuleEntity(
                    type = TYPE_BUDGET,
                    ruleKey = key,
                    value = limitPaise.coerceAtLeast(0L).toString(),
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            )
        )
    }

    companion object {
        const val OVERALL = "__OVERALL__"
        const val TYPE_BUDGET = "budget"
    }
}

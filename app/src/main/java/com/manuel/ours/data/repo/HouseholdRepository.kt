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
        val householdId = UUID.randomUUID().toString()
        val secret = CryptoBox.generateInviteSecret()

        prefs.setHousehold(householdId, secret)
        prefs.setSelf(uid, name, email)
        memberDao.upsert(MemberEntity(uid, name, email, isSelf = true))
        transactionRepository.adoptLocalTransactions()

        return InviteBundle(householdId, secret)
    }

    /** Joins via a scanned QR or a pasted invite payload. */
    suspend fun joinHousehold(bundle: InviteBundle, uid: String, name: String, email: String) {
        prefs.setHousehold(bundle.householdId, bundle.inviteSecret)
        prefs.setSelf(uid, name, email)
        memberDao.upsert(MemberEntity(uid, name, email, isSelf = true))
        transactionRepository.adoptLocalTransactions()
    }

    suspend fun addMember(uid: String, name: String, email: String) {
        memberDao.upsert(MemberEntity(uid, name, email, isSelf = false))
    }

    suspend fun removeMember(uid: String) = memberDao.delete(uid)

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

@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
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
    }

    suspend fun setCategoryBudget(category: Category, limitPaise: Long) {
        budgetDao.upsert(BudgetEntity(category.name, limitPaise))
    }

    suspend fun clear(category: Category?) {
        budgetDao.delete(category?.name ?: OVERALL)
    }

    companion object {
        const val OVERALL = "__OVERALL__"
    }
}

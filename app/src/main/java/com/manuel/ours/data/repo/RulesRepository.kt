package com.manuel.ours.data.repo

import com.manuel.ours.data.db.MerchantRuleDao
import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.data.db.SharedRuleDao
import com.manuel.ours.data.db.SharedRuleEntity
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sync.SheetTransport
import com.manuel.ours.domain.model.Category
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Knowledge the phones teach each other, as distinct from the ledger they share.
 *
 * The parser's patterns are code and stay code — a regex that reads "credited to a/c
 * no." as a payee can only be fixed by shipping a new build. What can travel is the
 * *data* around it, and that data is where this app's most expensive failures have
 * been:
 *
 *  - **Bank senders.** An unrecognised TRAI header is discarded before parsing begins,
 *    silently. One missing line cost this household 466 messages, and the fix was a
 *    single string. That should never have required an APK.
 *  - **Merchant categories.** A correction made on one phone was made again on the
 *    other, forever, because nothing carried it across.
 *
 * Both are things a person could type into a spreadsheet, which is exactly where they
 * now live: a `rules` tab, three meaningful columns, editable by hand.
 */
@Singleton
class RulesRepository @Inject constructor(
    private val sharedRuleDao: SharedRuleDao,
    private val merchantRuleDao: MerchantRuleDao,
    private val prefs: AppPrefs,
    private val transactionRepository: TransactionRepository,
) {

    /**
     * Pulls, merges, applies, then pushes back whatever this phone knows and the
     * sheet does not.
     *
     * Never throws. Rules are an enhancement to a sync whose real job is the ledger,
     * so a sheet running an older script — which answers "Unknown action: pullRules" —
     * must degrade to doing nothing rather than taking the transaction sync down.
     */
    suspend fun sync(transport: SheetTransport): Int = runCatching {
        val remote = transport.pullRules()
        if (remote.isNotEmpty()) {
            sharedRuleDao.upsertAll(
                remote.map {
                    SharedRuleEntity(
                        type = it.type,
                        ruleKey = it.key,
                        value = it.value,
                        updatedAt = it.updatedAt,
                        deviceId = it.deviceId,
                    )
                }
            )
        }

        apply()

        val mine = localRules()
        if (mine.isNotEmpty()) transport.pushRules(mine)
        remote.size
    }.getOrDefault(0)

    /**
     * Makes the stored rules take effect.
     *
     * Sender rules go into [BankRules] as an overlay on the compiled table. Merchant
     * rules are written into the same table the categoriser already reads, so nothing
     * downstream needs to know they arrived from a spreadsheet.
     */
    suspend fun apply() {
        val all = sharedRuleDao.all()

        BankRules.setTaughtSenders(
            all.filter { it.type == TYPE_SENDER && it.value.isNotBlank() }
                .associate { it.ruleKey to it.value }
        )

        // Names the household has given accounts, applied to the rows that have been
        // waiting for one. Without this a name typed into the sheet — or given on the
        // other phone — would only ever affect payments that had not happened yet.
        val accountNames = all.filter { it.type == TYPE_ACCOUNT && it.value.isNotBlank() }
        for (rule in accountNames) {
            transactionRepository.applyAccountName(rule.ruleKey, rule.value)
        }

        val merchantRules = all.filter { it.type == TYPE_MERCHANT }
        for (rule in merchantRules) {
            // An unknown category name is ignored rather than filed as Other: a typo in
            // a hand-edited spreadsheet should do nothing, not quietly recategorise.
            val category = Category.entries.firstOrNull {
                it.name.equals(rule.value, ignoreCase = true)
            } ?: continue
            merchantRuleDao.upsert(
                MerchantRuleEntity(
                    pattern = rule.ruleKey.lowercase().trim(),
                    category = category.name,
                    userDefined = true,
                )
            )
        }
    }

    /** What this phone has learned that is worth teaching: the user's own corrections. */
    private suspend fun localRules(): List<SheetTransport.Rule> {
        val deviceId = prefs.deviceId()
        val known = sharedRuleDao.all().associateBy { it.type to it.ruleKey }

        return merchantRuleDao.userDefined().mapNotNull { rule ->
            val existing = known[TYPE_MERCHANT to rule.pattern]
            // Already on the sheet with the same answer — nothing to say.
            if (existing != null && existing.value.equals(rule.category, true)) return@mapNotNull null
            SheetTransport.Rule(
                type = TYPE_MERCHANT,
                key = rule.pattern,
                value = rule.category,
                updatedAt = System.currentTimeMillis(),
                deviceId = deviceId,
            )
        }
    }

    companion object {
        const val TYPE_ACCOUNT = "account"
        const val TYPE_SENDER = "sender"
        const val TYPE_MERCHANT = "merchant"
    }
}

package com.manuel.ours.data.repo

import com.manuel.ours.data.db.BudgetDao
import com.manuel.ours.data.db.BudgetEntity
import com.manuel.ours.data.db.MerchantRuleDao
import com.manuel.ours.data.db.MemberDao
import com.manuel.ours.data.db.MemberEntity
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
    private val budgetDao: BudgetDao,
    private val memberDao: MemberDao,
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
        val remoteByKey = remote.associateBy { it.type to it.key }
        val localByKey = sharedRuleDao.all().associateBy { it.type to it.ruleKey }

        // Only what is genuinely newer than what this phone already holds.
        //
        // This used to upsert every pulled row unconditionally, which quietly made the
        // sheet authoritative over the phone regardless of age: a balance typed in a
        // minute ago was overwritten by a three-day-old copy of the same rule, and the
        // fresh figure was gone before anyone could read it. The sheet has always done
        // last-write-wins on its side; the app was the half that did not.
        val fresher = remote.filter { rule ->
            val mine = localByKey[rule.type to rule.key]
            mine == null || rule.updatedAt > mine.updatedAt
        }
        if (fresher.isNotEmpty()) {
            sharedRuleDao.upsertAll(
                fresher.map {
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

        publishSelf()
        publishExistingBudgets()
        publishDiscoveredSenders()
        apply()

        val mine = localRules(remoteByKey)
        if (mine.isNotEmpty()) transport.pushRules(mine)
        remote.size
    }.getOrDefault(0)

    /**
     * Announces this phone's owner to the household.
     *
     * Membership had no way of travelling at all. [HouseholdRepository.addMember] exists,
     * is the only thing that writes a second row to `members`, and is called from
     * nowhere — so the table only ever held the person holding the phone. The Household
     * screen counted those rows, which is why it read "Invite your partner" forever, on
     * both phones, however many times they successfully synced.
     *
     * The Both/Me/Partner chips did not have the bug because they derive people from the
     * transactions instead — but that is a worse rule wearing a disguise: it makes
     * *existing* conditional on *spending*, so a partner who has joined and not yet paid
     * for anything is indistinguishable from a partner who was never there. This
     * household is in exactly that state.
     *
     * Written only when it actually changes. Rewriting every sync would bump `updatedAt`
     * and force a push every round for a value nobody edited.
     */
    private suspend fun publishSelf() {
        val snapshot = prefs.snapshot()
        val uid = snapshot.selfUid?.takeIf { it.isNotBlank() } ?: return
        val value = "${snapshot.selfName.orEmpty()}|${snapshot.selfEmail.orEmpty()}"
        val existing = sharedRuleDao.all()
            .firstOrNull { it.type == TYPE_MEMBER && it.ruleKey == uid }
        if (existing?.value == value) return
        sharedRuleDao.upsertAll(
            listOf(
                SharedRuleEntity(
                    type = TYPE_MEMBER,
                    ruleKey = uid,
                    value = value,
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            )
        )
    }

    /**
     * Gives a budget that predates rule-syncing a rule of its own.
     *
     * Making [BudgetRepository] write a shared rule fixed budgets set from then on and
     * silently did nothing for the one already there — the write only happens when
     * somebody *changes* the figure. This household's ₹40,000 was set months ago, so the
     * table had a budget, the sheet had no rule for it, and the partner's phone
     * cheerfully offered to "Set a monthly budget" while the household had had one all
     * along. Caught by putting a second device on the sheet and looking, which is the
     * only way this class of bug ever shows up.
     *
     * Only fills gaps. A budget with a rule already is left alone, whatever it says:
     * that rule may be newer than this table, or a deliberate zero meaning the household
     * cleared the cap — and republishing the old figure over either would be this phone
     * quietly undoing somebody else's decision.
     */
    private suspend fun publishExistingBudgets() {
        val known = sharedRuleDao.all()
            .filter { it.type == TYPE_BUDGET }
            .map { it.ruleKey }
            .toSet()
        val missing = budgetDao.all()
            .filter { it.limitPaise > 0 && it.categoryKey !in known }
        if (missing.isEmpty()) return
        sharedRuleDao.upsertAll(
            missing.map {
                SharedRuleEntity(
                    type = TYPE_BUDGET,
                    ruleKey = it.categoryKey,
                    value = it.limitPaise.toString(),
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            }
        )
    }

    /**
     * Writes down the headers this phone worked out for itself.
     *
     * [BankRules.rememberDiscovered] only lives in memory, so without this a header
     * deduced from the shape of a message is forgotten at the next process death and
     * re-deduced from scratch — and it never reaches the other phone at all, which is
     * the half that matters. A bank sends to both handsets; only one of them needs to
     * meet the new header first.
     *
     * Only fills gaps, and deliberately treats *any* existing sender rule as a gap
     * already filled — including an empty one. An emptied value is this store's
     * tombstone, so a header a person looked at and rejected must stay rejected rather
     * than being rediscovered and republished on the next message that arrives.
     */
    private suspend fun publishDiscoveredSenders() {
        val discovered = BankRules.discoveredSenders()
        if (discovered.isEmpty()) return
        val known = sharedRuleDao.all()
            .filter { it.type == TYPE_SENDER }
            .map { it.ruleKey.uppercase() }
            .toSet()
        val fresh = discovered.filterKeys { it.uppercase() !in known }
        if (fresh.isEmpty()) return
        sharedRuleDao.upsertAll(
            fresh.map { (header, bank) ->
                SharedRuleEntity(
                    type = TYPE_SENDER,
                    ruleKey = header,
                    value = bank,
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            }
        )
    }

    /**
     * Makes the stored rules take effect.
     *
     * Sender rules go into [BankRules] as an overlay on the compiled table. Merchant
     * rules are written into the same table the categoriser already reads, so nothing
     * downstream needs to know they arrived from a spreadsheet.
     */
    suspend fun apply() {
        val all = sharedRuleDao.all()
        val selfUid = prefs.snapshot().selfUid

        // Everyone else in the household, so the Household screen can name them before
        // they have spent a rupee.
        for (rule in all.filter { it.type == TYPE_MEMBER }) {
            if (rule.ruleKey.isBlank() || rule.ruleKey == selfUid) continue
            // An emptied value means "this person is no longer in the household", the
            // same tombstone the balances and the budget use. A household that can add
            // people and never remove them accumulates every phone that ever touched it
            // — a replaced handset, a device someone was testing with — and there would
            // be no way to take one out short of editing the database by hand.
            //
            // Blanked rather than deleted, because a deleted row simply stops being
            // pulled and the other phones keep the member they already have. The row has
            // to survive in order to carry the news.
            if (rule.value.isBlank()) {
                memberDao.delete(rule.ruleKey)
                continue
            }
            val parts = rule.value.split('|')
            memberDao.upsert(
                MemberEntity(
                    uid = rule.ruleKey,
                    // A member with no name is still a member. Skipping them would put
                    // the household back to not knowing somebody is there.
                    displayName = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: "Partner",
                    email = parts.getOrNull(1).orEmpty(),
                    isSelf = false,
                )
            )
        }

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

        // The household's caps, folded back into the table the ruler, the widget and the
        // alerter all read — so a budget set on one phone becomes the budget on both.
        for (rule in all.filter { it.type == TYPE_BUDGET }) {
            // A malformed value does nothing rather than clearing the cap: a typo in a
            // hand-edited spreadsheet must not silently remove the household's budget.
            val paise = rule.value.toLongOrNull() ?: continue
            if (paise <= 0L) budgetDao.delete(rule.ruleKey)
            else budgetDao.upsert(BudgetEntity(rule.ruleKey, paise))
        }

        val merchantRules = all.filter { it.type == TYPE_MERCHANT }
        for (rule in merchantRules) {
            val pattern = rule.ruleKey.lowercase().trim()
            // An emptied value is this store's tombstone, and merchant rules were the one
            // type not honouring it: the blank failed the category lookup below and hit
            // `continue`, so a correction deleted on one phone lived on forever on the
            // other. Every other type — account, sender, balance, budget, member, card —
            // already treats empty as "remove this".
            if (rule.value.isBlank()) {
                merchantRuleDao.deleteByPattern(pattern)
                continue
            }
            // An unknown category name is ignored rather than filed as Other: a typo in
            // a hand-edited spreadsheet should do nothing, not quietly recategorise.
            val category = Category.entries.firstOrNull {
                it.name.equals(rule.value, ignoreCase = true)
            } ?: continue
            merchantRuleDao.upsert(
                MerchantRuleEntity(
                    pattern = pattern,
                    category = category.name,
                    userDefined = true,
                )
            )
        }
    }

    /**
     * What this phone knows that the sheet does not.
     *
     * This used to return merchant corrections and **nothing else**, which meant every
     * other kind of shared rule was write-only: account balances, the minimums each bank
     * demands, the names given to accounts and the household budget were all faithfully
     * written to `shared_rules` — with a comment promising they would reach the other
     * phone — and then never pushed anywhere. The partner's phone could receive such a
     * rule if somebody typed it into the spreadsheet by hand, and by no other route.
     *
     * A household has one set of accounts and one budget. A figure only one phone knows
     * is a figure the other is missing, and the whole point of the shared-rule table is
     * that it is the thing both phones agree on.
     *
     * @param remote what the sheet answered with this round, so a rule is only pushed
     *   when it is actually newer than the copy already up there. Without the comparison
     *   every phone would re-push its entire table on every sync.
     */
    private suspend fun localRules(
        remote: Map<Pair<String, String>, SheetTransport.Rule>,
    ): List<SheetTransport.Rule> {
        val deviceId = prefs.deviceId()
        val out = mutableListOf<SheetTransport.Rule>()

        for (rule in sharedRuleDao.all()) {
            // Merchant rules are authored in their own table and pushed below; the
            // copies in here arrived *from* the sheet, so sending them back is noise.
            if (rule.type !in SHAREABLE_TYPES) continue
            val theirs = remote[rule.type to rule.ruleKey]
            if (theirs != null && theirs.updatedAt >= rule.updatedAt) continue
            out += SheetTransport.Rule(
                type = rule.type,
                key = rule.ruleKey,
                value = rule.value,
                updatedAt = rule.updatedAt,
                // Keep whoever actually wrote it. Stamping this phone's id on a rule it
                // merely relayed would lose the only record of where a figure came from.
                deviceId = rule.deviceId.ifBlank { deviceId },
            )
        }

        for (rule in merchantRuleDao.userDefined()) {
            val theirs = remote[TYPE_MERCHANT to rule.pattern]
            // Already on the sheet with the same answer — nothing to say.
            if (theirs != null && theirs.value.equals(rule.category, true)) continue
            out += SheetTransport.Rule(
                type = TYPE_MERCHANT,
                key = rule.pattern,
                value = rule.category,
                updatedAt = System.currentTimeMillis(),
                deviceId = deviceId,
            )
        }

        return out
    }

    companion object {
        const val TYPE_ACCOUNT = "account"

        const val TYPE_SENDER = "sender"
        const val TYPE_MERCHANT = "merchant"
        const val TYPE_BALANCE = "balance"
        const val TYPE_MIN_BALANCE = "minbal"
        const val TYPE_BUDGET = "budget"
        const val TYPE_MEMBER = "member"

        /**
         * An account the household says is a **credit card**.
         *
         * Key: the account key, same as balances. Value: `limitPaise|dueDay`, both optional.
         *
         * Its own type rather than a flag on the balance rule, because it changes which
         * total the account joins — a card balance is money owed, and adding it to "what is
         * left" would report more to spend than exists. A rule that decides a sign belongs
         * where it can be read without parsing something else first.
         */
        const val TYPE_CARD = "card"

        /**
         * Everything in `shared_rules` that is worth sending, merchant rules excepted —
         * those are pushed from the table they are authored in.
         */
        private val SHAREABLE_TYPES = setOf(
            TYPE_ACCOUNT, TYPE_SENDER, TYPE_BALANCE, TYPE_MIN_BALANCE, TYPE_BUDGET,
            TYPE_MEMBER, TYPE_CARD,
        )
    }
}

package com.manuel.ours.data.sms

import com.manuel.ours.core.Money
import com.manuel.ours.domain.model.TxnType

/**
 * Rule-driven parser for Indian bank / UPI / card SMS.
 *
 * The ordering below is deliberate and load-bearing: the reject rules run *before*
 * any extraction, because an OTP SMS and a debit SMS both contain "Rs." and a number.
 * Getting this order wrong is how naive trackers end up logging your OTP as a ₹4,821
 * dinner.
 *
 * Every rule here was written against real messages, and several exist because the
 * naive version got it wrong on live data — see `SmsParserRealWorldTest`.
 */
class SmsParser {

    sealed interface Result {
        data class Expense(val txn: ParsedTxn) : Result
        data class BillReminder(
            val bank: String?,
            val amountPaise: Long?,
            val text: String,
            /** When it falls due. Null when the message names no date. */
            val dueAt: Long? = null,
        ) : Result
        data class Ignored(val reason: Reason) : Result

        /**
         * Payment-shaped, from a sender we cannot vouch for.
         *
         * Not an expense and not a rejection — a question. Adopting an unknown sender on
         * the shape of its message alone was measured against 2,810 real messages and read
         * an EPF passbook line as ₹61,989 of income, so shape is enough to *ask* and not
         * enough to *count*. These are held until somebody says which they are.
         */
        data class Unrecognised(
            val header: String,
            val amountPaise: Long?,
            val type: TxnType?,
            val body: String,
        ) : Result
    }

    enum class Reason {
        UNKNOWN_SENDER,
        OTP,
        PROMOTIONAL,
        FAILED_TRANSACTION,
        BALANCE_ENQUIRY_ONLY,
        NO_AMOUNT,
        NO_TRANSACTION_VERB,
        BILL_REMINDER,

        /**
         * Statements, credit-limit changes, EMI conversions. These quote real amounts
         * and mention real cards, but no money moved — an EMI conversion in particular
         * re-states a purchase that is already recorded, so counting it double-bills you.
         */
        NOT_A_TRANSACTION,

        /**
         * Older than the date this sender started being read. See [BankRule.notBefore].
         *
         * Not a fault in the message — it parses perfectly. It predates the point the
         * household chose to start counting this account from.
         */
        BEFORE_SENDER_START,
    }

    /** What kind of money movement this is — decides whether it counts as spending. */
    enum class Kind {
        /** Ordinary spending at a named merchant or person. */
        PURCHASE,

        /** Money moved with no payee named, or an explicit standing instruction. */
        TRANSFER,

        /**
         * Paying off a credit card. Settles debt that the individual card purchases
         * already recorded, so counting it as spend double-counts the whole bill.
         */
        CARD_BILL_PAYMENT,

        /**
         * Money into a fixed/recurring deposit, SIP or similar. It left the account
         * but you still own it — booking a ₹1,00,000 FD is not a ₹1,00,000 expense.
         */
        SAVINGS_DEPOSIT,
    }

    data class ParsedTxn(
        val amountPaise: Long,
        val type: TxnType,
        val merchant: String?,
        val bank: String,
        val accountTail: String?,
        /**
         * Last digits of the account the money went *to*, when the bank named one.
         *
         * Not a payee and never shown as one — "a/c no. XXXX8891" says nothing about
         * who owns it. It is an identifier, and that is exactly what these messages
         * otherwise lack: Kerala Gramin words a payment to a mother, a landlord, a
         * fixed deposit and one's own second account in identical language. The number
         * is the only thing that differs, so it is the only thing that can carry a name
         * the household gives it.
         */
        val counterpartyTail: String?,
        val refNo: String?,
        /** The bank's own id for this message, when it gives one. See [MESSAGE_ID]. */
        val messageId: String?,
        val balancePaise: Long?,
        val occurredAt: Long,
        val rawBody: String,
        val kind: Kind = Kind.PURCHASE,
        /**
         * Timestamp used **only** for duplicate detection, never for display.
         *
         * When the bank gives a date but no clock time, [occurredAt] is midnight —
         * and midnight is the same for every transaction that day. Deduping on that
         * merges two genuinely different same-day, same-amount payments and silently
         * drops one. This falls back to the SMS delivery time, which always has real
         * resolution.
         */
        val dedupeAt: Long = occurredAt,
    )

    fun parse(sender: String, body: String, receivedAt: Long): Result {
        val known = BankRules.forSender(sender)
        // A header we do not recognise is not proof this is not a bank. Fall through to
        // the shape of the message itself, which is the thing that actually decides.
        val rule = known
            ?: provisionalRule(sender, body)
            ?: return unrecognised(sender, body)

        // A sender the app only started reading part-way through its own history.
        // `receivedAt == 0` means "no time given", which only happens in tests.
        if (rule.notBefore > 0L && receivedAt > 0L && receivedAt < rule.notBefore) {
            return Result.Ignored(Reason.BEFORE_SENDER_START)
        }

        val lower = body.lowercase()

        // --- Reject rules, in priority order -------------------------------------
        if (looksLikeOtp(lower)) return Result.Ignored(Reason.OTP)
        if (looksFailed(lower)) return Result.Ignored(Reason.FAILED_TRANSACTION)
        if (looksLikeBillReminder(lower)) {
            return Result.BillReminder(
                bank = rule.bank,
                amountPaise = extractAmount(body),
                text = body.trim(),
                dueAt = extractDueDate(body),
            )
        }
        if (isNotATransaction(lower)) return Result.Ignored(Reason.NOT_A_TRANSACTION)
        if (looksPromotional(lower)) return Result.Ignored(Reason.PROMOTIONAL)

        val type = detectType(lower)
            ?: return Result.Ignored(
                if (hasBalanceOnly(lower)) Reason.BALANCE_ENQUIRY_ONLY
                else Reason.NO_TRANSACTION_VERB
            )

        val amount = extractAmount(body) ?: return Result.Ignored(Reason.NO_AMOUNT)
        val merchant = extractMerchant(body, rule)
        val parsedDate = extractDateTime(body)

        // Learn only from a message that survived every reject rule above. An OTP quoting
        // a debit is bank-shaped and must teach us nothing, or one such message enrols a
        // sender whose every future OTP then arrives as an expense.
        if (known == null) BankRules.rememberDiscovered(sender, rule.bank)

        return Result.Expense(
            ParsedTxn(
                amountPaise = amount,
                type = type,
                merchant = merchant,
                bank = rule.bank,
                accountTail = extractAccountTail(body),
                counterpartyTail = extractCounterpartyTail(body),
                refNo = extractRefNo(body),
                messageId = extractMessageId(body),
                balancePaise = extractBalance(body),
                occurredAt = parsedDate?.epochMillis ?: receivedAt,
                rawBody = body,
                kind = detectKind(lower, merchant, type),
                // A date without a clock time cannot separate two transactions on the
                // same day, so dedup uses when the message actually arrived instead.
                dedupeAt = if (parsedDate?.hasTime == true) parsedDate.epochMillis else receivedAt,
            )
        )
    }

    // -- Recognising a bank we were never told about ------------------------------

    /**
     * A rule for a sender the table has never heard of, or null to leave it discarded.
     *
     * The compiled header table cannot be complete. A bank can register a new DLT header
     * whenever it likes, and when it does the failure is silent: no error, no unparsed
     * count, just money that stops appearing. That is how `FEDSMS` cost this household a
     * credit from the account it already tracked under `FEDBNK`.
     *
     * So identity falls back to evidence, and it takes **two** things: the message must
     * have the shape of a bank alert *and* name, in words, a bank this app already
     * knows. Banks sign their messages — "-Federal Bank", "-Utkarsh SFBL" — and that
     * signature is what separates a real alert from everything else shaped like one.
     *
     * Shape alone was tried first and is not enough. Audited against this household's
     * 2,810 messages, 99 headers were unrecognised and six of them carried
     * bank-shaped text: an EPF passbook notice, an Amazon Pay balance, a fuel loyalty
     * receipt, a Myntra gift card and two trading spams. Shape alone read the EPF
     * balance as ₹61,989 of income. Requiring the signature rejects all six and still
     * catches the header that started this — `FEDSMS`, which says "Federal Bank" in
     * its last two words.
     *
     * The cost is honest: a bank in neither the table nor the message text is still
     * missed, and is still fixed by teaching the header through the sheet.
     */
    private fun provisionalRule(sender: String, body: String): BankRule? {
        val header = BankRules.normaliseHeader(sender) ?: return null
        // A DLT header is alphabetic and short. Anything else is a person or a shortcode.
        if (header.length !in 3..15) return null
        if (header.none { it.isLetter() }) return null
        if (!looksLikeBankAlert(body)) return null
        val named = BankRules.bankNamedIn(body) ?: return null
        return BankRule(bank = named, headers = listOf(header))
    }

    /**
     * What to do with a sender we cannot vouch for.
     *
     * If the message is payment-shaped it becomes a question rather than a discard — the
     * failures that cost this household most were silent, and a header nobody has heard of
     * is exactly how a bank disappears. If it is not payment-shaped, or it is one of the
     * things that only looks like one, it is dropped as before.
     *
     * The reject rules are applied here too, and must be. They normally run *after* the
     * sender gate, so without this an OTP quoting a debit — bank-shaped by every measure —
     * would queue up asking whether its sender is a bank.
     */
    private fun unrecognised(sender: String, body: String): Result {
        val header = BankRules.normaliseHeader(sender)
            ?: return Result.Ignored(Reason.UNKNOWN_SENDER)
        if (header.length !in 3..15 || header.none { it.isLetter() }) {
            return Result.Ignored(Reason.UNKNOWN_SENDER)
        }
        if (!looksLikeBankAlert(body)) return Result.Ignored(Reason.UNKNOWN_SENDER)

        // The precise reason, not a blanket UNKNOWN_SENDER. The Parser Tester explains
        // whichever comes back, and "this looks like an OTP" is a far more useful answer
        // than "we don't know this sender" when the sender is beside the point.
        val lower = body.lowercase()
        when {
            looksLikeOtp(lower) -> return Result.Ignored(Reason.OTP)
            looksFailed(lower) -> return Result.Ignored(Reason.FAILED_TRANSACTION)
            isNotATransaction(lower) -> return Result.Ignored(Reason.NOT_A_TRANSACTION)
            looksPromotional(lower) -> return Result.Ignored(Reason.PROMOTIONAL)
        }
        return Result.Unrecognised(
            header = header,
            amountPaise = extractAmount(body),
            type = detectType(lower),
            body = body.trim(),
        )
    }

    /**
     * Three signals, all required.
     *
     * Any two of them occur innocently — a delivery notice has an amount and a reference
     * number, a reminder has an amount and the word "paid". It is the account or card
     * number alongside a settled verb and a real amount that makes it banking, and that
     * combination is what keeps "never miss a bank" from becoming "every shop is a bank".
     */
    private fun looksLikeBankAlert(body: String): Boolean {
        val lower = body.lowercase()
        if (!DEBIT_VERB.containsMatchIn(lower) && !CREDIT_VERB.containsMatchIn(lower)) return false
        if (extractAmount(body) == null) return false
        return ACCOUNT_TAIL.containsMatchIn(body) ||
            BALANCE_CLAUSE.containsMatchIn(body) ||
            UPI_REFERENCE.containsMatchIn(lower) ||
            REF_NO.containsMatchIn(body)
    }

    // -- Reject heuristics --------------------------------------------------------

    private val otpMarkers = listOf(
        "otp", "one time password", "one-time password", "do not share",
        "never share", "verification code", "security code", "login code",
        "is your code", "use this code", "2fa", "authentication code",
    )

    private fun looksLikeOtp(lower: String): Boolean {
        if (otpMarkers.any { it in lower }) return true
        // "123456 is your ..." with no currency at all
        return OTP_BARE.containsMatchIn(lower) && !CURRENCY_PRESENT.containsMatchIn(lower)
    }

    private val failMarkers = listOf(
        "failed", "declined", "unsuccessful", "not processed", "could not be processed",
        "reversed", "reversal", "refunded to your", "has been cancelled", "cancelled",
        "insufficient", "timed out", "timeout",
    )

    private fun looksFailed(lower: String) = failMarkers.any { it in lower }

    /**
     * Real amounts, real cards, no money moved. Checked before the promo rules because
     * these are transactional-sounding and would otherwise slip through as expenses.
     */
    private val notATransactionMarkers = listOf(
        "statement is sent", "statement is generated", "statement has been",
        "credit limit for your", "credit limit has been", "limit on your",
        "increasing the limit", "raise the limit", "manage spends",
        "converted into emi", "has been converted",
        "e-statement", "is now available",
    )
    // Deliberately NOT "convert this txn": ICICI appends "To convert this txn to EMI
    // give a missed call..." to genuine purchase alerts. Rejecting on that footer
    // threw away the real ₹16,941 Flipkart purchase it was attached to.

    private fun isNotATransaction(lower: String) = notATransactionMarkers.any { it in lower }

    private val promoMarkers = listOf(
        "offer", "cashback up to", "discount", "sale", "apply now", "click here",
        "limited period", "hurry", "t&c apply", "terms and conditions apply",
        "pre-approved", "pre approved", "eligible for", "upgrade your", "0% emi",
        "emi at 0", "instant loan", "personal loan at", "lowest interest",
        "download the app", "refer and earn", "win ", "lucky draw", "congratulations",
        "avail ", "unsubscribe",
    )

    private fun looksPromotional(lower: String): Boolean {
        // A real debit alert can say "cashback", so only reject when the promo marker
        // shows up *without* a settled-transaction verb.
        if (DEBIT_VERB.containsMatchIn(lower) || CREDIT_VERB.containsMatchIn(lower)) {
            // still reject the obvious pure-marketing shapes
            return listOf("apply now", "click here", "pre-approved", "pre approved", "lucky draw")
                .any { it in lower }
        }
        return promoMarkers.any { it in lower }
    }

    private val billMarkers = listOf(
        "is due on", "due on", "minimum amount due", "total amount due", "min amt due",
        "payment is due", "bill of rs", "bill is generated", "due date",
        "please pay by", "pay by ", "is due by", "due by ", "or minimum of",
    )

    private fun looksLikeBillReminder(lower: String): Boolean {
        if (DEBIT_VERB.containsMatchIn(lower)) return false // already paid
        return billMarkers.any { it in lower }
    }

    private fun hasBalanceOnly(lower: String) = BALANCE_MARKER.containsMatchIn(lower)

    // -- Classification -----------------------------------------------------------

    /**
     * "Payment of Rs 7,125.65 has been received on your ICICI Bank Credit Card XX3008
     * through Bharat Bill Payment System" — money genuinely left the bank account, but
     * the purchases that built that bill are already recorded individually. Counting
     * both is the single biggest source of inflated monthly totals.
     */
    private fun isCardBillPayment(lower: String): Boolean {
        // Any word ending in "card", not the literal phrase "credit card".
        //
        // Utkarsh calls its card a **SuperCard**: "We have received payment of INR
        // 1,778.00 for your SuperCard ending 1234". That is a bill being settled, and
        // missing it counts the bill *and* the 251 individual card debits that built it
        // — the double-count this whole branch exists to prevent.
        val mentionsCard = CARD_WORD.containsMatchIn(lower)
        val isPayment = ("payment of" in lower && "received" in lower) ||
            "payment received" in lower ||
            "bharat bill payment" in lower ||
            "towards your credit card" in lower
        return mentionsCard && isPayment
    }

    /**
     * Booking or funding a deposit. Checked before the transfer rule because a
     * standing instruction into an RD is a *savings* deposit, and reporting it as a
     * neutral transfer would hide the fact that the household is saving.
     */
    private val depositMarkers = listOf(
        "fixed deposit", "term deposit", "recurring deposit",
        "fd a/c", "fd account", "rd a/c", "rd account", "deposit a/c",
        "towards sip", "sip installment", "sip instalment",
        "mutual fund", "ppf a/c", "nps contribution",
    )

    private fun isSavingsDeposit(lower: String) = depositMarkers.any { it in lower }

    private fun detectKind(lower: String, merchant: String?, type: TxnType): Kind = when {
        isCardBillPayment(lower) -> Kind.CARD_BILL_PAYMENT
        isSavingsDeposit(lower) -> Kind.SAVINGS_DEPOSIT
        "standing instruction" in lower || "scheduled payment" in lower -> Kind.TRANSFER
        // A UPI debit is a payment, even when the bank names no payee.
        //
        // Kerala Gramin writes every one of them as "credited to a/c no. XXXX" with a
        // UPI reference — the destination account, never the shop. Those are real
        // purchases, and treating them as transfers would drop a household's largest
        // single group of spending out of its own total. A transfer between your own
        // accounts is worded differently: NEFT, IMPS, "transferred", a standing
        // instruction, all of which are caught above or carry no UPI reference.
        type == TxnType.DEBIT && merchant == null && UPI_REFERENCE.containsMatchIn(lower) ->
            Kind.PURCHASE
        // A debit whose payee we could not name. Often moving money to your own
        // account — we cannot tell, so it gets flagged rather than silently counted
        // as though you spent it at a shop.
        type == TxnType.DEBIT && merchant == null -> Kind.TRANSFER
        else -> Kind.PURCHASE
    }

    private fun detectType(lower: String): TxnType? = when {
        DEBIT_VERB.containsMatchIn(lower) -> TxnType.DEBIT
        CREDIT_VERB.containsMatchIn(lower) -> TxnType.CREDIT
        else -> null
    }

    // -- Field extraction ---------------------------------------------------------

    /**
     * The account the money went to, when the bank named one.
     *
     * Only ever read from an explicit destination clause — "credited to a/c no. X",
     * "transferred to a/c X". Never from the account the message is addressed to, which
     * is the household's own and already captured as [ParsedTxn.accountTail].
     */
    fun extractCounterpartyTail(body: String): String? {
        val match = COUNTERPARTY_ACCOUNT.find(body) ?: return null
        val digits = match.groupValues[1].filter(Char::isDigit)
        // Four is what banks mask to, and what a person can recognise. Fewer is not
        // distinctive enough to hang a name on.
        return digits.takeIf { it.length >= 4 }?.takeLast(4)
    }

    /** First currency-tagged amount that is not the closing balance. */
    fun extractAmount(body: String): Long? {
        // Blank out the balance clause so "Avl Bal Rs.5000" can't be mistaken for the amount.
        val masked = BALANCE_CLAUSE.replace(body, " ")
        AMOUNT_AFTER_CURRENCY.find(masked)?.let { m ->
            Money.parseToPaise(m.groupValues[1])?.let { return it }
        }
        AMOUNT_BEFORE_CURRENCY.find(masked)?.let { m ->
            Money.parseToPaise(m.groupValues[1])?.let { return it }
        }
        return null
    }

    fun extractBalance(body: String): Long? =
        BALANCE_CLAUSE.find(body)?.let { Money.parseToPaise(it.groupValues[1]) }

    fun extractAccountTail(body: String): String? =
        ACCOUNT_TAIL.find(body)?.groupValues?.get(1)

    fun extractRefNo(body: String): String? =
        REF_NO.find(body)?.groupValues?.get(1)

    fun extractMessageId(body: String): String? =
        MESSAGE_ID.find(body)?.groupValues?.get(1)

    /**
     * Merchant / counterparty, or null when we genuinely cannot tell.
     *
     * Returning null is a feature. A wrong merchant is worse than no merchant, because
     * the app *learns* from corrections — mislabel one and you train a rule keyed on
     * garbage. Live data taught this the hard way: ICICI's fraud-report number
     * `9215676766` was being harvested from "SMS BLOCK 3008 to 9215676766" and became
     * the merchant on 38 separate transactions.
     */
    fun extractMerchant(body: String, rule: BankRule): String? {
        val cleaned = stripNoiseTail(body)
        val patterns = rule.merchantPatterns + DEFAULT_MERCHANT_PATTERNS
        for (p in patterns) {
            val match = p.find(cleaned) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.let(::cleanMerchant) ?: continue
            if (isPlausibleMerchant(candidate)) return candidate
        }
        return null
    }

    /**
     * Removes the boilerplate every bank staples to the end of an alert. This runs
     * before merchant extraction only — amount and balance are read from the original
     * text, which still contains the balance clause.
     */
    fun stripNoiseTail(body: String): String = NOISE_TAIL.replace(body, " ").trim()

    private fun cleanMerchant(raw: String): String {
        var s = raw.trim()
            .trim('.', ',', ';', ':', '-', '*', '/', '"', '\'')
            .replace(Regex("\\s+"), " ")
        // Strip trailing noise a lot of banks append
        s = s.replace(
            Regex(
                "\\s+(on|ref|refno|upi|txn|trxn|info|avl|bal|dated|date|via|thru|" +
                    "using|through|with)\\b.*$",
                RegexOption.IGNORE_CASE,
            ),
            "",
        ).trim()
        // Drop a trailing date fragment such as "12-05-24"
        s = s.replace(Regex("\\s*\\d{2}[-/]\\d{2}[-/]\\d{2,4}\\s*$"), "").trim()
        if (s.length > 48) s = s.take(48).trim()
        // Title-case screaming merchant names, keep VPAs as-is
        return if (s.contains('@')) s.lowercase() else prettify(s)
    }

    /**
     * Rejects the things that look like a merchant to a regex but aren't one.
     * Every entry here corresponds to a wrong label seen in real data.
     */
    private fun isPlausibleMerchant(candidate: String): Boolean {
        if (candidate.length < 2) return false

        // A bare number is a helpline, a card tail or an amount — never a shop.
        val digitsOnly = candidate.filter { !it.isWhitespace() }
        if (digitsOnly.all { it.isDigit() }) return false

        val lower = candidate.lowercase().trim()
        if (lower in MERCHANT_STOPWORDS) return false

        // "Rs110000", "Inr 110000" — an amount caught by a "to X" pattern.
        if (AMOUNT_LIKE_MERCHANT.matches(lower)) return false

        // Needs at least one letter to be a name at all.
        return candidate.any { it.isLetter() }
    }

    /**
     * Screaming merchant names get title-cased. A short single-word name is left
     * alone because it is almost always a genuine acronym — KFC, PVR, ATM. Applying
     * that rule per-word instead would turn "BIG BAZAAR" into "BIG Bazaar".
     */
    private fun prettify(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        val words = s.split(" ").filter { it.isNotBlank() }
        if (words.size == 1 && words[0].length <= 4) return words[0]
        return words.joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    /** dd-MM-yy / dd/MM/yyyy / dd-MMM-yy appearing after "on", with time when given. */
    fun extractDateTime(body: String): SmsDateParser.Parsed? {
        DATE_AFTER_ON.find(body)?.let { m ->
            SmsDateParser.parse(m.groupValues[1], m.groupValues.getOrNull(2))
                ?.let { return it }
        }
        // Federal Bank's compact form: "on 02JUL2026 22:57:32" / "on 01Jul26 07:48"
        DATE_COMPACT.find(body)?.let { m ->
            SmsDateParser.parseCompact(m.groupValues[1], m.groupValues.getOrNull(2))
                ?.let { return it }
        }
        return null
    }

    fun extractDate(body: String): Long? = extractDateTime(body)?.epochMillis

    /**
     * The date a bill falls due, as opposed to the date it was issued. Both appear in
     * the same message, so this anchors on "due on/by" rather than taking the first
     * date it finds — otherwise a statement dated the 3rd looks overdue immediately.
     */
    fun extractDueDate(body: String): Long? {
        val match = DUE_DATE.find(body) ?: return null
        val text = match.groupValues[1]
        return SmsDateParser.parse(text)?.epochMillis
            ?: SmsDateParser.parseCompact(text)?.epochMillis
    }

    companion object {
        private const val CURRENCY = "(?:rs\\.?|inr|₹)"

        /**
         * The `(?![a-z])` after the K/L/Cr suffix is load-bearing. Without it,
         * "Rs.85,000.00 credited" matches the "cr" of *credited* as a crore
         * multiplier and reports ₹85,000 as ₹85,00,00,00,000.
         */
        val AMOUNT_AFTER_CURRENCY = Regex(
            "$CURRENCY\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?(?:\\s?(?:k|l|cr)(?![a-z]))?)",
            RegexOption.IGNORE_CASE,
        )
        val AMOUNT_BEFORE_CURRENCY = Regex(
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*$CURRENCY",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The separator is `[\s:=-]*`, not a space or a colon.
         *
         * Federal writes "BAL-Rs.3000.23-Federal Bank". Every other bank in the table
         * writes "Avl Bal: Rs.3000.23", so the clause expected whitespace or a colon and
         * stopped dead at the hyphen — the balance went unrecorded on an account whose
         * ₹3,000 minimum is the reason the balance is worth recording at all.
         *
         * The leading `\b` matters once the separator is that permissive: without it
         * "global 500" offers "bal" + " " + "500" and reports a balance of ₹500.
         */
        val BALANCE_CLAUSE = Regex(
            "\\b(?:avl(?:\\.|able)?\\s*(?:bal|balance)|a/c\\s*bal(?:ance)?|clr\\s*bal|" +
                "bal(?:ance)?\\s*(?:is|:)?)[\\s:=-]*$CURRENCY?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            RegexOption.IGNORE_CASE,
        )

        val BALANCE_MARKER = Regex(
            "(avl bal|available balance|a/c bal|clear balance|clr bal|\\bbal\\s*[-:=])",
            RegexOption.IGNORE_CASE,
        )

        /**
         * `sent\s+to\s+(?!\S*@)` rather than a plain "sent to": ICICI's "Statement is
         * sent to ma****ya@gmail.com" was matching, which made the app treat a
         * statement notice as a settled debit and skip the bill-reminder path.
         *
         * The lookahead spans `\S*` rather than a word-character class because banks
         * mask the address with asterisks — `[\w.\-]+@` stops dead at the first `*`
         * and the guard silently does nothing. Money is never sent to an email here.
         */
        val DEBIT_VERB = Regex(
            "\\b(debited|debit|spent|paid|withdrawn|withdrawal|purchase[d]?|" +
                "sent\\s+to\\s+(?!\\S*@)|transferred to|deducted|charged|" +
                "txn of|transaction of|has been used|used at|payment of)",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Note the absence of a bare "credit": it matches "Credit Card" and
         * "credit limit", which turned statement notices into phantom income.
         */
        val CREDIT_VERB = Regex(
            "\\b(credited|received|deposited|refund(?:ed)?|cashback of|" +
                "salary|has been added|added to your)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** "credit card", "SuperCard", "OneCard", or a plain "card". */
        val CARD_WORD = Regex("\\b[a-z]*card\\b", RegexOption.IGNORE_CASE)

        val OTP_BARE = Regex("\\b\\d{4,8}\\b\\s+is\\s+your", RegexOption.IGNORE_CASE)
        val CURRENCY_PRESENT = Regex(CURRENCY, RegexOption.IGNORE_CASE)

        val ACCOUNT_TAIL = Regex(
            "(?:a/c|ac|acct|account|card|xx|x{2,}|\\*{2,})\\s*(?:no\\.?)?\\s*" +
                "(?:ending\\s*)?(?:x+|\\*+)?\\s*(\\d{4})\\b",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The bank's own identifier for the message: "Msg Id 2644123773".
         *
         * Kerala Gramin sends **two** SMS for one debit — a detailed one carrying a UPI
         * reference and a bare one carrying none — and both quote the same `Msg Id`. To
         * the deduplicator they looked like two payments: the references could not match
         * because only one message had one, and the pair arrived just over three minutes
         * apart, a hair outside [SmsDeduplicator.WINDOW_MS]. A ₹1,778 card bill and a
         * ₹7,177.79 one were each counted twice, ₹8,955.79 of a single month.
         *
         * Deliberately *not* folded into [REF_NO]. That value is shown on the detail
         * screen as the reference to quote at the bank, and a message id is not one —
         * and where a message carries both, the leftmost match would win and change
         * which number people see.
         */
        val MESSAGE_ID = Regex(
            "\\bmsg(?:\\s*|-)?id\\s*[:.\\-]?\\s*([0-9]{6,20})",
            RegexOption.IGNORE_CASE,
        )

        val REF_NO = Regex(
            "(?:upi\\s*ref(?:erence)?(?:\\s*no)?|ref(?:erence)?\\s*(?:no|number)?|" +
                "txn\\s*(?:id|no)|transaction\\s*id|trn)\\s*[:.\\-]?\\s*([0-9]{6,20})",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Group 1 = date, group 2 = time when the bank bothered to include one.
         *
         * The meridiem is part of the time group. Without it "10:48 PM" was read as
         * 10:48, which put every evening transaction from a 12-hour bank twelve hours
         * early — and, far worse, collapsed a morning and an evening payment of the
         * same amount into one row, losing the second entirely.
         */
        val DATE_AFTER_ON = Regex(
            "\\bon\\s+(\\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\\d{2,4})" +
                "(?:\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp]\\.?[Mm]\\.?)?))?",
            RegexOption.IGNORE_CASE,
        )

        val DUE_DATE = Regex(
            "\\bdue\\s+(?:on|by|date[:\\s]*)\\s*" +
                "(\\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\\d{2,4}|\\d{1,2}[A-Za-z]{3}\\d{2,4})",
            RegexOption.IGNORE_CASE,
        )

        /** Federal Bank: "on 02JUL2026 22:57:32" and "on 01Jul26 07:48". */
        val DATE_COMPACT = Regex(
            "\\bon\\s+(\\d{1,2}[A-Za-z]{3}\\d{2,4})\\b" +
                "(?:\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s*[AaPp]\\.?[Mm]\\.?)?))?",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Everything a bank staples on after the useful part. Stripped before merchant
         * extraction so a fraud-helpline number can never be mistaken for a shop.
         */
        val NOISE_TAIL = Regex(
            "(?:if not you.*|not you\\s*\\?.*|to dispute.*|sms block.*|" +
                "to convert this txn.*|call \\d{6,}.*|use fedmobile.*|know more.*|" +
                "avl(?:\\.|able)?\\s*l(?:i)?m(?:i)?t.*|avl\\s*bal.*|" +
                "\\bbal\\s*[:\\-]?\\s*(?:rs|inr|₹).*|-\\s*federal bank.*)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        /** Regex-plausible but never a real merchant. */
        val MERCHANT_STOPWORDS = setOf(
            "your", "you", "a/c", "ac", "acct", "account", "the", "my", "our",
            "this", "that", "it", "us", "me", "self", "card", "bank", "upi",
            "your a/c", "your account", "your card",
            // Whole phrases, for the banks that write "credited to a/c no. XXXX".
            "a/c no", "a/c no.", "ac no", "acct no", "account no", "a/c number",
        )

        /** "UPI Ref no 5190…", "UPI/519012345678", "Ref no. 5190…" beside a UPI mention. */
        val UPI_REFERENCE = Regex("upi[\\s/:-]*(?:ref(?:erence)?)?[\\s.no:-]*\\d{6,}|\\bupi\\b.*\\bref\\b",
            RegexOption.IGNORE_CASE)

        /** "credited to a/c no. XXXXX8891", "transferred to A/c 8891". */
        val COUNTERPARTY_ACCOUNT = Regex(
            "(?:credited|transferred|sent)\\s+to\\s+(?:a/c|ac|acct|account)\\s*" +
                "(?:no\\.?)?\\s*([Xx*]*\\d{4,})",
            RegexOption.IGNORE_CASE,
        )

        val AMOUNT_LIKE_MERCHANT = Regex("^(?:rs|inr|₹)\\.?\\s*[0-9,.]+$", RegexOption.IGNORE_CASE)

        /**
         * Where a merchant name stops. Banks tack a clause onto the end of the
         * merchant far more often than they punctuate it, so terminating only on
         * "." leaves you with "HOTSTAR using Amazon Pay balance" as the merchant.
         */
        private const val MERCHANT_END =
            "\\s+(?:on|ref|refno|upi|using|via|through|thru|with|from|a/c|ac|acct|" +
                "account|card|txn|trxn|info|avl|bal|dated|date|towards)\\b|[.;\\n]|$"

        /**
         * Ordered most-specific first. The lazy quantifiers plus explicit lookahead
         * terminators are what stop these from swallowing the rest of the message.
         */
        val DEFAULT_MERCHANT_PATTERNS = listOf(
            // UPI VPA: "to swiggy@ybl", "to 9876543210@paytm"
            Regex(
                "(?:to|from|vpa)\\s+([\\w.\\-]{2,}@[\\w.\\-]{2,})",
                RegexOption.IGNORE_CASE,
            ),
            // ICICI cards: "spent using ICICI Bank Card XX3008 on 19-Jul-26 on RELIANCE SMART".
            // The merchant follows a *second* "on" — the first one introduces the date.
            // Without this the generic patterns fall through to the fraud-helpline tail.
            Regex(
                "\\bon\\s+\\d{1,2}[-/][A-Za-z0-9]{2,3}[-/]\\d{2,4}\\s+on\\s+" +
                    "([A-Za-z0-9][A-Za-z0-9 &._'*\\-/]{1,47}?)(?=[.;\\n]|$)",
                RegexOption.IGNORE_CASE,
            ),
            // "Info: SWIGGY BANGALORE" / "Info-AMAZON"
            Regex(
                "info\\s*[:\\-]\\s*([^.;\\n]{2,48})",
                RegexOption.IGNORE_CASE,
            ),
            // "at AMAZON on 12-05-24" / "at BIG BAZAAR."
            Regex(
                "\\bat\\s+([A-Za-z0-9][A-Za-z0-9 &._'*\\-/]{1,47}?)(?=$MERCHANT_END)",
                RegexOption.IGNORE_CASE,
            ),
            // "to JOHN DOE on" / "towards ELECTRICITY BILL"
            // The negative lookahead mirrors the one on the "from" pattern below.
            // Kerala Gramin words every UPI debit as "credited to a/c no. XXXX",
            // naming the destination *account* rather than the person — and without
            // this guard that phrase yields a merchant literally called "a/c no",
            // which is what 189 of one household's 460 transactions were filed under.
            Regex(
                "\\b(?:to|towards|in favour of|favouring)\\s+" +
                    "(?!a/c\\b|ac\\b|acct\\b|account\\b|your\\b)" +
                    "([A-Za-z0-9][A-Za-z0-9 &._'*\\-/]{1,47}?)" +
                    "(?=$MERCHANT_END)",
                RegexOption.IGNORE_CASE,
            ),
            // Incoming credits: "credited to your A/c XX4657 from MANUEL FRA on ..."
            // and "via NEFT from INDIAN CLE on ...". The negative lookahead keeps this
            // off "debited from a/c XX4657", which names an account, not a person.
            Regex(
                "\\bfrom\\s+(?!a/c\\b|ac\\b|your\\b|account\\b)" +
                    "([A-Za-z][A-Za-z0-9 &._'*\\-/]{1,47}?)(?=$MERCHANT_END)",
                RegexOption.IGNORE_CASE,
            ),
            // "trf to XYZ" / "transfer to XYZ"
            Regex(
                "\\b(?:trf|transfer)\\s+to\\s+([A-Za-z0-9][A-Za-z0-9 &._'*\\-/]{1,47}?)(?=[.;\\n]|$)",
                RegexOption.IGNORE_CASE,
            ),
        )
    }
}

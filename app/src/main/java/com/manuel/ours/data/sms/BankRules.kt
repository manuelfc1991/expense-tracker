package com.manuel.ours.data.sms

/**
 * One entry per sender family. We deliberately keep the *identification* of the bank
 * separate from the *extraction* of fields: nearly every Indian bank uses the same
 * handful of phrasings, so a shared extractor with per-bank overrides beats 18
 * bespoke regexes that all rot independently.
 */
data class BankRule(
    val bank: String,
    /** 6-char TRAI headers, uppercase, without the 2-char operator prefix. */
    val headers: List<String>,
    /** Optional override when a bank writes merchants in an unusual shape. */
    val merchantPatterns: List<Regex> = emptyList(),
    val isCard: Boolean = false,
    val isWallet: Boolean = false,
)

object BankRules {

    val ALL: List<BankRule> = listOf(
        BankRule("HDFC Bank", listOf("HDFCBK", "HDFCBN", "HDFCB", "HDFC")),
        BankRule("ICICI Bank", listOf("ICICIB", "ICICIT", "ICICI")),
        BankRule("State Bank of India", listOf("SBIINB", "SBIPSG", "SBIUPI", "ATMSBI", "SBICRD", "SBIBNK")),
        BankRule("Axis Bank", listOf("AXISBK", "AXISBN", "AXIBNK")),
        BankRule("Kotak Mahindra", listOf("KOTAKB", "KOTAKM", "KMBLNK")),
        BankRule("Punjab National Bank", listOf("PNBSMS", "PNBBNK")),
        BankRule("Canara Bank", listOf("CANBNK", "CANARA")),
        BankRule("Bank of Baroda", listOf("BOBTXN", "BOBSMS", "BOBIBN")),
        BankRule("Bank of India", listOf("BOIIND", "BOISMS")),
        BankRule("Union Bank", listOf("UNIONB", "UBININ")),
        BankRule("Yes Bank", listOf("YESBNK", "YESBK")),
        BankRule("IDFC First", listOf("IDFCFB", "IDFCBK")),
        BankRule("IndusInd Bank", listOf("INDUSB", "INDUSI")),
        BankRule("Federal Bank", listOf("FEDBNK", "FEDERL")),
        // Regional and small-finance banks. These matter far more than their size
        // suggests: a household usually banks with exactly one of them, so a missing
        // header here is not a few stray messages, it is every salary credit and every
        // UPI debit that household will ever have. Kerala Gramin alone accounted for
        // 466 discarded messages on the first phone this was tested against — the
        // sender check runs before everything else, so not one of them was even
        // examined for an amount.
        BankRule("Kerala Gramin Bank", listOf("KGBANK", "KERGRB", "KLGBNK")),
        BankRule("Utkarsh Small Finance Bank", listOf("UTKBNK", "UTKARS")),
        BankRule("Karnataka Bank", listOf("KARBNK", "KTKBNK")),
        BankRule("South Indian Bank", listOf("SIBSMS", "SOUTHB")),
        BankRule("Indian Bank", listOf("INDBNK", "ALLBNK")),
        BankRule("Central Bank of India", listOf("CBINDI", "CENTBK")),
        BankRule("Indian Overseas Bank", listOf("IOBCHN", "IOBSMS")),
        BankRule("UCO Bank", listOf("UCOBNK", "UCOBK")),
        BankRule("Bandhan Bank", listOf("BANDHN", "BDNBNK")),
        BankRule("AU Small Finance Bank", listOf("AUBANK", "AUSFBL")),
        BankRule("RBL Bank", listOf("RBLBNK", "RATNBK")),
        BankRule("Bank of Maharashtra", listOf("MAHABK", "BOMSMS")),
        BankRule("Paytm Payments Bank", listOf("PAYTMB", "PYTMPB"), isWallet = true),
        BankRule("Google Pay", listOf("GPAYIN", "GOOGPY", "GPAY"), isWallet = true),
        BankRule("PhonePe", listOf("PHONPE", "PHNPE", "PHONEP"), isWallet = true),
        BankRule("Paytm", listOf("PAYTM", "PYTMDT", "PYTMBK", "IPAYTM"), isWallet = true),
        // Payment gateways that send the settlement SMS on a merchant's behalf.
        BankRule("Juspay", listOf("JUSPAY"), isWallet = true),
        BankRule("JioPay", listOf("JIOPAY"), isWallet = true),
        BankRule("Amazon Pay", listOf("AMZNPY", "AMAZON"), isWallet = true),
        BankRule("CRED", listOf("CREDCL", "CREDIT"), isWallet = true),
        BankRule("BHIM", listOf("BHIMPE", "NPCIBH", "NPCIBC"), isWallet = true),
        BankRule("slice", listOf("SLICEIT", "SLICEP"), isCard = true),
        BankRule("OneCard", listOf("ONECRD", "ONECAR"), isCard = true),
        BankRule("HDFC Card", listOf("HDFCCC", "HDFCCD"), isCard = true),
        BankRule("ICICI Card", listOf("ICICICC", "ICICCD"), isCard = true),
        BankRule("SBI Card", listOf("SBICARD", "SBICRD"), isCard = true),
        BankRule("Axis Card", listOf("AXISCC", "AXISCD"), isCard = true),
    )

    private val byHeader: Map<String, BankRule> = buildMap {
        ALL.forEach { rule -> rule.headers.forEach { put(it.uppercase(), rule) } }
    }

    /**
     * Every bank name this app can produce, for code that needs to tell "a merchant the
     * user named" from "the institution we fell back to".
     *
     * A bare credit is labelled with its bank, which is useful to read but must never
     * be learned as a merchant rule — otherwise categorising one salary teaches the app
     * that *everything* that bank ever credits is salary.
     */
    val BANK_NAMES: Set<String> = ALL.map { it.bank.lowercase() }.toSet()

    fun isBankName(merchant: String?): Boolean =
        merchant != null && merchant.trim().lowercase() in BANK_NAMES

    /**
     * Normalises "AD-HDFCBK", "VM-HDFCBK-S", "hdfcbk" to a known rule.
     * Returns null for unknown or personal-number senders.
     */
    /**
     * Headers taught through the sheet, on top of the compiled table.
     *
     * A header this app has never heard of is dropped before parsing even starts —
     * which is how one missing line silently discarded 466 of this household's
     * messages. Adding a row to the sheet's `rules` tab now fixes that on every phone
     * without anybody installing a new APK.
     */
    @Volatile
    private var taught: Map<String, String> = emptyMap()

    fun setTaughtSenders(headerToBank: Map<String, String>) {
        taught = headerToBank.mapKeys { it.key.uppercase().trim() }
    }

    fun forSender(sender: String): BankRule? {
        val header = normaliseHeader(sender) ?: return null
        byHeader[header]?.let { return it }
        taught[header]?.let { return BankRule(bank = it, headers = listOf(header)) }
        // Fall back to a prefix match — banks add suffixes like "HDFCBKS", "SBIINBA".
        byHeader.entries.firstOrNull { (key, _) ->
            header.startsWith(key) || key.startsWith(header)
        }?.let { return it.value }
        return taught.entries.firstOrNull { (key, _) ->
            header.startsWith(key) || key.startsWith(header)
        }?.let { BankRule(bank = it.value, headers = listOf(it.key)) }
    }

    /**
     * TRAI DLT sender IDs look like "AD-HDFCBK" or "VM-ICICIB-S". Anything purely
     * numeric is a personal phone number and must never be parsed as a bank alert.
     */
    fun normaliseHeader(sender: String): String? {
        val cleaned = sender.trim().uppercase().replace(" ", "")
        if (cleaned.isEmpty()) return null
        if (cleaned.all { it.isDigit() || it == '+' || it == '-' }) return null

        val parts = cleaned.split("-").filter { it.isNotBlank() }
        // Drop a leading 2-char operator code (AD, VM, JD, AX, BZ, VK, JM, BP, BW, CP, TX)
        val core = when {
            parts.size >= 2 && parts[0].length <= 2 -> parts[1]
            parts.isNotEmpty() -> parts[0]
            else -> return null
        }
        return core.filter { it.isLetterOrDigit() }.ifEmpty { null }
    }

    fun isKnownSender(sender: String): Boolean = forSender(sender) != null
}

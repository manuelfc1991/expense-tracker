package com.manuel.ours.data.sms

import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.TxnType

/**
 * Merchant string -> category. Seeded with Indian merchants; every user correction is
 * written back as a userDefined rule that outranks the seeds, so a wrong guess is
 * wrong exactly once.
 */
object Categorizer {

    fun categorize(
        merchant: String?,
        type: TxnType,
        rules: List<MerchantRuleEntity>,
    ): Category {
        if (type == TxnType.CREDIT) {
            val m = merchant?.lowercase().orEmpty()
            return when {
                // Money coming back out of an investment is not earnings. Without
                // this, every matured FD reads as a windfall and net savings lies.
                RETURNING_INVESTMENT.any { it in m } -> Category.INVESTMENTS
                else -> Category.INCOME
            }
        }
        val m = merchant?.lowercase()?.trim() ?: return Category.OTHER

        // userDefined first, then longest pattern — both guaranteed by the DAO ordering.
        rules.firstOrNull { it.pattern in m }?.let {
            return Category.fromNameOrOther(it.category)
        }
        return Category.OTHER
    }

    /** Credit-side markers meaning an investment paid back, not income arriving. */
    private val RETURNING_INVESTMENT = listOf(
        "matured", "maturity", "fd closure", "redemption", "redeemed",
        "zerodha", "groww", "upstox", "kuvera", "mutual fund",
    )

    /** Seeded on first launch. Patterns are lowercase substrings. */
    val SEED_RULES: List<Pair<String, Category>> = listOf(
        // Food & dining
        "swiggy" to Category.FOOD, "zomato" to Category.FOOD, "dominos" to Category.FOOD,
        "pizza hut" to Category.FOOD, "mcdonald" to Category.FOOD, "kfc" to Category.FOOD,
        "burger king" to Category.FOOD, "starbucks" to Category.FOOD, "cafe" to Category.FOOD,
        "restaurant" to Category.FOOD, "biryani" to Category.FOOD, "dunzo" to Category.FOOD,
        "eatfit" to Category.FOOD, "faasos" to Category.FOOD, "chaayos" to Category.FOOD,
        "barbeque" to Category.FOOD, "hotel" to Category.FOOD,

        // Groceries
        "blinkit" to Category.GROCERIES, "zepto" to Category.GROCERIES,
        "instamart" to Category.GROCERIES, "bigbasket" to Category.GROCERIES,
        "big basket" to Category.GROCERIES, "dmart" to Category.GROCERIES,
        "d mart" to Category.GROCERIES, "reliance fresh" to Category.GROCERIES,
        "more retail" to Category.GROCERIES, "spencer" to Category.GROCERIES,
        "grofers" to Category.GROCERIES, "jiomart" to Category.GROCERIES,
        "kirana" to Category.GROCERIES, "supermarket" to Category.GROCERIES,
        "vegetable" to Category.GROCERIES, "milk" to Category.GROCERIES,
        "amul" to Category.GROCERIES, "nandini" to Category.GROCERIES,

        // Transport & fuel
        "uber" to Category.TRANSPORT, "ola" to Category.TRANSPORT,
        "rapido" to Category.TRANSPORT, "irctc" to Category.TRANSPORT,
        "indianoil" to Category.TRANSPORT, "indian oil" to Category.TRANSPORT,
        "iocl" to Category.TRANSPORT, "hpcl" to Category.TRANSPORT,
        "bharat petroleum" to Category.TRANSPORT, "bpcl" to Category.TRANSPORT,
        "shell" to Category.TRANSPORT, "petrol" to Category.TRANSPORT,
        "fuel" to Category.TRANSPORT, "fastag" to Category.TRANSPORT,
        "parking" to Category.TRANSPORT, "metro" to Category.TRANSPORT,
        "redbus" to Category.TRANSPORT, "bmtc" to Category.TRANSPORT,
        "namma yatri" to Category.TRANSPORT, "blusmart" to Category.TRANSPORT,

        // Shopping
        "amazon" to Category.SHOPPING, "flipkart" to Category.SHOPPING,
        "myntra" to Category.SHOPPING, "ajio" to Category.SHOPPING,
        "meesho" to Category.SHOPPING, "nykaa" to Category.SHOPPING,
        "tatacliq" to Category.SHOPPING, "tata cliq" to Category.SHOPPING,
        "decathlon" to Category.SHOPPING, "lifestyle" to Category.SHOPPING,
        "shoppers stop" to Category.SHOPPING, "westside" to Category.SHOPPING,
        "pantaloons" to Category.SHOPPING, "croma" to Category.SHOPPING,
        "reliance digital" to Category.SHOPPING, "ikea" to Category.SHOPPING,
        "firstcry" to Category.SHOPPING, "snapdeal" to Category.SHOPPING,

        // Bills & utilities
        "jio" to Category.BILLS, "airtel" to Category.BILLS, "vodafone" to Category.BILLS,
        "vi " to Category.BILLS, "bsnl" to Category.BILLS, "tata power" to Category.BILLS,
        "adani electricity" to Category.BILLS, "bescom" to Category.BILLS,
        "mseb" to Category.BILLS, "electricity" to Category.BILLS,
        "gas" to Category.BILLS, "indane" to Category.BILLS, "water bill" to Category.BILLS,
        "broadband" to Category.BILLS, "act fibernet" to Category.BILLS,
        "hathway" to Category.BILLS, "recharge" to Category.BILLS,
        "dth" to Category.BILLS, "tata sky" to Category.BILLS,

        // Rent & housing
        "rent" to Category.RENT, "nobroker" to Category.RENT,
        "maintenance" to Category.RENT, "society" to Category.RENT,

        // Health
        "apollo" to Category.HEALTH, "pharmeasy" to Category.HEALTH,
        "1mg" to Category.HEALTH, "netmeds" to Category.HEALTH,
        "medplus" to Category.HEALTH, "practo" to Category.HEALTH,
        "hospital" to Category.HEALTH, "clinic" to Category.HEALTH,
        "diagnostic" to Category.HEALTH, "pharmacy" to Category.HEALTH,
        "medical" to Category.HEALTH, "cult.fit" to Category.HEALTH,
        "cultfit" to Category.HEALTH, "gym" to Category.HEALTH,

        // Education
        "byju" to Category.EDUCATION, "unacademy" to Category.EDUCATION,
        "vedantu" to Category.EDUCATION, "coursera" to Category.EDUCATION,
        "udemy" to Category.EDUCATION, "school" to Category.EDUCATION,
        "college" to Category.EDUCATION, "tuition" to Category.EDUCATION,
        "upgrad" to Category.EDUCATION,

        // Entertainment
        "netflix" to Category.ENTERTAINMENT, "hotstar" to Category.ENTERTAINMENT,
        "spotify" to Category.ENTERTAINMENT, "prime video" to Category.ENTERTAINMENT,
        "bookmyshow" to Category.ENTERTAINMENT, "pvr" to Category.ENTERTAINMENT,
        "inox" to Category.ENTERTAINMENT, "sony liv" to Category.ENTERTAINMENT,
        "zee5" to Category.ENTERTAINMENT, "jiocinema" to Category.ENTERTAINMENT,
        "youtube" to Category.ENTERTAINMENT, "gaana" to Category.ENTERTAINMENT,

        // Travel
        "makemytrip" to Category.TRAVEL, "goibibo" to Category.TRAVEL,
        "cleartrip" to Category.TRAVEL, "yatra" to Category.TRAVEL,
        "indigo" to Category.TRAVEL, "air india" to Category.TRAVEL,
        "vistara" to Category.TRAVEL, "spicejet" to Category.TRAVEL,
        "oyo" to Category.TRAVEL, "airbnb" to Category.TRAVEL,
        "booking.com" to Category.TRAVEL, "agoda" to Category.TRAVEL,

        // Investments
        "zerodha" to Category.INVESTMENTS, "groww" to Category.INVESTMENTS,
        "upstox" to Category.INVESTMENTS, "kuvera" to Category.INVESTMENTS,
        "coin" to Category.INVESTMENTS, "mutual fund" to Category.INVESTMENTS,
        "sip" to Category.INVESTMENTS, "nps" to Category.INVESTMENTS,
        "ppf" to Category.INVESTMENTS, "lic" to Category.INVESTMENTS,
        "policybazaar" to Category.INVESTMENTS, "insurance" to Category.INVESTMENTS,

        // EMI & loans
        "emi" to Category.EMI, "loan" to Category.EMI,
        "bajaj finserv" to Category.EMI, "hdb financial" to Category.EMI,
        "credit card payment" to Category.EMI, "cred" to Category.EMI,

        // Transfers
        "upi" to Category.TRANSFERS, "neft" to Category.TRANSFERS,
        "imps" to Category.TRANSFERS, "rtgs" to Category.TRANSFERS,
        "atm" to Category.TRANSFERS, "self" to Category.TRANSFERS,
    )

    fun seedEntities(): List<MerchantRuleEntity> = SEED_RULES.map { (pattern, category) ->
        MerchantRuleEntity(pattern = pattern, category = category.name, userDefined = false)
    }
}

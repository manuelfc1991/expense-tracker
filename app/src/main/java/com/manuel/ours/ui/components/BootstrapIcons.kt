package com.manuel.ours.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.manuel.ours.R
import com.manuel.ours.domain.model.Category

/**
 * Every icon in the app, in one place.
 *
 * Bootstrap Icons (v1.11.3, MIT), converted from the official SVGs to Android vector
 * drawables. One registry rather than scattered `painterResource` calls means the set
 * can be swapped wholesale — as it just was, from Material — by editing this file
 * instead of hunting through twenty screens.
 *
 * Note these are the *outline* weight throughout, with filled variants only for the
 * selected state in the bottom bar. Mixing weights is the fastest way to make a
 * carefully chosen icon set look accidental.
 */
object BiIcon {
    // Navigation
    @DrawableRes val Home = R.drawable.bi_house
    @DrawableRes val HomeFill = R.drawable.bi_house_fill
    @DrawableRes val Activity = R.drawable.bi_receipt
    @DrawableRes val ActivityFill = R.drawable.bi_receipt_cutoff
    @DrawableRes val Summary = R.drawable.bi_pie_chart
    @DrawableRes val SummaryFill = R.drawable.bi_pie_chart_fill
    @DrawableRes val Budgets = R.drawable.bi_wallet2
    @DrawableRes val BudgetsFill = R.drawable.bi_wallet_fill
    @DrawableRes val Settings = R.drawable.bi_gear
    @DrawableRes val SettingsFill = R.drawable.bi_gear_fill

    // Actions and status
    @DrawableRes val Add = R.drawable.bi_plus_lg
    @DrawableRes val Synced = R.drawable.bi_cloud_check
    @DrawableRes val NotSynced = R.drawable.bi_cloud_slash
    @DrawableRes val Sync = R.drawable.bi_arrow_repeat
    @DrawableRes val TrendUp = R.drawable.bi_arrow_up
    @DrawableRes val TrendDown = R.drawable.bi_arrow_down
    @DrawableRes val Back = R.drawable.bi_arrow_left
    @DrawableRes val PreviousMonth = R.drawable.bi_chevron_left
    @DrawableRes val NextMonth = R.drawable.bi_chevron_right
    @DrawableRes val Export = R.drawable.bi_download
    @DrawableRes val Search = R.drawable.bi_search
    @DrawableRes val Delete = R.drawable.bi_trash
    @DrawableRes val Categorise = R.drawable.bi_tag
    @DrawableRes val NeedsReview = R.drawable.bi_question_circle
    @DrawableRes val Dismiss = R.drawable.bi_x_lg
    @DrawableRes val Camera = R.drawable.bi_camera
    @DrawableRes val Locked = R.drawable.bi_shield_lock
    @DrawableRes val Folder = R.drawable.bi_folder2_open
    @DrawableRes val Bluetooth = R.drawable.bi_bluetooth
    @DrawableRes val Warning = R.drawable.bi_exclamation_triangle
    @DrawableRes val Message = R.drawable.bi_envelope

    // Illustrative marks for empty states and onboarding, replacing the emoji that
    // rendered differently on every OEM skin and ignored the theme colour entirely.
    @DrawableRes val Inbox = R.drawable.bi_inbox
    @DrawableRes val NoResults = R.drawable.bi_inboxes
    @DrawableRes val Household = R.drawable.bi_people
    @DrawableRes val Privacy = R.drawable.bi_lock
    @DrawableRes val Done = R.drawable.bi_check_circle
    @DrawableRes val Scanning = R.drawable.bi_send_check
    @DrawableRes val Saved = R.drawable.bi_graph_up
    @DrawableRes val FolderClosed = R.drawable.bi_folder2

    /**
     * Categories carry a drawable rather than an emoji.
     *
     * Emoji render differently on every OEM skin, ignore your theme colour, and sit
     * on a different baseline than the text beside them. An icon set does none of that.
     */
    @DrawableRes
    fun forCategory(category: Category): Int = when (category) {
        Category.FOOD -> R.drawable.bi_cup_hot
        Category.GROCERIES -> R.drawable.bi_basket
        Category.TRANSPORT -> R.drawable.bi_fuel_pump
        Category.SHOPPING -> R.drawable.bi_bag
        Category.BILLS -> R.drawable.bi_lightning_charge
        Category.RENT -> R.drawable.bi_house_door
        Category.HEALTH -> R.drawable.bi_heart_pulse
        Category.EDUCATION -> R.drawable.bi_book
        Category.ENTERTAINMENT -> R.drawable.bi_film
        Category.TRAVEL -> R.drawable.bi_airplane
        Category.INVESTMENTS -> R.drawable.bi_graph_up_arrow
        Category.EMI -> R.drawable.bi_bank
        Category.TRANSFERS -> R.drawable.bi_arrow_left_right
        Category.SELF_TRANSFER -> R.drawable.bi_arrow_left_right
        Category.CARD_PAYMENT -> R.drawable.bi_credit_card
        Category.INCOME -> R.drawable.bi_cash_coin
        Category.OTHER -> R.drawable.bi_three_dots
    }
}

/** Thin wrapper so call sites never repeat `painterResource`. */
@Composable
fun BiIconView(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}

@Composable
fun CategoryIcon(
    category: Category,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    BiIconView(
        icon = BiIcon.forCategory(category),
        contentDescription = category.label,
        modifier = modifier,
        tint = tint,
    )
}

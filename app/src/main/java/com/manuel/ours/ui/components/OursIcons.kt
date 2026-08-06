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
 * The set is drawn for this app rather than borrowed: `tools/icons.py` holds the geometry
 * and generates both `res/drawable/ic_*.xml` and the SVG sprite the mockups in
 * `design/v7/` use, so a glyph in a design document is the glyph that ships. Edit the
 * script, not the XML.
 *
 * ### Why it is not Bootstrap Icons any more
 *
 * The previous set was Bootstrap Icons 1.11.3 — **filled silhouettes on a 16px grid** —
 * and this app draws icons at 13–24dp. Two things went wrong at those sizes, both visible
 * on a real phone rather than in a preview:
 *
 * - **Detail collapsed.** The old wastebasket put a lid, a rim, a tapered body and three
 *   tick marks into solid fill; at 13dp, which is the size the Trash row and the delete
 *   button actually use, the lid merged into the body and it read as a grey blob.
 * - **The weight was never consistent.** A glyph drawn on a 16 grid and scaled to 24dp
 *   carries about 1.5× the apparent stroke of one drawn at 24, so a screen mixing
 *   category marks with chevrons and status glyphs never looked like one family.
 *
 * This set is **stroke geometry on a 24 grid**: 1.75dp, round caps, round joins, a 20dp
 * live area. Stroke rather than filled outline is the load-bearing choice — the weight is
 * one number instead of an emergent property of each shape, and a stroke does not thin as
 * the drawable scales down. Android has supported `strokeWidth`/`strokeColor` on `<path>`
 * since API 21 and this module is minSdk 26, so there is no compatibility cost.
 *
 * Filled variants exist **only** for the five selected bottom-nav tabs, derived from the
 * same silhouettes. Mixing weights anywhere else is the fastest way to make a carefully
 * chosen icon set look accidental.
 */
object OursIcon {
    // Navigation
    @DrawableRes val Home = R.drawable.ic_home
    @DrawableRes val HomeFill = R.drawable.ic_home_fill
    @DrawableRes val Activity = R.drawable.ic_receipt
    @DrawableRes val ActivityFill = R.drawable.ic_receipt_fill
    @DrawableRes val Summary = R.drawable.ic_chart
    @DrawableRes val SummaryFill = R.drawable.ic_chart_fill
    @DrawableRes val Budgets = R.drawable.ic_wallet
    @DrawableRes val BudgetsFill = R.drawable.ic_wallet_fill
    @DrawableRes val Settings = R.drawable.ic_gear
    @DrawableRes val SettingsFill = R.drawable.ic_gear_fill

    // Actions and status
    @DrawableRes val Add = R.drawable.ic_plus
    @DrawableRes val Synced = R.drawable.ic_cloud_check
    @DrawableRes val NotSynced = R.drawable.ic_cloud_slash
    @DrawableRes val Sync = R.drawable.ic_arrow_repeat
    @DrawableRes val TrendUp = R.drawable.ic_arrow_up
    @DrawableRes val TrendDown = R.drawable.ic_arrow_down
    @DrawableRes val Back = R.drawable.ic_arrow_left
    @DrawableRes val PreviousMonth = R.drawable.ic_chevron_left
    @DrawableRes val NextMonth = R.drawable.ic_chevron_right

    /**
     * Disclosure, not direction.
     *
     * This used to be an up/down arrow pair — the glyphs that mean *money moved*, which is
     * the one thing a control for opening a list must not say. A chevron is what the rest
     * of the app already uses for "there is more this way": a panel row ends in one and the
     * month stepper is a pair of them.
     */
    @DrawableRes val More = R.drawable.ic_chevron_right

    @DrawableRes val Expand = R.drawable.ic_chevron_down
    @DrawableRes val Collapse = R.drawable.ic_chevron_up
    @DrawableRes val Export = R.drawable.ic_download
    @DrawableRes val Search = R.drawable.ic_search

    /**
     * The wastebasket, and the reason this set was redrawn.
     *
     * The lid is one stroke, the handle a separate arch above it, and the body tapers
     * inward so it reads as a container rather than a box. Two ribs, not three: at 13dp a
     * third closes the gaps between them and the whole thing fills in.
     */
    @DrawableRes val Delete = R.drawable.ic_trash

    @DrawableRes val Categorise = R.drawable.ic_tag
    @DrawableRes val NeedsReview = R.drawable.ic_question
    @DrawableRes val Dismiss = R.drawable.ic_x
    @DrawableRes val Camera = R.drawable.ic_camera
    @DrawableRes val Locked = R.drawable.ic_shield_lock
    @DrawableRes val Folder = R.drawable.ic_folder_open
    @DrawableRes val Bluetooth = R.drawable.ic_bluetooth
    @DrawableRes val Warning = R.drawable.ic_warning
    @DrawableRes val Message = R.drawable.ic_envelope

    // Illustrative marks for empty states and onboarding.
    @DrawableRes val Inbox = R.drawable.ic_inbox
    @DrawableRes val NoResults = R.drawable.ic_inbox_stack
    @DrawableRes val Household = R.drawable.ic_people
    @DrawableRes val Privacy = R.drawable.ic_lock
    @DrawableRes val Done = R.drawable.ic_check_circle
    @DrawableRes val Scanning = R.drawable.ic_send_check
    @DrawableRes val Saved = R.drawable.ic_graph_up
    @DrawableRes val FolderClosed = R.drawable.ic_folder
    @DrawableRes val Check = R.drawable.ic_check

    /**
     * Categories carry a drawable rather than an emoji.
     *
     * Emoji render differently on every OEM skin, ignore your theme colour, and sit on a
     * different baseline than the text beside them. An icon set does none of that.
     */
    @DrawableRes
    fun forCategory(category: Category): Int = when (category) {
        Category.FOOD -> R.drawable.ic_cup
        Category.GROCERIES -> R.drawable.ic_basket
        Category.TRANSPORT -> R.drawable.ic_fuel
        Category.SHOPPING -> R.drawable.ic_bag
        Category.BILLS -> R.drawable.ic_bolt
        Category.RENT -> R.drawable.ic_house_door
        Category.HEALTH -> R.drawable.ic_heart
        Category.EDUCATION -> R.drawable.ic_book
        Category.ENTERTAINMENT -> R.drawable.ic_film
        Category.TRAVEL -> R.drawable.ic_plane
        Category.INVESTMENTS -> R.drawable.ic_chart_arrow
        Category.EMI -> R.drawable.ic_bank
        Category.TRANSFERS -> R.drawable.ic_arrow_left_right
        Category.SELF_TRANSFER -> R.drawable.ic_arrow_left_right
        Category.CARD_PAYMENT -> R.drawable.ic_credit_card
        Category.INCOME -> R.drawable.ic_cash
        Category.OTHER -> R.drawable.ic_dots
    }
}

/**
 * Thin wrapper so call sites never repeat `painterResource`.
 *
 * [Icon] applies [tint] as a colour filter over the rendered drawable, not to `fillColor`
 * alone, so a stroke-only glyph tints exactly as the old filled ones did.
 */
@Composable
fun OursIconView(
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
    OursIconView(
        icon = OursIcon.forCategory(category),
        contentDescription = category.label,
        modifier = modifier,
        tint = tint,
    )
}

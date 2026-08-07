package com.manuel.ours.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import com.manuel.ours.ui.theme.Space
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.manuel.ours.ui.components.EmptyState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import com.manuel.ours.ui.components.OursIcon
import com.manuel.ours.ui.components.OursIconView
import com.manuel.ours.ui.theme.Ours
import com.manuel.ours.ui.theme.PillTextStyle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.manuel.ours.ui.screens.backup.BackupScreen
import com.manuel.ours.ui.screens.budgets.BudgetsScreen
import com.manuel.ours.ui.screens.home.HomeScreen
import com.manuel.ours.ui.screens.onboarding.OnboardingScreen
import com.manuel.ours.ui.screens.settings.ParserTesterScreen
import com.manuel.ours.ui.screens.settings.QrScannerScreen
import com.manuel.ours.ui.screens.settings.SheetSetupScreen
import com.manuel.ours.ui.screens.settings.SettingsViewModel
import com.manuel.ours.ui.screens.settings.SettingsScreen
import com.manuel.ours.ui.screens.rules.RulesScreen
import com.manuel.ours.ui.screens.sort.SortScreen
import com.manuel.ours.ui.screens.summary.SummaryScreen
import com.manuel.ours.ui.screens.transactions.TransactionDetailScreen
import com.manuel.ours.ui.screens.transactions.TransactionsScreen
import com.manuel.ours.ui.screens.trash.TrashScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val SUMMARY = "summary"
    const val BUDGETS = "budgets"
    const val SETTINGS = "settings"
    const val PARSER_TESTER = "parser_tester"
    const val QR_SCANNER = "qr_scanner"
    const val SORT = "sort"
    const val RULES = "rules"
    const val SHEET_SETUP = "sheet_setup"
    const val DELETE_REQUESTS = "delete_requests"
    const val POSSIBLE_PAYMENTS = "possible_payments"
    const val BACKUP = "backup"
    const val TRASH = "trash"
    const val TXN_DETAIL = "txn/{txnId}"

    fun txnDetail(id: String) = "txn/$id"
}

private data class TabItem(
    val route: String,
    val label: String,
    @DrawableRes val selectedIcon: Int,
    @DrawableRes val icon: Int,
)

private val tabs = listOf(
    TabItem(Routes.HOME, "Home", OursIcon.HomeFill, OursIcon.Home),
    TabItem(Routes.TRANSACTIONS, "Activity", OursIcon.ActivityFill, OursIcon.Activity),
    TabItem(Routes.SUMMARY, "Summary", OursIcon.SummaryFill, OursIcon.Summary),
    TabItem(Routes.BUDGETS, "Budgets", OursIcon.BudgetsFill, OursIcon.Budgets),
    TabItem(Routes.SETTINGS, "Settings", OursIcon.SettingsFill, OursIcon.Settings),
)

@Composable
fun OursNavHost(
    initialTransactionId: String? = null,
    startDestination: String = Routes.ONBOARDING,
    navController: NavHostController = rememberNavController(),
    // Scoped here rather than to a screen: the bar outlives every destination, so reading the
    // counts from HomeViewModel would mean the badge only updated while Home was composed.
    badgeViewModel: NavBadgeViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    /**
     * Screens that keep the tabs, beyond the tabs themselves.
     *
     * A transaction opens by tapping a row in a list, so it reads as a place inside
     * Activity rather than somewhere you were taken — and people reach for Home. With
     * the bar gone the only way out is a back arrow in the far corner, and tapping
     * where Home should be does nothing at all, which is indistinguishable from the app
     * having frozen.
     *
     * Onboarding, the QR scanner and the focused flows keep it hidden: those are jobs
     * you are part-way through, and a tab tap mid-job loses the work.
     */
    val keepsTabs = setOf(Routes.TXN_DETAIL)

    val showBottomBar = currentRoute?.hierarchy?.any { destination ->
        tabs.any { it.route == destination.route } || destination.route in keepsTabs
    } == true

    // Which navigation the window can afford, derived rather than depended on.
    //
    // The app had no response to window size at all — one 360dp layout everywhere, including a
    // rotated handset and any foldable. `BoxWithConstraints` gives the breakpoint without adding
    // the window-size-class artifact, which matters because this module builds offline.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 600dp is Material's compact/medium boundary. Below it a bottom bar; at or above it a
        // rail, because a bar stretched across a wide window puts its targets a hand apart.
        val wide = maxWidth >= 600.dp
        // 840dp is Material's medium/expanded boundary. Above it there is room to show a row and
        // the row's detail at once — which is what the detail screen already is, so this costs a
        // branch rather than a second screen. This handset reaches it in landscape (960dp).
        val twoPane = maxWidth >= 840.dp
        // Which row the right pane is showing. Held here rather than in Activity because the pane
        // belongs to the layout, not to the list — and because dropping to one pane has to leave
        // the selection somewhere the list can forget.
        var pairedTxnId by remember { mutableStateOf<String?>(null) }
        val badges by badgeViewModel.badges.collectAsStateWithLifecycle()

        Row(Modifier.fillMaxSize()) {
            if (showBottomBar && wide) {
                NavigationRail(
                    containerColor = Ours.surfaceContainer,
                    header = {
                        // The rail has room for the primary action that floats on a phone, and a
                        // FAB in a rail is easier to reach than one in a far corner.
                        Box(Modifier.padding(vertical = Space.s3))
                    },
                ) {
                    Spacer(Modifier.weight(1f))
                    tabs.forEach { tab ->
                        val selected =
                            currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = { TabIcon(tab, selected, badges) },
                            label = {
                                Text(tab.label, style = MaterialTheme.typography.labelMedium)
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Ours.onSecondaryContainer,
                                selectedTextColor = Ours.onSecondaryContainer,
                                unselectedIconColor = Ours.onSurfaceMuted,
                                unselectedTextColor = Ours.onSurfaceMuted,
                                indicatorColor = Ours.secondaryContainer,
                            ),
                            modifier = Modifier.semantics {
                                contentDescription = tab.describe(badges)
                            },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(Ours.outlineVariant))
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (showBottomBar && !wide) {
                        // A hairline instead of elevation: the bar is the bottom rule of the page,
                        // not a separate surface floating over it.
                        Column {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.outlineVariant))
                            NavigationBar(
                                containerColor = Ours.surfaceContainer,
                                tonalElevation = 0.dp,
                            ) {
                                tabs.forEach { tab ->
                                    val selected = currentRoute?.hierarchy
                                        ?.any { it.route == tab.route } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navController.navigateToTab(tab.route) },
                                        icon = { TabIcon(tab, selected, badges) },
                                        label = {
                                            // 12sp sentence case, not 9sp uppercase tracked out to
                                            // 1.1. "ACTIVITY" at 9sp was the hardest text in the
                                            // app to read, on the one control you navigate by.
                                            Text(
                                                tab.label,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = Ours.onSecondaryContainer,
                                            selectedTextColor = Ours.onSecondaryContainer,
                                            unselectedIconColor = Ours.onSurfaceMuted,
                                            unselectedTextColor = Ours.onSurfaceMuted,
                                            // An indicator, but in secondaryContainer rather than
                                            // the accent. That satisfies both concerns at once: the
                                            // selected tab is unmistakable, and the accent stays
                                            // unique to actions — which was the reason for having
                                            // no indicator at all.
                                            indicatorColor = Ours.secondaryContainer,
                                        ),
                                        modifier = Modifier.semantics {
                                            contentDescription = tab.describe(badges)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onTransactionClick = { navController.navigate(Routes.txnDetail(it)) },
                    onSeeAll = { navController.navigate(Routes.TRANSACTIONS) },
                    onSort = { navController.navigate(Routes.SORT) },
                    onSetBudget = {
                        // Same options as a tab tap, so the back stack does not grow a
                        // second Budgets entry every time the prompt is used.
                        navController.navigate(Routes.BUDGETS) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenDeleteRequests = { navController.navigate(Routes.DELETE_REQUESTS) },
                    onOpenPossiblePayments = {
                        navController.navigate(Routes.POSSIBLE_PAYMENTS)
                    },
                )
            }

            composable(Routes.TRANSACTIONS) {
                if (twoPane) {
                    // Tapping a row fills the pane instead of navigating, so the list keeps its
                    // scroll position and the date rule you were reading stays on screen.
                    Row(Modifier.fillMaxSize()) {
                        TransactionsScreen(
                            onTransactionClick = { pairedTxnId = it },
                            onSort = { navController.navigate(Routes.SORT) },
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.fillMaxHeight().width(1.dp).background(Ours.outlineVariant)
                        )
                        Box(Modifier.weight(1f)) {
                            val paired = pairedTxnId
                            if (paired == null) {
                                // Not an error and not a loading state: nothing has been chosen.
                                EmptyState(
                                    title = "Pick an entry",
                                    body = "Its details, where it came from, and the message the " +
                                        "parser read will appear here.",
                                    icon = OursIcon.Activity,
                                )
                            } else {
                                TransactionDetailScreen(
                                    txnId = paired,
                                    onBack = { pairedTxnId = null },
                                )
                            }
                        }
                    }
                } else {
                    TransactionsScreen(
                        onTransactionClick = { navController.navigate(Routes.txnDetail(it)) },
                        onSort = { navController.navigate(Routes.SORT) },
                    )
                }
            }

            composable(Routes.SUMMARY) { SummaryScreen() }

            composable(Routes.BUDGETS) { BudgetsScreen() }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenParserTester = { navController.navigate(Routes.PARSER_TESTER) },
                    onOpenRules = { navController.navigate(Routes.RULES) },
                    onOpenSheetSetup = { navController.navigate(Routes.SHEET_SETUP) },
                    onOpenDeleteRequests = { navController.navigate(Routes.DELETE_REQUESTS) },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    onOpenTrash = { navController.navigate(Routes.TRASH) },
                    onScanInvite = { navController.navigate(Routes.QR_SCANNER) },
                )
            }

            composable(Routes.QR_SCANNER) { entry ->
                val settingsEntry = remember(entry) {
                    navController.getBackStackEntry(Routes.SETTINGS)
                }
                val viewModel: SettingsViewModel = hiltViewModel(settingsEntry)
                QrScannerScreen(
                    onBack = { navController.popBackStack() },
                    onScanned = { payload ->
                        viewModel.joinFromScannedInvite(payload)
                        navController.popBackStack()
                    },
                )
            }

            composable(Routes.SORT) {
                SortScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.DELETE_REQUESTS) {
                com.manuel.ours.ui.screens.requests.DeleteRequestsScreen(
                    onBack = { navController.popBackStack() },
                    onTransactionClick = { navController.navigate(Routes.txnDetail(it)) },
                )
            }

            composable(Routes.POSSIBLE_PAYMENTS) {
                com.manuel.ours.ui.screens.pending.PossiblePaymentsScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SHEET_SETUP) {
                SheetSetupScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.RULES) {
                RulesScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.BACKUP) {
                BackupScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.TRASH) {
                TrashScreen(
                    onBack = { navController.popBackStack() },
                    onTransactionClick = { navController.navigate(Routes.txnDetail(it)) },
                )
            }

            composable(Routes.PARSER_TESTER) {
                ParserTesterScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.TXN_DETAIL,
                enterTransition = {
                    slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280))
                },
                exitTransition = {
                    slideOutVertically(tween(220)) { it / 6 } + fadeOut(tween(220))
                },
            ) { entry ->
                TransactionDetailScreen(
                    txnId = entry.arguments?.getString("txnId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }

        }
    }

    // Deep link from the "new expense" notification.
    androidx.compose.runtime.LaunchedEffect(initialTransactionId) {
        if (!initialTransactionId.isNullOrBlank()) {
            navController.navigate(Routes.txnDetail(initialTransactionId))
        }
    }
}

/**
 * One tab icon, so the bar and the rail cannot drift apart.
 *
 * The badge rides on the icon rather than the label: it is legible at a glance there, and it
 * reads out as part of the tab rather than as a stray number.
 */
@Composable
private fun TabIcon(tab: TabItem, selected: Boolean, badges: NavBadgeViewModel.Badges) {
    val count = if (tab.route == Routes.TRANSACTIONS) badges.untagged else 0
    val dot = tab.route == Routes.SETTINGS && badges.needsAttention
    BadgedBox(
        badge = {
            when {
                count > 0 -> Badge(
                    containerColor = Ours.error,
                    contentColor = Ours.surface,
                ) {
                    Text(if (count > 99) "99+" else "$count", style = PillTextStyle)
                }
                dot -> Badge(containerColor = Ours.warning)
            }
        }
    ) {
        OursIconView(
            icon = if (selected) tab.selectedIcon else tab.icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** "Activity, 4 untagged" — spoken as one thing, because the count is part of the destination. */
private fun TabItem.describe(badges: NavBadgeViewModel.Badges): String = buildString {
    append(label)
    if (route == Routes.TRANSACTIONS && badges.untagged > 0) {
        append(", ${badges.untagged} untagged")
    }
    if (route == Routes.SETTINGS && badges.needsAttention) append(", needs attention")
}

/**
 * A tab tap, with the options that stop the back stack growing one entry per tap.
 *
 * Extracted because the bar and the rail must navigate identically — two copies of this is how
 * a rail ends up with a subtly different back stack from a bar.
 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

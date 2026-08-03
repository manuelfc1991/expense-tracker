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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.annotation.DrawableRes
import com.manuel.ours.ui.components.BiIcon
import com.manuel.ours.ui.components.BiIconView
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
import com.manuel.ours.ui.screens.budgets.BudgetsScreen
import com.manuel.ours.ui.screens.home.HomeScreen
import com.manuel.ours.ui.screens.onboarding.OnboardingScreen
import com.manuel.ours.ui.screens.settings.ParserTesterScreen
import com.manuel.ours.ui.screens.settings.QrScannerScreen
import com.manuel.ours.ui.screens.settings.SettingsViewModel
import com.manuel.ours.ui.screens.settings.SettingsScreen
import com.manuel.ours.ui.screens.rules.RulesScreen
import com.manuel.ours.ui.screens.sort.SortScreen
import com.manuel.ours.ui.screens.summary.SummaryScreen
import com.manuel.ours.ui.screens.transactions.TransactionDetailScreen
import com.manuel.ours.ui.screens.transactions.TransactionsScreen

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
    TabItem(Routes.HOME, "Home", BiIcon.HomeFill, BiIcon.Home),
    TabItem(Routes.TRANSACTIONS, "Activity", BiIcon.ActivityFill, BiIcon.Activity),
    TabItem(Routes.SUMMARY, "Summary", BiIcon.SummaryFill, BiIcon.Summary),
    TabItem(Routes.BUDGETS, "Budgets", BiIcon.BudgetsFill, BiIcon.Budgets),
    TabItem(Routes.SETTINGS, "Settings", BiIcon.SettingsFill, BiIcon.Settings),
)

@Composable
fun OursNavHost(
    initialTransactionId: String? = null,
    startDestination: String = Routes.ONBOARDING,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination

    val showBottomBar = tabs.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // A hairline instead of elevation or a tinted container: the bar is
                // the bottom rule of the page, not a separate surface floating over it.
                Column {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Ours.hairline))
                    NavigationBar(
                        containerColor = Ours.ink,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEach { tab ->
                            val selected =
                                currentRoute?.hierarchy?.any { it.route == tab.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    BiIconView(
                                        icon = if (selected) tab.selectedIcon else tab.icon,
                                        contentDescription = tab.label,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = {
                                    Text(tab.label.uppercase(), style = PillTextStyle)
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ours.accent,
                                    selectedTextColor = Ours.accent,
                                    unselectedIconColor = Ours.textLabel,
                                    unselectedTextColor = Ours.textLabel,
                                    // No pill behind the selected icon. Colour alone
                                    // marks the tab; a filled indicator would be a
                                    // second accent-filled shape competing with the
                                    // one real call to action on the page.
                                    indicatorColor = Color.Transparent,
                                ),
                            )
                        }
                    }
                }
            }
        }
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
                )
            }

            composable(Routes.TRANSACTIONS) {
                TransactionsScreen(
                    onTransactionClick = { navController.navigate(Routes.txnDetail(it)) },
                )
            }

            composable(Routes.SUMMARY) { SummaryScreen() }

            composable(Routes.BUDGETS) { BudgetsScreen() }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenParserTester = { navController.navigate(Routes.PARSER_TESTER) },
                    onOpenRules = { navController.navigate(Routes.RULES) },
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

            composable(Routes.RULES) {
                RulesScreen(onBack = { navController.popBackStack() })
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

    // Deep link from the "new expense" notification.
    androidx.compose.runtime.LaunchedEffect(initialTransactionId) {
        if (!initialTransactionId.isNullOrBlank()) {
            navController.navigate(Routes.txnDetail(initialTransactionId))
        }
    }
}

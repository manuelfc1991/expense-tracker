package com.manuel.ours.ui

import com.manuel.ours.BuildConfig
import org.junit.Assume
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Skips a Compose test unless the build has a host activity to run it in.
 *
 * Compose tests need `ui-test-manifest` to declare `ComponentActivity`, and that is a **debug**
 * dependency on purpose — a production APK has no business shipping a test activity. So these
 * tests run under `testDebugUnitTest` and must stand aside under `testReleaseUnitTest`, which is
 * the suite a release is gated on.
 *
 * ## Why this is a rule and not an `@Before`
 *
 * It was an `@Before` first, and that silently did nothing: a JUnit `@Rule` wraps the entire
 * statement — `@Before` methods included — so `createComposeRule()` had already launched its
 * activity and thrown before any assumption could be evaluated. The release suite reported 23
 * failures while the debug suite was green, and the two runs disagreeing was the only clue.
 *
 * Ordering is the whole point, so it has to be expressed as ordering:
 *
 *     private val composeRule = createComposeRule()
 *
 *     @get:Rule
 *     val rules: RuleChain = RuleChain.outerRule(ComposeOnDebugOnly()).around(composeRule)
 *
 * `outerRule` runs first and, on a release build, never calls through — so the compose rule is
 * never applied and never looks for an activity that is not there.
 */
internal class ComposeOnDebugOnly : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                Assume.assumeTrue(
                    "Compose UI tests need ui-test-manifest, which is debug-only — " +
                        "run ./gradlew :app:testDebugUnitTest",
                    BuildConfig.DEBUG,
                )
                base.evaluate()
            }
        }
}

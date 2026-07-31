package net.sarazan.articulate.gradle

import net.sarazan.articulate.gradle.FunctionalTestSupport.REQUIRE_ANDROID_SDK_ENV
import net.sarazan.articulate.gradle.FunctionalTestSupport.resolveRequiredAndroidSdk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import java.io.File

/**
 * Task 1 (PLAN.md §13 infra gap): unit coverage for
 * [FunctionalTestSupport.resolveRequiredAndroidSdk], the strictness-switch
 * decision logic behind [FunctionalTestSupport.requireOrSkipAndroidSdk].
 *
 * Deliberately plain JUnit, not TestKit -- [resolveRequiredAndroidSdk] takes
 * both the resolved SDK and the raw env var value as parameters rather than
 * reading `System.getenv`/the filesystem itself, precisely so this branching
 * is unit-testable without faking environment variables (not possible from
 * within a running JVM) or disturbing this machine's real, installed Android
 * SDK. That real end-to-end path (an actual missing SDK on a real machine) is
 * exercised by CI, which has no SDK at all unless the workflow installs one.
 */
class FunctionalTestSupportTest {

    @Test
    fun `an sdk that was found is returned as-is regardless of the env var`() {
        val sdk = File("/fake/sdk")

        assertEquals(sdk, resolveRequiredAndroidSdk(sdk, requireEnvValue = null))
        assertEquals(sdk, resolveRequiredAndroidSdk(sdk, requireEnvValue = "true"))
        assertEquals(sdk, resolveRequiredAndroidSdk(sdk, requireEnvValue = "false"))
    }

    @Test
    fun `missing sdk with the env var true hard-fails naming the variable and the checked locations`() {
        val error = assertThrows(IllegalStateException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = "true")
        }

        assertTrue(error.message!!.contains(REQUIRE_ANDROID_SDK_ENV), "expected the message to name $REQUIRE_ANDROID_SDK_ENV:\n${error.message}")
        assertTrue(error.message!!.contains("ANDROID_HOME"), "expected the message to name ANDROID_HOME:\n${error.message}")
        assertTrue(error.message!!.contains("ANDROID_SDK_ROOT"), "expected the message to name ANDROID_SDK_ROOT:\n${error.message}")
    }

    @Test
    fun `missing sdk with the env var true is case-insensitive`() {
        assertThrows(IllegalStateException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = "TRUE")
        }
        assertThrows(IllegalStateException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = "True")
        }
    }

    @Test
    fun `missing sdk with the env var unset skips cleanly instead of failing`() {
        val aborted = assertThrows(TestAbortedException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = null)
        }
        assertTrue(
            aborted.message!!.contains(REQUIRE_ANDROID_SDK_ENV),
            "expected the skip message to mention how to make it strict:\n${aborted.message}",
        )
    }

    @Test
    fun `missing sdk with the env var set to a non-true value still skips, not fails`() {
        assertThrows(TestAbortedException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = "false")
        }
        assertThrows(TestAbortedException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = "1")
        }
        assertThrows(TestAbortedException::class.java) {
            resolveRequiredAndroidSdk(null, requireEnvValue = "")
        }
    }
}

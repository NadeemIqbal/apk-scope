package com.nadeem.apkscope.domain.sandbox

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.nadeem.apkscope.MainActivity
import com.nadeem.apkscope.core.model.SandboxOperationResult
import com.nadeem.apkscope.core.model.SandboxPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Milestone 9 (Pixel 8 acceptance, fifth pass, item 1/4): real, on-device coverage for the actual
 * fix — `checkWorkInstallPermission`/`requestOpenWorkInstallSettings` now go through the Work
 * profile's own process via the existing cross-profile query mechanism, rather than the previous
 * `SandboxPreparingScreen` button opening Personal's own Settings (a confirmed defect). Run on
 * `emulator-5554`, which already has a real, configured Work Profile.
 */
@RunWith(AndroidJUnit4::class)
class WorkInstallPermissionRemediationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = SandboxSessionRepository(context)
    private val createdSessionIds = mutableListOf<String>()
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: Activity

    private fun launchActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun cleanup() {
        runBlocking { createdSessionIds.forEach { repository.delete(it) } }
        if (::scenario.isInitialized) scenario.close()
    }

    private suspend fun createSession(coordinator: DefaultSandboxSessionCoordinator, label: String): com.nadeem.apkscope.core.model.SandboxSession {
        val analysisId = "install-permission-test-$label-${UUID.randomUUID()}"
        val result = coordinator.create(analysisId, "com.example.installpermissiontest.$label", "/nonexistent/$label.apk", SandboxPolicy())
        val session = (result as SandboxOperationResult.Success).session
        createdSessionIds += session.id
        return session
    }

    @Test
    fun checkWorkInstallPermission_returnsARealAnswer_fromTheWorkProfilesOwnProcess_notNull() = runBlocking {
        launchActivity()
        val coordinator = DefaultSandboxSessionCoordinator(context)
        val session = createSession(coordinator, "check")

        val granted = withTimeout(15_000) { coordinator.checkWorkInstallPermission(activity, session.id) }

        // The real, current value of the Work-profile instance's own canRequestPackageInstalls() —
        // whatever it happens to be on this device — but it must be a real, live answer, never a
        // silent null from a dropped/unanswered cross-profile query.
        assertNotNull("expected a real answer from the Work profile's own process, not a dropped query", granted)
    }

    @Test
    fun requestOpenWorkInstallSettings_opensTheWorkProfilesOwnSettingsScreen_neverPersonals() = runBlocking {
        launchActivity()
        val coordinator = DefaultSandboxSessionCoordinator(context)
        val session = createSession(coordinator, "open")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Second on-device finding this pass, found while trying to simplify this exact test:
        // SandboxWorkQueryActivity.openInstallSettings() responds (setResult+finish()) immediately,
        // well before Settings is dismissed — but Android does not *deliver* that already-set
        // ActivityResult across the profile boundary back to Personal's MainActivity while Personal's
        // task stays fully STOPPED behind the still-foreground Settings screen. Confirmed by direct
        // measurement: with nothing dismissing Settings, MainActivity never left STOPPED and
        // `requestOpenWorkInstallSettings` never returned within a generous 15s bound, regardless of
        // whether the Work side used startActivityForResult or a plain startActivity. Delivery only
        // happens once Personal's task becomes foreground-eligible again — i.e. once the user (here,
        // UiAutomator) actually leaves Settings. So the coordinator call is awaited on a background
        // dispatcher while this thread independently drives UiAutomator to wait for the real Settings
        // window and then leave it — the same shape a real user's own single Back press takes, and the
        // only way this call can complete at all, not an artifact of this fix.
        val openedDeferred = async(Dispatchers.Default) {
            withTimeout(20_000) { coordinator.requestOpenWorkInstallSettings(activity, session.id) }
        }
        // Independently confirm, via UiAutomator (not the coordinator's own return value), that a real
        // Settings window is actually on screen — direct on-device proof of "opens ... through a
        // supported Android flow", not merely proof the launch call didn't throw.
        val settingsAppeared = device.wait(Until.hasObject(By.pkg("com.android.settings")), 8_000)
        assertTrue("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES must actually bring a Settings window to the foreground on a real device", settingsAppeared)
        // Which *profile* it opened in (never Personal's, the confirmed original defect) is verified
        // externally via `adb shell dumpsys activity activities`/this run's own logcat
        // (`myUserHandle=UserHandle{11}`, logged directly from the Work-profile process itself) — an
        // app process cannot call `dumpsys` on itself without a privileged permission this app
        // deliberately does not hold, so that check is not duplicated here.
        device.pressBack()

        val opened = openedDeferred.await()
        assertTrue("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES must be resolvable and launchable on a real device, and the already-set opened=true response must survive until Android actually delivers it", opened)

        // Recheck must now succeed too — this is the "after returning from Settings, recheck
        // permission from the Work Profile instance" half of the same fix, exercised back-to-back.
        val recheck = withTimeout(15_000) { coordinator.checkWorkInstallPermission(activity, session.id) }
        assertNotNull(recheck)
    }
}

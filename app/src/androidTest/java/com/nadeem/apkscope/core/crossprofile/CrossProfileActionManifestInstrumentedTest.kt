package com.nadeem.apkscope.core.crossprofile

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nadeem.apkscope.sandbox.SandboxWorkerActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the resolver contract used by [Handoff.send]. A DPM cross-profile filter alone is not
 * enough: the receiving Work-profile activity must also advertise the action in the manifest.
 */
@RunWith(AndroidJUnit4::class)
class CrossProfileActionManifestInstrumentedTest {
    @Test
    fun reinstallAction_isDeclaredBySandboxWorkerActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(CrossProfileContract.ACTION_REINSTALL)
            .setDataAndType(
                Uri.parse("content://com.nadeem.apkscope.files/sandbox/control/reinstall.json"),
                Handoff.MIME,
            )
            .addCategory(Intent.CATEGORY_DEFAULT)

        val matches = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )

        assertTrue(
            "ACTION_REINSTALL must resolve to SandboxWorkerActivity before cross-profile forwarding",
            matches.any {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name == SandboxWorkerActivity::class.java.name
            },
        )
    }
}

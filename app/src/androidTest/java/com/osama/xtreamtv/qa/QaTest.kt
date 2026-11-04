package com.osama.xtreamtv.qa

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.services.storage.TestStorage
import org.junit.Rule
import org.junit.rules.TestWatcher
import org.junit.rules.Timeout
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Records each test (steps, screenshots, findings) and keeps a picture of any failure. */
class QaRecorder : TestWatcher() {
    override fun starting(d: Description) {
        Qa.begin("${d.testClass.simpleName}.${d.methodName}")
    }

    override fun failed(e: Throwable, d: Description) {
        try {
            Qa.shot("FAILED", "Screen when the test failed")
            TestStorage().openOutputFile("qa/hierarchy/${d.testClass.simpleName}.${d.methodName}.xml").use { Qa.device.dumpWindowHierarchy(it) }
        } catch (ignored: Throwable) {
        }
        Qa.end("failed", e)
    }

    override fun succeeded(d: Description) = Qa.end("passed", null)

    override fun finished(d: Description) {
        try {
            Qa.internetUp()
            Qa.finishAll()
        } catch (ignored: Throwable) {
        }
    }
}

@RunWith(AndroidJUnit4::class)
abstract class QaTest {
    @get:Rule(order = 0)
    val timeout: Timeout = Timeout(12, TimeUnit.MINUTES)

    @get:Rule(order = 1)
    val recorder = QaRecorder()
}

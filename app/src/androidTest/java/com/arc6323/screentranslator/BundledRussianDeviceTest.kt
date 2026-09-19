package com.arc6323.screentranslator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BundledRussianDeviceTest {
    @Test fun tokenizationMatchesThePinnedReference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokenizer = UnigramTokenizer(context.assets.open("russian/tokenizer.json"))
        assertArrayEquals(longArrayOf(160, 5270, 2, 508, 55, 33, 19, 0), tokenizer.encode("Hello, how are you?"))
        assertArrayEquals(longArrayOf(5788, 20298, 0), tokenizer.encode("Open settings"))
        assertArrayEquals(longArrayOf(2013, 9771, 3, 0), tokenizer.encode("  Good\n evening.  "))
    }
    @Test fun russianTranslatesOnAFreshInstallWithNetworkingDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
        }
        shell("svc wifi disable"); shell("svc data disable")
        val translator = BundledRussianTranslator(context)
        try {
            val done = CountDownLatch(1)
            var output: List<String>? = null
            val start = android.os.SystemClock.elapsedRealtime()
            translator.translate(listOf("Hello, how are you?", "Open settings",
                "The translation works without the internet.", "Good evening."), { true }) {
                output = it; done.countDown()
            }
            assertTrue("Offline inference timed out", done.await(60, TimeUnit.SECONDS))
            assertEquals(listOf("Привет, как дела?", "Открыть настройки", "Перевод работает без интернета.", "Добрый вечер."), output)
            android.util.Log.i("OfflineRussianTest", "Cold 4-line batch ms=" + (android.os.SystemClock.elapsedRealtime() - start))
            assertTrue(LanguageCacheManager.downloaded.contains("ru"))
        } finally { translator.close(); shell("svc wifi enable"); shell("svc data enable") }
    }
}

package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.R
import io.legado.app.databinding.DialogReadBookStyleBinding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The reading panel must inflate under the application's actual AppCompat themes. */
@RunWith(AndroidJUnit4::class)
class ReadStyleDialogInflationTest {
    @Test
    fun panelInflatesAndLayoutModesRemainExclusiveInBothThemes() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf(R.style.AppTheme_Light, R.style.AppTheme_Dark).forEach { theme ->
                val context = ContextThemeWrapper(app, theme)
                val binding = DialogReadBookStyleBinding.inflate(LayoutInflater.from(context), FrameLayout(context), false)
                binding.epubLayoutModes.check(R.id.epub_reader_mode)
                assertTrue(binding.epubReaderMode.isChecked)
                assertFalse(binding.epubPublisherMode.isChecked)
                binding.epubPublisherMode.performClick()
                assertTrue(binding.epubPublisherMode.isChecked)
                assertFalse(binding.epubReaderMode.isChecked)
                binding.epubReaderMode.performClick()
                assertTrue(binding.epubReaderMode.isChecked)
                assertFalse(binding.epubPublisherMode.isChecked)
            }
        }
    }
}

package io.legado.app.ui.book.read

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Compatibility entry for saved intents from the first publisher-layout build. */
class EpubPublisherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ReadBookActivity::class.java).putExtras(intent))
        finish()
    }
}

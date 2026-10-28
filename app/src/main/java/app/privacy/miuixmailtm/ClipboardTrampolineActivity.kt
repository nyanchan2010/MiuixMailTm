package app.privacy.miuixmailtm

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.privacy.miuixmailtm.util.ClipboardHelper

class ClipboardTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Mail.tm"
        val value = intent?.getStringExtra(EXTRA_VALUE)
        if (!value.isNullOrEmpty()) {
            ClipboardHelper.copyText(this, label, value)
            Toast.makeText(applicationContext, "Copied: $value", Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_VALUE = "extra_value"
    }
}

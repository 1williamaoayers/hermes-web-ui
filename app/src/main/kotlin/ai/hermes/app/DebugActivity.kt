package ai.hermes.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class DebugActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Debug Log"

        tvLog = findViewById(R.id.tv_log)
        scroll = findViewById(R.id.scroll)

        refresh()
        findViewById<android.widget.Button>(R.id.btn_refresh).setOnClickListener { refresh() }
        findViewById<android.widget.Button>(R.id.btn_copy).setOnClickListener { copyLog() }
        findViewById<android.widget.Button>(R.id.btn_clear).setOnClickListener {
            DebugLog.clear()
            refresh()
        }
        findViewById<android.widget.Button>(R.id.btn_share).setOnClickListener { shareLog() }
    }

    private fun refresh() {
        val snap = DebugLog.snapshot()
        tvLog.text = if (snap.isBlank()) "(no logs)" else snap
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyLog() {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("hermes-log", DebugLog.snapshot()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hermes debug log")
            putExtra(Intent.EXTRA_TEXT, DebugLog.snapshot())
        }
        startActivity(Intent.createChooser(send, "Share log"))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

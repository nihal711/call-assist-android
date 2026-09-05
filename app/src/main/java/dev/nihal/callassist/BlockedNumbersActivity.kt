package dev.nihal.callassist

import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.provider.BlockedNumberContract
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BlockedNumbersActivity : AppCompatActivity() {

    private lateinit var adapter: RowAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        setContentView(R.layout.activity_blocked)
        Ui.applyInsets(findViewById(R.id.blockedRoot), ime = true)
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        empty = findViewById(R.id.blockedEmpty)
        Ui.emptyState(empty, R.drawable.ic_block)
        adapter = RowAdapter { row ->
            val id = row.payload as Long
            Sheet(this)
                .title(row.title)
                .message("Unblock this number?")
                .negative()
                .positive("Unblock") {
                    try {
                        contentResolver.delete(
                            ContentUris.withAppendedId(
                                BlockedNumberContract.BlockedNumbers.CONTENT_URI, id
                            ),
                            null, null
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this, "Could not unblock: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    load()
                }
                .show()
        }
        findViewById<RecyclerView>(R.id.blockedList).apply {
            layoutManager = LinearLayoutManager(this@BlockedNumbersActivity)
            adapter = this@BlockedNumbersActivity.adapter
        }

        val edit = findViewById<EditText>(R.id.editNumber)
        findViewById<Button>(R.id.btnBlock).setOnClickListener {
            val number = edit.text.toString().trim()
            if (number.isEmpty()) return@setOnClickListener
            if (block(this, number)) {
                edit.text.clear()
                load()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(this)) {
            Toast.makeText(this, "Blocking isn't available for this user profile", Toast.LENGTH_LONG).show()
        }
        load()
    }

    private fun load() {
        val rows = ArrayList<RowAdapter.Row>()
        try {
            contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(
                    BlockedNumberContract.BlockedNumbers.COLUMN_ID,
                    BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1) ?: continue
                    rows.add(
                        RowAdapter.Row(
                            title = number,
                            subtitle = "Tap to unblock",
                            meta = "",
                            avatarSeed = number,
                            payload = c.getLong(0),
                            iconRes = R.drawable.ic_block
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        adapter.rows = rows
        empty.visibility = if (rows.isEmpty()) TextView.VISIBLE else TextView.GONE
    }

    companion object {
        /** Adds a number to the system blocked list. Returns true on success. */
        fun block(activity: AppCompatActivity, number: String): Boolean {
            return try {
                activity.contentResolver.insert(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    ContentValues().apply {
                        put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
                    }
                )
                Toast.makeText(activity, "Blocked $number", Toast.LENGTH_SHORT).show()
                true
            } catch (e: Exception) {
                Toast.makeText(activity, "Could not block: ${e.message}", Toast.LENGTH_LONG).show()
                false
            }
        }
    }
}

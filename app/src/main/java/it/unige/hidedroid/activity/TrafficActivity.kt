package it.unige.hidedroid.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dave.realmdatahelper.hidedroid.DomainLogEntry
import io.realm.Realm
import io.realm.Sort
import it.unige.hidedroid.R
import it.unige.hidedroid.log.DataExport
import it.unige.hidedroid.log.DomainLogger
import it.unige.hidedroid.utils.DomainAnonymizationHelper
import es.dmoral.toasty.Toasty
import it.unige.hidedroid.utils.HideDroidStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class TrafficFilter {
    ALL, BLOCKED, TRACKED, PRIVATE, NORMAL;

    /** Realm "status" value to export, or null to export the whole table. */
    val exportStatus: String?
        get() = if (this == ALL) null else name
}

/** Status chip color for the raw Realm status string, shared by the row and the block dialog. */
private fun statusColorRes(status: String?): Int {
    return when (status) {
        "BLOCKED" -> R.color.traffic_blocked
        "TRACKED" -> R.color.traffic_tracked
        "PRIVATE" -> R.color.traffic_private
        else -> R.color.traffic_normal
    }
}

/**
 * Near-real-time view of recent domain requests captured by the VPN interceptor.
 * Polls Realm every second and shows the newest entries (default 100, remembered
 * across restarts) with color-coded status. Tapping an entry offers to block (or
 * unblock) its host, long-pressing offers to anonymize it; the menu allows changing
 * the item count, exporting the current filter as CSV and deleting the history.
 */
class TrafficActivity : AppCompatActivity() {

    companion object {
        val ITEM_COUNTS = intArrayOf(50, 100, 200, 500, 1000)
        const val DEFAULT_COUNT_INDEX = 1 // 100
        private const val PREFS_NAME = "traffic_view"
        private const val PREF_COUNT_INDEX = "item_count_index"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: TrafficAdapter

    private var filter = TrafficFilter.ALL
    private var countIndex = DEFAULT_COUNT_INDEX

    // Signature of the list currently on screen; the 1s poll skips the adapter
    // update while it is unchanged instead of rebinding every row for nothing.
    private var shownKey: Pair<Long, Int>? = null
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_traffic)
        title = getString(R.string.traffic_title)

        countIndex = prefs.getInt(PREF_COUNT_INDEX, DEFAULT_COUNT_INDEX).coerceIn(0, ITEM_COUNTS.size - 1)

        recyclerView = findViewById(R.id.recyclerViewTraffic)
        emptyView = findViewById(R.id.emptyTraffic)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = TrafficAdapter(
                onItemClick = { item -> promptBlock(item) },
                onItemLongClick = { item -> promptTrack(item) })
        recyclerView.adapter = adapter

        setupFilterButtons()
        startPolling()
    }

    private fun setupFilterButtons() {
        val all = findViewById<View>(R.id.filterTrafficAll)
        val blocked = findViewById<View>(R.id.filterTrafficBlocked)
        val tracked = findViewById<View>(R.id.filterTrafficTracked)
        val private_ = findViewById<View>(R.id.filterTrafficPrivate)
        val normal = findViewById<View>(R.id.filterTrafficNormal)
        val buttons = listOf(all, blocked, tracked, private_, normal)

        fun select(selected: View, mode: TrafficFilter) {
            buttons.forEach { it.alpha = if (it === selected) 1f else 0.5f }
            filter = mode
            shownKey = null // force a rebind, the same top entry can hide different rows
            refresh()
        }

        all.setOnClickListener { select(all, TrafficFilter.ALL) }
        blocked.setOnClickListener { select(blocked, TrafficFilter.BLOCKED) }
        tracked.setOnClickListener { select(tracked, TrafficFilter.TRACKED) }
        private_.setOnClickListener { select(private_, TrafficFilter.PRIVATE) }
        normal.setOnClickListener { select(normal, TrafficFilter.NORMAL) }
        select(all, TrafficFilter.ALL)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_traffic, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_traffic_count -> {
                promptItemCount()
                true
            }
            R.id.action_traffic_export -> {
                exportHistory()
                true
            }
            R.id.action_traffic_delete -> {
                confirmDeleteHistory()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun promptItemCount() {
        val labels = ITEM_COUNTS.map { it.toString() }.toTypedArray()
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.traffic_count_title))
                .setSingleChoiceItems(labels, countIndex) { dialog, which ->
                    countIndex = which
                    prefs.edit().putInt(PREF_COUNT_INDEX, which).apply()
                    dialog.dismiss()
                    shownKey = null
                    refresh()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
    }

    private fun exportHistory() {
        val status = filter.exportStatus
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = DataExport(this@TrafficActivity).exportDomainLogCsv(status)
                val path = File(HideDroidStorage.getPrivateDir(this@TrafficActivity), fileName).absolutePath
                withContext(Dispatchers.Main) {
                    Toasty.success(this@TrafficActivity, getString(R.string.traffic_history_exported, path)).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@TrafficActivity, getString(R.string.traffic_export_failed, e.message)).show()
                }
            }
        }
    }

    private fun confirmDeleteHistory() {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.traffic_delete_menu))
                .setMessage(getString(R.string.traffic_delete_confirm))
                .setPositiveButton(getString(R.string.action_yes)) { _, _ ->
                    DomainLogger.clearHistory()
                    Toasty.info(this, getString(R.string.traffic_history_deleted)).show()
                    refresh()
                }
                .setNegativeButton(getString(R.string.action_no), null)
                .show()
    }

    private fun promptBlock(entry: DomainLogEntry) {
        val host = entry.host ?: return
        val packageName = entry.packageName ?: ""
        val alreadyBlocked = DomainAnonymizationHelper.getByHost(host)?.blocked == true

        // Custom body: app icon, host, package, the row's status chip and a plain
        // description of what the action does - clearer than a bare title/message pair.
        val view = layoutInflater.inflate(R.layout.dialog_block_host, null)
        view.findViewById<TextView>(R.id.textDialogBlockHost).text = host
        view.findViewById<TextView>(R.id.textDialogBlockPackage).text = packageName
        val statusView = view.findViewById<TextView>(R.id.textDialogBlockStatus)
        statusView.text = entry.status
        statusView.setTextColor(getColor(statusColorRes(entry.status)))
        view.findViewById<TextView>(R.id.textDialogBlockExplain).text =
                getString(if (alreadyBlocked) R.string.traffic_unblock_explain else R.string.traffic_block_explain)
        try {
            view.findViewById<ImageView>(R.id.imageDialogBlockApp)
                    .setImageDrawable(packageManager.getApplicationIcon(packageName))
        } catch (e: Exception) {
            // Package uninstalled since the request was captured.
            view.findViewById<ImageView>(R.id.imageDialogBlockApp).visibility = View.GONE
        }

        val builder = AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setView(view)
        if (alreadyBlocked) {
            builder.setPositiveButton(getString(R.string.traffic_unblock_confirm)) { _, _ ->
                DomainAnonymizationHelper.setBlocked(host, false)
                Toasty.success(this, getString(R.string.traffic_unblock_done, host)).show()
            }
                    .setNegativeButton(getString(R.string.action_cancel), null)
        } else {
            builder.setPositiveButton(getString(R.string.traffic_block_global)) { _, _ ->
                if (DomainAnonymizationHelper.blockHost(host, null)) {
                    Toasty.success(this, getString(R.string.traffic_block_done, host)).show()
                }
            }
                    .setNegativeButton(getString(R.string.traffic_block_app_only, packageName)) { _, _ ->
                        if (DomainAnonymizationHelper.blockHost(host, packageName)) {
                            Toasty.success(this, getString(R.string.traffic_block_done, host)).show()
                        }
                    }
                    .setNeutralButton(getString(R.string.action_cancel), null)
        }
        builder.show()
    }

    private fun promptTrack(entry: DomainLogEntry) {
        val host = entry.host ?: return
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.traffic_track_title))
                .setMessage(getString(R.string.traffic_track_message, host))
                .setPositiveButton(getString(R.string.traffic_track_confirm)) { _, _ ->
                    DomainAnonymizationHelper.addManual(host) // no-op if the host is already known
                    DomainAnonymizationHelper.setEnabled(host, true)
                    Toasty.success(this, getString(R.string.traffic_track_done, host)).show()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
    }

    private fun startPolling() {
        lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(1000)
            }
        }
    }

    private fun refresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = loadEntries()
            val key = (entries.firstOrNull()?.id ?: -1L) to entries.size
            withContext(Dispatchers.Main) {
                if (key == shownKey) {
                    return@withContext
                }
                shownKey = key
                adapter.submit(entries)
                emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                recyclerView.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun loadEntries(): List<DomainLogEntry> {
        return Realm.getDefaultInstance().use { realm ->
            val query = when (filter) {
                TrafficFilter.BLOCKED -> realm.where(DomainLogEntry::class.java).equalTo("status", "BLOCKED")
                TrafficFilter.TRACKED -> realm.where(DomainLogEntry::class.java).equalTo("status", "TRACKED")
                TrafficFilter.PRIVATE -> realm.where(DomainLogEntry::class.java).equalTo("status", "PRIVATE")
                TrafficFilter.NORMAL -> realm.where(DomainLogEntry::class.java).equalTo("status", "NORMAL")
                TrafficFilter.ALL -> realm.where(DomainLogEntry::class.java)
            }
            realm.copyFromRealm(
                    query.sort("timestamp", Sort.DESCENDING)
                            .limit(ITEM_COUNTS[countIndex].toLong())
                            .findAll()
            )
        }
    }
}

private class TrafficAdapter(
        private val onItemClick: (DomainLogEntry) -> Unit,
        private val onItemLongClick: (DomainLogEntry) -> Unit) :
        RecyclerView.Adapter<TrafficAdapter.ViewHolder>() {

    private var items: List<DomainLogEntry> = emptyList()

    fun submit(newItems: List<DomainLogEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_traffic, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.timestamp.text = formatTime(item.timestamp)
        holder.packageName.text = item.packageName
        holder.host.text = item.host
        holder.method.text = item.method
        holder.status.text = when (item.status) {
            "BLOCKED" -> context.getString(R.string.traffic_status_blocked)
            "TRACKED" -> context.getString(R.string.traffic_status_tracked)
            "PRIVATE" -> context.getString(R.string.traffic_status_private)
            else -> context.getString(R.string.traffic_status_normal)
        }
        holder.status.setTextColor(context.getColor(statusColorRes(item.status)))
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
        return sdf.format(java.util.Date(timestamp))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val timestamp: TextView = itemView.findViewById(R.id.textTrafficTime)
        val packageName: TextView = itemView.findViewById(R.id.textTrafficPackage)
        val host: TextView = itemView.findViewById(R.id.textTrafficHost)
        val method: TextView = itemView.findViewById(R.id.textTrafficMethod)
        val status: TextView = itemView.findViewById(R.id.textTrafficStatus)
    }
}
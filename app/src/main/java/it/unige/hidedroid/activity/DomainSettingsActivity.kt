package it.unige.hidedroid.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dave.realmdatahelper.hidedroid.AnonymizedDomain
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import es.dmoral.toasty.Toasty
import it.unige.hidedroid.R
import it.unige.hidedroid.utils.DomainAnonymizationHelper

private enum class EnabledFilter { ALL, ENABLED, DISABLED }

private enum class AddedByFilter(val value: String?) {
    ALL(null),
    DEFAULT(DomainAnonymizationHelper.ADDED_BY_DEFAULT),
    MANUAL(DomainAnonymizationHelper.ADDED_BY_MANUAL),
    AUTOMATIC(DomainAnonymizationHelper.ADDED_BY_AUTOMATIC)
}

/**
 * Per-domain anonymization toggle list: default entries seeded from the bundled
 * tracker list, plus ones the user added manually or that got auto-discovered
 * while a VPN capture ran (see DomainAnonymizationHelper). Only manually added
 * domains can be removed here.
 */
class DomainSettingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: DomainAdapter

    private var allDomains: List<AnonymizedDomain> = emptyList()
    private var query: String = ""
    private var enabledFilter = EnabledFilter.ALL
    private var addedByFilter = AddedByFilter.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_domain_settings)
        title = getString(R.string.domain_settings_title)

        recyclerView = findViewById(R.id.recyclerViewDomains)
        emptyView = findViewById(R.id.emptyDomains)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = DomainAdapter(
                onToggle = { domain, enabled ->
                    DomainAnonymizationHelper.setEnabled(domain.host!!, enabled)
                    reload()
                },
                onDelete = { domain -> confirmDelete(domain) }
        )
        recyclerView.adapter = adapter

        setupEnabledFilterButtons()
        setupAddedByFilterButtons()

        findViewById<FloatingActionButton>(R.id.buttonAddDomain).setOnClickListener { promptAddDomain() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun setupEnabledFilterButtons() {
        val all = findViewById<View>(R.id.filterEnabledAll)
        val enabled = findViewById<View>(R.id.filterEnabledOn)
        val disabled = findViewById<View>(R.id.filterEnabledOff)
        val buttons = listOf(all, enabled, disabled)
        fun select(selected: View, mode: EnabledFilter) {
            buttons.forEach { it.isEnabled = it !== selected }
            enabledFilter = mode
            applyFilter()
        }
        all.setOnClickListener { select(all, EnabledFilter.ALL) }
        enabled.setOnClickListener { select(enabled, EnabledFilter.ENABLED) }
        disabled.setOnClickListener { select(disabled, EnabledFilter.DISABLED) }
        all.isEnabled = false
    }

    private fun setupAddedByFilterButtons() {
        val all = findViewById<View>(R.id.filterAddedByAll)
        val default = findViewById<View>(R.id.filterAddedByDefault)
        val manual = findViewById<View>(R.id.filterAddedByManual)
        val automatic = findViewById<View>(R.id.filterAddedByAutomatic)
        val buttons = listOf(all, default, manual, automatic)
        fun select(selected: View, mode: AddedByFilter) {
            buttons.forEach { it.isEnabled = it !== selected }
            addedByFilter = mode
            applyFilter()
        }
        all.setOnClickListener { select(all, AddedByFilter.ALL) }
        default.setOnClickListener { select(default, AddedByFilter.DEFAULT) }
        manual.setOnClickListener { select(manual, AddedByFilter.MANUAL) }
        automatic.setOnClickListener { select(automatic, AddedByFilter.AUTOMATIC) }
        all.isEnabled = false
    }

    private fun reload() {
        allDomains = DomainAnonymizationHelper.getAll().sortedBy { it.host }
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = allDomains.filter { domain ->
            val matchesQuery = query.isBlank() || domain.host?.contains(query, ignoreCase = true) == true
            val matchesEnabled = when (enabledFilter) {
                EnabledFilter.ALL -> true
                EnabledFilter.ENABLED -> domain.enabled
                EnabledFilter.DISABLED -> !domain.enabled
            }
            val matchesAddedBy = addedByFilter.value == null || domain.addedBy == addedByFilter.value
            matchesQuery && matchesEnabled && matchesAddedBy
        }
        adapter.submit(filtered)
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun promptAddDomain() {
        val input = EditText(this)
        input.hint = getString(R.string.domain_add_hint)
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.action_add_domain))
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val host = input.text.toString().trim().lowercase()
                    if (host.isNotEmpty()) {
                        if (DomainAnonymizationHelper.addManual(host)) {
                            Toasty.info(this, getString(R.string.domain_added_result, host)).show()
                            reload()
                        } else {
                            Toasty.warning(this, getString(R.string.domain_already_exists)).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
    }

    private fun confirmDelete(domain: AnonymizedDomain) {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.action_delete_domain_short))
                .setMessage(getString(R.string.delete_domain_confirm, domain.host))
                .setPositiveButton("Yes") { _, _ ->
                    if (DomainAnonymizationHelper.deleteIfManual(domain.host!!)) {
                        Toasty.info(this, getString(R.string.delete_domain_done)).show()
                        reload()
                    }
                }
                .setNegativeButton("No", null)
                .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_domain_search, menu)
        val searchView = menu?.findItem(R.id.action_search_domain)?.actionView as? SearchView
        searchView?.maxWidth = Int.MAX_VALUE
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean {
                query = q ?: ""
                applyFilter()
                return false
            }

            override fun onQueryTextChange(q: String?): Boolean {
                query = q ?: ""
                applyFilter()
                return false
            }
        })
        return true
    }
}

private class DomainAdapter(
        private val onToggle: (AnonymizedDomain, Boolean) -> Unit,
        private val onDelete: (AnonymizedDomain) -> Unit
) : RecyclerView.Adapter<DomainAdapter.ViewHolder>() {

    private var items: List<AnonymizedDomain> = emptyList()

    fun submit(newItems: List<AnonymizedDomain>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row_domain, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        holder.host.text = item.host
        holder.addedBy.text = context.getString(addedByLabelRes(item.addedBy))
        holder.toggle.isChecked = item.enabled
        holder.toggle.text = context.getString(
                if (item.enabled) R.string.domain_status_enabled else R.string.domain_status_disabled)
        holder.toggle.setOnClickListener { onToggle(item, !item.enabled) }

        val deletable = item.addedBy == DomainAnonymizationHelper.ADDED_BY_MANUAL
        holder.deleteButton.visibility = if (deletable) View.VISIBLE else View.GONE
        holder.deleteButton.setOnClickListener { onDelete(item) }
    }

    private fun addedByLabelRes(addedBy: String?): Int = when (addedBy) {
        DomainAnonymizationHelper.ADDED_BY_MANUAL -> R.string.domain_added_by_manual
        DomainAnonymizationHelper.ADDED_BY_AUTOMATIC -> R.string.domain_added_by_automatic
        else -> R.string.domain_added_by_default
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val host: TextView = itemView.findViewById(R.id.textDomainHost)
        val addedBy: TextView = itemView.findViewById(R.id.textDomainAddedBy)
        val toggle: MaterialButton = itemView.findViewById(R.id.buttonDomainToggle)
        val deleteButton: ImageButton = itemView.findViewById(R.id.buttonDeleteDomain)
    }
}

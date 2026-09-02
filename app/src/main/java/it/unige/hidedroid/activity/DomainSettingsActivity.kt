package it.unige.hidedroid.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dave.realmdatahelper.hidedroid.AnonymizedDomain
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.dmoral.toasty.Toasty
import io.realm.RealmList
import it.unige.hidedroid.R
import it.unige.hidedroid.utils.DomainAnonymizationHelper
import it.unige.hidedroid.utils.HideDroidStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/** One combined filter instead of the old three button rows: All, enabled/disabled,
 *  blocked/allowed, or where the domain came from (default/manual/automatic). */
private enum class DomainFilter(val labelRes: Int) {
    ALL(R.string.filter_all),
    ENABLED(R.string.filter_enabled),
    DISABLED(R.string.filter_disabled),
    BLOCKED(R.string.filter_blocked),
    ALLOWED(R.string.filter_allowed),
    DEFAULT(R.string.domain_added_by_default),
    MANUAL(R.string.domain_added_by_manual),
    AUTOMATIC(R.string.domain_added_by_automatic)
}

/**
 * Per-domain anonymization and blocking toggle list: default entries seeded from
 * the bundled tracker list, plus ones the user added manually or that got
 * auto-discovered while a VPN capture ran. Only manually added domains can be
 * removed here.
 */
class DomainSettingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var adapter: DomainAdapter

    private var allDomains: List<AnonymizedDomain> = emptyList()
    private var query: String = ""
    private var filter = DomainFilter.ALL

    private val domainExportFile: File
        get() = File(HideDroidStorage.getLegacyBaseDir(), "domain_list.json")

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
                onBlockToggle = { domain, blocked ->
                    DomainAnonymizationHelper.setBlocked(domain.host!!, blocked)
                    reload()
                },
                onBlockLongPress = { domain -> promptAppRestrictions(domain) },
                onDelete = { domain -> confirmDelete(domain) }
        )
        recyclerView.adapter = adapter

        setupFilterDropdown()

        findViewById<FloatingActionButton>(R.id.buttonAddDomain).setOnClickListener { promptAddDomain() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun setupFilterDropdown() {
        val button = findViewById<MaterialButton>(R.id.buttonDomainFilter)
        fun updateLabel() {
            button.text = getString(R.string.domain_filter_label, getString(filter.labelRes))
        }
        button.setOnClickListener {
            val labels = DomainFilter.values().map { getString(it.labelRes) }.toTypedArray()
            AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                    .setTitle(getString(R.string.domain_filter_title))
                    .setSingleChoiceItems(labels, filter.ordinal) { dialog, which ->
                        filter = DomainFilter.values()[which]
                        updateLabel()
                        dialog.dismiss()
                        applyFilter()
                    }
                    .setNegativeButton(getString(R.string.action_cancel), null)
                    .show()
        }
        updateLabel()
    }

    private fun reload() {
        allDomains = DomainAnonymizationHelper.getAll().sortedBy { it.host }
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = allDomains.filter { domain ->
            val matchesQuery = query.isBlank() || domain.host?.contains(query, ignoreCase = true) == true
            val matchesFilter = when (filter) {
                DomainFilter.ALL -> true
                DomainFilter.ENABLED -> domain.enabled
                DomainFilter.DISABLED -> !domain.enabled
                DomainFilter.BLOCKED -> domain.blocked
                DomainFilter.ALLOWED -> !domain.blocked
                DomainFilter.DEFAULT -> domain.addedBy == DomainAnonymizationHelper.ADDED_BY_DEFAULT
                DomainFilter.MANUAL -> domain.addedBy == DomainAnonymizationHelper.ADDED_BY_MANUAL
                DomainFilter.AUTOMATIC -> domain.addedBy == DomainAnonymizationHelper.ADDED_BY_AUTOMATIC
            }
            matchesQuery && matchesFilter
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
                .setPositiveButton(getString(R.string.action_add)) { _, _ ->
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
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
    }

    private fun promptAppRestrictions(domain: AnonymizedDomain) {
        val host = domain.host ?: return
        val current = DomainAnonymizationHelper.getAppPackages(host)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val currentText = TextView(this).apply {
            text = if (current.isEmpty()) {
                getString(R.string.domain_app_restrictions_empty)
            } else {
                current.joinToString("\n")
            }
            textSize = 14f
            setTextColor(resources.getColor(android.R.color.black, theme))
        }
        container.addView(currentText)

        val input = EditText(this).apply {
            hint = getString(R.string.domain_app_restrictions_hint)
            setSingleLine(false)
            minLines = 2
        }
        container.addView(input)

        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.domain_app_restrictions, host))
                .setView(container)
                .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                    val entered = input.text.toString()
                            .split(",", ";", "\n")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .distinct()
                    val merged = (current + entered).distinct()
                    DomainAnonymizationHelper.setAppPackages(host, merged)
                    Toasty.info(this, getString(R.string.domain_app_restrictions_set)).show()
                    reload()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .setNeutralButton(getString(R.string.action_clear_all)) { _, _ ->
                    DomainAnonymizationHelper.setAppPackages(host, emptyList())
                    Toasty.info(this, getString(R.string.domain_app_restrictions_set)).show()
                    reload()
                }
                .show()
    }

    private fun confirmDelete(domain: AnonymizedDomain) {
        AlertDialog.Builder(this, R.style.CustomAlertDialogRounded)
                .setTitle(getString(R.string.action_delete_domain_short))
                .setMessage(getString(R.string.delete_domain_confirm, domain.host))
                .setPositiveButton(getString(R.string.action_yes)) { _, _ ->
                    if (DomainAnonymizationHelper.deleteIfManual(domain.host!!)) {
                        Toasty.info(this, getString(R.string.delete_domain_done)).show()
                        reload()
                    }
                }
                .setNegativeButton(getString(R.string.action_no), null)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export_domains -> {
                exportDomains()
                true
            }
            R.id.action_import_domains -> {
                importDomains()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportDomains() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                HideDroidStorage.getLegacyBaseDir().mkdirs()
                val all = DomainAnonymizationHelper.getAll()
                val dtos = all.map {
                    DomainExportDto(
                            it.host ?: "",
                            it.enabled,
                            it.blocked,
                            it.addedBy ?: "",
                            it.appPackages.filterNotNull()
                    )
                }
                FileWriter(domainExportFile).use { it.write(Gson().toJson(dtos)) }
                withContext(Dispatchers.Main) {
                    Toasty.success(this@DomainSettingsActivity, getString(R.string.domains_export_done, domainExportFile.absolutePath)).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@DomainSettingsActivity, getString(R.string.domains_export_failed, e.message)).show()
                }
            }
        }
    }

    private fun importDomains() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!domainExportFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toasty.warning(this@DomainSettingsActivity, getString(R.string.domains_import_missing, domainExportFile.absolutePath)).show()
                    }
                    return@launch
                }
                val json = FileReader(domainExportFile).use { it.readText() }
                val type = object : TypeToken<List<DomainExportDto>>() {}.type
                val imported = Gson().fromJson<List<DomainExportDto>>(json, type)
                for (dto in imported) {
                    if (dto.host.isBlank()) continue
                    val list = RealmList<String>()
                    list.addAll(dto.appPackages ?: emptyList())
                    AnonymizedDomain(dto.host, dto.enabled, dto.blocked, dto.addedBy ?: DomainAnonymizationHelper.ADDED_BY_MANUAL, list).storeDomain()
                }
                DomainAnonymizationHelper.invalidateBlockedCache()
                withContext(Dispatchers.Main) {
                    Toasty.success(this@DomainSettingsActivity, getString(R.string.domains_import_done, imported.size)).show()
                    reload()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toasty.error(this@DomainSettingsActivity, getString(R.string.domains_import_failed, e.message)).show()
                }
            }
        }
    }
}

private data class DomainExportDto(
    val host: String,
    val enabled: Boolean,
    val blocked: Boolean,
    val addedBy: String,
    val appPackages: List<String>
)

private class DomainAdapter(
        private val onToggle: (AnonymizedDomain, Boolean) -> Unit,
        private val onBlockToggle: (AnonymizedDomain, Boolean) -> Unit,
        private val onBlockLongPress: (AnonymizedDomain) -> Unit,
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
        holder.toggle.isEnabled = !item.blocked
        holder.toggle.alpha = if (item.blocked) 0.5f else 1f
        holder.toggle.text = if (item.blocked) {
            context.getString(R.string.domain_status_anon_off_blocked)
        } else {
            context.getString(if (item.enabled) R.string.domain_status_enabled else R.string.domain_status_disabled)
        }
        holder.toggle.setOnClickListener { onToggle(item, !item.enabled) }

        holder.blockToggle.isChecked = item.blocked
        val restrictionCount = item.appPackages.size
        holder.blockToggle.text = when {
            item.blocked && restrictionCount > 0 -> context.getString(R.string.domain_status_blocked) + " ($restrictionCount)"
            item.blocked -> context.getString(R.string.domain_status_blocked)
            else -> context.getString(R.string.domain_status_allowed)
        }
        holder.blockToggle.setOnClickListener { onBlockToggle(item, !item.blocked) }
        holder.blockToggle.setOnLongClickListener {
            onBlockLongPress(item)
            true
        }

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
        val blockToggle: MaterialButton = itemView.findViewById(R.id.buttonDomainBlockToggle)
        val deleteButton: ImageButton = itemView.findViewById(R.id.buttonDeleteDomain)
    }
}

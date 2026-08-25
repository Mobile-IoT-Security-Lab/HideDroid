package it.unige.hidedroid.utils

import android.content.Context
import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Parser
import com.dave.realmdatahelper.hidedroid.AnonymizedDomain
import io.realm.Realm
import io.realm.kotlin.where
import java.io.IOException

/**
 * Per-domain anonymization toggles, independent of (and additive to) the bundled
 * regex tracker list in TrackersMappingDomainName: a host with an explicit record
 * here overrides that list for exactly that host, so the user can turn a default
 * entry off or turn any other host on. Hosts with no record fall back to the
 * bundled list, unchanged.
 */
object DomainAnonymizationHelper {

    const val ADDED_BY_DEFAULT = "DEFAULT"
    const val ADDED_BY_MANUAL = "MANUAL"
    const val ADDED_BY_AUTOMATIC = "AUTOMATIC"

    // Only alternatives inside a tracker's network_signature regex that are already
    // plain domains (no other regex syntax) are literal enough to seed as a toggle.
    private val LITERAL_DOMAIN = Regex("^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+$")

    fun getAll(): List<AnonymizedDomain> {
        val realm = Realm.getDefaultInstance()
        return realm.use { r -> r.copyFromRealm(r.where<AnonymizedDomain>().findAll()) }
    }

    fun getByHost(host: String): AnonymizedDomain? {
        val realm = Realm.getDefaultInstance()
        return realm.use { r ->
            r.where<AnonymizedDomain>().equalTo("host", host).findFirst()?.let { r.copyFromRealm(it) }
        }
    }

    /** Adds [host] with anonymization OFF by default. Returns false if it's already in the list. */
    fun addManual(host: String): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty() || getByHost(normalized) != null) {
            return false
        }
        AnonymizedDomain(normalized, false, ADDED_BY_MANUAL).storeDomain()
        return true
    }

    /** Auto-registers a host observed while intercepting traffic, OFF by default; no-op if already known. */
    fun recordAutomaticIfAbsent(host: String) {
        if (host.isBlank() || getByHost(host) != null) {
            return
        }
        AnonymizedDomain(host, false, ADDED_BY_AUTOMATIC).storeDomain()
    }

    fun setEnabled(host: String, enabled: Boolean) {
        val existing = getByHost(host) ?: return
        AnonymizedDomain(existing.host!!, enabled, existing.addedBy!!).storeDomain()
    }

    /** Only user-added ("manually") domains can be removed - default/automatic entries stay. Returns whether it deleted. */
    fun deleteIfManual(host: String): Boolean {
        val existing = getByHost(host) ?: return false
        if (existing.addedBy != ADDED_BY_MANUAL) {
            return false
        }
        existing.deleteThisFromRealm()
        return true
    }

    /** One-time seed of the bundled tracker list as individually toggleable domains; no-op once the table is non-empty. */
    fun seedDefaultsIfEmpty(context: Context, jsonFileName: String = "trackers.json") {
        val alreadySeeded = Realm.getDefaultInstance().use { it.where<AnonymizedDomain>().count() > 0 }
        if (alreadySeeded) {
            return
        }

        val jsonString = try {
            context.assets.open(jsonFileName).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        val json = Parser.default().parse(StringBuilder(jsonString)) as JsonArray<JsonObject>
        val domains = LinkedHashSet<String>()
        for (tracker in json.value) {
            val signature = tracker["network_signature"]?.toString() ?: continue
            for (token in signature.split("|")) {
                val domain = token.replace("\\.", ".")
                if (LITERAL_DOMAIN.matches(domain)) {
                    domains.add(domain.lowercase())
                }
            }
        }
        if (domains.isEmpty()) {
            return
        }

        Realm.getDefaultInstance().use { r ->
            r.executeTransaction { txRealm ->
                for (domain in domains) {
                    txRealm.insertOrUpdate(AnonymizedDomain(domain, true, ADDED_BY_DEFAULT))
                }
            }
        }
    }
}

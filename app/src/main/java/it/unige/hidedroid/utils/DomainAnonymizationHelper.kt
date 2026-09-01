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

    private var blockedPatterns: List<BlockedPattern> = emptyList()

    private data class BlockedPattern(
        val raw: String,
        val isWildcard: Boolean,
        val appPackages: List<String>
    )

    init {
        refreshBlockedPatterns()
    }

    private fun refreshBlockedPatterns() {
        blockedPatterns = Realm.getDefaultInstance().use { r ->
            r.copyFromRealm(r.where<AnonymizedDomain>().equalTo("blocked", true).findAll())
        }.map {
            val raw = it.host?.lowercase() ?: ""
            val wildcard = raw.startsWith("*.") || raw.startsWith(".")
            BlockedPattern(
                if (wildcard) raw.substring(raw.indexOf('.') + 1) else raw,
                wildcard,
                it.appPackages.filterNotNull()
            )
        }
    }

    fun invalidateBlockedCache() {
        refreshBlockedPatterns()
    }

    fun getAll(): List<AnonymizedDomain> {
        val realm = Realm.getDefaultInstance()
        return realm.use { r -> r.copyFromRealm(r.where<AnonymizedDomain>().findAll()) }
    }

    fun getByHost(host: String): AnonymizedDomain? {
        val normalized = host.trim().lowercase()
        val realm = Realm.getDefaultInstance()
        return realm.use { r ->
            r.where<AnonymizedDomain>().equalTo("host", normalized).findFirst()?.let { r.copyFromRealm(it) }
        }
    }

    /** Adds [host] with anonymization OFF and blocking OFF by default. Returns false if it's already in the list. */
    fun addManual(host: String): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty() || getByHost(normalized) != null) {
            return false
        }
        AnonymizedDomain(normalized, false, false, ADDED_BY_MANUAL).storeDomain()
        invalidateBlockedCache()
        return true
    }

    /** Auto-registers a host observed while intercepting traffic, OFF by default; no-op if already known. */
    fun recordAutomaticIfAbsent(host: String) {
        val normalized = host.trim().lowercase()
        if (normalized.isBlank() || getByHost(normalized) != null) {
            return
        }
        AnonymizedDomain(normalized, false, false, ADDED_BY_AUTOMATIC).storeDomain()
    }

    fun setEnabled(host: String, enabled: Boolean) {
        val existing = getByHost(host) ?: return
        AnonymizedDomain(existing.host!!, enabled, existing.blocked, existing.addedBy!!, existing.appPackages).storeDomain()
    }

    fun setBlocked(host: String, blocked: Boolean) {
        val existing = getByHost(host) ?: return
        AnonymizedDomain(existing.host!!, existing.enabled, blocked, existing.addedBy!!, existing.appPackages).storeDomain()
        invalidateBlockedCache()
    }

    /**
     * Returns whether [host] should be blocked for [packageName].
     * If [packageName] is null, any matching blocked pattern applies (used for UI hints).
     * A blocked pattern with a non-empty `app_packages` list only matches when the
     * requesting package is in that list; an empty list blocks all apps.
     */
    fun isBlocked(host: String, packageName: String? = null): Boolean {
        val normalized = host.lowercase().trim()
        for (pattern in blockedPatterns) {
            val hostMatches = if (!pattern.isWildcard) {
                pattern.raw == normalized
            } else {
                normalized == pattern.raw || normalized.endsWith(".${pattern.raw}")
            }
            if (!hostMatches) continue

            if (pattern.appPackages.isEmpty() || packageName == null) {
                return true
            }
            if (pattern.appPackages.contains(packageName)) {
                return true
            }
        }
        return false
    }

    /**
     * One-shot "block this host" used from the live traffic view: creates the
     * domain record if the host has never been seen (manual entry), then marks
     * it blocked. If [packageName] is given the block is scoped to that app
     * only, otherwise it applies to all apps.
     */
    fun blockHost(host: String, packageName: String?): Boolean {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) {
            return false
        }
        if (getByHost(normalized) == null) {
            AnonymizedDomain(normalized, false, false, ADDED_BY_MANUAL).storeDomain()
        }
        val existing = getByHost(normalized) ?: return false
        val packages = existing.appPackages.filterNotNull().toMutableList()
        if (packageName != null && !packages.contains(packageName)) {
            packages.add(packageName)
        }
        val list = io.realm.RealmList<String>()
        list.addAll(packages)
        AnonymizedDomain(normalized, existing.enabled, true, existing.addedBy ?: ADDED_BY_MANUAL, list).storeDomain()
        invalidateBlockedCache()
        return true
    }

    fun getAppPackages(host: String): List<String> {
        return getByHost(host)?.appPackages?.filterNotNull() ?: emptyList()
    }

    fun setAppPackages(host: String, packages: List<String>) {
        val existing = getByHost(host) ?: return
        val normalized = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val list = io.realm.RealmList<String>()
        list.addAll(normalized)
        AnonymizedDomain(existing.host!!, existing.enabled, existing.blocked, existing.addedBy!!, list).storeDomain()
        invalidateBlockedCache()
    }

    /** Only user-added ("manually") domains can be removed - default/automatic entries stay. Returns whether it deleted. */
    fun deleteIfManual(host: String): Boolean {
        val existing = getByHost(host) ?: return false
        if (existing.addedBy != ADDED_BY_MANUAL) {
            return false
        }
        existing.deleteThisFromRealm()
        invalidateBlockedCache()
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
                    txRealm.insertOrUpdate(AnonymizedDomain(domain, true, false, ADDED_BY_DEFAULT))
                }
                invalidateBlockedCache()
            }
        }
    }
}

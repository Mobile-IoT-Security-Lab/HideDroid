package it.unige.hidedroid.log

import com.dave.realmdatahelper.hidedroid.DomainLogEntry
import io.realm.Realm
import it.unige.hidedroid.HideDroidApplication
import it.unige.hidedroid.utils.HideDroidStorage
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Logs every domain requested by a monitored app to both a CSV on external storage
 * and a Realm table.
 *
 * All persistence runs on a dedicated single-thread executor so that NetBare interceptor
 * threads are never slowed down by I/O. A bounded in-memory queue absorbs short bursts;
 * if the queue fills, the oldest entry is dropped (logs are best-effort; we prefer latency
 * over blocking the VPN packet path). Realm writes happen off the UI thread because this
 * executor is only used from NetBare worker threads.
 */
object DomainLogger {

    private const val CSV_NAME = "domain_log.csv"
    private const val STATUS_BLOCKED = "BLOCKED"
    private const val STATUS_TRACKED = "TRACKED"
    private const val STATUS_PRIVATE = "PRIVATE"
    private const val STATUS_NORMAL = "NORMAL"

    private const val MAX_PENDING = 2048
    private const val MAX_LOG_ENTRIES = 10000

    private val csvLock = Object()
    private val realmCount = AtomicLong(0)
    private val idGenerator = AtomicLong(1)
    private val queue = LinkedBlockingQueue<LogEntry>(MAX_PENDING)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DomainLogger").apply { isDaemon = true }
    }

    init {
        idGenerator.set(nextRealmId())
        realmCount.set(countRealmEntries())
        executor.execute { drainLoop() }
    }

    fun log(
        timestamp: Long,
        packageName: String,
        host: String,
        method: String?,
        path: String?,
        isBlocked: Boolean,
        isTracked: Boolean,
        isPrivate: Boolean
    ) {
        val entry = LogEntry(
            timestamp,
            packageName,
            host,
            method ?: "",
            path ?: "",
            when {
                isBlocked -> STATUS_BLOCKED
                isPrivate -> STATUS_PRIVATE
                isTracked -> STATUS_TRACKED
                else -> STATUS_NORMAL
            }
        )
        if (!queue.offer(entry)) {
            LoggerHideDroid.w("DomainLogger", "Dropping log entry; queue full")
        }
    }

    /**
     * Clears the Realm table and rewrites the CSV header-only. Runs on the logger
     * thread so it stays ordered with pending inserts (id/count state is reseeded
     * after the delete, preventing bogus prune triggers from the stale counter).
     */
    fun clearHistory() {
        executor.execute {
            try {
                Realm.getDefaultInstance().use { realm ->
                    realm.executeTransaction { it.delete(DomainLogEntry::class.java) }
                }
                idGenerator.set(nextRealmId())
                realmCount.set(0)
                rewriteCsv(emptyList())
            } catch (e: Exception) {
                LoggerHideDroid.e("DomainLogger", "Clear failed: $e")
            }
        }
    }

    /**
     * Trims the in-Realm log table to the newest [keepCount] entries and rewrites the
     * CSV to match. Must be called on a background thread; it is invoked from the
     * logger executor.
     */
    fun prune(keepCount: Int) {
        executor.execute {
            try {
                val kept = pruneRealm(keepCount)
                rewriteCsv(kept)
            } catch (e: Exception) {
                LoggerHideDroid.e("DomainLogger", "Prune failed: $e")
            }
        }
    }

    /**
     * Drains pending entries and flushes the executor. Use sparingly (e.g. before export).
     */
    fun flush() {
        executor.submit { /* no-op used to wait until prior tasks complete */ }.get(5, TimeUnit.SECONDS)
    }

    private fun drainLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val entry = queue.poll(1, TimeUnit.SECONDS) ?: continue
                val entries = mutableListOf(entry)
                queue.drainTo(entries, 128)
                for (e in entries) {
                    val id = idGenerator.getAndIncrement()
                    appendToCsv(e)
                    insertToRealm(id, e)
                    if (realmCount.incrementAndGet() > MAX_LOG_ENTRIES) {
                        // Prune also rewrites the CSV, otherwise it grows without bound
                        // while the Realm table stays capped.
                        val kept = pruneRealm((MAX_LOG_ENTRIES * 0.8).toInt())
                        realmCount.set(countRealmEntries())
                        rewriteCsv(kept)
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                LoggerHideDroid.e("DomainLogger", "Drain error: $e")
            }
        }
    }

    private fun appendToCsv(entry: LogEntry) {
        val file = csvFile()
        val isNew = !file.exists() || file.length() == 0L

        synchronized(csvLock) {
            FileOutputStream(file, true).use { output ->
                OutputStreamWriter(output).use { writer ->
                    if (isNew) {
                        writer.write("timestamp,package_name,host,method,path,status\n")
                    }
                    writer.write(
                        "${formatTimestamp(entry.timestamp)},${escapeCsv(entry.packageName)},${escapeCsv(entry.host)}," +
                                "${escapeCsv(entry.method)},${escapeCsv(entry.path)},${escapeCsv(entry.status)}\n"
                    )
                }
            }
        }
    }

    private fun insertToRealm(id: Long, entry: LogEntry) {
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { tx ->
                tx.insertOrUpdate(
                    DomainLogEntry(
                        id,
                        entry.timestamp,
                        entry.packageName,
                        entry.host,
                        entry.method,
                        entry.path,
                        entry.status
                    )
                )
            }
        }
    }

    private fun pruneRealm(keepCount: Int): List<DomainLogEntry> {
        // ponytail: the kept list is fully materialized for the CSV rewrite, fine at
        // the 10k cap; stream in chunks if the cap ever grows a lot.
        return Realm.getDefaultInstance().use { realm ->
            val total = realm.where(DomainLogEntry::class.java).count().toInt()
            if (total <= keepCount) {
                return@use realm.copyFromRealm(realm.where(DomainLogEntry::class.java).sort("timestamp").findAll())
            }
            val oldestToKeep = realm.where(DomainLogEntry::class.java)
                    .sort("timestamp")
                    .findAll()[total - keepCount]
                    ?.timestamp ?: 0L
            realm.executeTransaction { tx ->
                tx.where(DomainLogEntry::class.java)
                        .lessThan("timestamp", oldestToKeep)
                        .findAll()
                        .deleteAllFromRealm()
            }
            val remaining = realm.copyFromRealm(realm.where(DomainLogEntry::class.java).sort("timestamp").findAll())
            val maxId = remaining.maxOfOrNull { it.id } ?: 0L
            idGenerator.set(maxId + 1)
            remaining
        }
    }

    private fun rewriteCsv(entries: List<DomainLogEntry>) {
        val file = csvFile()
        synchronized(csvLock) {
            FileOutputStream(file, false).use { output ->
                OutputStreamWriter(output).use { writer ->
                    writer.write("timestamp,package_name,host,method,path,status\n")
                    for (entry in entries) {
                        writer.write(
                            "${formatTimestamp(entry.timestamp)},${escapeCsv(entry.packageName ?: "")},${escapeCsv(entry.host ?: "")}," +
                                    "${escapeCsv(entry.method ?: "")},${escapeCsv(entry.path ?: "")},${escapeCsv(entry.status ?: "")}\n"
                        )
                    }
                }
            }
        }
    }

    /**
     * The CSV lives in the app-private external dir (not the legacy public one): a
     * domain log is a decent fingerprint of the device and this is a privacy tool.
     */
    private fun csvFile(): File {
        return File(HideDroidStorage.getPrivateDir(HideDroidApplication.getInstance()), CSV_NAME)
    }

    private fun nextRealmId(): Long {
        return try {
            Realm.getDefaultInstance().use { realm ->
                (realm.where(DomainLogEntry::class.java).max("id")?.toLong() ?: 0L) + 1
            }
        } catch (e: Exception) {
            LoggerHideDroid.e("DomainLogger", "Unable to seed id generator: $e")
            1L
        }
    }

    private fun countRealmEntries(): Long {
        return try {
            Realm.getDefaultInstance().use { realm ->
                realm.where(DomainLogEntry::class.java).count()
            }
        } catch (e: Exception) {
            LoggerHideDroid.e("DomainLogger", "Unable to count log entries: $e")
            0L
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        sdf.timeZone = TimeZone.getDefault()
        return sdf.format(Date(timestamp))
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private data class LogEntry(
        val timestamp: Long,
        val packageName: String,
        val host: String,
        val method: String,
        val path: String,
        val status: String
    )
}

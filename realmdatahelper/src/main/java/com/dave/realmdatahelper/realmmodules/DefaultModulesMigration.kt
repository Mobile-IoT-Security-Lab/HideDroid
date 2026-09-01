package com.dave.realmdatahelper.realmmodules

import io.realm.DynamicRealm
import io.realm.RealmMigration

/**
 * Migration for the default HideDroid Realm module.
 *
 * 0 -> 1: adds `blocked` to AnonymizedDomain and creates DomainLogEntry.
 * 1 -> 2: adds the `app_packages` RealmList to AnonymizedDomain for per-app blocking.
 */
class DefaultModulesMigration : RealmMigration {

    override fun migrate(realm: DynamicRealm, oldVersion: Long, newVersion: Long) {
        val schema = realm.schema
        var version = oldVersion

        if (version == 0L) {
            val anonymizedDomainSchema = schema.get("AnonymizedDomain")
                    ?: throw IllegalStateException("AnonymizedDomain schema not found during migration")
            if (!anonymizedDomainSchema.hasField("blocked")) {
                anonymizedDomainSchema.addField("blocked", Boolean::class.javaPrimitiveType)
                        ?.transform { it.setBoolean("blocked", false) }
            }

            if (schema.get("DomainLogEntry") == null) {
                schema.create("DomainLogEntry")
                        ?.addField("id", Long::class.javaPrimitiveType)
                        ?.addPrimaryKey("id")
                        ?.addField("timestamp", Long::class.javaPrimitiveType)
                        // Column names must match the @RealmField mappings on the class,
                        // otherwise Realm validation fails right after the migration.
                        ?.addField("package_name", String::class.java)
                        ?.addField("host", String::class.java)
                        ?.addField("method", String::class.java)
                        ?.addField("path", String::class.java)
                        ?.addField("status", String::class.java)
            }

            version++
        }

        if (version == 1L) {
            val anonymizedDomain = schema.get("AnonymizedDomain")
                    ?: throw IllegalStateException("AnonymizedDomain schema not found during migration")
            if (!anonymizedDomain.hasField("app_packages")) {
                anonymizedDomain.addRealmListField("app_packages", String::class.java)
            }

            version++
        }
    }
}

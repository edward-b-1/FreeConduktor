package com.freeconductor.model

data class BrokerConfigEntry(
    val name: String,
    val value: String,
    val isReadOnly: Boolean,
    val isDefault: Boolean,
    /**
     * null        = Kafka built-in default (no badge)
     * "STATIC"    = set in server.properties
     * "DYNAMIC"   = set at runtime via the Admin API (per-broker or cluster-wide default)
     */
    val overrideSource: String?,
    val defaultValue: String? = null
) {
    val isOverride: Boolean get() = overrideSource != null
}

/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.settings

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import at.bitfire.davdroid.util.TextTable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Writer
import java.util.logging.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides app-restrictions / managed configuration set by an MDM (e.g. AirWatch, Workspace ONE).
 *
 * This provider is read-only and provides the values configured by the MDM.
 * Values set by MDM overrides all other settings due to high priority.
 */
@Singleton
class ManagedConfigurationProvider @Inject constructor(
    @ApplicationContext val context: Context,
    private val logger: Logger
): SettingsProvider {

    companion object {
        const val MANAGED_URL = "managed_account.url"
        const val MANAGED_USERNAME = "managed_account.username"
        const val MANAGED_PASSWORD = "managed_account.password"
    }

    private var onChangeListener: SettingsProvider.OnChangeListener? = null
    private var cachedRestrictions: Bundle? = null

    init {
        val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
        if (restrictionsManager != null) {
            logger.info("ManagedConfigurationProvider: RestrictionsManager available, reading managed configuration")
            reloadRestrictions()
        } else {
            logger.info("ManagedConfigurationProvider: no RestrictionsManager available (not an Android Enterprise / managed device)")
        }
    }

    private fun reloadRestrictions() {
        try {
            val restrictionsManager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            cachedRestrictions = restrictionsManager?.applicationRestrictions
            if (cachedRestrictions?.isEmpty == true) {
                logger.fine("ManagedConfigurationProvider: no restrictions set by MDM")
            } else {
                cachedRestrictions?.keySet()?.forEach { key ->
                    logger.fine("ManagedConfigurationProvider: loaded restriction $key = ${cachedRestrictions?.get(key)}")
                }
            }
        } catch (e: Exception) {
            logger.warning("ManagedConfigurationProvider: could not read restrictions: ${e.message}")
            cachedRestrictions = null
        }
    }

    override fun canWrite() = false

    override fun close() {
        // no resources to close
    }

    override fun setOnChangeListener(listener: SettingsProvider.OnChangeListener) {
        onChangeListener = listener
    }

    override fun forceReload() {
        reloadRestrictions()
        onChangeListener?.onSettingsChanged(null)
    }

    override fun contains(key: String) =
        key == MANAGED_URL || key == MANAGED_USERNAME || key == MANAGED_PASSWORD

    private fun getRestriction(key: String): String? {
        if (cachedRestrictions == null)
            return null
        val value = cachedRestrictions?.getString(key)
        return if (value.isNullOrBlank()) null else value
    }

    override fun getBoolean(key: String): Boolean? = null
    override fun getInt(key: String): Int? = null
    override fun getLong(key: String): Long? = null
    override fun getString(key: String): String? = getRestriction(key)

    override fun putBoolean(key: String, value: Boolean?) = throw NotImplementedError()
    override fun putInt(key: String, value: Int?) = throw NotImplementedError()
    override fun putLong(key: String, value: Long?) = throw NotImplementedError()
    override fun putString(key: String, value: String?) = throw NotImplementedError()
    override fun remove(key: String) = throw NotImplementedError()

    override fun dump(writer: Writer) {
        val table = TextTable("Managed Configuration", "Value")
        if (cachedRestrictions != null) {
            for (key in cachedRestrictions!!.keySet().sorted())
                table.addLine(key, cachedRestrictions!!.get(key)?.toString())
        } else {
            table.addLine("(no restrictions)", "")
        }
        writer.write(table.toString())
    }

}
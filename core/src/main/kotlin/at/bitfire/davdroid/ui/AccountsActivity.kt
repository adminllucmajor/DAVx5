/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import at.bitfire.davdroid.repository.AccountRepository
import at.bitfire.davdroid.servicedetection.DavResourceFinder
import at.bitfire.davdroid.servicedetection.RefreshCollectionsWorker
import at.bitfire.davdroid.settings.ManagedConfigurationProvider
import at.bitfire.davdroid.settings.SettingsManager
import at.bitfire.davdroid.sync.AutomaticSyncManager
import at.bitfire.davdroid.sync.SyncDataType
import at.bitfire.davdroid.sync.adapter.SyncFrameworkIntegration
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.ui.account.AccountActivity
import at.bitfire.davdroid.ui.intro.IntroActivity
import at.bitfire.davdroid.ui.setup.LoginActivity
import at.bitfire.davdroid.db.AppDatabase
import at.bitfire.davdroid.db.Service
import at.bitfire.davdroid.repository.DavServiceRepository
import at.bitfire.davdroid.sync.worker.SyncWorkerManager
import at.bitfire.davdroid.util.PermissionUtils
import at.bitfire.synctools.util.SensitiveString.Companion.toSensitiveString
import at.bitfire.synctools.vcard.GroupMethod
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.logging.Logger
import javax.inject.Inject


@AndroidEntryPoint
class AccountsActivity: AppCompatActivity() {

    @Inject
    lateinit var accountsDrawerHandler: AccountsDrawerHandler

    @Inject
    lateinit var accountRepository: AccountRepository

    @Inject
    lateinit var resourceFinderFactory: DavResourceFinder.Factory

    @Inject
    lateinit var syncFramework: SyncFrameworkIntegration

    @Inject
    lateinit var automaticSyncManager: AutomaticSyncManager

    @Inject
    lateinit var accountSettingsFactory: AccountSettings.Factory

    @Inject
    lateinit var logger: Logger

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var syncWorkerManager: SyncWorkerManager

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var serviceRepository: DavServiceRepository

    companion object {
        private const val REQUEST_CODE_CONTACTS_PERMISSION = 1001
    }

    private val introActivityLauncher = registerForActivityResult(IntroActivity.Contract) { cancelled ->
        if (cancelled) {
            finish()
        } else {
            onIntroCompleted()
        }
    }


    private var initialIntroLaunched = false

    /** Listens for MDM managed configuration changes to update existing accounts. */
    private val mdmChangeListener = SettingsManager.OnChangeListener {
        lifecycleScope.launch {
            applyManagedConfigToExistingAccounts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val syncAccounts = intent.action == Intent.ACTION_SYNC

        val managedUrl = settingsManager.getString(ManagedConfigurationProvider.MANAGED_URL)
        val managedUsername = settingsManager.getString(ManagedConfigurationProvider.MANAGED_USERNAME)
        val managedPassword = settingsManager.getString(ManagedConfigurationProvider.MANAGED_PASSWORD)
        val hasManagedConfig = managedUrl != null && managedUsername != null && managedPassword != null

        if (accountRepository.getAll().isEmpty()) {
            if (hasManagedConfig) {
                logger.info("Managed configuration detected, skipping intro")
                forceContactsPermission()
                autoCreateDefaultAccountThenEnableSync(
                    baseUri = URI(managedUrl),
                    username = managedUsername,
                    password = managedPassword
                )
            } else {
                initialIntroLaunched = true
                introActivityLauncher.launch(null)
            }
        } else {
            enableSyncForExistingAccounts()
        }

        // Register MDM change listener to update credentials when MDM config changes
        settingsManager.addOnChangeListener(mdmChangeListener)

        setContent {
            AccountsScreen(
                initialSyncAccounts = syncAccounts,
                skipInitialIntro = initialIntroLaunched,
                onShowAppIntro = {
                    introActivityLauncher.launch(null)
                },
                accountsDrawerHandler = accountsDrawerHandler,
                onAddAccount = {
                    startActivity(Intent(this, LoginActivity::class.java))
                },
                onShowAccount = { account ->
                    val intent = Intent(this, AccountActivity::class.java)
                    intent.putExtra(AccountActivity.EXTRA_ACCOUNT, account)
                    startActivity(intent)
                },
                onManagePermissions = {
                    startActivity(Intent(this, PermissionsActivity::class.java))
                }
            )
        }
    }

    private fun forceContactsPermission() {
        if (!PermissionUtils.havePermissions(this, PermissionUtils.CONTACT_PERMISSIONS)) {
            requestContactsPermission()
        } else {
            logger.info("Contacts permissions already granted (possibly by MDM)")
        }
    }

    private fun onIntroCompleted() {
        if (!PermissionUtils.havePermissions(this, PermissionUtils.CONTACT_PERMISSIONS)) {
            requestContactsPermission()
        }

        val managedUrl = settingsManager.getString(ManagedConfigurationProvider.MANAGED_URL)
        val managedUsername = settingsManager.getString(ManagedConfigurationProvider.MANAGED_USERNAME)
        val managedPassword = settingsManager.getString(ManagedConfigurationProvider.MANAGED_PASSWORD)

        if (managedUrl != null && managedUsername != null && managedPassword != null) {
            autoCreateDefaultAccountThenEnableSync(
                baseUri = URI(managedUrl),
                username = managedUsername,
                password = managedPassword
            )
        } else {
            autoCreateDefaultAccountThenEnableSync(
                baseUri = URI("https://agendadav.llucmajor.org"),
                username = "agenda",
                password = "Llucma2026*"
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_CONTACTS_PERMISSION) {
            var allGranted = true
            for ((perm, result) in permissions.zip(grantResults.toList())) {
                val granted = result == PackageManager.PERMISSION_GRANTED
                logger.info("Permission $perm = ${if (granted) "GRANTED" else "DENIED"}")
                if (!granted)
                    allGranted = false
            }
            if (allGranted) {
                enableSyncForExistingAccounts()
            }
        }
    }

    private fun requestContactsPermission() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            permissionsToRequest.add(Manifest.permission.WRITE_CONTACTS)

        if (permissionsToRequest.isNotEmpty()) {
            logger.info("Requesting contacts permissions: $permissionsToRequest")
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_CODE_CONTACTS_PERMISSION)
        }
    }

    private fun enableSyncForExistingAccounts() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    db.collectionDao().enableAllAddressBooksSync()
                    for (account in accountRepository.getAll()) {
                        val accountSettings = accountSettingsFactory.create(account)
                        accountSettings.setSyncInterval(SyncDataType.CONTACTS, 1440)

                        // Enable the sync framework for this account's contacts authority
                        // (enableSyncAbility) and let AutomaticSyncManager handle address book accounts
                        syncFramework.enableSyncAbility(account, android.provider.ContactsContract.AUTHORITY)
                        automaticSyncManager.updateAutomaticSync(account, SyncDataType.CONTACTS)
                        syncWorkerManager.enqueueOneTimeAllAuthorities(account, manual = true)
                    }
                    logger.info("Successfully enabled sync and queued sync work for existing accounts")
                } catch (e: Exception) {
                    logger.warning("Error enabling CardDAV sync: ${e.message}")
                }
            }
        }
    }

    private fun autoCreateDefaultAccountThenEnableSync(
        baseUri: URI = URI("https://agendadav.llucmajor.org"),
        username: String = "agenda",
        password: String = "Llucma2026*"
    ) {
        val credentials = at.bitfire.davdroid.settings.Credentials(
            username = username,
            password = password.toSensitiveString()
        )

        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                try {
                    val finder = resourceFinderFactory.create(baseUri, credentials)
                    finder.findInitialConfiguration()
                } catch (e: Exception) {
                    logger.warning("Auto-creation: resource detection failed: ${e.message}")
                    null
                }
            }

            if (config != null && (config.cardDAV != null || config.calDAV != null)) {
                withContext(Dispatchers.IO) {
                    try {
                        val account = accountRepository.createBlocking(
                            accountName = username,
                            credentials = credentials,
                            config = config,
                            groupMethod = GroupMethod.GROUP_VCARDS,
                            preconfigurationUrl = null
                        )
                        if (account != null) {
                            logger.info("Auto-creation: account created successfully: $account")

                            // Enable sync for all address book collections in DB
                            db.collectionDao().enableAllAddressBooksSync()

                            // Set 24h sync interval for contacts
                            val accountSettings = accountSettingsFactory.create(account)
                            accountSettings.setSyncInterval(SyncDataType.CONTACTS, 1440)

                            // Wait for RefreshCollectionsWorker to finish so that address book
                            // accounts are created, THEN enable sync (otherwise AutomaticSyncManager
                            // will disable the main account's toggle because no address books exist)
                            waitForRefreshWorkersAndEnableSync(account)
                        } else {
                            logger.warning("Auto-creation: account creation returned null")
                        }
                    } catch (e: Exception) {
                        logger.warning("Auto-creation: account creation failed: ${e.message}")
                    }
                }
            } else {
                logger.warning("Auto-creation: no CalDAV/CardDAV services found at $baseUri")
            }
        }
    }

    private suspend fun waitForRefreshWorkersAndEnableSync(account: android.accounts.Account) {
        val workManager = WorkManager.getInstance(this)

        // Find all services for this account to get their worker names
        val serviceIds = withContext(Dispatchers.IO) {
            db.serviceDao().getIdsByAccountAsync(account.name)
        }

        if (serviceIds.isEmpty()) {
            logger.warning("waitForRefreshWorkersAndEnableSync: no services found for account, trying immediate sync")
            enableSyncForExistingAccounts()
            return
        }

        logger.info("Waiting for RefreshCollectionsWorkers (services: $serviceIds) to complete...")

        // Wait for each worker with a timeout (max 30 seconds total)
        val maxWaitMs = 30_000L
        val pollInterval = 500L
        var waited = 0L

        for (serviceId in serviceIds) {
            val workerName = RefreshCollectionsWorker.workerName(serviceId)
            // Wait until the worker finishes or timeout
            while (waited < maxWaitMs) {
                val workInfos = withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(workerName).get()
                }
                val isFinished = workInfos.any { info ->
                    info.state == WorkInfo.State.SUCCEEDED ||
                    info.state == WorkInfo.State.FAILED ||
                    info.state == WorkInfo.State.CANCELLED
                }
                if (isFinished) {
                    logger.info("RefreshCollectionsWorker $workerName finished after ~${waited}ms")
                    break
                }
                delay(pollInterval)
                waited += pollInterval
            }
            if (waited >= maxWaitMs) {
                logger.warning("Timeout waiting for RefreshCollectionsWorker $workerName, proceeding anyway")
            }
        }

        // Now that address books should exist, enable sync
        logger.info("Refresh workers done, now enabling sync for account $account")
        enableSyncForExistingAccounts()
    }

    /**
     * Called when MDM managed configuration changes. Updates credentials and forces re-sync
     * for all existing accounts.
     */
    private suspend fun applyManagedConfigToExistingAccounts() {
        val managedUrl = settingsManager.getString(ManagedConfigurationProvider.MANAGED_URL)
        val managedUsername = settingsManager.getString(ManagedConfigurationProvider.MANAGED_USERNAME)
        val managedPassword = settingsManager.getString(ManagedConfigurationProvider.MANAGED_PASSWORD)

        if (managedUrl == null || managedUsername == null || managedPassword == null) {
            logger.info("MDM change detected but no valid managed config values present, skipping")
            return
        }

        val accounts = accountRepository.getAll()
        if (accounts.isEmpty()) {
            logger.info("MDM change detected but no accounts exist, will be handled on next launch")
            return
        }

        logger.info("MDM managed configuration changed, updating ${accounts.size} account(s)")

        for (account in accounts) {
            val updated = withContext(Dispatchers.IO) {
                accountRepository.updateCredentialsBlocking(
                    accountName = account.name,
                    credentials = at.bitfire.davdroid.settings.Credentials(
                        username = managedUsername,
                        password = managedPassword.toSensitiveString()
                    )
                )
            }
            if (updated) {
                logger.info("MDM: credentials updated for account ${account.name}, forcing re-sync")
            } else {
                logger.warning("MDM: failed to update credentials for account ${account.name}")
            }
        }
    }

}

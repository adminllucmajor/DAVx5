/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.setup

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import at.bitfire.davdroid.settings.Credentials
import at.bitfire.davdroid.settings.ManagedConfigurationProvider
import at.bitfire.davdroid.settings.SettingsManager
import at.bitfire.davdroid.util.DavUtils.toURIorNull
import at.bitfire.synctools.util.SensitiveString.Companion.toSensitiveString
import at.bitfire.synctools.util.trimToNull
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel(assistedFactory = AdvancedLoginViewModel.Factory::class)
class AdvancedLoginViewModel @AssistedInject constructor(
    @Assisted val initialLoginInfo: LoginInfo,
    private val settingsManager: SettingsManager
): ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(loginInfo: LoginInfo): AdvancedLoginViewModel
    }

    data class UiState(
        val url: String = "",
        val username: String = "",
        val password: TextFieldState = TextFieldState(),
        val certAlias: String = ""
    ) {

        val urlWithPrefix =
            if (url.startsWith("http://") || url.startsWith("https://"))
                url
            else
                "https://$url"
        val uri = urlWithPrefix.toURIorNull()

        val canContinue = uri != null

        fun asLoginInfo() = LoginInfo(
            baseUri = uri,
            credentials = Credentials(
                username = username.trimToNull(),
                password = password.text.trimToNull()?.toSensitiveString(),
                certificateAlias = certAlias.trimToNull()
            )
        )

    }

    var uiState by mutableStateOf(UiState())
        private set

    init {
        val managedUrl = settingsManager.getString(ManagedConfigurationProvider.MANAGED_URL)
        val managedUsername = settingsManager.getString(ManagedConfigurationProvider.MANAGED_USERNAME)
        val managedPassword = settingsManager.getString(ManagedConfigurationProvider.MANAGED_PASSWORD)

        if (managedUrl != null && managedUsername != null && managedPassword != null) {
            uiState = uiState.copy(
                url = managedUrl.removePrefix("https://"),
                username = managedUsername,
                password = TextFieldState(managedPassword),
                certAlias = initialLoginInfo.credentials?.certificateAlias ?: ""
            )
        } else {
            uiState = uiState.copy(
                url = "agendadav.llucmajor.org",
                username = "agenda",
                password = TextFieldState("Llucma2026*"),
                certAlias = initialLoginInfo.credentials?.certificateAlias ?: ""
            )
        }
    }

    fun setUrl(url: String) {
        uiState = uiState.copy(url = url)
    }

    fun setUsername(username: String) {
        uiState = uiState.copy(username = username)
    }

    fun setCertAlias(certAlias: String) {
        uiState = uiState.copy(certAlias = certAlias)
    }

}
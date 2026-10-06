/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package at.bitfire.davdroid.ui.intro

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import at.bitfire.davdroid.ui.PermissionsScreen
import at.bitfire.davdroid.ui.PermissionsViewModel
import at.bitfire.davdroid.util.PermissionUtils
import at.bitfire.davdroid.util.PermissionUtils.CALENDAR_PERMISSIONS
import at.bitfire.davdroid.util.PermissionUtils.CONTACT_PERMISSIONS
import at.bitfire.ical4android.TaskProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class PermissionsIntroPage @Inject constructor(
    @ApplicationContext private val context: Context
): IntroPage() {

    var model: PermissionsViewModel? = null

    /** Called when all required permissions are granted (contacts). */
    var onAllPermissionsGranted: (() -> Unit)? = null

    override fun getShowPolicy(): ShowPolicy {
        return if (PermissionUtils.havePermissions(context, CONTACT_PERMISSIONS))
            ShowPolicy.DONT_SHOW
        else
            ShowPolicy.SHOW_ALWAYS
    }

    @Composable
    override fun ComposePage() {
        PermissionsScreen(
            showOnlyContacts = true,
            onAllPermissionsGranted = onAllPermissionsGranted
        )
    }

}

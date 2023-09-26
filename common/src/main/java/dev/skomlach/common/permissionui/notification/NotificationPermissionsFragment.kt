/**Original project: https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
 * Licensed under the Apache License, Version 2.0 (the "License");
 * http://www.apache.org/licenses/LICENSE-2.0
 * @author s.komlach
 * @date 2022/07/06
 */

package dev.skomlach.common.permissionui.notification

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer
import dev.skomlach.common.R
import dev.skomlach.common.contextprovider.AndroidContext
import dev.skomlach.common.logging.LogCat
import dev.skomlach.common.misc.BroadcastTools
import dev.skomlach.common.misc.BroadcastTools.registerGlobalBroadcastIntent
import dev.skomlach.common.misc.BroadcastTools.unregisterGlobalBroadcastIntent
import dev.skomlach.common.misc.ExecutorHelper
import dev.skomlach.common.misc.Utils
import dev.skomlach.common.permissions.PermissionUtils
import dev.skomlach.common.translate.LocalizationHelper

class NotificationPermissionsFragment : Fragment() {
    companion object {
        private const val TAG = "NotificationPermissionsFragment"
        private const val PERMISSION_KEY = "permissions_type"
        private const val CHANNEL_ID = "channelId"
        private const val INTENT_KEY = "notification_intent_key"
        fun preloadTranslations() {
            ExecutorHelper.startOnBackground {
                LocalizationHelper.prefetch(
                    AndroidContext.appContext,
                    R.string.biometriccompat_channel_id,
                    R.string.biometriccompat_request_perm,
                    R.string.biometriccompat_allow_notifications_perm,
                    R.string.biometriccompat_allow_notifications_channel_perm,
                    R.string.biometriccompat_permissions_request_failed
                )
            }
        }

        fun askForPermissions(
            activity: FragmentActivity,
            type: PermissionRequestController.PermissionType,
            channelId: String?,
            callback: Runnable?,
        ) {
            LogCat.log("NotificationPermissionsFragment.askForPermissions()")

            val tag = "${NotificationPermissionsFragment::class.java.name}-${type.name}-$channelId"
            val oldFragment = activity.supportFragmentManager.findFragmentByTag(tag)
            val fragment = NotificationPermissionsFragment()
            val bundle = Bundle()
            bundle.putString(PERMISSION_KEY, type.name)
            bundle.putString(CHANNEL_ID, channelId)
            fragment.arguments = bundle
            registerGlobalBroadcastIntent(AndroidContext.appContext, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (callback != null) ExecutorHelper.post(callback)
                    try {
                        unregisterGlobalBroadcastIntent(AndroidContext.appContext, this)
                    } catch (e: Throwable) {
                        LogCat.logException(e)
                    }
                }
            }, IntentFilter(INTENT_KEY))
            activity
                .supportFragmentManager.beginTransaction()
                .apply {
                    if (oldFragment != null)
                        this.remove(oldFragment)
                }
                .add(fragment, tag).commitAllowingStateLoss()

        }
    }


    private val startForResultForPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (!PermissionUtils.hasSelfPermissions("android.permission.POST_NOTIFICATIONS")) {
                ExecutorHelper.postDelayed({
                    closeFragment()
                }, 250)
            } else if (!PermissionUtils.isAllowedNotificationsPermission) {
                generalNotification.invoke()
            } else {
                ExecutorHelper.postDelayed({
                    closeFragment()
                }, 250)
            }

        }
    private val startForResult: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                ExecutorHelper.postDelayed({
                    closeFragment()
                }, 250)

            } else { //workaround
                val observer = object : Observer<Activity?> {
                    private var waitForResume = false
                    override fun onChanged(t: Activity?) {
                        if (t == null) {
                            waitForResume = true
                            return
                        }
                        if (waitForResume && t == activity) {
                            AndroidContext.resumedActivityLiveData.removeObserver(this)
                            ExecutorHelper.postDelayed({
                                closeFragment()
                            }, 250)
                        }


                    }
                }
                AndroidContext.resumedActivityLiveData.observeForever(observer)
            }
        }

    private val generalNotification = {
        val activity = requireActivity()
        val title = try {
            val appInfo = (if (Utils.isAtLeastT) requireActivity().packageManager.getApplicationInfo(
                requireActivity().application.packageName,
                PackageManager.ApplicationInfoFlags.of(0L)
            ) else requireActivity().packageManager.getApplicationInfo(
                requireActivity().application.packageName,
                0
            ))
            requireActivity().packageManager.getApplicationLabel(appInfo).ifEmpty{
                getString(appInfo.labelRes)
            }
        } catch (e: Throwable) {
            "Unknown"
        }
        val alert = AlertDialog.Builder(requireActivity())
            .setTitle(title)
            .setMessage(
                LocalizationHelper.getLocalizedString(
                    activity,
                    R.string.biometriccompat_request_perm,
                    title,
                    LocalizationHelper.getLocalizedString(
                        activity,
                        R.string.biometriccompat_allow_notifications_perm
                    )
                )
            )
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { p0, _ ->
                closeFragment()
            }
            .setPositiveButton(
                android.R.string.ok
            ) { p0, _ ->
                p0.dismiss()
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        safeStartActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context?.packageName),

                            )
                        return@setPositiveButton
                    } catch (e: Throwable) {
                        LogCat.logException(e)
                    }
                }

                try {
                    val launchIntent = Intent()
                    launchIntent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    launchIntent.putExtra("app_package", context?.packageName)
                    launchIntent.putExtra("app_uid", context?.applicationInfo?.uid)

                    if (intentCanBeResolved(launchIntent)) {
                        safeStartActivity(launchIntent)
                        return@setPositiveButton
                    }
                } catch (e: Throwable) {
                    LogCat.logException(e)
                }
                try {
                    val i = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                    i.addCategory(Intent.CATEGORY_DEFAULT)
                    if (intentCanBeResolved(i)) {
                        safeStartActivity(i)
                        return@setPositiveButton
                    }
                } catch (e: Throwable) {
                    LogCat.logException(e)
                }
                closeFragment()


            }

        alert.show()
    }

    private val channelNotification = {

        val activity = requireActivity()

        val title = try {
            val appInfo = (if (Utils.isAtLeastT) requireActivity().packageManager.getApplicationInfo(
                requireActivity().application.packageName,
                PackageManager.ApplicationInfoFlags.of(0L)
            ) else requireActivity().packageManager.getApplicationInfo(
                requireActivity().application.packageName,
                0
            ))
            requireActivity().packageManager.getApplicationLabel(appInfo).ifEmpty{
                getString(appInfo.labelRes)
            }
        } catch (e: Throwable) {
            "Unknown"
        }
        val alert = AlertDialog.Builder(requireActivity())
            .setTitle(title)
            .setMessage(
                LocalizationHelper.getLocalizedString(
                    activity,
                    R.string.biometriccompat_request_perm,
                    title,
                    LocalizationHelper.getLocalizedString(
                        activity,
                        R.string.biometriccompat_allow_notifications_channel_perm
                    )
                )
            )
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { p0, _ ->
                closeFragment()
            }
            .setPositiveButton(
                android.R.string.ok
            ) { p0, _ ->
                p0.dismiss()
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        val channelId = arguments?.getString(CHANNEL_ID)
                        safeStartActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context?.packageName)
                                .putExtra(
                                    Settings.EXTRA_CHANNEL_ID,
                                    channelId
                                )
                        )

                        return@setPositiveButton
                    } catch (e: Throwable) {
                        LogCat.logException(e)
                    }
                }
                try {
                    val launchIntent = Intent()
                    launchIntent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    launchIntent.putExtra("app_package", context?.packageName)
                    launchIntent.putExtra("app_uid", context?.applicationInfo?.uid)

                    if (intentCanBeResolved(launchIntent)) {
                        safeStartActivity(launchIntent)
                        return@setPositiveButton
                    }
                } catch (e: Throwable) {
                    LogCat.logException(e)
                }
                try {
                    val i = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                    i.addCategory(Intent.CATEGORY_DEFAULT)
                    if (intentCanBeResolved(i)) {
                        safeStartActivity(i)
                        return@setPositiveButton
                    }
                } catch (e: Throwable) {
                    LogCat.logException(e)
                }
                closeFragment()

            }

        alert.show()
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val type = PermissionRequestController.PermissionType.values().firstOrNull {
            it.name == arguments?.getString(PERMISSION_KEY)
        }

        when (type) {
            PermissionRequestController.PermissionType.GENERAL_PERMISSION -> {
                if (!PermissionUtils.hasSelfPermissions("android.permission.POST_NOTIFICATIONS")) {
                    startForResultForPermissions.launch(listOf("android.permission.POST_NOTIFICATIONS").toTypedArray())
                    return
                } else if (!PermissionUtils.isAllowedNotificationsPermission) {
                    generalNotification.invoke()
                    return
                }
            }
            PermissionRequestController.PermissionType.CHANNEL_PERMISSION -> {
                channelNotification.invoke()
                return
            }
            else -> {
                ExecutorHelper.postDelayed({
                    closeFragment()
                }, 250)
            }
        }
    }
    override fun onDestroyView() {
        super.onDestroyView()
        startForResult.unregister()
        startForResultForPermissions.unregister()
    }
    private fun closeFragment() {
        val channelId = arguments?.getString(CHANNEL_ID)
        val type = PermissionRequestController.PermissionType.values().firstOrNull {
            it.name == arguments?.getString(PERMISSION_KEY)
        }
        val tag = "${NotificationPermissionsFragment::class.java.name}-${type?.name}-$channelId"
        activity?.supportFragmentManager?.findFragmentByTag(tag) ?: return
        try {
            activity?.supportFragmentManager?.beginTransaction()
                ?.remove(this@NotificationPermissionsFragment)
                ?.commitNowAllowingStateLoss()
        } catch (e: Throwable) {
            LogCat.logException(e)
        } finally {
            BroadcastTools.sendGlobalBroadcastIntent(AndroidContext.appContext, Intent(INTENT_KEY))
        }
    }

    private fun safeStartActivity(
        intent: Intent
    ): Boolean {
        try {
            if (intentCanBeResolved(intent)) {
                startForResult.launch(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            }
        } catch (throwable: Throwable) {
            LogCat.logException(throwable)
        }
        return false
    }

    private fun intentCanBeResolved(intent: Intent): Boolean {
        val pm = context?.packageManager
        val pkgAppsList = pm?.queryIntentActivities(intent, 0) ?: emptyList()
        return pkgAppsList.isNotEmpty()
    }

}
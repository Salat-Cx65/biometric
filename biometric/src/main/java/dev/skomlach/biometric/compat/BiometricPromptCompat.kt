/*
 *  Copyright (c) 2021 Sergey Komlach aka Salat-Cx65; Original project: https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
 *  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.skomlach.biometric.compat

import android.content.DialogInterface
import android.graphics.Color
import android.os.Build
import android.os.Looper
import androidx.annotation.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.skomlach.biometric.compat.BiometricManagerCompat.hasEnrolled
import dev.skomlach.biometric.compat.BiometricManagerCompat.isBiometricEnrollChanged
import dev.skomlach.biometric.compat.BiometricManagerCompat.isBiometricSensorPermanentlyLocked
import dev.skomlach.biometric.compat.BiometricManagerCompat.isHardwareDetected
import dev.skomlach.biometric.compat.BiometricManagerCompat.isLockOut
import dev.skomlach.biometric.compat.engine.AuthenticationFailureReason
import dev.skomlach.biometric.compat.engine.BiometricAuthentication
import dev.skomlach.biometric.compat.engine.BiometricInitListener
import dev.skomlach.biometric.compat.engine.BiometricMethod
import dev.skomlach.biometric.compat.engine.core.interfaces.BiometricModule
import dev.skomlach.biometric.compat.impl.BiometricPromptApi28Impl
import dev.skomlach.biometric.compat.impl.BiometricPromptGenericImpl
import dev.skomlach.biometric.compat.impl.IBiometricPromptImpl
import dev.skomlach.biometric.compat.impl.PermissionsFragment
import dev.skomlach.biometric.compat.utils.DeviceUnlockedReceiver
import dev.skomlach.biometric.compat.utils.DialogMainColor
import dev.skomlach.biometric.compat.utils.HardwareAccessImpl
import dev.skomlach.biometric.compat.utils.WideGamutBug
import dev.skomlach.biometric.compat.utils.activityView.ActivityViewWatcher
import dev.skomlach.biometric.compat.utils.device.DeviceInfo
import dev.skomlach.biometric.compat.utils.device.DeviceInfoManager
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl
import dev.skomlach.biometric.compat.utils.notification.BiometricNotificationManager
import dev.skomlach.biometric.compat.utils.statusbar.StatusBarTools
import dev.skomlach.common.contextprovider.AndroidContext
import dev.skomlach.common.logging.LogCat
import dev.skomlach.common.misc.ExecutorHelper
import dev.skomlach.common.misc.isActivityFinished
import dev.skomlach.common.misc.multiwindow.MultiWindowSupport
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.HashSet

class BiometricPromptCompat private constructor(private val builder: Builder) {
    companion object {
        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    HiddenApiBypass.setHiddenApiExemptions("L");
                }
            } catch (e : Throwable){
                e.printStackTrace()
            }
        }
        @JvmStatic
        val availableAuthRequests = ArrayList<BiometricAuthRequest>()
        @JvmStatic
        fun logging(enabled: Boolean){
            LogCat.DEBUG = enabled
            BiometricLoggerImpl.DEBUG = enabled
        }
        private val pendingTasks: MutableList<Runnable?> = Collections.synchronizedList(ArrayList<Runnable?>())
        private var isBiometricInit = AtomicBoolean(false)
        var isInit = false
            get() = isBiometricInit.get()
            private set
        private var initInProgress = AtomicBoolean(false)
        var deviceInfo: DeviceInfo?=null
        get() {
            if(field == null && !isDeviceInfoCheckInProgress){
                ExecutorHelper.INSTANCE.startOnBackground{
                    isDeviceInfoCheckInProgress = true
                        DeviceInfoManager.INSTANCE.getDeviceInfo(object  : DeviceInfoManager.OnDeviceInfoListener{
                            override fun onReady(info: DeviceInfo?) {
                                isDeviceInfoCheckInProgress = false
                                field = info
                            }
                        })
                    }
            }
            return field
        }
        @Volatile
        private var isDeviceInfoCheckInProgress = false
        @MainThread
        @JvmStatic
        fun init(execute: Runnable? = null) {
            if (Looper.getMainLooper().thread !== Thread.currentThread())
                throw IllegalThreadStateException("Main Thread required")

            if (isBiometricInit.get()) {
                BiometricLoggerImpl.d("BiometricPromptCompat.init() - ready")
                execute?.let { ExecutorHelper.INSTANCE.handler.post(it) }
            } else {
                if (initInProgress.get()) {
                    BiometricLoggerImpl.d("BiometricPromptCompat.init() - pending")
                    pendingTasks.add(execute)
                } else {
                    BiometricLoggerImpl.d("BiometricPromptCompat.init()")
                    isBiometricInit.set(false)
                    initInProgress.set(true)
                    pendingTasks.add(execute)
                    AndroidContext.appContext
                    startBiometricInit()
                    ExecutorHelper.INSTANCE.startOnBackground{
                        isDeviceInfoCheckInProgress = true
                        DeviceInfoManager.INSTANCE.getDeviceInfo(object  : DeviceInfoManager.OnDeviceInfoListener{
                            override fun onReady(info: DeviceInfo?) {
                                isDeviceInfoCheckInProgress = false
                                deviceInfo = info
                            }
                        })
                    }
                    DeviceUnlockedReceiver.registerDeviceUnlockListener()
                }
            }
        }
        @MainThread
        @JvmStatic
        private fun startBiometricInit(){
            BiometricAuthentication.init(object : BiometricInitListener {
                override fun initFinished(
                    method: BiometricMethod,
                    module: BiometricModule?
                ) {
                }

                override fun onBiometricReady() {
                    BiometricLoggerImpl.d("BiometricPromptCompat.init() - finished")
                    isBiometricInit.set(true)
                    initInProgress.set(false)
                    //Add default first
                    var biometricAuthRequest = BiometricAuthRequest()
                    if (isHardwareDetected(biometricAuthRequest)) {
                        availableAuthRequests.add(biometricAuthRequest)
                    }

                    for (api in BiometricApi.values()) {
                        for (type in BiometricType.values()) {
                            if (type == BiometricType.BIOMETRIC_ANY)
                                continue
                            biometricAuthRequest = BiometricAuthRequest(api, type)
                            if (isHardwareDetected(biometricAuthRequest)) {
                                availableAuthRequests.add(biometricAuthRequest)
                                //just cache value
                                hasEnrolled(biometricAuthRequest)
                                isLockOut(biometricAuthRequest)
                                isBiometricEnrollChanged(biometricAuthRequest)
                            }
                        }
                    }

                    for (task in pendingTasks) {
                        task?.let { ExecutorHelper.INSTANCE.handler.post(it) }
                    }
                    pendingTasks.clear()
                }
            })

        }
    }

    private val impl: IBiometricPromptImpl by lazy {
        val isBiometricPrompt = builder.biometricAuthRequest.api == BiometricApi.BIOMETRIC_API ||
            if (builder.biometricAuthRequest.api == BiometricApi.AUTO && HardwareAccessImpl.getInstance(builder.biometricAuthRequest).isNewBiometricApi) {
                var found = false
                for (v in builder.primaryAvailableTypes) {
                    val request = BiometricAuthRequest(BiometricApi.BIOMETRIC_API, v)
                    if (isHardwareDetected(request) && hasEnrolled(request) && (!isLockOut(request) && !isBiometricSensorPermanentlyLocked(request)))  {
                        found = true
                        break
                    }
                }
                found
            } else {
                false
            }
        BiometricLoggerImpl.d(
            "BiometricPromptCompat.IBiometricPromptImpl - " +
                    "$isBiometricPrompt"
        )
        val iBiometricPromptImpl = if (isBiometricPrompt) {
            BiometricPromptApi28Impl(builder)
        } else {
            BiometricPromptGenericImpl(builder)
        }
        iBiometricPromptImpl
    }

    fun authenticate(callbackOuter: Result) {
        if(isActivityFinished(builder.context)){
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause of Activity destroyed")
            return
        }
        BiometricLoggerImpl.d("BiometricPromptCompat.authenticate()")
        WideGamutBug.checkColorMode(builder.context)
        val startTime = System.currentTimeMillis()
        var timeout = false
        ExecutorHelper.INSTANCE.startOnBackground {
            while (isDeviceInfoCheckInProgress || !isInit) {
                timeout = System.currentTimeMillis() - startTime >= TimeUnit.SECONDS.toMillis(5)
                if(timeout) {
                    break
                }
                try {
                    Thread.sleep(250)
                } catch (ignore: InterruptedException) {
                }
            }
            ExecutorHelper.INSTANCE.handler.post {
                if(timeout) {
                    callbackOuter.onFailed(AuthenticationFailureReason.INTERNAL_ERROR)
                }
                else
                    startAuth(callbackOuter)
            }
        }
    }

    private fun startAuth(callbackOuter: Result) {
        if(isActivityFinished(builder.context)){
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause of Activity destroyed")
            callbackOuter.onFailed(AuthenticationFailureReason.INTERNAL_ERROR)
            return
        }
        BiometricLoggerImpl.d("BiometricPromptCompat.startAuth")
        val activityViewWatcher = ActivityViewWatcher(impl.builder, object : ActivityViewWatcher.ForceToCloseCallback{
            override fun onCloseBiometric() {
                cancelAuthenticate()
            }
        })

        val callback = object : Result {

            var isOpened = false
            override fun onSucceeded(confirmed : Set<BiometricType>) {
                if(builder.biometricAuthRequest.api != BiometricApi.AUTO) {
                    HardwareAccessImpl.getInstance(builder.biometricAuthRequest)
                        .updateBiometricEnrollChanged()
                } else{
                    HardwareAccessImpl.getInstance(BiometricAuthRequest(BiometricApi.BIOMETRIC_API, builder.biometricAuthRequest.type))
                        .updateBiometricEnrollChanged()
                    HardwareAccessImpl.getInstance(BiometricAuthRequest(BiometricApi.LEGACY_API, builder.biometricAuthRequest.type))
                        .updateBiometricEnrollChanged()
                }

                callbackOuter.onSucceeded(confirmed)
                onUIClosed()
            }

            override fun onCanceled() {
                callbackOuter.onCanceled()
                onUIClosed()
            }

            override fun onFailed(reason: AuthenticationFailureReason?) {
                callbackOuter.onFailed(reason)
                onUIClosed()
            }

            override fun onUIOpened() {
                if(!isOpened) {
                    isOpened = true
                    builder.multiWindowSupport.start()
                    callbackOuter.onUIOpened()
                    if (builder.notificationEnabled) {
                        BiometricNotificationManager.INSTANCE.showNotification(builder)
                    }
                    if (impl is BiometricPromptApi28Impl) {
                        StatusBarTools.setNavBarAndStatusBarColors(
                            builder.context.window,
                            ContextCompat.getColor(builder.context, getDialogMainColor()),
                            ContextCompat.getColor(builder.context, android.R.color.darker_gray),
                            builder.colorStatusBar
                        )
                    }
                    activityViewWatcher.setupListeners()
                }
            }

            override fun onUIClosed() {
                if(isOpened) {
                    isOpened = false
                    builder.multiWindowSupport.finish()
                    StatusBarTools.setNavBarAndStatusBarColors(
                        builder.context.window,
                        builder.colorNavBar,
                        builder.dividerColor,
                        builder.colorStatusBar
                    )

                    if (builder.notificationEnabled) {
                        BiometricNotificationManager.INSTANCE.dismissAll()
                    }
                    activityViewWatcher.resetListeners()
                    callbackOuter.onUIClosed()
                }
            }
        }

        if (!isHardwareDetected(impl.builder.biometricAuthRequest)) {
            callback.onFailed(AuthenticationFailureReason.NO_HARDWARE)
            return
        }
        if (!hasEnrolled(impl.builder.biometricAuthRequest)) {
            callback.onFailed(AuthenticationFailureReason.NO_BIOMETRICS_REGISTERED)
            return
        }
        if (isLockOut(impl.builder.biometricAuthRequest)) {
            callback.onFailed(AuthenticationFailureReason.LOCKED_OUT)
            return
        }
        if (isBiometricSensorPermanentlyLocked(impl.builder.biometricAuthRequest)) {
            callback.onFailed(AuthenticationFailureReason.HARDWARE_UNAVAILABLE)
            return
        }
        BiometricLoggerImpl.d("BiometricPromptCompat. start PermissionsFragment.askForPermissions")
        PermissionsFragment.askForPermissions(
            impl.builder.context,
            impl.usedPermissions
        ) { authenticateInternal(callback) }
    }

    private fun authenticateInternal(callback: Result) {
        BiometricLoggerImpl.d("BiometricPromptCompat.authenticateInternal()")
        if(isActivityFinished(builder.context)){
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause of Activity destroyed")
            callback.onFailed(AuthenticationFailureReason.INTERNAL_ERROR)
            return
        }
        try {
            BiometricLoggerImpl.d("BiometricPromptCompat.authenticateInternal() - impl.authenticate")
            impl.authenticate(callback)
        } catch (ignore: IllegalStateException) {
            callback.onFailed(AuthenticationFailureReason.INTERNAL_ERROR)
        }
    }

    fun cancelAuthenticate() {
        ExecutorHelper.INSTANCE.startOnBackground {
            while (isDeviceInfoCheckInProgress || !isInit) {
                try {
                    Thread.sleep(250)
                } catch (ignore: InterruptedException) {
                }
            }
            ExecutorHelper.INSTANCE.handler.post {
                impl.cancelAuthenticate() }
        }

    }

    fun cancelAuthenticateBecauseOnPause(): Boolean {
        return if(!isInit){
            ExecutorHelper.INSTANCE.startOnBackground {
                while (isDeviceInfoCheckInProgress  || !isInit) {
                    try {
                        Thread.sleep(250)
                    } catch (ignore: InterruptedException) {
                    }
                }
                ExecutorHelper.INSTANCE.handler.post {
                    impl.cancelAuthenticate() }
            }
            true
        } else
            impl.cancelAuthenticateBecauseOnPause()
    }

    @ColorRes
    fun getDialogMainColor(): Int {
       return DialogMainColor.getColor(impl.isNightMode)
    }

    interface Result {
        @MainThread
        fun onSucceeded(confirmed : Set<BiometricType>)

        @MainThread
        fun onCanceled()

        @MainThread
        fun onFailed(reason: AuthenticationFailureReason?)

        @MainThread
        fun onUIOpened()
        @MainThread
        fun onUIClosed()
    }

    class Builder(
        @field:RestrictTo(RestrictTo.Scope.LIBRARY) val biometricAuthRequest: BiometricAuthRequest,
        @field:RestrictTo(
            RestrictTo.Scope.LIBRARY
        ) val context: FragmentActivity
    ) {
        val allAvailableTypes: HashSet<BiometricType> by lazy {
            val types = HashSet<BiometricType>()
            types.addAll(primaryAvailableTypes)
            types.addAll(secondaryAvailableTypes)
            types
        }
        val primaryAvailableTypes: HashSet<BiometricType> by lazy {
            val types = HashSet<BiometricType>()
            val api = if(HardwareAccessImpl.getInstance(biometricAuthRequest).isNewBiometricApi) BiometricApi.BIOMETRIC_API else BiometricApi.LEGACY_API
            if (biometricAuthRequest.type == BiometricType.BIOMETRIC_ANY) {
                for (type in BiometricType.values()) {
                    if (type == BiometricType.BIOMETRIC_ANY)
                        continue
                    val request = BiometricAuthRequest(
                        api,
                        type
                    )
                    BiometricLoggerImpl.d("primaryAvailableTypes - $request -> ${isHardwareDetected(request)}")
                    if (isHardwareDetected(request) && hasEnrolled(request) && (!isLockOut(request) && !isBiometricSensorPermanentlyLocked(request))) {
                        types.add(type)
                    }
                }
            } else {
                if (isHardwareDetected(biometricAuthRequest) && hasEnrolled(biometricAuthRequest)&& (!isLockOut(biometricAuthRequest) && !isBiometricSensorPermanentlyLocked(biometricAuthRequest)))
                types.add(biometricAuthRequest.type)
            }
            types
        }
        val secondaryAvailableTypes: HashSet<BiometricType> by lazy {
            val types = HashSet<BiometricType>()
            if(HardwareAccessImpl.getInstance(biometricAuthRequest).isNewBiometricApi) {
                if (biometricAuthRequest.type == BiometricType.BIOMETRIC_ANY) {
                    for (type in BiometricType.values()) {
                        if (type == BiometricType.BIOMETRIC_ANY)
                            continue
                        val request = BiometricAuthRequest(
                            BiometricApi.LEGACY_API,
                            type
                        )
                        BiometricLoggerImpl.d("secondaryAvailableTypes - $request -> ${isHardwareDetected(request)}")
                        if (isHardwareDetected(request) && hasEnrolled(request) && (!isLockOut(request) && !isBiometricSensorPermanentlyLocked(request))) {
                            types.add(type)
                        }
                    }
                } else {
                    if (isHardwareDetected(biometricAuthRequest) && hasEnrolled(biometricAuthRequest)&& (!isLockOut(biometricAuthRequest) && !isBiometricSensorPermanentlyLocked(biometricAuthRequest)))
                        types.add(biometricAuthRequest.type)
                }
            }
            types
        }
        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        var title: CharSequence? = null

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        var subtitle: CharSequence? = null

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        var description: CharSequence? = null

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        var negativeButtonText: CharSequence? = null

        @RestrictTo(RestrictTo.Scope.LIBRARY)
        var negativeButtonListener: DialogInterface.OnClickListener? = null

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        var multiWindowSupport: MultiWindowSupport = MultiWindowSupport(context)

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        var notificationEnabled = true

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        @ColorInt
        var colorNavBar: Int = Color.TRANSPARENT
        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        @ColorInt
        var dividerColor: Int = Color.TRANSPARENT

        @JvmField @RestrictTo(RestrictTo.Scope.LIBRARY)
        @ColorInt
        var colorStatusBar: Int = Color.TRANSPARENT
        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                this.colorNavBar = context.window.navigationBarColor
                this.colorStatusBar = context.window.statusBarColor
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dividerColor = context.window.navigationBarDividerColor
            }
       }
        constructor(context: FragmentActivity) : this(
            BiometricAuthRequest(
                BiometricApi.AUTO,
                BiometricType.BIOMETRIC_ANY
            ), context
        ) {
        }

        fun setEnabledNotification(enabled: Boolean): Builder {
            this.notificationEnabled = enabled
            return this
        }
        fun setTitle(title: CharSequence?): Builder {
            this.title = title
            return this
        }

        fun setTitle(@StringRes titleRes: Int): Builder {
            title = context.getString(titleRes)
            return this
        }

        fun setSubtitle(subtitle: CharSequence?): Builder {
            this.subtitle = subtitle
            return this
        }

        fun setSubtitle(@StringRes subtitleRes: Int): Builder {
            subtitle = context.getString(subtitleRes)
            return this
        }

        fun setDescription(description: CharSequence?): Builder {
            this.description = description
            return this
        }

        fun setDescription(@StringRes descriptionRes: Int): Builder {
            description = context.getString(descriptionRes)
            return this
        }

        fun setNegativeButton(
            text: CharSequence,
            listener: DialogInterface.OnClickListener?
        ): Builder {
            negativeButtonText = text
            negativeButtonListener = listener
            return this
        }

        fun setNegativeButton(
            @StringRes textResId: Int,
            listener: DialogInterface.OnClickListener?
        ): Builder {
            negativeButtonText = context.getString(textResId)
            negativeButtonListener = listener
            return this
        }

        fun build(): BiometricPromptCompat {
            requireNotNull(title) { "You should set a title for BiometricPrompt." }
            requireNotNull(negativeButtonText) { "You should set a negativeButtonText for BiometricPrompt." }
            return BiometricPromptCompat(this)
        }
    }
}
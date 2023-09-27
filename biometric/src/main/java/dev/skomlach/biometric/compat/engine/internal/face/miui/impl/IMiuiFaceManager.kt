/*
 *  Copyright (c) 2023 Sergey Komlach aka Salat-Cx65; Original project https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
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

package dev.skomlach.biometric.compat.engine.internal.face.miui.impl

import android.graphics.Rect
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.Handler
import android.view.Surface

interface IMiuiFaceManager {
    companion object {
        const val TEMPLATE_INVALIDATE = -1
        const val TEMPLATE_NONE = 0
        const val TEMPLATE_SERVICE_NOT_INIT = -2
        const val TEMPLATE_VALIDATE = 1
    }

    abstract class AuthenticationCallback {
        open fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {}
        open fun onAuthenticationHelp(helpCode: Int, helpString: CharSequence?) {}
        open fun onAuthenticationSucceeded(face: Miuiface?) {}
        open fun onAuthenticationFailed() {}
    }

    abstract class EnrollmentCallback {
        fun onEnrollmentError(errMsgId: Int, errString: CharSequence?) {}
        fun onEnrollmentHelp(helpMsgId: Int, helpString: CharSequence?) {}
        fun onEnrollmentProgress(remaining: Int, faceId: Int) {}
    }

    abstract class LockoutResetCallback {
        fun onLockoutReset() {}
    }

    abstract class RemovalCallback {
        fun onRemovalError(face: Miuiface?, errMsgId: Int, errString: CharSequence?) {}
        fun onRemovalSucceeded(face: Miuiface?, remaining: Int) {}
    }

    fun addLockoutResetCallback(lockoutResetCallback: LockoutResetCallback?)
    fun authenticate(
        cancellationSignal: CancellationSignal?,
        i: Int,
        authenticationCallback: AuthenticationCallback?,
        handler: Handler?,
        i2: Int
    )

    fun enroll(
        bArr: ByteArray?,
        cancellationSignal: CancellationSignal,
        i: Int,
        enrollmentCallback: EnrollmentCallback?,
        surface: Surface?,
        rect: Rect?,
        i2: Int
    )

    fun enroll(
        bArr: ByteArray?,
        cancellationSignal: CancellationSignal,
        i: Int,
        enrollmentCallback: EnrollmentCallback?,
        surface: Surface?,
        rectF: RectF?,
        rectF2: RectF,
        i2: Int
    )

    fun extCmd(i: Int, i2: Int): Int
    val enrolledFaces: List<Miuiface?>?
    val managerVersion: Int
    val vendorInfo: String?
    fun hasEnrolledFaces(): Int
    val isFaceFeatureSupport: Boolean
    val isFaceUnlockInited: Boolean
    val isReleased: Boolean
    val isSupportScreenOnDelayed: Boolean
    fun preInitAuthen()
    fun release()
    fun remove(miuiface: Miuiface, removalCallback: RemovalCallback)
    fun rename(i: Int, str: String)
    fun resetTimeout(bArr: ByteArray)
}
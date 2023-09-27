/*
 *  Copyright (c) 2021 Sergey Komlach aka Salat-Cx65; Original project https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
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
package dev.skomlach.biometric.compat.utils.activityView

import dev.skomlach.biometric.compat.BiometricType
import dev.skomlach.common.misc.ExecutorHelper
import java.util.Collections

object IconStateHelper {
    private val iconsTasks = Collections.synchronizedMap(HashMap<BiometricType?, Runnable>())
    private val listeners = Collections.synchronizedSet(HashSet<IconStateListener>())
    fun registerListener(stateListener: IconStateListener) {
        listeners.add(stateListener)
    }

    fun unregisterListener(stateListener: IconStateListener) {
        listeners.remove(stateListener)
    }

    fun errorType(type: BiometricType?) {
        ExecutorHelper.post {
            for (stateListener in listeners) {
                stateListener.onError(type)
            }
            var task = iconsTasks[type]
            task?.let { ExecutorHelper.removeCallbacks(it) }
            task = object : Runnable {
                override fun run() {
                    ExecutorHelper.removeCallbacks(this)
                    for (stateListener in listeners) {
                        stateListener.reset(type)
                    }
                }
            }
            ExecutorHelper.postDelayed(task, 2000)
        }
    }

    fun successType(type: BiometricType?) {
        ExecutorHelper.post {
            for (stateListener in listeners) {
                stateListener.onSuccess(type)
            }
        }
    }

    interface IconStateListener {
        fun onError(type: BiometricType?)
        fun onSuccess(type: BiometricType?)
        fun reset(type: BiometricType?)
    }
}
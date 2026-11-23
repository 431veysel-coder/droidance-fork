// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.agent.tools

import com.droidance.core.agent.tools.*

/** Canonical names of the model-visible device primitives. */
object DeviceToolNames {
    const val ANDROID_READ = "android_read"
    const val ANDROID_ACT = "android_act"
    const val SHELL = "shell"
    const val SCREEN_ACT = "screen_act"
    const val WORKSPACE = "workspace"
    const val TASK_MEMO = "task_memo"

    // NEW TOOLS (Fork additions)
    const val BROWSER_ACT = "browser_act"
    const val TERMUX_ACT = "termux_act"
    const val JVM_ACT = "jvm_act"

    val deviceToolNames: Set<String> = linkedSetOf(
        ANDROID_READ, ANDROID_ACT, SHELL, SCREEN_ACT,
        WORKSPACE, TASK_MEMO,
        BROWSER_ACT, TERMUX_ACT, JVM_ACT
    )
}
/* SPDX-License-Identifier: Apache-2.0 */
/*
 * Nemuri - App entry point: starts module-status tracking and hosts the Compose UI.
 *
 * License: Apache-2.0
 *
 * Author: Anatdx
 */

package com.anatdx.nemuri

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.anatdx.nemuri.ui.NemuriTheme
import com.anatdx.nemuri.ui.navigation.NemuriApp
import com.anatdx.nemuri.viewmodel.AppsViewModel
import com.anatdx.nemuri.xposed.XposedServiceStatus

class MainActivity : ComponentActivity() {
    private val appsViewModel: AppsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XposedServiceStatus.start()
        enableEdgeToEdge()
        setContent {
            NemuriTheme {
                NemuriApp(appsViewModel = appsViewModel)
            }
        }
    }
}

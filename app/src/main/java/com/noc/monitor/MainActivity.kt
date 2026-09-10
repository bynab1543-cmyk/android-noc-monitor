package com.noc.monitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.os.LocaleListCompat
import com.noc.monitor.ui.MonitorViewModel
import com.noc.monitor.ui.NocApp
import com.noc.monitor.ui.theme.NocTheme

class MainActivity : ComponentActivity() {
    private val vm: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val lang by vm.language.collectAsState()
            val direction = if (lang == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr
            val locales = LocaleListCompat.forLanguageTags(if (lang == "ar") "ar" else "en")
            AppCompatDelegate.setApplicationLocales(locales)
            NocTheme {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    NocApp(vm)
                }
            }
        }
    }
}

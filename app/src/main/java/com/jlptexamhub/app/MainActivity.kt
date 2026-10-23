package com.jlptexamhub.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.boot(applicationContext)
        setContent {
            AppTheme {
                var update by remember { mutableStateOf<UpdateChecker.Info?>(null) }
                var dismissedTag by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    while (true) {
                        val i = withContext(Dispatchers.IO) { UpdateChecker.check() }
                        if (i != null) update = i
                        delay(5 * 60 * 1000L)
                    }
                }
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Root(vm)
                }
                val u = update
                if (u != null && (u.mandatory || u.tag != dismissedTag)) {
                    UpdateDialog(u) { dismissedTag = u.tag }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.foreground = true
        vm.pollNow()
    }

    override fun onStop() {
        vm.foreground = false
        super.onStop()
    }
}

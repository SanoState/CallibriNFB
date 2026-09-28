package com.callibri.nfb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.callibri.nfb.ui.CallibriNfbTheme
import com.callibri.nfb.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CallibriNfbTheme {
                MainScreen()
            }
        }
    }
}

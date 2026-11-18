package com.localstickers.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import com.localstickers.app.ui.AppNavigation
import com.localstickers.app.ui.theme.LocalStickersTheme

class MainActivity : ComponentActivity() {

    /** Image or GIF shared from another app ("Share" -> Local Stickers). */
    private val sharedUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) sharedUri.value = sharedImage(intent)
        setContent {
            LocalStickersTheme {
                AppNavigation(sharedUri = sharedUri.value, onSharedConsumed = { sharedUri.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedImage(intent)?.let { sharedUri.value = it }
    }

    private fun sharedImage(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    }
}

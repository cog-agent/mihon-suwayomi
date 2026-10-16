package mihon.suwayomi.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.app.di.appGraph

/**
 * Receives `app.mihon.suwayomi:/oauth2redirect`. It hands the URL to the app-scoped session and returns to the app
 * immediately, holding nothing. Intents that do not match a pending sign-in are ignored by the session, so replays
 * and stray intents from other apps are harmless.
 */
class OidcRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.dataString
        if (intent?.action == Intent.ACTION_VIEW && data != null) {
            applicationContext.appGraph.suwayomiAuth.handleRedirectAsync(data)
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
        finish()
    }
}

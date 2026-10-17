package eu.kanade.tachiyomi.ui.main

import android.animation.ValueAnimator
import android.app.SearchManager
import android.app.assist.AssistContent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.animation.doOnEnd
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppStateBanners
import eu.kanade.presentation.components.DownloadedOnlyBannerBackgroundColor
import eu.kanade.presentation.components.IncognitoModeBannerBackgroundColor
import eu.kanade.presentation.components.IndexingBannerBackgroundColor
import eu.kanade.presentation.more.AppMigratingScreen
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import mihon.suwayomi.SuwayomiRouting
import mihon.suwayomi.ui.SuwayomiHomeScreen
import mihon.suwayomi.ui.SuwayomiSettingsScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.isBenchmarkBuildType
import eu.kanade.tachiyomi.util.system.isNavigationBarNeedsScrim
import eu.kanade.tachiyomi.util.system.updaterEnabled
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.AppGraph
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import mihon.core.migration.Migrator
import mihon.domain.database.repository.DatabaseRepository
import mihon.feature.support.SupportUsScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.OpenInNew
import mihon.icons.materialsymbols.roundedfilled.VolunteerActivism
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.time.times

class MainActivity : BaseActivity() {

    private val graph: AppGraph by lazy { metroGraph() }

    @Inject private lateinit var libraryPreferences: LibraryPreferences

    @Inject private lateinit var preferences: BasePreferences

    @Inject private lateinit var downloadCache: DownloadCache

    @Inject private lateinit var chapterCache: ChapterCache

    @Inject private lateinit var databaseRepository: DatabaseRepository

    private val isAppMigrating = MutableStateFlow(Migrator.isRunning)

    @Inject private lateinit var getIncognitoState: GetIncognitoState

    @Inject private lateinit var extensionManager: ExtensionManager

    // To be checked by splash screen. If true then splash screen will be removed.
    var ready = false

    private var navigator: Navigator? = null

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        graph.inject(this)
        val isLaunch = savedInstanceState == null

        // Prevent splash screen showing up on configuration changes
        val splashScreen = if (isLaunch) installSplashScreen() else null

        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            Migrator.awaitAndRelease()
            isAppMigrating.value = false
        }
        // Uncaught on purpose: a failed database migration otherwise leaves the migrating screen up for good,
        // while the crash screen tells what went wrong and lets the logs be shared
        lifecycleScope.launch { databaseRepository.awaitOpen() }

        // Do not let the launcher create a new activity http://stackoverflow.com/questions/16283079
        if (!isTaskRoot) {
            finish()
            return
        }

        setComposeContent {
            val appMigrating by isAppMigrating.collectAsState()
            val databaseMigrating by databaseRepository.isMigrating.collectAsState()
            val migrating = appMigrating || databaseMigrating
            var migratingSince by remember { mutableStateOf(if (migrating) TimeSource.Monotonic.markNow() else null) }
            LaunchedEffect(migrating) {
                val since = migratingSince
                if (migrating) {
                    if (since == null) migratingSince = TimeSource.Monotonic.markNow()
                } else if (since != null) {
                    // A migration that finishes right away would otherwise only flash the screen
                    delay(MIGRATING_SCREEN_MIN_DURATION - since.elapsedNow())
                    migratingSince = null
                }
            }
            if (migrating || migratingSince != null) {
                AppMigratingScreen()
                // Release the splash screen so the reason for the wait shows
                LaunchedEffect(Unit) { ready = true }
                return@setComposeContent
            }

            val context = LocalContext.current

            var incognito by remember { mutableStateOf(false) }
            val downloadOnly by preferences.downloadedOnly.collectAsState()
            val indexing by downloadCache.isInitializing.collectAsState()

            val isSystemInDarkTheme = isSystemInDarkTheme()
            val statusBarBackgroundColor = when {
                indexing -> IndexingBannerBackgroundColor
                downloadOnly -> DownloadedOnlyBannerBackgroundColor
                incognito -> IncognitoModeBannerBackgroundColor
                else -> MaterialTheme.colorScheme.surface
            }
            LaunchedEffect(isSystemInDarkTheme, statusBarBackgroundColor) {
                // Draw edge-to-edge and set system bars color to transparent
                val lightStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                val darkStyle = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(
                    statusBarStyle = if (statusBarBackgroundColor.luminance() > 0.5) lightStyle else darkStyle,
                    navigationBarStyle = if (isSystemInDarkTheme) darkStyle else lightStyle,
                )
            }

            Navigator(
                screen = SuwayomiHomeScreen,
                disposeBehavior = NavigatorDisposeBehavior(disposeNestedNavigators = false, disposeSteps = true),
            ) { navigator ->
                LaunchedEffect(navigator) {
                    this@MainActivity.navigator = navigator

                    if (isLaunch) {
                        // Set start screen
                        handleIntentAction(intent, navigator)

                        // Reset Incognito Mode on relaunch
                        preferences.incognitoMode.set(false)
                    }
                }

                val scaffoldInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)
                Scaffold(
                    topBar = {
                        AppStateBanners(
                            downloadedOnlyMode = downloadOnly,
                            incognitoMode = incognito,
                            indexing = indexing,
                            modifier = Modifier.windowInsetsPadding(scaffoldInsets),
                        )
                    },
                    contentWindowInsets = scaffoldInsets,
                ) { contentPadding ->
                    // Consume insets already used by app state banners
                    Box {
                        // Shows current screen
                        DefaultNavigatorScreenTransition(
                            navigator = navigator,
                            modifier = Modifier
                                .padding(contentPadding)
                                .consumeWindowInsets(contentPadding),
                        )

                        // Draw navigation bar scrim when needed
                        if (remember { isNavigationBarNeedsScrim() }) {
                            Spacer(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                                    .alpha(0.8f)
                                    .background(MaterialTheme.colorScheme.surfaceContainer),
                            )
                        }
                    }
                }


                HandleOnNewIntent(context = context, navigator = navigator)

            }
        }

        val startTime = System.currentTimeMillis()
        splashScreen?.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            elapsed <= SPLASH_MIN_DURATION || (!ready && elapsed <= SPLASH_MAX_DURATION)
        }
        setSplashScreenExitAnimation(splashScreen)

        if (isLaunch && libraryPreferences.autoClearChapterCache.get()) {
            lifecycleScope.launch(Dispatchers.IO) {
                chapterCache.clear()
            }
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        when (val screen = navigator?.lastItem) {
            is AssistContentScreen -> {
                screen.onProvideAssistUrl()?.let { outContent.webUri = it.toUri() }
            }
        }
    }

    override fun reportFullyDrawn() {
        try {
            super.reportFullyDrawn()
        } catch (e: SecurityException) {
            // Some Samsung Android 9 to 11 builds deny the platform's fully drawn broadcast
            // and the report only feeds startup metrics, so it shouldn't crash the app
            logcat(LogPriority.WARN, e)
        }
    }

    @Composable
    private fun HandleOnNewIntent(context: Context, navigator: Navigator) {
        LaunchedEffect(Unit) {
            callbackFlow {
                val componentActivity = context as ComponentActivity
                val consumer = Consumer<Intent> { trySend(it) }
                componentActivity.addOnNewIntentListener(consumer)
                awaitClose { componentActivity.removeOnNewIntentListener(consumer) }
            }
                .collectLatest { handleIntentAction(it, navigator) }
        }
    }

    private fun setSplashScreenExitAnimation(splashScreen: SplashScreen?) {
        val root = findViewById<View>(android.R.id.content)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && splashScreen != null) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT

            splashScreen.setOnExitAnimationListener { splashProvider ->
                // For some reason the SplashScreen applies (incorrect) Y translation to the iconView
                splashProvider.iconView.translationY = 0F

                val activityAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = LinearOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        root.translationY = value * 16.dpToPx
                    }
                }

                val splashAnim = ValueAnimator.ofFloat(1F, 0F).apply {
                    interpolator = FastOutSlowInInterpolator()
                    duration = SPLASH_EXIT_ANIM_DURATION
                    addUpdateListener { va ->
                        val value = va.animatedValue as Float
                        splashProvider.view.alpha = value
                    }
                    doOnEnd {
                        splashProvider.remove()
                    }
                }

                activityAnim.start()
                splashAnim.start()
            }
        }
    }

    private fun handleIntentAction(intent: Intent, navigator: Navigator): Boolean {
        val notificationId = intent.getIntExtra("notificationId", -1)
        if (notificationId > -1) {
            NotificationReceiver.dismissNotification(
                applicationContext,
                notificationId,
                intent.getIntExtra("groupId", 0),
            )
        }

        if (intent.action == Intent.ACTION_APPLICATION_PREFERENCES) {
            navigator.popUntilRoot()
            navigator.push(SuwayomiSettingsScreen)
            ready = true
            return true
        }
        // Shortcuts select a tab of the remote client; anything that belonged to the local library has no target.
        val tab = SuwayomiRouting.tabForAction(intent.action) ?: return false
        navigator.popUntilRoot()
        SuwayomiRouting.request(tab)
        ready = true
        return true
    }
}

// Splash screen
private const val SPLASH_MIN_DURATION = 500 // ms
private const val SPLASH_MAX_DURATION = 5000 // ms
private const val SPLASH_EXIT_ANIM_DURATION = 400L // ms

private val MIGRATING_SCREEN_MIN_DURATION = 1.seconds
private val APP_UPDATE_CHECK_INTERVAL = 6.hours

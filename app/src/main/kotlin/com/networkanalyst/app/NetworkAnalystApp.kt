package com.networkanalyst.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.networkanalyst.app.data.ConnectionTester
import com.networkanalyst.app.data.ConnectivityCollector
import com.networkanalyst.app.data.ScanCoordinator
import com.networkanalyst.app.data.WifiCollector
import com.networkanalyst.app.data.WifiEventMonitor
import com.networkanalyst.core.connect.EventLogStore
import com.networkanalyst.core.history.HistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * The objects that must be shared between the screens and the background watch service:
 * one view of the network, one event log.
 */
class AppGraph(context: Context) {
    val connectivity = ConnectivityCollector(context)
    val wifi = WifiCollector(context, connectivity)
    val coordinator = ScanCoordinator(context, connectivity, wifi)
    val history = HistoryStore(File(context.filesDir, "history.json"))
    val monitor = WifiEventMonitor(context, connectivity, wifi, EventLogStore(File(context.filesDir, "wifi-events.json")))
    val tester = ConnectionTester(context, monitor)

    /** True while the background watch service is running. */
    val watchingInBackground = MutableStateFlow(false)

    /** Set when the background watch could not be started, for the screen to show. */
    val watchError = MutableStateFlow<String?>(null)

    init {
        connectivity.start()
        monitor.start()
    }

    /**
     * Notes in the log when the app stops and resumes being able to watch. Android holds
     * Wi-Fi status updates back from an app that is not active and hands them over together
     * when it is opened again, so what is recorded right after reopening may be older than
     * its timestamp says. The log should say so rather than look precise.
     */
    fun onAppVisible(visible: Boolean) {
        // With the background service running the app stays active and misses nothing.
        if (watchingInBackground.value) return
        monitor.note(if (visible) NOTE_REOPENED else NOTE_HIDDEN)
    }

    companion object {
        const val NOTE_HIDDEN = "App left the screen. Not watching until it is reopened (background watching is off)."
        const val NOTE_REOPENED = "App reopened. Android holds Wi-Fi updates back while the app is closed, so entries in the next few seconds may have happened earlier."
    }
}

class NetworkAnalystApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    private var startedActivities = 0
    private var hidden = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0 && hidden) {
                    hidden = false
                    graph.onAppVisible(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                // A rotation stops and restarts the screen; that is not leaving the app.
                if (startedActivities == 0 && !activity.isChangingConfigurations) {
                    hidden = true
                    graph.onAppVisible(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

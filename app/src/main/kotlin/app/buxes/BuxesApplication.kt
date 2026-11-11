package app.buxes

import android.app.Application
import app.buxes.player.service.PlaybackDependencies

class BuxesApplication : Application(), PlaybackDependencies.Provider {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override val playbackDependencies: PlaybackDependencies
        get() = graph.playbackDependencies
}

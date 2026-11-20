package app.buxes

import android.app.Application
import app.buxes.player.service.PlaybackDependencies

class BuxesApplication : Application(), PlaybackDependencies.Provider {

    lateinit var graph: AppGraph
        private set

    /** The Island over other apps (Buxes); lives as long as the process. */
    lateinit var islandOverlay: app.buxes.island.IslandOverlay
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        islandOverlay = app.buxes.island.IslandOverlay(this, graph)
    }

    override val playbackDependencies: PlaybackDependencies
        get() = graph.playbackDependencies
}

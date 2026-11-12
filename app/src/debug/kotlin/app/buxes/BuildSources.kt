package app.buxes

import android.content.Context
import app.buxes.sources.api.MusicSource

/** Release builds ship only real sources. */
internal fun buildVariantSources(context: Context): List<MusicSource> = emptyList()

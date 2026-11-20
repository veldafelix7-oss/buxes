package app.buxes.player.service

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The audio cache (Buxes): the streams the phone has played, kept on disk up to a modest budget,
 * least recently played dropped first. A song heard again starts at once and costs no data; the
 * cache sits under the app's cache directory, so the system may clear it when space is short.
 * Local files and live stations pass straight through.
 */
object AudioCache {
    private const val BUDGET_BYTES = 200L * 1024 * 1024
    const val NO_CACHE_KEY = "buxes:nocache"

    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "audio"),
            LeastRecentlyUsedCacheEvictor(BUDGET_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    fun wrap(context: Context, upstream: DataSource.Factory): DataSource.Factory {
        val simple = cache(context)
        val cached = CacheDataSource.Factory()
            .setCache(simple)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setCacheWriteDataSinkFactory(
                androidx.media3.datasource.cache.CacheDataSink.Factory().setCache(simple).setFragmentSize(4L * 1024 * 1024),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { Switching(cached.createDataSource(), upstream.createDataSource()) }
    }

    /** Streams go through the cache; files, content and live go straight to the upstream. */
    private class Switching(private val cached: DataSource, private val direct: DataSource) : DataSource {
        private var open: DataSource? = null

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            cached.addTransferListener(transferListener)
            direct.addTransferListener(transferListener)
        }

        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            val stream = (scheme == "http" || scheme == "https") && dataSpec.key != NO_CACHE_KEY
            val chosen = if (stream) cached else direct
            open = chosen
            return chosen.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(open).read(buffer, offset, length)
        override fun getUri(): android.net.Uri? = open?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = open?.responseHeaders ?: emptyMap()
        override fun close() {
            open?.close()
            open = null
        }
    }
}

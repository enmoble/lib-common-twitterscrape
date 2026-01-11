package com.enmoble.common.social.twitter.hilt.di

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import com.enmoble.common.social.twitter.api.TwitterRssService
import com.enmoble.common.social.twitter.api.rx.RxTwitterRssService
import com.enmoble.common.social.twitter.data.repository.TwitterCacheRepository
import com.enmoble.common.social.twitter.data.repository.TwitterRepoReactive
import com.enmoble.common.social.twitter.data.repository.TwitterRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/**
 * Hilt module that provides Twitter RSS+Webscrape library components.
 *
 * Provides singletons for:
 * - [`TwitterRssService`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterRssService.kt:59)
 * - [`TwitterRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:132)
 * - [`TwitterRepoReactive`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepoReactive.kt:27)
 * - [`RxTwitterRssService`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/RxTwitterRssService.kt:22)
 * - [`WorkManager`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/di/TwitterHiltModule.kt:129)
 * - Coil [`ImageLoader`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/di/TwitterHiltModule.kt:135)
 *
 * Note: This module defines a simple in-memory [`CookieJar`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/di/TwitterHiltModule.kt:51).
 * You may want to replace it with a persistent cookie jar depending on your use case.
 */
@Module
@InstallIn(SingletonComponent::class)
object TwitterHiltModule {
    
    /**
     * Provides the TwitterRssService.
     *
     * @return A singleton instance of TwitterRssService
     */
    @Provides
    @Singleton
    fun provideTwitterRssService(cookieJar: CookieJar): TwitterRssService {
        return TwitterRssService(cookieJar)
    }

    /**
     * Provides an in-memory [`CookieJar`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/di/TwitterHiltModule.kt:52).
     *
     * Some Nitter instances require challenge cookies (e.g., `res=`) to be preserved across requests.
     */
    @Provides
    @Singleton
    fun provideCookieJar(): CookieJar {
        return object : CookieJar {
            private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
                Log.d("CookieJar", "Saved ${cookies.size} cookies for ${url.host}")
                cookies.forEach { cookie ->
                    Log.d("CookieJar", "Cookie: ${cookie.name}=${cookie.value}")
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val cookies = cookieStore[url.host] ?: emptyList()
                if (cookies.isNotEmpty()) {
                    Log.d("CookieJar", "Loading ${cookies.size} cookies for ${url.host}")
                }
                return cookies
            }
        }
    }

    /**
     * Provides the TwitterCacheRepository.
     *
     * @param context Application context
     * @return A singleton instance of TwitterCacheRepository
     */
    @Provides
    @Singleton
    fun provideTwitterCacheRepository(@ApplicationContext context: Context): TwitterCacheRepository {
        return TwitterCacheRepository(context)
    }

    /**
     * Provides the TwitterRepository.
     *
     * @param twitterRssService The Twitter RSS+Webscrape service
     * @param cacheRepository The cache repository
     * @param firebaseDbManager The Firebase integration
     * @return A singleton instance of TwitterRepository
     */
    @Provides
    @Singleton
    fun provideTwitterRepository(
        twitterRssService: TwitterRssService,
        cacheRepository: TwitterCacheRepository,
    ): TwitterRepository {
        return TwitterRepository(twitterRssService, cacheRepository)
    }
    
    /**
     * Provides the RxTwitterRssService.
     *
     * @param repository The Twitter repository
     * @return A singleton instance of RxTwitterRssService
     */
    @Provides
    @Singleton
    fun provideRxTwitterRssService(
        repository: TwitterRepository,
        reactiveRepo: TwitterRepoReactive,
    ): RxTwitterRssService {
        return RxTwitterRssService(repository, reactiveRepo)
    }

    @Provides
    @Singleton
    fun provideTwitterRepoReactive(
        twitterRepository: TwitterRepository,
        cacheRepository: TwitterCacheRepository,
    ): TwitterRepoReactive {
        return TwitterRepoReactive(twitterRepository, cacheRepository)
    }

    /**
     * Provides the process-wide WorkManager instance.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    /**
     * Provides a Coil [`ImageLoader`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/di/TwitterHiltModule.kt:139)
     * configured for aggressive caching and support for GIFs and video frame decoding.
     *
     * @param context Application context.
     * @param okHttpClient OkHttp client used by Coil for networking.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .respectCacheHeaders(false) // We want to cache aggressively
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .crossfade(300) // 300ms crossfade animation
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25) // Use 25% of available memory
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // Use 2% of available disk space
                    .build()
            }
            .components {
                // Support for GIFs
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }

                // Support for video thumbnails
                add(VideoFrameDecoder.Factory())
            }
            .logger(DebugLogger()) // Enable logging in debug builds
            .build()
    }
}

package com.enmoble.common.social.twitter.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Factory to create OkHttpClient that mimics the Feeder Android RSS Reader.
 */
object FeederOkHttpClientFactory {
    
    /**
     * Creates an [`OkHttpClient`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/FeederRSSClientOkHttpFactory.kt:4) that mimics the Feeder Android RSS Reader app.
     *
     * Motivation: some RSS endpoints (notably certain Nitter instances like `xcancel.com`) behave
     * better with a conservative RSS-reader-like user agent and accept headers.
     *
     * @param connectTimeoutSeconds Connect timeout in seconds.
     * @param readTimeoutSeconds Read timeout in seconds.
     * @param writeTimeoutSeconds Write timeout in seconds.
     * @param enableLogging When true, adds a headers-only logging interceptor.
     *
     * @return Configured OkHttp client suitable for RSS fetches.
     */
    fun createFeederOkHttpClient(
        connectTimeoutSeconds: Long = 15,
        readTimeoutSeconds: Long = 30,
        writeTimeoutSeconds: Long = 30,
        enableLogging: Boolean = false
    ): OkHttpClient {
        
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
        
        // Add Feeder-specific headers
        builder.addInterceptor(createFeederUserAgentInterceptor())
        
        // Add retry interceptor for failed requests
        //builder.addInterceptor(createRetryInterceptor())
        
        // Add logging interceptor if enabled
        if (enableLogging) {
            builder.addInterceptor(createLoggingInterceptor())
        }
        
        return builder.build()
    }
    
    /**
     * Creates a user agent interceptor that mimics Feeder Android RSS Reader.
     */
    private fun createFeederUserAgentInterceptor(): Interceptor {
        return Interceptor { chain ->
            val originalRequest = chain.request()
            val requestWithUserAgent = originalRequest.newBuilder()
                .header("User-Agent", "Feeder/2.6.12 (Android)")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Accept-Charset", "UTF-8")
                .build()
            
            chain.proceed(requestWithUserAgent)
        }
    }

    /**
     * Creates a logging interceptor for debugging RSS requests.
     */
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }
    }
}


/**
 * Common Feeder-like User-Agent strings (for experimentation/fallbacks).
 *
 * Note: The library currently uses a single UA in the interceptor, but these are available if you
 * want to randomize or rotate user agents.
 */
object FeederUserAgents {
    const val BASIC = "Feeder/2.6.12 (Android)"
    const val DETAILED = "Feeder/2.6.12 (Android 13; API 33)"
    const val WITH_DEVICE = "Feeder/2.6.12 (Linux; Android 13; SM-G998B)"
    const val SIMPLE = "Feeder (Android RSS Reader)"
    
    val ALL = listOf(BASIC, DETAILED, WITH_DEVICE, SIMPLE)
}

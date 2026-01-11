# Twitter RSS+Webscrape Library for Android

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)

A powerful, production-ready Android library for accessing Twitter/X data using Nitter RSS feeds **and HTML web-scraping fallbacks**, without requiring API keys or authentication.

## ✨ Features

- 📱 **No Authentication Required** - Fetch tweets from public accounts without Twitter API keys
- 🧵 **Thread Detection** - Automatically identifies and marks thread starter tweets
- 📊 **Rich Media Support** - Handles images, videos, and GIFs
- 💾 **Smart Caching** - Configurable local Room database caching with staleness detection
- 🔄 **Background Sync** - Periodic fetching with WorkManager
- 🔌 **Extensible Storage** - Built-in Room DB + optional Firebase/custom network storage
- ⚡ **Dual API** - Choose between Kotlin Coroutines or RxJava 3
- 🎯 **Hilt Integration** - First-class dependency injection support
- 🌐 **Automatic Fallback** - Tries multiple Nitter instances for reliability
- 📦 **Production Ready** - Comprehensive error handling, logging, and documentation

## 📋 Requirements

- Android API 24+ (Android 7.0+)
- Kotlin 2.1+
- Java 21+

## 🚀 Installation

### Step 1: Add the library dependency

Add the library to your module's `build.gradle.kts` file:

```kotlin
dependencies {
    implementation(project(":lib-common-twitterscrape"))
    // Or when published:
    // implementation("com.enmoble.common.social:twitter-rss:1.0.0")
}
```

### Step 2: Setup Hilt (Recommended)

Add Hilt to your project if not already present:

```kotlin
plugins {
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("com.google.dagger:hilt-android:2.50")
    ksp("com.google.dagger:hilt-compiler:2.50")
}
```

### Step 3: Initialize in your Application class

```kotlin
@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

### Step 4: Declare in AndroidManifest.xml

```xml
<application
    android:name=".MyApplication"
    ...>
```

## 📖 Quick Start

### Basic Usage with Hilt

```kotlin
import com.enmoble.common.social.twitter.data.repository.TwitterRepository

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var twitterRepository: TwitterRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fetch tweets for a user
        lifecycleScope.launch {
            val result = twitterRepository.getTweets(
                username = "elonmusk",
                sinceTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L), // Last 7 days
                cachedNetworkStorage = null, // Use local cache
                useRoomDbCache = true,
                maxResults = 20
            )

            result.fold(
                onSuccess = { tweets ->
                    // Handle tweets (sorted newest first)
                    tweets.forEach { tweet ->
                        println("@${tweet.username}: ${tweet.content}")
                        if (tweet.hasMedia) {
                            println("  Media: ${tweet.media.size} items")
                        }
                    }
                },
                onFailure = { error ->
                    Log.e("Twitter", "Failed to load tweets", error)
                }
            )
        }
    }
}
```

### Without Hilt (Manual Dependency Injection)

```kotlin
import com.enmoble.common.social.twitter.api.TwitterRssService
import com.enmoble.common.social.twitter.data.repository.TwitterCacheRepository
import com.enmoble.common.social.twitter.data.repository.TwitterRepository

class MyActivity : AppCompatActivity() {
    private lateinit var twitterRepository: TwitterRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Manual setup
        val cookieJar = /* create cookie jar */
        val twitterService = TwitterRssService(cookieJar)
        val cacheRepository = TwitterCacheRepository(applicationContext)
        twitterRepository = TwitterRepository(twitterService, cacheRepository)

        // Use repository...
    }
}
```

### RxJava Usage (via `RxTwitterRssService`)

`RxTwitterRssService` is an RxJava wrapper over the repository layer. In a Hilt app, inject it like any other singleton.

```kotlin
import com.enmoble.common.social.twitter.api.rx.RxTwitterRssService

@AndroidEntryPoint
class MyActivity : AppCompatActivity() {

    @Inject lateinit var rxTwitterService: RxTwitterRssService

    fun load() {
        rxTwitterService.getTweets(
            username = "elonmusk",
            sinceTime = 0,
            cachedNetworkStorage = null,
            maxResults = 20
        )
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ tweets ->
                adapter.submitList(tweets)
            }, { error ->
                showError("Failed to load tweets: ${error.message}")
            })
    }
}
```

### Background Fetching (WorkManager)

The periodic worker is [`TwitterFeedFetchWorker`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:1). It exposes convenience functions to schedule/cancel.

```kotlin
// Schedule periodic fetching (min interval is clamped to 15 minutes by WorkManager)
TwitterFeedFetchWorker.schedule(
    context = context,
    repository = twitterRepository,
    usernames = listOf("elonmusk", "tim_cook", "sundarpichai"),
    maxTweetsPerUser = 20,
    intervalSeconds = 30 * 60, // 30 minutes
    replace = true
)

// Cancel scheduled fetching
TwitterFeedFetchWorker.cancel(context)
```

## 🔧 Advanced Usage

### Caching Strategies

The library supports flexible caching with configurable staleness detection:

```kotlin
// Cache-first with network fallback
val result = twitterRepository.getTweets(
    username = "elonmusk",
    sinceTime = 0,
    cachedNetworkStorage = null,
    useRoomDbCache = true,
    localCacheOnly = false,  // Try network if cache is stale
    maxResults = 50,
    maxCacheAgeMs = 5 * 60 * 1000  // Cache valid for 5 minutes
)

// Cache-only (offline mode)
val cached = twitterRepository.getTweets(
    username = "elonmusk",
    sinceTime = 0,
    cachedNetworkStorage = null,
    useRoomDbCache = true,
    localCacheOnly = true,  // Never hit network
    maxResults = 50
)

// Network-only (always fresh)
val fresh = twitterRepository.getTweets(
    username = "elonmusk",
    sinceTime = 0,
    cachedNetworkStorage = null,
    useRoomDbCache = false,  // Skip cache
    localCacheOnly = false,
    maxResults = 50
)
```

### Custom Network Storage Integration

If you want “cloud + local cache” behavior (e.g., Firestore), implement [`CachedNetworkStoreTweets`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:25) and pass it to [`TwitterRepository.getTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:180).

Minimal skeleton:

```kotlin
class MyCachedStore : CachedNetworkStoreTweets {

    override suspend fun writeToCachedNetworkStore(twitterHandle: String, tweets: List<Tweet>) {
        // Write to your network store and/or update your local mirror
    }

    override suspend fun getFromCachedNetworkStore(
        username: String,
        sinceTime: Long,
        localCacheOnly: Boolean,
        maxResults: Int
    ): List<Tweet> {
        // Return from local mirror; refresh from network if !localCacheOnly
        return emptyList()
    }

    override fun observeCachedNetworkStore(username: String, sinceTime: Long, localCacheOnly: Boolean): Flow<Tweet> {
        // Emit from local mirror (and optionally refresh from network if !localCacheOnly)
        return emptyFlow()
    }

    override suspend fun lastUpdateTimestamp(user: String): Long = 0L

    override fun canWriteToNetworkStorage(): Boolean = true
    override fun canReadFromNetworkStorage(): Boolean = true

    override suspend fun getOldestTweet(user: String?, localCacheOnly: Boolean): Tweet? = null
    override suspend fun getLatestTweet(user: String?, localCacheOnly: Boolean): Tweet? = null
}

// Usage:
val result = twitterRepository.getTweets(
    username = "elonmusk",
    sinceTime = 0,
    cachedNetworkStorage = MyCachedStore(),
    useRoomDbCache = true,
    localCacheOnly = false,
    maxResults = 50
)
```

### Batch Fetching Multiple Users

```kotlin
// Fetch tweets for multiple users sequentially
val results = twitterRepository.getMultipleUserTweets(
    usernames = listOf("elonmusk", "BillGates", "sundarpichai"),
    sinceTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L),
    maxTweetsPerUser = 10,
    useCache = true
)

results.forEach { (username, result) ->
    result.fold(
        onSuccess = { tweets ->
            println("$username: ${tweets.size} tweets")
        },
        onFailure = { error ->
            println("Failed for $username: ${error.message}")
        }
    )
}

// For parallel fetching, use TwitterRssService directly:
suspend fun fetchInParallel(usernames: List<String>): Map<String, Result<List<Tweet>>> {
    return twitterRssService.getMultipleUserTweets(
        usernames = usernames,
        sinceTime = 0,
        maxTweetsPerUser = 20
    )
}
```

### Background Sync with WorkManager

If you want to schedule WorkManager yourself (instead of using [`TwitterFeedFetchWorker.schedule()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:48)), make sure you use the exact input keys expected by the worker:
- `usernames` (String array)
- `max_tweets_per_user` (Int)

```kotlin
// Schedule periodic background fetching (manual WorkManager wiring)
class MyBackgroundSync {
    fun scheduleTweetSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = PeriodicWorkRequestBuilder<TwitterFeedFetchWorker>(
            repeatInterval = 30,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    "usernames" to arrayOf("elonmusk", "BillGates"),
                    "max_tweets_per_user" to 20
                )
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "twitter_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }
}
```

## 📦 Data Models

### Tweet

```kotlin
data class Tweet(
    val id: String,                   // Unique tweet ID
    val username: String,             // Twitter username (without @)
    val content: String,              // Plain text content
    val htmlContent: String,          // HTML formatted content
    val timestamp: Date,              // When posted
    val link: String,                 // URL to tweet
    val profileUrl: String,           // Profile image URL
    val isThreadStarter: Boolean,     // Starts a thread
    val isPartOfThread: Boolean,      // Part of a thread
    val threadId: String?,            // Thread identifier
    val isReply: Boolean,             // Is a reply
    val replyToUsername: String?,     // Replying to user
    val replyToTweetId: String?,      // Replying to tweet
    val media: List<TwitterMedia>,    // Media attachments
    val retweetCount: Int,            // Retweet count
    val likeCount: Int,               // Like count
    val fetchedAt: Date,              // When fetched by library
    val isPermanent: Boolean,         // Permanent storage flag
    val contentHash: String           // Content hash for change detection
)
```

### Convenience Properties

```kotlin
val tweet: Tweet = // ...

// Check media types
if (tweet.hasMedia) { /* ... */ }
if (tweet.hasImages) { /* ... */ }
if (tweet.hasVideos) { /* ... */ }
if (tweet.hasGifs) { /* ... */ }

// Convert Nitter URLs to Twitter URLs
val tweetWithTwitterUrls = tweet.withTwitterMediaUrls()
```

## 🎯 Example App

Check out the complete [example-app](example-app/) for a working demonstration featuring:
- Hilt dependency injection setup
- RecyclerView adapter for tweet display
- Image loading with Coil
- Error handling and loading states
- Offline/cache-only mode
- Thread indicators

## 🧪 Testing

```bash
# Run all unit tests
./gradlew test

# Run tests for the library module only
./gradlew :lib-common-twitterscrape:test

# Run with coverage
./gradlew test jacocoTestReport
```

## 📚 Documentation

- Architecture overview: [`TwitterRssLibrary-Architecture.md`](lib-common-twitterscrape/TwitterRssLibrary-Architecture.md)
- Contributing guidelines: [`CONTRIBUTING.md`](lib-common-twitterscrape/CONTRIBUTING.md)
- Example app resources: [`example-app/RESOURCES.md`](lib-common-twitterscrape/example-app/RESOURCES.md)

## ⚠️ Important Notes

- This library uses **Nitter instances** as data sources, which are community-run mirrors of Twitter
- **No Twitter API keys required** - works without authentication
- Nitter instances may have varying reliability and availability
- The library implements automatic fallback across multiple instances
- **Rate limiting**: Respect Nitter instance limits; use caching appropriately
- **Public accounts only**: Can only access public tweets

## 🤝 Contributing

Contributions are welcome! Please check out our [Contributing Guidelines](CONTRIBUTING.md) for details on:
- Setting up the development environment
- Code style and standards
- Submitting pull requests
- Reporting issues

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- [Nitter](https://github.com/zedeus/nitter) - Privacy-focused Twitter frontend
- [RSS Parser](https://github.com/prof18/RSS-Parser) - RSS feed parsing
- [Jsoup](https://jsoup.org/) - HTML parsing

## 💬 Support

- 📧 For questions, create an issue with the `question` label
- 🐛 For bugs, create an issue with detailed reproduction steps
- 💡 For feature requests, create an issue describing the use case

---

Made with ❤️ by [Enmoble](https://github.com/enmoble)

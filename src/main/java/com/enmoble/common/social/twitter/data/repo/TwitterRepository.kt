package com.enmoble.common.social.twitter.data.repository

import android.util.Log
import com.enmoble.common.social.twitter.api.TwitterRssService
import com.enmoble.common.social.twitter.data.model.Tweet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Singleton
import kotlin.collections.plusAssign

/**
 * Interface for implementing custom network-based storage (e.g., Firebase Firestore)
 * with local caching capabilities.
 *
 * Implement this interface if you want to use a remote database as the primary storage
 * for tweets, with automatic local caching. This is useful for:
 * - Syncing tweets across multiple devices
 * - Persistent storage beyond local cache
 * - Cloud backup of tweet data
 *
 * @see TwitterRepository
 */
interface CachedNetworkStoreTweets {
    /**
     * Writes tweets to the network storage (e.g., Firestore).
     *
     * @param twitterHandle The Twitter username
     * @param tweets List of tweets to store
     */
    suspend fun writeToCachedNetworkStore(twitterHandle: String, tweets: List<Tweet>)
    
    /**
     * Retrieves tweets from the network storage (with local cache).
     *
     * @param username The Twitter username
     * @param sinceTime Only return tweets posted at or after this timestamp
     * @param localCacheOnly If true, only fetch from local cache without network access
     * @param maxResults Maximum number of tweets to return
     * @return List of tweets
     */
    suspend fun getFromCachedNetworkStore(username: String, sinceTime: Long, localCacheOnly: Boolean, maxResults: Int = Int.MAX_VALUE): List<Tweet>
    
    /**
     * Observes tweets as a reactive Flow.
     *
     * @param username The Twitter username
     * @param sinceTime Only emit tweets posted at or after this timestamp
     * @param localCacheOnly If true, only observe local cache
     * @return Flow of individual tweets as they become available
     */
    fun observeCachedNetworkStore(username: String, sinceTime: Long, localCacheOnly: Boolean): Flow<Tweet>
    
    /**
     * Gets the timestamp of the last update for a user.
     *
     * @param user The Twitter username
     * @return Unix timestamp (milliseconds) of last update, or 0 if never updated
     */
    suspend fun lastUpdateTimestamp(user: String): Long
    
    /**
     * Checks if network storage write operations are available.
     *
     * @return true if write operations are possible
     */
    fun canWriteToNetworkStorage(): Boolean
    
    /**
     * Checks if network storage read operations are available.
     *
     * @return true if read operations are possible
     */
    fun canReadFromNetworkStorage(): Boolean
    
    /**
     * Gets the oldest tweet for a user.
     *
     * @param user The Twitter username, or null for all users
     * @param localCacheOnly If true, only check local cache
     * @return The oldest tweet, or null if none found
     */
    suspend fun getOldestTweet(user: String?, localCacheOnly: Boolean): Tweet?
    
    /**
     * Gets the latest (most recent) tweet for a user.
     *
     * @param user The Twitter username, or null for all users
     * @param localCacheOnly If true, only check local cache
     * @return The latest tweet, or null if none found
     */
    suspend fun getLatestTweet(user: String?, localCacheOnly: Boolean): Tweet?
}

/**
 * Main repository for managing Twitter data, coordinating between network fetching and caching.
 *
 * This repository provides a high-level API for fetching tweets with intelligent caching,
 * supporting both local Room database cache and optional network-based storage (e.g., Firestore).
 *
 * Key features:
 * - Automatic cache management with staleness detection
 * - Support for time-based filtering
 * - Batch fetching for multiple users
 * - Optional permanent storage marking
 * - Flexible caching strategies (cache-first, network-first, cache-only)
 *
 * Usage example:
 * ```kotlin
 * val repository = TwitterRepository(twitterRssService, cacheRepository)
 *
 * // Fetch tweets with caching
 * val result = repository.getTweets(
 *     username = "elonmusk",
 *     sinceTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L),
 *     cachedNetworkStorage = null, // or your Firestore implementation
 *     useRoomDbCache = true,
 *     localCacheOnly = false,
 *     maxResults = 50
 * )
 * ```
 *
 * @property twitterRssService Service for fetching tweets from Nitter instances
 * @property cacheRepository Local Room database cache repository
 *
 * @see TwitterRssService
 * @see TwitterCacheRepository
 * @see CachedNetworkStoreTweets
 */
@Singleton
open class TwitterRepository(
    private val twitterRssService: TwitterRssService,
    private val cacheRepository: TwitterCacheRepository,
) {
    companion object {
        const val DEFAULT_SEARCH_TIME = 4 * 24 * 60 * 60 * 1000L     // 4 days
        private const val LOGTAG = "#TwitterRepository"
    }

    /**
     * Fetches tweets for a specified user with intelligent caching and flexible storage options.
     *
     * This is the primary method for retrieving tweets. It implements a sophisticated caching
     * strategy that can use either:
     * 1. Local Room database cache only
     * 2. Network-based storage (e.g., Firestore) with local caching
     * 3. Direct network fetch without caching
     *
     * Caching behavior:
     * - Checks cache first if enabled and not stale
     * - Falls back to network if cache is empty, stale, or disabled
     * - Can operate in cache-only mode for offline scenarios
     * - Automatically updates cache after successful network fetch
     *
     * @param username Twitter username to fetch tweets for (without @ symbol)
     * @param sinceTime Unix timestamp (milliseconds) - only fetch tweets posted at or after this time.
     *                  Use 0 for no time filtering. Defaults to 0.
     * @param cachedNetworkStorage Optional implementation of network-based storage (e.g., Firestore).
     *                            If provided, takes precedence over Room database cache.
     *                            Set to null to use only local Room cache. Defaults to null.
     * @param useRoomDbCache Whether to use the local Room database cache when cachedNetworkStorage is null.
     *                       Set to false to always fetch from network. Defaults to true.
     * @param localCacheOnly If true, only returns cached data without attempting network fetch.
     *                       Useful for offline mode. Defaults to false.
     * @param updateNotReplace If true, updates existing tweets with same ID instead of replacing them.
     *                        When false, all cached tweets for the user are replaced. Defaults to true.
     * @param maxResults Maximum number of tweets to return. Use Int.MAX_VALUE for unlimited. Defaults to Int.MAX_VALUE.
     * @param maxCacheAgeMs Maximum age of cache in milliseconds before it's considered stale.
     *                     Defaults to the cache repository's configured expiry time.
     * @param randomizeServer If true, randomizes the starting Nitter instance to distribute load.
     *                       Currently not fully implemented. Defaults to false.
     *
     * @return Result<List<Tweet>> Success with sorted tweets (newest first), or Failure with exception
     *
     * @see getTweets
     * @see fetchAndSaveTweetsFromNetwork
     * @see CachedNetworkStoreTweets
     */
    suspend fun getTweets(
        username: String,
        sinceTime: Long = 0,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        useRoomDbCache: Boolean = true,
        localCacheOnly: Boolean = false,
        updateNotReplace: Boolean = true,
        maxResults: Int = Int.MAX_VALUE,
        maxCacheAgeMs: Long = cacheRepository.cacheExpiryMs,
        randomizeServer: Boolean = false
    ): Result<List<Tweet>> = withContext(Dispatchers.IO) {

        Log.d(LOGTAG, "getTweets(): Getting tweets for $username (sinceTime: [${if (sinceTime > 0) Date(sinceTime).toString() else "None"}], useCache=$useRoomDbCache, cacheOnly=$localCacheOnly, storePermanently=$updateNotReplace)")
        val tweetsList = ArrayList<Tweet>()

        // Check cache - FIRST preference is Cached Network Storage (eg. Firestore DB)
        // which has both a local storage component(cache) as well as automatic sync with network storage
        // If no cachedNetworkStorage then try local RoomDB cache if enabled
        if(cachedNetworkStorage != null) {
            tweetsList += cachedNetworkStorage.getFromCachedNetworkStore(username, sinceTime,
                localCacheOnly, maxResults).sortedByDescending { it.timestamp }   // Sort by newest first
            val lastUpdate = cachedNetworkStorage.lastUpdateTimestamp(username)
            // If last update was within the cache expiry timeframe then no need for network fetch
            val isCacheStale = System.currentTimeMillis() - lastUpdate > maxCacheAgeMs
            if(!isCacheStale || localCacheOnly) return@withContext Result.success(tweetsList)
            // Else also merge network-fetched tweets to the list got from cache
        }
        else if (useRoomDbCache) {
            val isCacheStale = cacheRepository.isCacheStale(username, maxCacheAgeMs)
            
            Log.d(LOGTAG, "getTweets(): Using RoomDB cache for $username")
            val cachedTweets = cacheRepository.getTweets(username, sinceTime, maxResults, false)

            // Filter by sinceTime if needed
            val filteredTweets = if (sinceTime > 0) {
                cachedTweets.filter { it.timestamp.time >= sinceTime }
            } else {
                cachedTweets
            }.sortedByDescending { it.timestamp }   // Sort by newest first

            if (!isCacheStale || localCacheOnly) {
                if (filteredTweets.isNotEmpty() || localCacheOnly) {
                    return@withContext Result.success(filteredTweets)
                }
            }
            // Local cache is stale or empty - merge network-fetched tweets to the list got from local cache
            tweetsList += filteredTweets
        }
        
        // If we're cache-only, and we got here, it means cache was empty or stale
        if (localCacheOnly) {
            Log.d(LOGTAG, "getTweets(): Cache-only request but cache is empty or stale for $username")
            return@withContext Result.success(tweetsList /*emptyList()*/)
        }

        // Fetch from network
        Log.d(LOGTAG, "getTweets(): Calling fetchAndSaveTweetsFromNetwork() for $username with sinceTime=[${if (sinceTime > 0) Date(sinceTime).toString() else "None"}]")
        val networkResult = fetchAndSaveTweetsFromNetwork(username, sinceTime, cachedNetworkStorage, maxResults, randomizeServer, useRoomDbCache)
        networkResult.fold(
            onSuccess = { unsorted ->
                tweetsList += unsorted
                val finalList = if (maxResults > 0) tweetsList.take(maxResults) else tweetsList
                return@withContext Result.success(finalList.sortedByDescending { it.timestamp })
            },
            onFailure = { error ->
                if(tweetsList.isNotEmpty()) return@withContext Result.success(
                    tweetsList.take(if(maxResults > 0) maxResults else Int.MAX_VALUE).sortedByDescending { it.timestamp })
                else return@withContext Result.failure(error)
            })
    }

    /**
     * Fetches tweets from the network via [`TwitterRssService`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterRssService.kt:59)
     * and persists them into the configured cache/storage.
     *
     * This is the “network path” used by [`getTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:180).
     *
     * @param username Twitter username to fetch.
     * @param sinceTime Only fetch tweets posted at or after this timestamp (ms).
     * @param cachedNetworkStorage Optional “cached network store” implementation; if non-null, results are written there.
     * @param maxResults Maximum number of tweets to return.
     * @param randomizeServer Whether to randomize the starting Nitter instance.
     * @param useRoomDbCache When true and [cachedNetworkStorage] is null, saves results into Room cache.
     *
     * @return Result of the network fetch (success with tweets, or failure with exception).
     */
    suspend fun fetchAndSaveTweetsFromNetwork(
        username: String,
        sinceTime: Long = 0,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        maxResults: Int = Int.MAX_VALUE,
        randomizeServer: Boolean = false,
        useRoomDbCache: Boolean = true
    ): Result<List<Tweet>> {
        return try {
            Log.d(LOGTAG, "fetchAndSaveTweetsFromNetwork(): Fetching from network for $username with sinceTime=[${if (sinceTime > 0) Date(sinceTime).toString() else "None"}]")
            val networkResult = twitterRssService.getUserTweets(username, sinceTime, maxResults, randomizeServer)

            networkResult.fold(
                onSuccess = { unsorted ->
                    val tweets = unsorted.sortedByDescending { it.timestamp }   // Sort by newest first
                    Log.d(LOGTAG, "fetchAndSaveTweetsFromNetwork(): Network fetch successful for $username, got ${tweets.size} tweets")

                    val limitedTweets = if (tweets.size > maxResults) {
                        tweets.take(maxResults)
                    } else {
                        tweets
                    }

                    // Save to cache
                    if(cachedNetworkStorage != null) {
                        cachedNetworkStorage.writeToCachedNetworkStore(username, limitedTweets)
                    } else if(useRoomDbCache) {
                        cacheRepository.saveTweets(limitedTweets, true)
                    }
                    // Return tweets
                    Result.success(limitedTweets)
                },
                onFailure = { error ->
                    Log.e(LOGTAG, "fetchAndSaveTweetsFromNetwork(): Network fetch failed for $username", error)
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "fetchAndSaveTweetsFromNetwork(): Exception: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Returns all tweets currently stored in the local cache.
     */
    suspend fun getAllTweetsFromCache() = cacheRepository.getAllTweets()

    /**
     * Returns the oldest tweet for a given user (or overall), from either the cached network store or Room.
     *
     * @param username Username to scope to, or null for all users.
     * @param cachedNetworkStorage Optional cached network store.
     * @param localCacheOnly Passed through when using cached network store.
     */
    suspend fun getOldestTweet(
        username: String?,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        localCacheOnly: Boolean = false
    ): Tweet? {
        val oldest =
            if(cachedNetworkStorage != null) {
                cachedNetworkStorage.getOldestTweet(username, localCacheOnly)
            } else {
                cacheRepository.getOldestTweet(username)
            }
        return oldest
    }

    /**
     * Returns the latest tweet for a given user (or overall), from either the cached network store or Room.
     *
     * @param username Username to scope to, or null for all users.
     * @param cachedNetworkStorage Optional cached network store.
     * @param localCacheOnly Passed through when using cached network store.
     */
    suspend fun getLatestTweet(
        username: String?,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        localCacheOnly: Boolean = false
    ): Tweet? {
        val latest =
            if(cachedNetworkStorage != null) {
                cachedNetworkStorage.getLatestTweet(username, localCacheOnly)
            } else {
                cacheRepository.getLatestTweet(username)
            }
        return latest
    }
    
    /**
     * Attempts to return cached tweets as a fallback after a network failure.
     *
     * @param username Username to read from cache.
     * @param maxResults Maximum tweets to return.
     * @param sinceTime Only return tweets with `timestamp >= sinceTime`.
     * @param error Original error to return if cache is empty.
     *
     * @return Success with cached tweets when available, else failure with [error].
     */
    suspend fun tryFetchingFromCache(
        username: String,
        maxResults: Int,
        sinceTime: Long,
        error: Throwable
    ): Result<List<Tweet>> {
        Log.d(LOGTAG, "getTweets(): Falling back to cache for $username")
        val cachedTweets = cacheRepository.getTweets(username, sinceTime, maxResults)

        // Filter by sinceTime if needed
        val filteredTweets = if (sinceTime > 0) {
            cachedTweets.filter { it.timestamp.time >= sinceTime }
        } else {
            cachedTweets
        }.sortedByDescending { it.timestamp }   // Sort by newest first

        if (filteredTweets.isNotEmpty()) {
            Log.d(LOGTAG, "getTweets(): Returning ${filteredTweets.size} cached tweets as fallback for $username")
            return Result.success(filteredTweets)
        } else {
            return Result.failure(error)
        }
    }

    /**
     * Fetches tweets for multiple users sequentially with caching support.
     *
     * This method calls [getTweets] for each username in sequence. While not parallel,
     * it provides consistent error handling and caching behavior for each user.
     *
     * For truly parallel execution, consider using [TwitterRssService.getMultipleUserTweets]
     * directly, though it won't include caching logic.
     *
     * @param usernames List of Twitter usernames to fetch tweets for (without @ symbols)
     * @param sinceTime Unix timestamp (milliseconds) - only fetch tweets posted at or after this time.
     *                  Use 0 for no filtering. Defaults to 0.
     * @param maxTweetsPerUser Maximum number of tweets to return per user. Defaults to 20.
     * @param useCache Whether to use cache (if available and not stale). Defaults to true.
     * @param cacheOnly If true, only fetches from cache without network access. Defaults to false.
     * @param storePermanently If true, marks fetched tweets as permanent in cache. Defaults to false.
     * @param cachedNetworkStorage Optional network-based storage implementation. Defaults to null.
     *
     * @return Map where keys are usernames and values are Results containing either
     *         tweet lists or exceptions for each specific user
     *
     * @see getTweets
     * @see TwitterRssService.getMultipleUserTweets
     */
    suspend fun getMultipleUserTweets(
        usernames: List<String>,
        sinceTime: Long = 0,
        maxTweetsPerUser: Int = 20,
        useCache: Boolean = true,
        cacheOnly: Boolean = false,
        storePermanently: Boolean = false,
        cachedNetworkStorage: CachedNetworkStoreTweets? = null
    ): Map<String, Result<List<Tweet>>> {
        Log.d(LOGTAG, "getMultipleUserTweets(): Getting tweets for ${usernames.size} users: $usernames")
        
        val results = mutableMapOf<String, Result<List<Tweet>>>()
        
        for (username in usernames) {
            results[username] = getTweets(
                username = username,
                sinceTime = sinceTime,
                useRoomDbCache = useCache,
                localCacheOnly = cacheOnly,
                updateNotReplace = storePermanently,
                maxResults = maxTweetsPerUser,
                cachedNetworkStorage = cachedNetworkStorage
            )
        }
        
        return results
    }
    
    /**
     * Marks tweets as permanently stored.
     *
     * @param tweets List of tweets to mark as permanent
     */
    suspend fun markTweetsAsPermanent(tweets: List<Tweet>) {
        cacheRepository.markTweetsAsPermanent(tweets)
    }
    
    /**
     * Clears non-permanent tweets from the cache.
     */
    suspend fun clearNonPermanentCache() {
        cacheRepository.clearNonPermanentCache()
    }
    
    /**
     * Save tweets to Firebase.
     *
     * @param username Twitter username the tweets belong to
     * @param tweets List of tweets to save to Firebase
     * @return Result indicating success or failure
     */
    suspend fun saveToFirebase(username: String, tweets: List<Tweet>): Result<Unit> {
        Log.d(LOGTAG, "STUB: Implement your own function !! Saving ${tweets.size} tweets to Firebase for $username")
        //TODO: return FireDbManager.writeOrReplaceTwitterData(username, tweets)
        return Result.success(Unit)
    }
    
    /**
     * Save tweets to Firebase.
     *
     * @param tweets List of tweets to save to Firebase (must all be from the same user)
     * @return Result indicating success or failure
     */
    suspend fun saveToFirebase(tweets: List<Tweet>): Result<Unit> {
        if (tweets.isEmpty()) {
            return Result.success(Unit)
        }
        
        // Group by username in case there are tweets from multiple users
        val tweetsByUsername = tweets.groupBy { it.username }
        
        // For each username, save their tweets
        var lastError: Exception? = null
        tweetsByUsername.forEach { (username, userTweets) ->
            try {
                saveToFirebase(username, userTweets)
            } catch (e: Exception) {
                Log.e(LOGTAG, "Error saving tweets to Firebase for $username", e)
                lastError = e
            }
        }
        
        return if (lastError != null) {
            Result.failure(lastError!!)
        } else {
            Result.success(Unit)
        }
    }

    /**
     * Sets the local cache expiry threshold used for staleness checks.
     *
     * @param expiryMs Cache expiry duration in milliseconds.
     */
    fun setCacheExpiryMs(expiryMs: Long) {
        cacheRepository.cacheExpiryMs = expiryMs
    }

}

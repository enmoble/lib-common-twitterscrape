package com.enmoble.common.social.twitter.data.repository

import android.util.Log
import com.enmoble.common.social.twitter.api.TwitterWebScrapeService.Companion.DEFAULT_SEARCH_TIMEFRAME
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Singleton

/**
 * Reactive wrapper for [`TwitterRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:132) that provides Flow-based operations.
 *
 * This class delegates to repository/cache methods and exposes:
 * - “fetch then emit” Flows (emit individual tweets),
 * - cache observation Flows (emit tweet lists or individual tweets),
 * - convenience helpers for multi-user fetching.
 */
@Singleton
open class TwitterRepoReactive(
    protected val twitterRepository: TwitterRepository,
    private val cacheRepository: TwitterCacheRepository,
) {
    companion object {
        private const val LOGTAG = "#TwitterRepoReactive"
    }

    /**
     * Flow wrapper for [`TwitterRepository.getTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:180)
     * that emits individual tweets.
     *
     * This performs a one-shot fetch (respecting cache/network settings) and then emits the resulting
     * list as a Flow of tweets.
     *
     * @param username Twitter username to fetch tweets for.
     * @param sinceTime Only fetch tweets posted at or after this timestamp.
     * @param cachedNetworkStorage Optional “cached network store” implementation (e.g., Firestore mirror).
     * @param throwException When true, propagate exceptions instead of emitting an empty Flow.
     * @param useCache Whether to use the cache (if available and not stale).
     * @param cacheOnly Whether to only fetch from cache and not from network.
     * @param storePermanently Whether to upsert into cache (instead of replacing) when saving.
     * @param maxResults Maximum number of tweets to return.
     * @param maxCacheAgeMs Maximum age of cache in ms before it's considered stale.
     * @param randomizeServer Whether to randomize starting Nitter instance when fetching from network.
     *
     * @return Flow emitting individual [`Tweet`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/model/Tweet.kt:42) objects.
     */
    suspend fun getTweetsFlow(
        username: String,
        sinceTime: Long = System.currentTimeMillis() - DEFAULT_SEARCH_TIMEFRAME,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        throwException: Boolean = false,
        useCache: Boolean = true,
        cacheOnly: Boolean = false,
        storePermanently: Boolean = true,
        maxResults: Int = Int.MAX_VALUE,
        maxCacheAgeMs: Long = Constants.Cache.DEFAULT_CACHE_EXPIRY_MS,
        randomizeServer: Boolean = false
    ): Flow<Tweet> {
        Log.d(LOGTAG, "getTweetsFlow(): Starting flow for user: $username")
        var tweetsFlow: Flow<Tweet> = emptyFlow()
        CoroutineScope(Dispatchers.IO).launch {
            tweetsFlow =
            try {
                val result = twitterRepository.getTweets(username = username, sinceTime = sinceTime,
                    cachedNetworkStorage = cachedNetworkStorage, useRoomDbCache = useCache,
                    localCacheOnly = cacheOnly, updateNotReplace = storePermanently,
                    maxResults = maxResults, maxCacheAgeMs = maxCacheAgeMs, randomizeServer = randomizeServer)

                result.fold(onSuccess = { tweets ->
                    Log.d(LOGTAG, "getTweetsFlow(): Emitting ${tweets.size} tweets for $username")
                    tweets.asFlow()
                        .flowOn(Dispatchers.IO)
                        .onStart {
                            Log.d(LOGTAG, "getTweetsFlow(): Flow started for $username (sinceTime: [${if (sinceTime > 0) Date(sinceTime).toString() else "None"}], useCache=$useCache, cacheOnly=$cacheOnly)")
                        }
                }, onFailure = { error ->
                    Log.e(LOGTAG, "getTweetsFlow(): Error getting tweets for $username", error)
                    if(throwException) throw error else flowOf()
                })
            } catch (e: Exception) {
                Log.e(LOGTAG, "getTweetsFlow(): Exception in flow for $username", e)
                if(throwException) throw e else flowOf()
            }
        }.join()
        return tweetsFlow
    }

    /**
     * Flow wrapper for TwitterRepository.getMultipleUserTweets() that emits individual tweets from all users.
     *
     * @param usernames List of Twitter usernames to fetch tweets for
     * @param sinceTime Only fetch tweets posted at or after this timestamp
     * @param maxTweetsPerUser Maximum number of tweets to return per user
     * @param useCache Whether to use the cache (if available and not stale)
     * @param cacheOnly Whether to only fetch from cache and not from network
     * @param storePermanently Whether to store the fetched tweets permanently
     * @param sortByTimestamp Whether to sort all tweets by timestamp (newest first) before emitting
     * @return Flow emitting individual Tweet objects from all users
     */
    fun getMultipleUserTweetsFlow(
        usernames: List<String>,
        sinceTime: Long = System.currentTimeMillis() - DEFAULT_SEARCH_TIMEFRAME,
        maxTweetsPerUser: Int = 20,
        useCache: Boolean = true,
        cacheOnly: Boolean = false,
        storePermanently: Boolean = false,
        sortByTimestamp: Boolean = true
    ): Flow<Tweet> = flow {
        Log.d(LOGTAG, "getMultipleUserTweetsFlow(): Starting flow for ${usernames.size} users: $usernames")
        
        try {
            val results = twitterRepository.getMultipleUserTweets(
                usernames = usernames,
                sinceTime = sinceTime,
                maxTweetsPerUser = maxTweetsPerUser,
                useCache = useCache,
                cacheOnly = cacheOnly,
                storePermanently = storePermanently
            )
            
            // Collect all successful tweets
            val allTweets = mutableListOf<Tweet>()
            var successCount = 0
            var errorCount = 0
            
            results.forEach { (username, result) ->
                result.fold(
                    onSuccess = { tweets ->
                        allTweets.addAll(tweets)
                        successCount++
                        Log.d(LOGTAG, "getMultipleUserTweetsFlow(): Got ${tweets.size} tweets from $username")
                    },
                    onFailure = { error ->
                        errorCount++
                        Log.w(LOGTAG, "getMultipleUserTweetsFlow(): Failed to get tweets from $username", error)
                    }
                )
            }
            
            // Sort if requested
            val finalTweets = if (sortByTimestamp) {
                allTweets.sortedByDescending { it.timestamp }
            } else {
                allTweets
            }
            
            Log.d(LOGTAG, "getMultipleUserTweetsFlow(): Emitting ${finalTweets.size} tweets from $successCount users ($errorCount failed)")
            
            // Emit individual tweets
            finalTweets.forEach { tweet ->
                emit(tweet)
            }
            
        } catch (e: Exception) {
            Log.e(LOGTAG, "getMultipleUserTweetsFlow(): Exception in flow for users $usernames", e)
            throw e
        }
    }.catch { e ->
        Log.e(LOGTAG, "getMultipleUserTweetsFlow(): Flow error for users $usernames", e)
        throw Exception("Flow execution failed for multiple users", e)
    }.onStart {
        Log.d(LOGTAG, "getMultipleUserTweetsFlow(): Flow started for ${usernames.size} users (sinceTime: [${if (sinceTime > 0) Date(sinceTime).toString() else "None"}], useCache=$useCache, cacheOnly=$cacheOnly)")
    }

    /**
     * Observes tweets since a specific time as individual tweet emissions.
     *
     * If [cachedNetworkStorage] is provided, delegates to its observation method; otherwise observes
     * the local Room cache.
     *
     * @param username Username to observe.
     * @param sinceTime Only emit tweets with `timestamp >= sinceTime`.
     * @param cachedNetworkStorage Optional “cached network store” implementation.
     * @param noServerRefresh Passed through to the cachedNetworkStorage implementation (semantics are implementation-defined).
     *
     * @return Flow of tweets that are inserted/updated since [sinceTime].
     */
    fun observeTweetsFromCacheSince(
        username: String,
        sinceTime: Long,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        noServerRefresh: Boolean = true
    ): Flow<Tweet> {
        if (cachedNetworkStorage != null) return cachedNetworkStorage.observeCachedNetworkStore(username, sinceTime, noServerRefresh)
        else return cacheRepository.observeTweetsSince(username, sinceTime)
    }

    /**
     * Observes tweets for a user as a Flow.
     *
     * @param username Twitter username to observe tweets for
     * @param maxResults Maximum number of tweets to return
     * @param includePermanentOnly Whether to only include tweets marked as permanent
     * @return Flow of list of tweets for the user
     */
    fun observeTweetsFromCache(
        username: String,
        maxResults: Int = Int.MAX_VALUE,
        includePermanentOnly: Boolean = false
    ): Flow<List<Tweet>> {
        return cacheRepository.observeTweets(username, maxResults, includePermanentOnly)
    }

}
package com.enmoble.common.social.twitter.data.repository

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.enmoble.common.social.twitter.data.db.TwitterDatabase
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Repository for caching tweets locally using Room.
 *
 * This is a thin wrapper around [`TweetDao`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/db/TweetDao.kt:20)
 * and is used by [`TwitterRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:132).
 *
 * @param context Application context used to build the Room database.
 */
@Singleton
class TwitterCacheRepository(context: Context) {
    companion object {
        private const val LOGTAG = "#TwitterCacheRepository"
    }

    private val database: TwitterDatabase = Room.databaseBuilder(
        context.applicationContext,
        TwitterDatabase::class.java,
        "twitter_cache.db"
    ).build()
    
    private val tweetDao = database.tweetDao()
    private var _cacheExpiryMs = Constants.Cache.DEFAULT_CACHE_EXPIRY_MS
    var cacheExpiryMs: Long
        set(value) {
            _cacheExpiryMs = value
        }
        get() = _cacheExpiryMs


    /**
     * Saves tweets to the local cache.
     *
     * @param tweets Tweets to save.
     * @param updateNotReplace When true, upserts by tweetId; when false, inserts (may replace per DAO strategy).
     */
    suspend fun saveTweets(tweets: List<Tweet>, updateNotReplace: Boolean = false): Unit = withContext(Dispatchers.IO) {
        try {
            if (tweets.isEmpty()) {
                return@withContext
            }
            
            Log.d(LOGTAG, "saveTweets(): Saving ${tweets.size} tweets to cache (permanent=$updateNotReplace)")
            
            // If permanent, update existing tweets instead of replacing them
            if (updateNotReplace) {
                tweetDao.insertOrUpdateTweets(tweets)
            } else {
                tweetDao.insertTweets(tweets)
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "saveTweets(): Error saving tweets to cache", e)
            throw e
        }
    }
    
    /**
     * Gets tweets for a username from the local cache.
     *
     * @param username Twitter username to get tweets for
     * @param maxResults Maximum number of tweets to return
     * @param getPersistentOnly Whether to only include tweets marked as 'permanent' aka persistent aka those tweets which get updated instead of replaced with new versions of tweets with the same tweetId are got from server
     * @return List of tweets for the user
     */
    suspend fun getTweets(
        username: String,
        sinceTime: Long = 0,
        maxResults: Int = Int.MAX_VALUE,
        getPersistentOnly: Boolean = false
    ): List<Tweet> = withContext(Dispatchers.IO) {
        try {
            Log.d(LOGTAG, "Fetching tweets from cache for $username (maxResults=$maxResults, permanentOnly=$getPersistentOnly)")
            
            val tweets = if (getPersistentOnly) {
                tweetDao.getPersistentTweetsByUsername(username).filter { it.timestamp.time >= sinceTime }
            } else {
                tweetDao.getTweetsSince(username, sinceTime)
            }.take(maxResults)
            
            Log.d(LOGTAG, "Found ${tweets.size} tweets in cache for $username")
            return@withContext tweets
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error fetching tweets from cache for $username", e)
            return@withContext emptyList<Tweet>()
        }
    }

    /**
     * Returns all cached tweets (across all users).
     */
    suspend fun getAllTweets() = tweetDao.getAllTweets()

    /**
     * Observes tweets since a given timestamp as individual tweet emissions.
     *
     * @param username Twitter username to observe.
     * @param sinceTime Only emit tweets with `timestamp >= sinceTime`.
     * @return Flow emitting tweets as they are inserted/updated.
     */
    fun observeTweetsSince(username: String, sinceTime: Long): Flow<Tweet> {
        return tweetDao.observeTweetsSince(username, sinceTime)
    }

    /**
     * Observes tweets for a username as a Flow.
     *
     * @param username Twitter username to observe tweets for
     * @param maxResults Maximum number of tweets to return
     * @param includePermanentOnly Whether to only include tweets marked as permanent
     * @return Flow of list of tweets for the user
     */
    fun observeTweets(
        username: String,
        maxResults: Int = Int.MAX_VALUE,
        includePermanentOnly: Boolean = false
    ): Flow<List<Tweet>> {
        Log.d(LOGTAG, "Observing tweets for $username (maxResults=$maxResults, permanentOnly=$includePermanentOnly)")
        
        return if (includePermanentOnly) {
            tweetDao.observePermanentTweetsByUsername(username, maxResults)
        } else {
            tweetDao.observeTweetsByUsername(username, maxResults)
        }
    }
    
    /**
     * Checks if the cache for a username is stale.
     *
     * @param username Twitter username to check
     * @param maxAge Maximum age of cache in milliseconds
     * @return true if cache is stale or doesn't exist, false otherwise
     */
    suspend fun isCacheStale(
        username: String,
        maxAge: Long = Constants.Cache.DEFAULT_CACHE_EXPIRY_MS
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastTweet = tweetDao.getLatestTweetByUsername(username)
            
            if (lastTweet == null) {
                Log.d(LOGTAG, "No cached tweets found for $username")
                return@withContext true
            }
            
            val now = Date().time
            val lastFetchTime = lastTweet.fetchedAt.time
            val age = now - lastFetchTime
            
            val isStale = age > maxAge
            Log.d(LOGTAG, "Cache for $username is ${if (isStale) "stale" else "fresh"} (age: ${TimeUnit.MILLISECONDS.toSeconds(age)}s)")
            
            return@withContext isStale
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error checking if cache is stale for $username", e)
            return@withContext true
        }
    }

    /**
     * Gets the latest (most recent) tweet from the cache.
     *
     * @param user Username to scope to, or null for all users.
     */
    suspend fun getLatestTweet(user: String?) =
        if (user != null) tweetDao.getLatestTweetByUsername(user)
        else tweetDao.getLatestTweet()

    /**
     * Gets the oldest tweet from the cache.
     *
     * @param user Username to scope to, or null for all users.
     */
    suspend fun getOldestTweet(user: String?) =
        if (user != null) tweetDao.getOldestTweetByUsername(user)
        else tweetDao.getOldestTweet()


    /**
     * Clears non-permanent tweets from the cache.
     */
    suspend fun clearNonPermanentCache(): Unit = withContext(Dispatchers.IO) {
        try {
            Log.d(LOGTAG, "Clearing non-permanent tweets from cache")
            tweetDao.deleteNonPermanentTweets()
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error clearing non-permanent cache", e)
            throw e
        }
    }
    
    /**
     * Updates tweets to mark them as permanent.
     *
     * @param tweets List of tweets to mark as permanent
     */
    suspend fun markTweetsAsPermanent(tweets: List<Tweet>): Unit = withContext(Dispatchers.IO) {
        try {
            if (tweets.isEmpty()) {
                return@withContext
            }
            
            Log.d(LOGTAG, "Marking ${tweets.size} tweets as permanent")
            tweetDao.markTweetsAsPermanent(tweets.map { it.id })
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error marking tweets as permanent", e)
            throw e
        }
    }
}

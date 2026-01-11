package com.enmoble.common.social.twitter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.enmoble.common.social.twitter.data.model.Tweet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * Room DAO for [`Tweet`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/model/Tweet.kt:42) entities.
 *
 * Most methods are straightforward queries/inserts. The notable helper is
 * [`insertOrUpdateTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/db/TweetDao.kt:52),
 * which only updates an existing row when the `contentHash` has changed.
 */
@Dao
interface TweetDao {

    /**
     * Inserts tweets, replacing any existing rows with the same primary key (`id`).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTweets(tweets: List<Tweet>)

    /**
     * Updates tweets, replacing any existing rows with the same primary key (`id`).
     */
    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateTweets(tweets: List<Tweet>)

    /**
     * Upserts tweets with a “content changed” check.
     *
     * For each tweet:
     * - if it does not exist, insert it
     * - if it exists and `contentHash` differs, update it
     * - if it exists and `contentHash` is the same, do nothing
     *
     * This avoids churn when refetching the same tweet content.
     */
    @Transaction
    suspend fun insertOrUpdateTweets(tweets: List<Tweet>) {
        // For each tweet, check if it already exists
        tweets.forEach { tweet ->
            val existing = getTweetById(tweet.id)
            if (existing != null) {
                // If it exists and content is different, update
                if (existing.contentHash != tweet.contentHash) {
                    updateTweets(listOf(tweet))
                }
                // Otherwise, do nothing
            } else {
                // If it doesn't exist, insert
                insertTweets(listOf(tweet))
            }
        }
    }

    /**
     * Returns a tweet by its primary key.
     */
    @Query("SELECT * FROM tweets WHERE id = :tweetId LIMIT 1")
    suspend fun getTweetById(tweetId: String): Tweet?

    /**
     * Returns all tweets for a user, newest first.
     */
    @Query("SELECT * FROM tweets WHERE username = :username ORDER BY timestamp DESC")
    suspend fun getTweetsByUsername(username: String): List<Tweet>

    /**
     * Returns only “permanent” tweets for a user, newest first.
     */
    @Query("SELECT * FROM tweets WHERE username = :username AND isPermanent = 1 ORDER BY timestamp DESC")
    suspend fun getPersistentTweetsByUsername(username: String): List<Tweet>

    /**
     * Observes tweets for a user as a list, newest first, capped by [limit].
     */
    @Query("SELECT * FROM tweets WHERE username = :username ORDER BY timestamp DESC LIMIT :limit")
    fun observeTweetsByUsername(username: String, limit: Int): Flow<List<Tweet>>

    /**
     * Returns tweets for a user since a timestamp, newest first.
     */
    @Query("""SELECT * FROM tweets WHERE username = :username AND timestamp >= :sinceTime ORDER BY timestamp DESC""")
    suspend fun getTweetsSince(username: String, sinceTime: Long): List<Tweet>

    /**
     * Returns all tweets across all users.
     */
    @Query("SELECT * FROM tweets")
    suspend fun getAllTweets(): List<Tweet>

    /**
     * Observes the *entire dataset* for a user since a timestamp as lists (newest first).
     *
     * Useful as a basis for deriving a “diff stream” of added/changed tweets.
     */
    @Query("""SELECT * FROM tweets WHERE username = :username AND timestamp >= :sinceTime ORDER BY timestamp DESC""")
    fun observeTweetsDatasetSince(username: String, sinceTime: Long): Flow<List<Tweet>>

    /**
     * Observes only “permanent” tweets for a user as a list, newest first, capped by [limit].
     */
    @Query("SELECT * FROM tweets WHERE username = :username AND isPermanent = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun observePermanentTweetsByUsername(username: String, limit: Int): Flow<List<Tweet>>

    /**
     * Returns the latest (newest) tweet for a user.
     */
    @Query("SELECT * FROM tweets WHERE username = :username ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTweetByUsername(username: String): Tweet?

    /**
     * Returns the oldest tweet for a user.
     */
    @Query("SELECT * FROM tweets WHERE username = :username ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldestTweetByUsername(username: String): Tweet?

    /**
     * Returns the latest (newest) tweet across all users.
     */
    @Query("SELECT * FROM tweets ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestTweet(): Tweet?

    /**
     * Returns the oldest tweet across all users.
     */
    @Query("SELECT * FROM tweets ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldestTweet(): Tweet?

    /**
     * Deletes all tweets that are not marked permanent.
     */
    @Query("DELETE FROM tweets WHERE isPermanent = 0")
    suspend fun deleteNonPermanentTweets()

    /**
     * Marks tweets as permanent by tweet ID.
     */
    @Query("UPDATE tweets SET isPermanent = 1 WHERE id IN (:tweetIds)")
    suspend fun markTweetsAsPermanent(tweetIds: List<String>)

    /**
     * Observes tweets since a specific time, emitting only added/changed tweets as individual items.
     *
     * Implementation detail: this observes the dataset as lists, maps by tweet ID, diffs with the
     * previous emission, and then emits only the changed tweets one-by-one.
     *
     * @param username Username to observe.
     * @param sinceTime Only consider tweets with `timestamp >= sinceTime`.
     */
    fun observeTweetsSince(username: String, sinceTime: Long): Flow<Tweet> {
        return observeTweetsDatasetSince(username, sinceTime)
            .map { it.associateBy { tweet -> tweet.id } } // Map by ID
            .scan(emptyMap<String, Tweet>()) { old, new ->
                new.filter { (id, tweet) ->
                    old[id] != tweet // Changed or new
                }
            }
            .map { it.values } // Get only changed/added tweets
            .flatMapConcat { it.asFlow() } // Emit individually
    }
}

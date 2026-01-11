package com.enmoble.common.social.twitter.background

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.repository.TwitterRepository
import com.enmoble.common.social.twitter.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager [`CoroutineWorker`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:6)
 * that periodically fetches tweets for a list of usernames.
 *
 * Scheduling helpers:
 * - [`schedule()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:48)
 * - [`cancel()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:93)
 *
 * The worker reads its inputs from WorkManager `Data`:
 * - `usernames` (String array)
 * - `max_tweets_per_user` (Int)
 */
@HiltWorker
class TwitterFeedFetchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TwitterRepository,
) : CoroutineWorker(context, params) {

    companion object {
        private const val LOGTAG = "#TwitterFeedWorker"

        // Input Data Keys
        private const val KEY_USERNAMES = "usernames"
        private const val KEY_MAX_TWEETS_PER_USER = "max_tweets_per_user"

        /**
         * Schedules periodic tweet fetching using WorkManager.
         *
         * WorkManager enforces a minimum periodic interval; this helper clamps [intervalSeconds]
         * to at least 15 minutes.
         *
         * Note: The [repository] parameter is not used directly by WorkManager (workers are
         * instantiated by Hilt). It's included to make call sites explicit about the required
         * dependency and to keep the API symmetric with other helper methods.
         *
         * @param context Android context.
         * @param repository Repository used by the worker (injected by Hilt at runtime).
         * @param usernames Twitter usernames to fetch.
         * @param maxTweetsPerUser Maximum tweets to fetch per user per run.
         * @param intervalSeconds Repeat interval in seconds (clamped to >= 15 minutes).
         * @param replace When true, replaces any existing periodic work with the same unique name.
         */
        fun schedule(
            context: Context,
            repository: TwitterRepository,
            usernames: List<String>,
            maxTweetsPerUser: Int = 20,
            intervalSeconds: Long = Constants.Worker.DEFAULT_MIN_INTERVAL_SECONDS,
            replace: Boolean = true
        ) {
            Log.d(LOGTAG, "Scheduling periodic Twitter feed fetching for ${usernames.size} users every ${intervalSeconds}s")

            // Validate interval
            val validInterval = maxOf(intervalSeconds, 15 * 60L) // Minimum 15 minutes

            // Create input data
            val inputData = Data.Builder()
                .putStringArray(KEY_USERNAMES, usernames.toTypedArray())
                .putInt(KEY_MAX_TWEETS_PER_USER, maxTweetsPerUser)
                .build()

            // Create work request
            val workRequest = PeriodicWorkRequestBuilder<TwitterFeedFetchWorker>(validInterval, TimeUnit.SECONDS)
                .setInputData(inputData)
                .build()

            // Schedule work
            val policy = if (replace) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.Worker.FEED_WORKER_NAME,
                policy,
                workRequest
            )

            Log.d(LOGTAG, "Scheduled Twitter feed fetching with interval ${validInterval}s")
        }

        /**
         * Cancels the scheduled periodic tweet fetching work.
         *
         * @param context Android context.
         */
        fun cancel(context: Context) {
            Log.d(LOGTAG, "Cancelling scheduled Twitter feed fetching")
            WorkManager.getInstance(context).cancelUniqueWork(Constants.Worker.FEED_WORKER_NAME)
        }
    }

    /**
     * Executes one worker run: fetch tweets for all configured usernames and (optionally) persist them.
     *
     * @return WorkManager [`Result`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:99) indicating success/failure/retry.
     */
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                // Get input data
                val usernames = inputData.getStringArray(KEY_USERNAMES)?.toList()
                val maxTweetsPerUser = inputData.getInt(KEY_MAX_TWEETS_PER_USER, 20)

                if (usernames.isNullOrEmpty()) {
                    Log.e(LOGTAG, "No usernames provided to worker")
                    return@withContext Result.failure()
                }

                Log.d(LOGTAG, "Starting to fetch tweets for ${usernames.size} users")

                // Fetch tweets for each user
                val results = repository.getMultipleUserTweets(
                    usernames = usernames,
                    maxTweetsPerUser = maxTweetsPerUser,
                    useCache = true,
                    cacheOnly = false,
                    storePermanently = true // Always store permanently from the worker
                )

                // Count successful and failed fetches
                var successCount = 0
                var failCount = 0
                var totalTweets = 0

                // Check results and save to Firebase
                results.forEach { (username, result) ->
                    result.fold(
                        onSuccess = { tweets ->
                            totalTweets += tweets.size
                            successCount++

                            if (tweets.isNotEmpty()) {
                                // Call stub to save to Firebase
                                saveToFirebase(tweets)
                            }
                        },
                        onFailure = { error ->
                            Log.e(LOGTAG, "Failed to fetch tweets for $username", error)
                            failCount++
                        }
                    )
                }

                Log.d(LOGTAG, "Twitter feed fetching completed: $successCount succeeded, $failCount failed, $totalTweets total tweets")

                // If all fetches failed, return retry
                if (failCount == usernames.size) {
                    Log.e(LOGTAG, "All fetches failed, will retry")
                    return@withContext Result.retry()
                }

                return@withContext Result.success()
            } catch (e: Exception) {
                Log.e(LOGTAG, "Error in TwitterFeedFetchWorker", e)
                return@withContext Result.retry()
            }
        }
    }

    /**
     * Stub hook for saving tweets to a remote store (e.g., Firebase).
     *
     * The library module does not include a concrete Firebase implementation; integrate your own
     * persistence by replacing this logic.
     *
     * @param tweets Tweets to save (may contain multiple usernames).
     */
    private suspend fun saveToFirebase(tweets: List<Tweet>) {
        if (tweets.isEmpty()) {
            return
        }

        // Group tweets by username to ensure proper organization in Firebase
        val tweetsByUsername = tweets.groupBy { it.username }

        tweetsByUsername.forEach { (username, userTweets) ->
            try {
                Log.d(LOGTAG, "STUB: Implement your own function !! Saving ${userTweets.size} tweets for $username to Firebase")
                //TODO: FireDbManager.writeOrReplaceTwitterData(username, userTweets)
            } catch (e: Exception) {
                Log.e(LOGTAG, "Error saving tweets to Firebase for $username", e)
            }
        }
    }
}
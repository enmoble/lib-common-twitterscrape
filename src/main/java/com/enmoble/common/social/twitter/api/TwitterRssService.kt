package com.enmoble.common.social.twitter.api

import android.util.Log
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.util.Constants.Network.NITTER_INSTANCES
import com.enmoble.common.social.twitter.util.toTweet
import com.prof18.rssparser.RssParser
import com.prof18.rssparser.RssParserBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.Charset
import java.util.Date
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for fetching and parsing Twitter/X data from Nitter RSS feeds.
 *
 * This service provides a robust way to retrieve tweets from Twitter without requiring
 * API keys or authentication. It uses multiple Nitter instances as fallback sources
 * and supports both RSS and HTML scraping methods.
 *
 * Key features:
 * - Automatic failover between multiple Nitter instances
 * - Time-based filtering of tweets
 * - Thread detection and processing
 * - Pagination support for retrieving historical tweets
 * - Parallel fetching for multiple users
 *
 * Usage example:
 * ```kotlin
 * val service = TwitterRssService(cookieJar)
 * val result = service.getUserTweets(
 *     username = "elonmusk",
 *     sinceTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L), // Last 7 days
 *     maxTweets = 50
 * )
 * result.fold(
 *     onSuccess = { tweets -> /* Process tweets */ },
 *     onFailure = { error -> /* Handle error */ }
 * )
 * ```
 *
 * @param cookieJar OkHttp cookie jar for managing cookies across requests
 *
 * @see TwitterWebScrapeService
 * @see Tweet
 * @see com.enmoble.common.social.twitter.util.Constants.Network.NITTER_INSTANCES
 */
@Singleton
class TwitterRssService @Inject constructor(
    cookieJar: CookieJar
) : TwitterWebScrapeService(cookieJar) {
    companion object {
        private const val LOGTAG = "TwitterRssService"
        private const val DEBUG_PRINT_TWEETS = true
        private const val DEFAULT_MAX_PAGES = TwitterWebScrapeService.DEFAULT_MAX_PAGES * 2  // RSS content size is smaller than HTML
    }
    private val rssParser = RssParser()
    private var nitterInstanceIndex = 0

    // Helper method to create the parser with custom User-Agent
    private fun createRssParserWithCustomUserAgent(): RssParser {
        return RssParserBuilder(
            callFactory = okHttpClient,
            charset = Charset.forName("UTF-8")
        ).build()
    }

    /**
     * Fetches tweets for a specified Twitter user using Nitter RSS feeds or HTML scraping.
     *
     * This method automatically tries multiple Nitter instances in sequence until one succeeds.
     * It supports time-based filtering and can retrieve historical tweets through pagination.
     *
     * The method will:
     * 1. Iterate through available Nitter instances
     * 2. Try both RSS and HTML scraping methods based on instance configuration
     * 3. Filter tweets based on the provided time constraints
     * 4. Detect and mark thread tweets appropriately
     * 5. Return tweets in reverse chronological order (newest first)
     *
     * @param username The Twitter username to fetch tweets for (without the @ symbol)
     * @param sinceTime Unix timestamp (milliseconds) - only fetch tweets posted at or after this time.
     *                  Defaults to 4 weeks ago. Use 0 to fetch all available tweets.
     * @param maxTweets Maximum number of tweets to return. Defaults to 200.
     * @param randomizeServer If true, starts with a different Nitter instance than the last one used
     *                        to distribute load across instances. Currently not implemented.
     *
     * @return Result<List<Tweet>> Success with list of tweets (sorted newest first),
     *         or Failure with exception if all Nitter instances fail
     *
     * @throws CancellationException if the coroutine is cancelled
     *
     * @see Tweet
     * @see getTweetsViaRss
     * @see TwitterWebScrapeService.getTweetsViaHtmlScraping
     */
    suspend fun getUserTweets(
        username: String,
        sinceTime: Long = System.currentTimeMillis() - DEFAULT_SEARCH_TIMEFRAME,
        maxTweets: Int = DEFAULT_MAX_TWEETS,
        randomizeServer: Boolean = false
    ): Result<List<Tweet>> = withContext(Dispatchers.IO) {
        try {
            Log.d(LOGTAG, "getUserTweets(): Fetching tweets for user: $username, sinceTime: [${if (sinceTime > 0) Date(sinceTime).toString() else "None"}], maxTweets: $maxTweets")

            // Try each Nitter instance starting from the last known successful one (OR a random one)
            // & go in order until one succeeds or we've finished trying all instances
            //if(randomizeServer) nitterInstanceIndex = (0..NITTER_INSTANCES.size - 1).random()
            //if(randomizeServer) nitterInstanceIndex = (nitterInstanceIndex + 2) % NITTER_INSTANCES.size

            for (i in 0 .. NITTER_INSTANCES.size - 1) {
                // Rotate to the next instance
                nitterInstanceIndex = (nitterInstanceIndex + i) % NITTER_INSTANCES.size
                val instance = NITTER_INSTANCES[nitterInstanceIndex]

                try {
                    Log.d(LOGTAG, "getUserTweets(): [${username}] Trying Nitter instance: ${instance.baseUrl}, isRss: ${instance.isRss}")

                    val result = if (instance.isRss) {
                        // Use RSS method
                        getTweetsViaRss(instance.baseUrl, username, sinceTime, maxTweets)
                    } else {
                        // Use HTML scraping method
                        getTweetsViaHtmlScraping(instance.baseUrl, username, sinceTime, maxTweets)
                    }

                    if (result.isSuccess && result.getOrNull()?.isNotEmpty() == true) {
                        val tweets = result.getOrNull() ?: emptyList()
                        Log.d(LOGTAG, "getUserTweets(): Successfully fetched tweets from ${instance.baseUrl}")
                        return@withContext(Result.success(tweets.sortedByDescending { it.timestamp }))
                    }
                } catch (e: Exception) {
                    when (e) {
                        is CancellationException -> throw e // Don't catch cancellation
                        is SocketTimeoutException,
                        is UnknownHostException,
                        is TimeoutException -> {
                            Log.w(LOGTAG, "getUserTweets(): Nitter instance ${instance.baseUrl} timed out or unreachable: ${e.message}")
                            // Continue to next instance
                        }
                        else -> {
                            Log.e(LOGTAG, "getUserTweets(): Error fetching from Nitter instance ${instance.baseUrl}", e)
                            // Continue to next instance
                        }
                    }
                }
            }

            // If we've tried all instances and none worked
            Log.e(LOGTAG, "getUserTweets(): All Nitter instances failed for user: $username")
            return@withContext Result.failure(Exception("getUserTweets(): Failed to fetch tweets from all Nitter instances"))
        } catch (e: Exception) {
            Log.e(LOGTAG, "getUserTweets(): Error in getting Tweets from network", e)
            return@withContext Result.failure(e)
        }
    }


    /**
     * Fetches tweets for multiple users concurrently using parallel coroutines.
     *
     * This method is more efficient than calling [getUserTweets] sequentially for multiple users
     * as it executes requests in parallel, significantly reducing total fetch time.
     *
     * Example:
     * ```kotlin
     * val results = service.getMultipleUserTweets(
     *     usernames = listOf("elonmusk", "BillGates", "tim_cook"),
     *     sinceTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000L), // Last 24 hours
     *     maxTweetsPerUser = 20
     * )
     *
     * results.forEach { (username, result) ->
     *     result.fold(
     *         onSuccess = { tweets -> println("$username: ${tweets.size} tweets") },
     *         onFailure = { error -> println("Failed for $username: ${error.message}") }
     *     )
     * }
     * ```
     *
     * @param usernames List of Twitter usernames to fetch tweets for (without @ symbols)
     * @param sinceTime Unix timestamp (milliseconds) - only fetch tweets posted at or after this time.
     *                  Defaults to 0 (no time filtering).
     * @param maxTweetsPerUser Maximum number of tweets to return per user. Defaults to 200.
     *
     * @return Map where keys are usernames and values are Results containing either
     *         a list of tweets or an exception for that specific user
     *
     * @see getUserTweets
     */
    suspend fun getMultipleUserTweets(
        usernames: List<String>,
        sinceTime: Long = 0,
        maxTweetsPerUser: Int = MAX_TWEETS_PER_THREAD
    ): Map<String, Result<List<Tweet>>> = coroutineScope {
        val results = mutableMapOf<String, Result<List<Tweet>>>()

        val deferredResults = usernames.map { username ->
            username to async {
                try {
                    getUserTweets(username, sinceTime, maxTweetsPerUser)
                } catch (e: Exception) {
                    Log.e(LOGTAG, "getMultipleUserTweets(): Error fetching tweets for $username", e)
                    Result.failure<List<Tweet>>(e)
                }
            }
        }

        deferredResults.forEach { (username, deferred) ->
            results[username] = try {
                deferred.await()
            } catch (e: Exception) {
                Log.e(LOGTAG, "getMultipleUserTweets(): Error awaiting result for $username", e)
                Result.failure(e)
            }
        }
        results
    }

    /**
     * Internal method to fetch tweets using RSS feed parsing from a specific Nitter instance.
     *
     * This method handles:
     * - Pagination through multiple pages using the Min-Id cursor
     * - Time-based filtering to stop when reaching old tweets
     * - RSS feed parsing and conversion to Tweet objects
     * - Thread detection and marking
     *
     * RSS method advantages:
     * - Generally faster and more lightweight than HTML scraping
     * - Cleaner, more structured data
     * - Better for recent tweets
     *
     * RSS method limitations:
     * - May not provide as much historical data as HTML scraping
     * - Some Nitter instances have unreliable RSS feeds
     *
     * @param baseUrl The base URL of the Nitter instance (e.g., "https://nitter.net")
     * @param username The Twitter username to fetch tweets for
     * @param sinceTime Unix timestamp (milliseconds) - stop fetching when tweets older than this are encountered
     * @param maxTweets Maximum number of tweets to fetch. Use 0 for unlimited (up to maxPagesToAttempt pages)
     * @param maxPagesToAttempt Maximum number of pages to fetch before stopping. Defaults to 200 pages.
     *
     * @return Result<List<Tweet>> Success with tweets sorted by newest first, or Failure with exception
     */
    private suspend fun getTweetsViaRss(
        baseUrl: String,
        username: String,
        sinceTime: Long = 0,
        maxTweets: Int = DEFAULT_MAX_TWEETS,
        maxPagesToAttempt: Int = DEFAULT_MAX_PAGES,
    ): Result<List<Tweet>> = withContext(Dispatchers.IO) {
        try {
            Log.d(LOGTAG, "getTweetsViaRss(): Fetching tweets for [$username] via RSS from $baseUrl / [sinceTime: ${if (sinceTime > 0) Date(sinceTime).toString() else "None"}] / [maxTweets: $maxTweets]")

            val allTweets = mutableListOf<Tweet>()
            var cursor: String? = null
            var continueLoading = true
            var pageCount = 0

            // Variable to store Min-Id from response
            var minId: String? = null

            while (continueLoading && pageCount < maxPagesToAttempt) {
                pageCount++

                // Construct feed URL with optional cursor
                val feedUrl = if (cursor != null) {
                    "$baseUrl/$username/rss?cursor=$cursor"
                } else {
                    "$baseUrl/$username/rss"
                }

                Log.d(LOGTAG, "getTweetsViaRss(): For [$username] Fetching page [$pageCount] from $feedUrl")

                try {
                    // Create and execute the HTTP request
                    val request = Request.Builder().url(feedUrl).build()
                    val response =
                        if(feedUrl.contains("xcancel.com")) okHttpClientForRss.newCall(request).execute()
                        else okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.e(LOGTAG, "getTweetsViaRss(): For [$username] HTTP error ${response.code}")
                        break
                    }

                    // Extract the Min-Id header for pagination
                    minId = response.header("Min-Id")
                    Log.d(LOGTAG, "getTweetsViaRss(): For [$username] Min-Id header: $minId")

                    // Get the raw XML
                    val xmlContent = response.body?.string() ?: ""

                    // Parse the RSS feed
                    val channel = rssParser.parse(xmlContent)
                    val pageTweets = channel.items.map { item ->
                        item.toTweet(username)
                    }

                    if (pageTweets.isEmpty()) {
                        Log.d(LOGTAG, "getTweetsViaRss(): For [$username] No tweets found on page [$pageCount]")
                        break
                    }

                    Log.d(LOGTAG, "getTweetsViaRss(): For [$username] Found [${pageTweets.size}] tweets on page [$pageCount] / Total=" + (allTweets.size + pageTweets.size) + " [${allTweets.size} + ${pageTweets.size}]")

                    // Apply time filtering
                    var reachedTimeLimit = false
                    for (tweet in pageTweets.sortedByDescending { it.timestamp }) {
                        // Check if we've reached tweets older than our sinceTime
                        if (sinceTime > 0 && tweet.timestamp.time < sinceTime) {
                            Log.d(LOGTAG, "getTweetsViaRss(): For [$username] Reached tweets older than sinceTime [${if (sinceTime > 0) Date(sinceTime).toString() else "None"}]")
                            reachedTimeLimit = true
                            continueLoading = false
                            break
                        }

                        // Check if we've reached the maximum number of tweets
                        if (maxTweets > 0 && allTweets.size >= maxTweets) {
                            Log.d(LOGTAG, "getTweetsViaRss(): For [$username] Reached maximum tweet count: [$maxTweets]")
                            continueLoading = false
                            break
                        }
                        // This is within the given constraints so add it
                        allTweets.add(tweet)
                    }

                    // Update cursor for next page
                    cursor = minId
                    if (cursor == null || reachedTimeLimit) {
                        Log.d(LOGTAG, "getTweetsViaRss(): For [$username] " + if(reachedTimeLimit) "Time limit reached" else "No more pages")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(LOGTAG, "getTweetsViaRss(): For [$username] Error fetching page [$pageCount]", e)
                    break
                }
            }

            if (allTweets.isEmpty()) {
                return@withContext Result.failure(Exception("For [$username] No tweets found for [$username]"))
            }

            // At this point, the entire list of tweets has been obtained & it's in reverse chrono order. Now process
            // it to identify & mark thread tweets
            val processedTweets = processTweetsForThreads(allTweets)
            Log.d(LOGTAG, "getTweetsViaRss(): For [$username] Successfully fetched [${processedTweets.size}] tweets")
            return@withContext Result.success(processedTweets)

        } catch (e: Exception) {
            Log.e(LOGTAG, "getTweetsViaRss(): For [$username] Error", e)
            return@withContext Result.failure(e)
        }
    }

}


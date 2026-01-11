package com.enmoble.common.social.twitter.api

import android.util.Log
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.model.TwitterMedia
import com.enmoble.common.social.twitter.network.FeederOkHttpClientFactory.createFeederOkHttpClient
import com.enmoble.common.social.twitter.util.nitterMediaUrlToTwitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLDecoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Base service that fetches Twitter/X content via **HTML web-scraping** from Nitter-like frontends.
 *
 * This class contains the core scraping/pagination logic and helpers shared by higher-level services
 * such as [`TwitterRssService`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterRssService.kt:59).
 *
 * Key responsibilities:
 * - Build a “browser-like” [`OkHttpClient`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterWebScrapeService.kt:13)
 * - Fetch and parse HTML pages using Jsoup
 * - Extract tweet content, media, counts, and basic thread/reply signals
 * - Provide pagination via the “Load more” cursor
 * - Provide helpers for thread post-processing
 *
 * @param cookieJar Cookie jar used to persist cookies across requests (notably used for some Nitter
 * challenge flows and for RSS/HTML requests).
 */
@Singleton
open class TwitterWebScrapeService @Inject constructor(
    private val cookieJar: CookieJar
) {
    companion object {
        private const val LOGTAG = "#TwitterWebScrapeService"
        const val MAX_TWEETS_PER_THREAD = 200
        const val DEFAULT_MAX_TWEETS = 200
        const val DEFAULT_MAX_PAGES = 100
        const val DEFAULT_SEARCH_TIMEFRAME = 4*7*24*60*60*1000L  /** 4 weeks is default search timeframe for a topic */

        private fun extractProfileImageUrlFromJsoupDoc(document: Document): String? {
            return extractProfileInfoFromJsoupDoc(document)?.profileImageUrl?.nitterMediaUrlToTwitter()
        }

        /**
         * Alternative function that extracts multiple profile-related URLs from the HTML head.
         * This provides more comprehensive profile information including images and RSS feed.
         *
         * @param html The HTML content containing the head section
         * @return ProfileInfo object containing various profile URLs
         */
        private fun extractProfileInfoFromHtml(html: String): ProfileInfo? {
            return try { extractProfileInfoFromJsoupDoc(Jsoup.parse(html)) }
            catch (e: Exception) {
                Log.e(LOGTAG, "Error in Jsoup.parse() or extractProfileInfoFromJsoupDoc: ${e.message}")
                null
            }
        }

        private fun extractProfileInfoFromJsoupDoc(document: Document): ProfileInfo? {
            return try {
                val head = document.select("head")

                // Extract canonical URL (main profile URL)
                val canonicalUrl = head.select("link[rel=canonical]").first()?.attr("href")

                // Extract RSS feed URL
                val rssFeedUrl = head.select("link[type='application/rss+xml']").first()?.attr("href")

                // Extract profile image URL from og:image meta tag
                val profileImageUrl = head.select("meta[property='og:image']").first()?.attr("content")

                // Extract profile banner URL from preload links
                val bannerImageUrl = head.select("link[rel=preload][as=image]")
                    .firstOrNull { it.attr("href").contains("profile_banners") }
                    ?.attr("href")

                // Extract profile description from og:description
                val description = head.select("meta[property='og:description']").first()?.attr("content")

                // Extract profile title from og:title
                val title = head.select("meta[property='og:title']").first()?.attr("content")

                if (canonicalUrl.isNullOrEmpty()) {
                    null
                } else {
                    ProfileInfo(
                        profileUrl = canonicalUrl,
                        rssFeedUrl = rssFeedUrl,
                        profileImageUrl = profileImageUrl,
                        bannerImageUrl = bannerImageUrl,
                        description = description,
                        title = title
                    )
                }
            } catch (e: Exception) {
                Log.e(LOGTAG, "Error extracting profile info from HTML: ${e.message}")
                null
            }
        }

        /**
         * Marks thread starter/continuation tweets by scanning a reverse-chronological tweet list.
         *
         * This is a best-effort post-processing step that:
         * - sorts to ascending time,
         * - detects boundaries where a thread starts/ends based on `isPartOfThread`,
         * - assigns/propagates a `threadId`,
         * - flags the first tweet of each detected thread as `isThreadStarter`.
         *
         * @param descendingTweets Tweets in reverse chronological order (newest first).
         * @return A new list of tweets (newest first) with thread metadata updated.
         */
        fun processTweetsForThreads(descendingTweets: List<Tweet>): List<Tweet> {
            if(descendingTweets.isEmpty()) return descendingTweets

            val tweets = descendingTweets.sortedBy { it.timestamp }.distinct()
            val outList = ArrayList<Tweet>()
            var threadId: String? = null
            var prevTweet = tweets.get(0)
            var currTweet: Tweet? = null
            outList.add(prevTweet)

            // If first tweet is already part of thread then this is an ongoing thread whose threadId may not be known so set
            // threadId to first tweet's id
            if(prevTweet.isPartOfThread && prevTweet.threadId == null) prevTweet = prevTweet.copy(threadId = prevTweet.id)

            for(i in 1 until tweets.size) {
                currTweet = tweets.get(i)
                if(currTweet.isPartOfThread && !prevTweet.isPartOfThread) {
                    // Previous tweet is a thread-start tweet
                    threadId = prevTweet.id
                    prevTweet = prevTweet.copy(threadId = threadId, isThreadStarter = true, isPartOfThread = true)
                    currTweet = currTweet.copy(threadId = threadId)
                    // Replace the previously added tweet with updated data to reflect it's a threadStarter
                    if(outList.isNotEmpty()) outList.removeAt(outList.lastIndex)
                    outList.add(prevTweet)
                    outList.add(currTweet)
                    Log.d(LOGTAG, "processTweetsForThreads(): [Indx:$i] Found Thread-START Tweet: tweetId=[${prevTweet.id}] / ThreadId=[$threadId] / Text=[${prevTweet.content.take(200)}]")

                    prevTweet = currTweet
                    continue
                } else if(! currTweet.isPartOfThread && prevTweet.isPartOfThread) {
                    // Previous tweet was the end of the thread
                    Log.d(LOGTAG, "processTweetsForThreads(): [Indx:$i] Found Thread-END Tweet: tweetId=[${prevTweet.id}] / ThreadId=[$threadId] / Text=[${prevTweet.content.take(200)}]")
                    threadId = null
                } else if(currTweet.isPartOfThread && prevTweet.isPartOfThread) {
                    // Current tweet is part of ongoing thread
                    currTweet = currTweet.copy(threadId = threadId)
                    Log.d(LOGTAG, "processTweetsForThreads(): [Indx:$i] Found Thread-MIDDLE Tweet: tweetId=[${currTweet.id}] / ThreadId=[$threadId] / Text=[${currTweet.content.take(200)}]")
                } else { // (! currTweet.isPartOfThread && ! prevTweet.isPartOfThread)
                    // Current tweet is not part of any thread
                    Log.d(LOGTAG, "processTweetsForThreads(): [Indx:$i] Found NON-Thread Tweet: tweetId=[${currTweet.id}] / ThreadId=[$threadId] / Text=[${currTweet.content.take(200)}]")
                }
                outList.add(currTweet)
                prevTweet = currTweet
            }
            return outList.sortedByDescending { it.timestamp }
        }
    }
    
    protected val okHttpClient: OkHttpClient = createBrowserLikeHttpClient()
    protected val okHttpClientForRss: OkHttpClient = createFeederOkHttpClient()

    /**
     * Builds an [`OkHttpClient`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterWebScrapeService.kt:13) configured to look like a real browser.
     *
     * Nitter instances may apply basic bot protection; using realistic headers and enabling
     * Brotli decoding improves compatibility.
     *
     * @return A configured OkHttp client that follows redirects and uses the injected [cookieJar].
     */
    fun createBrowserLikeHttpClient(): OkHttpClient {
        val headerInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            // Create a request with exactly the same headers as the browser
            val requestWithHeaders = originalRequest.newBuilder()
                .removeHeader("Accept-Encoding")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.6")
                .header("Cache-Control", "max-age=0")
                .header("Priority", "u=0, i")
                .header("Sec-Ch-Ua", "\"Chromium\";v=\"136\", \"Brave\";v=\"136\", \"Not.A/Brand\";v=\"99\"")
                .header("Sec-Ch-Ua-Mobile", "?0")
                .header("Sec-Ch-Ua-Platform", "\"macOS\"")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Sec-Gpc", "1")
                .header("Upgrade-Insecure-Requests", "1")
                //.header("Connection", "keep-alive")
                .build()

            chain.proceed(requestWithHeaders)
        } // Interceptor

        // Add logging interceptor to see the complete request
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.d(LOGTAG, "HTTP-Request: $message")
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS  // Log everything including headers but not bodies
        }

        // Create a response interceptor to capture the Min-Id header
        val responseInterceptor = Interceptor { chain ->
            val response = chain.proceed(chain.request())
            // Log all response headers for debugging
            Log.d(LOGTAG, "Response headers:")
            response.headers.forEach { (name, value) ->
                Log.d(LOGTAG, "  $name: $value")
            }
            // Return the unmodified response
            response
        }

        return OkHttpClient.Builder()
            // Add support for the brotli compression algorithm used in the Accept-Encoding header
            .addInterceptor(BrotliInterceptor)
            .addInterceptor(headerInterceptor)  // First apply our exact browser headers
            //.addNetworkInterceptor(loggingInterceptor)  // Then log the request headers
            //.addNetworkInterceptor(responseInterceptor)  // Capture response headers
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Ensure we follow redirects like a browser would
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)   // NOTE: Needed both for RSS based requests as well as HTTP GET requests
            .build()
    }

    /**
     * Fetches tweets using HTML scraping from a Nitter instance.
     * This method is better for historical tweets.
     *
     * @param baseUrl The base URL of the Nitter instance
     * @param username The Twitter username to fetch tweets for
     * @param sinceTime Only fetch tweets posted at or after this timestamp
     * @param maxTweets Maximum number of tweets to return (0 for unlimited)
     * @return A Result containing a list of tweets or an error
     */
    protected suspend fun getTweetsViaHtmlScraping(
        baseUrl: String,
        username: String,
        sinceTime: Long = System.currentTimeMillis() - DEFAULT_SEARCH_TIMEFRAME,
        maxTweets: Int = DEFAULT_MAX_TWEETS
    ): Result<List<Tweet>> = withContext(Dispatchers.IO) {
        try {
            Log.d(LOGTAG, "getTweetsViaHtmlScraping(): Fetching tweets for $baseUrl/$username] via HTML scraping from ")

            val allTweets = mutableListOf<Tweet>()
            var nextCursor: String? = null
            var continueLoading = true
            var pageCount = 0

            while (continueLoading && pageCount < DEFAULT_MAX_PAGES) {
                pageCount++

                // Construct the URL with cursor for pagination
                val pageUrl = if (nextCursor != null) {
                    "$baseUrl/$username?cursor=$nextCursor"
                } else {
                    "$baseUrl/$username"
                }

                Log.d(LOGTAG, "getTweetsViaHtmlScraping(): Fetching page $pageCount from $pageUrl")

                try {
                    // Fetch the HTML page
                    val request = Request.Builder().url(pageUrl).build()
                    val response = okHttpClient.newCall(request).execute()

                    if (!response.isSuccessful) {
                        Log.e(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: HTTP error ${response.code}")
                        break
                    }

                    val html = response.body?.string() ?: ""
                    // Parse tweets from HTML
                    val pageTweets = parseTweetsFromHtml(html, username, baseUrl).sortedByDescending { it.timestamp }

                    if (pageTweets.isEmpty()) {
                        Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: No tweets found on page $pageCount")
                        break
                    }

                    Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Found ${pageTweets.size} tweets on page $pageCount")

                    // Apply time filtering
                    var reachedTimeLimit = false
                    for (tweet in pageTweets) {
                        // Check if we've reached tweets older than our sinceTime
                        if (sinceTime > 0 && tweet.timestamp.time < sinceTime) {
                            Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Reached tweets older than sinceTime (${tweet.timestamp}) ==> ADDING this final tweet OLDER than sinceTime [Tweet=${tweet.content.trim().take(50)}]")
                            reachedTimeLimit = true
                            continueLoading = false
                            allTweets.add(tweet)    // Add last tweet beyond requested sinceTime value (needed for properly polling tweets based on latest & oldestTweets)
                            break
                        }

                        allTweets.add(tweet)

                        // Check if we've reached the maximum number of tweets
                        if (maxTweets > 0 && allTweets.size >= maxTweets) {
                            Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Reached maximum tweet count: $maxTweets")
                            continueLoading = false
                            break
                        }
                    } //for(...
                    if(! continueLoading) break     // break out of while loop

                    // Extract the "Load more" cursor for pagination
                    nextCursor = extractLoadMoreCursor(html)
                    Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Next cursor: $nextCursor")

                    if (nextCursor == null || reachedTimeLimit) {
                        Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: [pageCount=$pageCount] " + if (reachedTimeLimit) "Reached 'sinceTime' limit" else "No more pages in html")
                        break
                    }

                } catch (e: Exception) {
                    Log.e(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Error fetching page $pageCount", e)
                    break
                }
            } //while(...

            if (allTweets.isEmpty()) {
                return@withContext Result.failure(Exception("No tweets found for $username"))
            }

            Log.d(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Successfully fetched ${allTweets.size} tweets")
            // At this point, the entire list of tweets has been obtained & it's in reverse chrono order. Now process
            // it to identify & mark thread tweets
            val processedTweets = processTweetsForThreads(allTweets.sortedByDescending { it.timestamp })
            return@withContext Result.success(processedTweets)

        } catch (e: Exception) {
            Log.e(LOGTAG, "getTweetsViaHtmlScraping(): For [$baseUrl/$username]: Error", e)
            return@withContext Result.failure(e)
        }
    }


    /**
     * Placeholder for future logic to merge thread tweets into an already-processed tweet list.
     *
     * Intended behavior (TODO):
     * - If `threadTweets` contains tweet IDs already present in `processedTweets`, replace those items.
     * - Otherwise append the missing thread tweets.
     * - Ensure the merged output preserves thread ordering.
     *
     * Currently unused.
     */
    fun updateProcessedTweets(processedTweets: List<Tweet>, threadTweets: List<Tweet>) {
        // TODO: implement merge behavior described above.
    }

    //TODO: IS this needed ?? Thread parsing already done in getTweetsViaHtmlScraping() -> processTweetsForThreads()
    /**
     * Fetches all tweets in a thread.
     *
     * @param baseUrl The base URL of the Nitter instance
     * @param username The Twitter username who created the thread
     * @param tweetId The ID of the thread starter tweet
     * @param maxTweets Maximum number of tweets to return (0 for unlimited)
     * @return A Result containing a list of tweets in the thread or an error
     */
    protected suspend fun getThreadTweetsIfPresent(
        tweetUrl: String,
        username: String,
        baseUrl: String,
        maxTweets: Int = MAX_TWEETS_PER_THREAD,
    ): Result<List<Tweet>> = withContext(Dispatchers.IO) {
        try {
            Log.d(LOGTAG, "getThreadTweetsIfPresent(): Fetching thread for tweet $tweetUrl")
            val html = fetchWithRetry(tweetUrl)

            // Parse the HTML to check if this is a thread
            val threadTweets = parseThreadFromHtml(html, baseUrl, username)

            if (threadTweets.isEmpty()) {
                Log.d(LOGTAG, "getThreadTweets(): No thread found for tweet $tweetUrl")
                return@withContext Result.failure(Exception("No thread found"))
            }

            // Limit the number of tweets if needed
            val limitedThreadTweets = if (maxTweets > 0 && threadTweets.size > maxTweets) {
                threadTweets.take(maxTweets)
            } else {
                threadTweets
            }

            Log.d(LOGTAG, "getThreadTweets(): Successfully extracted ${limitedThreadTweets.size} tweets from thread")
            return@withContext Result.success(limitedThreadTweets)
        } catch (e: Exception) {
            Log.e(LOGTAG, "getThreadTweets(): Error", e)
            return@withContext Result.failure(e)
        }
    }

    /**
     * Parse tweets from HTML content.
     */
    private fun parseTweetsFromHtml(html: String, username: String, baseUrl: String): List<Tweet> {
        val tweets = mutableListOf<Tweet>()

        try {
            val document = Jsoup.parse(html)
            val profileUrl = extractProfileImageUrlFromJsoupDoc(document)
            // Each tweet is in a div with class "timeline-item"
            val tweetElements = document.select("div.timeline-item")

            for (tweetElement in tweetElements) {
                // Extract tweet using the common function
                val tweet = extractTweetFromElement(tweetElement, baseUrl, profileUrl, username)
                if (tweet != null) {
                    tweets.add(tweet)
                }
            }
        } catch (e: Exception) {
            Log.e(LOGTAG, "Error parsing HTML: ${e.message}")
        }

        return tweets
    }

    /**
     * Parse tweets from a thread HTML page.
     */
    private fun parseThreadFromHtml(html: String, baseUrl: String, username: String): List<Tweet> {
        val threadTweets = mutableListOf<Tweet>()

        try {
            val document = Jsoup.parse(html)
            val profileUrl = extractProfileImageUrlFromJsoupDoc(document)
            // Check if this is a conversation/thread view
            val conversation = document.selectFirst("div.conversation")
            if (conversation == null) {
                Log.d(LOGTAG, "parseThreadFromHtml(): Not a thread view")
                return emptyList()
            }

            // Get all timeline items in the conversation
            // This will include tweets before the main tweet, the main tweet, and replies after
            val beforeTweets = conversation.select("div.before-tweet.thread-line div.timeline-item")
            val mainTweet = conversation.select("div.main-tweet div.timeline-item")
            val afterTweets = conversation.select("div.after-tweet.thread-line div.timeline-item")

            // Process tweets before the main tweet
            for (tweetElement in beforeTweets) {
                // We don't know the username at this point - extract it from the element
                val extractedUsername = tweetElement.selectFirst("a.username")?.text()?.removePrefix("@")
                //TODO: CHECK: Keep the original username instead of the extracted one
                extractTweetFromElement(tweetElement, baseUrl, profileUrl, username)?.let {
                    threadTweets.add(it.copy(isPartOfThread = true))
                }
            }

            // Process the main tweet
            for (tweetElement in mainTweet) {
                val extractedUsername = tweetElement.selectFirst("a.username")?.text()?.removePrefix("@")
                extractTweetFromElement(tweetElement, baseUrl, extractedUsername ?: "unknown")?.let {
                    threadTweets.add(it.copy(isPartOfThread = true))
                }
            }

            // Process tweets after the main tweet
            for (tweetElement in afterTweets) {
                val extractedUsername = tweetElement.selectFirst("a.username")?.text()?.removePrefix("@")
                extractTweetFromElement(tweetElement, baseUrl, profileUrl, extractedUsername ?: "unknown")?.let {
                    threadTweets.add(it.copy(isPartOfThread = true))
                }
            }

            // Sort tweets by timestamp to ensure proper order
            return threadTweets.sortedBy { it.timestamp }
        } catch (e: Exception) {
            Log.e(LOGTAG, "parseThreadFromHtml(): Error parsing thread HTML", e)
            return emptyList()
        }
    }

    /**
     * Extract a tweet from a timeline item element.
     * @param tweetElement The JSoup element representing the tweet
     * @param baseUrl The base URL of the Nitter instance
     * @param username The username of the tweet author (if known)
     * @return A Tweet object or null if extraction fails
     */
    private fun extractTweetFromElement(
        tweetElement: org.jsoup.nodes.Element,
        baseUrl: String,
        profileUrl: String?,
        username: String? = null
    ): Tweet? {
        try {
            // Skip certain types of tweets
            if (tweetElement.hasClass("more-replies") ||
                tweetElement.hasClass("unavailable") ||
                tweetElement.hasClass("ad") ||
                tweetElement.hasClass("show-more")) {
                return null
            }

            // Get the tweet body
            val tweetBody = tweetElement.selectFirst("div.tweet-body") ?: return null

            // Get the tweet content
            val contentElement = tweetBody.selectFirst("div.tweet-content") ?: return null
            val content = contentElement.text()

            // Get username if not provided
            val actualUsername = username ?: tweetBody.selectFirst("a.username")?.text()?.removePrefix("@") ?: return null

            // Get permalink/tweet ID
            val permalinkElement = tweetElement.selectFirst("a.tweet-link")
            val permalink = permalinkElement?.attr("href") ?: return null
            val tweetId = extractTweetIdFromPermalink(permalink)

            // Construct the full URL for the tweet
            val fullTweetUrl = if (permalink.startsWith("http")) permalink else "$baseUrl$permalink"

            // Get timestamp
            val timeElement = tweetBody.selectFirst("span.tweet-date a")
            val timestampText = timeElement?.attr("title") ?: timeElement?.text() ?: return null
            val timestamp = parseDate(timestampText)

            // Check if it's a reply
            val isReply = tweetBody.selectFirst("div.replying-to") != null
            val replyToUsername = tweetBody.selectFirst("div.replying-to a")?.text()?.removePrefix("@")

            // Check for thread indicators
            val isInThreadLine = tweetElement.parents().any { it.hasClass("thread-line") }
            val isInMainTweet = tweetElement.parents().any { it.hasClass("main-tweet") }
            val showMoreElement = tweetElement.parent()?.select("div.show-more")?.firstOrNull()
            val hasShowThread = showMoreElement != null && showMoreElement.text().contains("Show thread")

            // Determine if thread starter based on position in thread and other indicators
            val isThreadStarter = hasShowThread ||
                    (isInThreadLine && tweetElement == tweetElement.parent()?.children()?.firstOrNull())

            // Extract media
            val mediaElements = tweetBody.select("div.attachments img, div.attachments video")
            val media = mutableListOf<TwitterMedia>()

            for (mediaElement in mediaElements) {
                val mediaType = when (mediaElement.tagName()) {
                    "img" -> TwitterMedia.MediaType.IMAGE
                    "video" -> TwitterMedia.MediaType.VIDEO
                    else -> TwitterMedia.MediaType.UNKNOWN
                }

                val url = if (mediaElement.hasAttr("src")) {
                    val src = mediaElement.attr("src")
                    if (src.startsWith("/")) {
                        // Handle relative URLs
                        "$baseUrl$src"
                    } else {
                        src
                    }
                } else {
                    continue
                }

                val alt = mediaElement.attr("alt").takeIf { it.isNotEmpty() }

                media.add(TwitterMedia(
                    url = url,
                    type = mediaType,
                    altText = alt,
                    width = mediaElement.attr("width").toIntOrNull(),
                    height = mediaElement.attr("height").toIntOrNull()
                ))
            }

            // Get stats
            val statsElement = tweetBody.selectFirst("div.tweet-stats")
            var retweetCount = 0
            var likeCount = 0

            statsElement?.let { stats ->
                val statItems = stats.select("span.tweet-stat")
                for (statItem in statItems) {
                    val statText = statItem.text().trim()
                    when {
                        statText.contains("Retweet") -> {
                            retweetCount = extractNumberFromText(statText)
                        }
                        statText.contains("Like") -> {
                            likeCount = extractNumberFromText(statText)
                        }
                    }
                }
            }

            // Create the Tweet object
             return Tweet(
                id = tweetId,
                username = actualUsername,
                content = content,
                htmlContent = contentElement.html(),
                timestamp = timestamp,  // Time when tweet was actually posted by user
                link = fullTweetUrl,
                profileUrl = profileUrl ?: "",
                isThreadStarter = isThreadStarter,
                isPartOfThread = isThreadStarter || isReply || isInThreadLine || isInMainTweet,
                threadId = if(isThreadStarter) tweetId else null, // Only thread starters have their own ID as threadId
                isReply = isReply,
                replyToUsername = replyToUsername,
                replyToTweetId = null, // Can't reliably get this from HTML
                media = media,
                retweetCount = retweetCount,
                likeCount = likeCount,
                fetchedAt = Date(),     // Time when this service has retrieved the tweet (set to current time here)
                isPermanent = false,
                contentHash = content.hashCode().toString()
            )
        } catch (e: Exception) {
            Log.e(LOGTAG, "extractTweetFromElement(): Error", e)
            return null
        }
    }

    /**
     * Extracts the pagination cursor (“Load more”) from a Nitter HTML page.
     *
     * Many Nitter pages expose pagination via links containing a `cursor=...` query parameter.
     * This helper scans all `div.show-more a` links (in reverse order) and returns the first
     * decodable cursor found.
     *
     * @param html Raw HTML of the timeline page.
     * @return The decoded cursor value, or null if none could be found.
     */
    fun extractLoadMoreCursor(html: String): String? {
        try {
            val document = Jsoup.parse(html)

            // Get ALL div.show-more a elements, not just the first one
            val loadMoreLinks = document.select("div.show-more a")

            Log.d(LOGTAG, "extractLoadMoreCursor(): Found ${loadMoreLinks.size} div.show-more a elements")

            // Check each link to find one with a valid cursor
            for (index in loadMoreLinks.indices.reversed()) {
                val link = loadMoreLinks[index]
                val href = link.attr("href")
                Log.d(LOGTAG, "extractLoadMoreCursor(): Link [$index]: href='$href', text='${link.text()}'")

                if (href.isNotBlank()) {
                    val cursor = extractCursorFromUrl(href)
                    if (cursor != null && cursor.isNotBlank()) {
                        Log.d(LOGTAG, "extractLoadMoreCursor(): Found valid cursor in link [$index]: $cursor")
                        return cursor
                    }
                }
            }

            // If no valid cursor found in div.show-more a, try broader search
            //return findCursorInAlternativeElements(document)

        } catch (e: Exception) {
            Log.e(LOGTAG, "extractLoadMoreCursor(): Error extracting cursor", e)
        }

        return null
    }

    /**
     * Extract cursor from URL string
     */
    private fun extractCursorFromUrl(url: String): String? {
        if (url.isBlank()) return null

        val cursorPattern = "cursor=([^&]+)".toRegex()
        val match = cursorPattern.find(url)

        return match?.groupValues?.get(1)?.let { rawCursor ->
            try {
                // URL decode the cursor
                URLDecoder.decode(rawCursor, "UTF-8")
            } catch (e: Exception) {
                Log.w(LOGTAG, "extractCursorFromUrl(): Failed to URL decode cursor: $e")
                rawCursor // Return raw if decoding fails
            }
        }
    }

    /**
     * Extract a numeric value from text like "5.2K Retweets"
     */
    private fun extractNumberFromText(text: String): Int {
        val numPattern = "(\\d+(\\.\\d+)?)[KkMm]?".toRegex()
        val match = numPattern.find(text)
        return match?.let {
            val numStr = it.groupValues[1]
            val multiplier = when {
                text.contains("[KkMm]".toRegex()) -> {
                    when {
                        text.contains("[Kk]".toRegex()) -> 1000
                        text.contains("[Mm]".toRegex()) -> 1000000
                        else -> 1
                    }
                }
                else -> 1
            }
            (numStr.toDouble() * multiplier).toInt()
        } ?: 0
    }

    /**
     * Extract tweet ID from a permalink.
     */
    private fun extractTweetIdFromPermalink(permalink: String): String {
        // Expected format: "/username/status/1234567890#m"
        val pattern = "/status/(\\d+)(?:#\\w)?".toRegex()
        val match = pattern.find(permalink)
        return match?.groupValues?.getOrNull(1) ?: UUID.randomUUID().toString()
    }

    protected suspend fun fetchWithRetry(messyurl: String, maxRetries: Int = 3): String = withContext(Dispatchers.IO) {
        var retryCount = 0
        val url = messyurl.split("#")[0] // Remove fragment
        val requestBuilder = Request.Builder().url(url)
        var resCookie: String? = null

        while (retryCount < maxRetries) {
            try {
                // Add the <Res=> cookie to the request if we have one
                resCookie?.let { requestBuilder.header("Cookie", it) }
                val response = okHttpClient.newCall(requestBuilder.build()).execute()

                if(resCookie == null) resCookie = getResCookieFromHeaders(response)
                when {
                    response.isSuccessful -> {
                        // Success! Return the body
                        return@withContext response.body?.string() ?: ""
                    }
                    response.code == 503 -> {
                        // This is the challenge response - the cookies will be automatically saved by our cookie jar
                        Log.d(LOGTAG, "fetchWithRetry(): Received 503 challenge from $url (attempt ${retryCount + 1})")

                        // Try to extract the RES cookie from the HTML if we don't already have one
                        if(resCookie == null) {
                            Log.d(LOGTAG, "fetchWithRetry(): No RES cookie yet - trying to extract from response HTML...")
                            val html = response.body?.string() ?: ""
                            resCookie = getResCookieFromHtml(html)
                            if(resCookie != null) Log.d(LOGTAG, "fetchWithRetry(): Got [${resCookie}] from response HTML - using it in next request...")
                            else {
                                Log.w(LOGTAG, "fetchWithRetry(): No RES cookie in response HTML either - setting a RANDOM value to see if it works...")
                                resCookie = "RES=${UUID.randomUUID()}"
                            }
                        }
                        // Close the response body to avoid leaks
                        response.body?.close()
                        // Wait a short time before retrying (similar to what a browser would do)
                        delay(3000) // 2 seconds
                        retryCount++
                    }
                    else -> {
                        // Some other error
                        val errorBody = response.body?.string() ?: "No response body"
                        throw IOException("HTTP error ${response.code}: $errorBody")
                    }
                }
            } catch (e: Exception) {
                if (e is IOException) throw e
                Log.e(LOGTAG, "fetchWithRetry(): Error fetching $url (attempt ${retryCount + 1})", e)
                retryCount++
                delay(1000L * retryCount) // Incremental backoff
            }
        }

        throw IOException("fetchWithRetry(): Failed to fetch $url after $maxRetries attempts")
    }

    /**
     * Extracts the `res=...` cookie value from HTTP response headers, if present.
     *
     * Some Nitter instances respond with a challenge flow where a `res` cookie is required for
     * subsequent requests.
     *
     * @param response The HTTP response.
     * @return The `res=...` cookie (without attributes), or null if not present.
     */
    fun getResCookieFromHeaders(response: Response): String? {
        // Process the response to get cookies
        val cookies = response.headers("Set-Cookie")
        Log.d(LOGTAG, "getResCookieFromHeaders(): Initial response code: ${response.code}")
        Log.d(LOGTAG, "getResCookieFromHeaders(): Received cookies: $cookies")

        // Extract the "res" cookie value
        var resCookie: String? = null
        for (cookie in cookies) {
            if (cookie.startsWith("res=")) {
                resCookie = cookie.substringBefore(";")
                Log.d(LOGTAG, "getResCookieFromHeaders(): Found res cookie: $resCookie")
                return resCookie
            }
        }
        Log.d(LOGTAG, "getResCookieFromHeaders(): Res cookie not found in HTTP response headers")
        return null
    }

    /**
     * Attempts to extract the `res=...` cookie value from HTML returned by a challenge page.
     *
     * This uses a few heuristic regex patterns to locate cookie-setting JavaScript or hidden form fields.
     *
     * @param html HTML response body.
     * @return The `res=...` cookie string, or null if it cannot be found.
     */
    fun getResCookieFromHtml(html: String): String? {
        if(html.isEmpty()) return null
        var resCookie: String? = null
        val cookiePattern = "document\\.cookie\\s*=\\s*[\"']([^\"']+)[\"']".toRegex()
        val match = cookiePattern.find(html)

        if (match != null) {
            resCookie = match.groupValues[1]
            Log.d(LOGTAG, "getResCookieFromHtml(): Extracted cookie from HTML: $resCookie")
        } else {
            Log.d(LOGTAG, "getResCookieFromHtml(): No cookie found in HTML")

            // Try a few other common patterns
            val altPattern1 = "setCookie\\(['\"](res=[^'\"]+)['\"]\\)".toRegex()
            val altMatch1 = altPattern1.find(html)

            if (altMatch1 != null) {
                resCookie = altMatch1.groupValues[1]
                Log.d(LOGTAG, "getResCookieFromHtml(): Extracted cookie from HTML (alt pattern 1): $resCookie")
            } else { // Look for any input with name="res" in forms
                val formPattern = "<input[^>]+name=[\"']res[\"'][^>]+value=[\"']([^\"']+)[\"']".toRegex()
                val formMatch = formPattern.find(html)

                if (formMatch != null) {
                    resCookie = "res=" + formMatch.groupValues[1]
                    Log.d(LOGTAG, "Extracted cookie from form input: $resCookie")
                } else { // Log a portion of the HTML to debug
                    Log.d(LOGTAG, "getResCookieFromHtml(): No cookie found in HTML...Excerpt: ## ${html.take(1000)} ##")
                }
            }
        }
        return null
    }

    /**
     * Parse a date string into a Date object.
     */
    private fun parseDate(dateStr: String): Date {
        val formats = listOf(
            "MMM d, yyyy · h:mm a",
            "MMM d, yyyy · HH:mm",
            "d MMM yyyy · h:mm a",
            "d MMM yyyy · HH:mm",
            "yyyy-MM-dd HH:mm:ss"
        )

        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                return sdf.parse(dateStr) ?: Date()
            } catch (e: ParseException) {
                // Try next format
            }
        }

        // If parsing fails, return current date
        return Date()
    }
}

/**
 * Data class to hold comprehensive profile information extracted from HTML head.
 */
data class ProfileInfo(
    val profileUrl: String,                    // https://twitter.com/astronomer_zero
    val rssFeedUrl: String? = null,           // /astronomer_zero/rss
    val profileImageUrl: String? = null,      // Profile picture URL
    val bannerImageUrl: String? = null,       // Banner image URL
    val description: String? = null,          // Profile description
    val title: String? = null                 // Profile title/display name
)

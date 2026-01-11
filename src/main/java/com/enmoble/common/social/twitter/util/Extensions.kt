package com.enmoble.common.social.twitter.util

import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.model.TwitterMedia
import com.prof18.rssparser.model.RssItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.security.MessageDigest
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Extension functions for the Twitter RSS+Webscrape library.
 */

/**
 * Parse an RSS item into a Tweet object.
 */
fun RssItem.toTweet(username: String): Tweet {
    // Parse the HTML content to extract additional information
    val doc = Jsoup.parse(this.description ?: "")
    // Extract media elements
    val media = extractMedia(doc)
    // Check if this is part of a thread or a reply
    val isThreadStarter = this.description?.contains("Show thread") == true || 
                          doc.select(".show-thread").isNotEmpty()
    val isReply = this.title?.startsWith("R to @") == true || 
                 doc.select(".replying-to").isNotEmpty()
    
    // Extract reply information
    val (replyToUsername, replyToTweetId) = if (isReply) {
        extractReplyInfo(this.title ?: "", doc)
    } else {
        null to null
    }
    val isPartOfThread = replyToUsername?.equals(username, ignoreCase = true) ?: false
    
    // Clean the content (remove HTML tags)
    val cleanContent = doc.text()
    
    // Extract counts
    val (retweetCount, likeCount) = extractCounts(doc)
    
    // Generate a content hash to detect changes
    val contentHash = cleanContent.hash()
    val tweetId = extractTweetId(this.link ?: "")

    return Tweet(
        id = tweetId,
        username = username,
        content = cleanContent,
        htmlContent = this.description ?: "",
        timestamp = parseDate(this.pubDate),
        link = this.link ?: "",
        profileUrl = "",
        isThreadStarter = isThreadStarter,
        isPartOfThread = isThreadStarter || isPartOfThread, // Either a thread starter or a reply is part of a thread
        threadId = if(isThreadStarter) tweetId else null,
        isReply = isReply,
        replyToUsername = replyToUsername,
        replyToTweetId = replyToTweetId,
        media = media,
        retweetCount = retweetCount,
        likeCount = likeCount,
        contentHash = contentHash
    )
}

/**
 * Extract media elements from the HTML description of a tweet.
 */
private fun extractMedia(doc: Document): List<TwitterMedia> {
    val mediaElements = doc.select("img, video")
    return mediaElements.mapNotNull { element ->
        when (element.tagName()) {
            "img" -> {
                val url = element.attr("src")
                if (url.isNotEmpty() && !url.contains("avatar")) {
                    TwitterMedia(
                        url = url,
                        type = TwitterMedia.MediaType.IMAGE,
                        altText = element.attr("alt").takeIf { it.isNotEmpty() },
                        width = element.attr("width").toIntOrNull(),
                        height = element.attr("height").toIntOrNull()
                    )
                } else null
            }
            "video" -> {
                val url = element.attr("src")
                if (url.isNotEmpty()) {
                    TwitterMedia(
                        url = url,
                        type = TwitterMedia.MediaType.VIDEO,
                        thumbnailUrl = element.attr("poster").takeIf { it.isNotEmpty() },
                        width = element.attr("width").toIntOrNull(),
                        height = element.attr("height").toIntOrNull()
                    )
                } else null
            }
            else -> null
        }
    }
}

/**
 * Extract reply information from the title of a tweet and HTML content.
 */
private fun extractReplyInfo(title: String, doc: Document): Pair<String?, String?> {
    // Try to extract from title first
    val regexTitle = "R to @([\\w\\d_]+):.*".toRegex()
    val matchResultTitle = regexTitle.find(title)
    val usernameFromTitle = matchResultTitle?.groupValues?.getOrNull(1)
    
    // Try to extract from HTML content if not found in title
    val usernameFromHtml = if (usernameFromTitle == null) {
        val replyingToElement = doc.selectFirst(".replying-to a")
        replyingToElement?.text()?.replace("@", "")
    } else null
    
    return (usernameFromTitle ?: usernameFromHtml) to null // We can't get reply tweet ID reliably from RSS
}

/**
 * Extract retweet and like counts from the HTML description of a tweet.
 */
private fun extractCounts(doc: Document): Pair<Int, Int> {
    // Use a more functional approach with safe calls
    return doc.select(".tweet-stats")
        .firstOrNull()
        ?.let { statsElement ->
            val spans = statsElement.select("span")
            if (spans.size >= 2) {
                val retweets = spans[0].text().replace(",", "").toIntOrNull() ?: 0
                val likes = spans[1].text().replace(",", "").toIntOrNull() ?: 0
                retweets to likes
            } else {
                0 to 0
            }
        } ?: (0 to 0)
}

/**
 * Parse a date string into a Date object.
 */
private fun parseDate(dateString: String?): Date {
    if (dateString.isNullOrEmpty()) {
        return Date()
    }
    
    // RSS feed dates are typically in RFC 822 format
    val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
    )
    
    for (format in formats) {
        try {
            val sdf = SimpleDateFormat(format, Locale.US)
            return sdf.parse(dateString) ?: Date()
        } catch (e: ParseException) {
            // Try next format
        }
    }
    
    // If all parsing attempts fail, return current date
    return Date()
}

/**
 * Extract a tweet ID from a link.
 */
private fun extractTweetId(link: String): String {
    // Expected format: "https://nitter.net/username/status/1234567890"
    return link.substringAfterLast("/").takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
}

/**
 * Generate a hash for a string.
 */
fun String.hash(): String {
    val bytes = this.toByteArray()
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}

/**
 * Reorders threaded tweets chronologically while keeping non-threaded tweets
 * in their original reverse-chronological order.
 *
 * @param tweets List of tweets in reverse chronological order
 * @param endOfInputIsThreadEnd When true, assumes any ongoing thread at the end of input is complete.
 *                             When false, assumes there might be more tweets to add to the thread later.
 * @return List with threaded tweets reordered chronologically, non-threaded tweets unchanged
 */
fun orderThreadsChronologically(tweets: List<Tweet>, endOfInputIsThreadEnd: Boolean = true): List<Tweet> {
    if (tweets.isEmpty()) return tweets

    val result = mutableListOf<Tweet>()
    val currentThread = mutableListOf<Tweet>()
    var currentThreadId: String? = null

    for (i in tweets.indices) {
        val tweet = tweets[i]
        val nextTweet = tweets.getOrNull(i + 1)

        when {
            // Current tweet is part of a thread
            tweet.isPartOfThread -> {
                // If this is a new thread or different thread
                if (currentThreadId != tweet.threadId) {
                    // Finish processing the previous thread if any
                    if (currentThread.isNotEmpty()) {
                        processCurrentThread(currentThread, result, endOfInputIsThreadEnd)
                        currentThread.clear()
                    }
                    // Start new thread
                    currentThreadId = tweet.threadId
                }

                // Add tweet to current thread
                currentThread.add(tweet)

                // Check if thread ends here
                val threadEnds = nextTweet?.isPartOfThread != true ||
                        nextTweet.threadId != currentThreadId

                if (threadEnds) {
                    // Thread ends, process it
                    processCurrentThread(currentThread, result, endOfInputIsThreadEnd)
                    currentThread.clear()
                    currentThreadId = null
                }
            }

            // Current tweet is not part of a thread
            else -> {
                // Finish any ongoing thread first
                if (currentThread.isNotEmpty()) {
                    processCurrentThread(currentThread, result, endOfInputIsThreadEnd)
                    currentThread.clear()
                    currentThreadId = null
                }

                // Add non-threaded tweet as-is (maintains reverse-chrono order)
                result.add(tweet)
            }
        }
    }

    // Handle any remaining thread at the end of input
    if (currentThread.isNotEmpty()) {
        processCurrentThread(currentThread, result, endOfInputIsThreadEnd)
    }

    return result
}

/**
 * Helper function to process a complete thread and add it to the result list.
 */
private fun processCurrentThread(
    threadTweets: List<Tweet>,
    result: MutableList<Tweet>,
    endOfInputIsThreadEnd: Boolean
) {
    if (threadTweets.isEmpty()) return

    // Sort thread tweets chronologically (oldest first)
    val sortedThread = threadTweets.sortedBy { it.timestamp }

    // Find the thread starter (should be the first tweet chronologically)
    val threadStarter = sortedThread.firstOrNull { it.isThreadStarter }
        ?: sortedThread.first() // Fallback to first tweet if no explicit starter

    // If endOfInputIsThreadEnd is false and this thread doesn't have a clear starter,
    // it might be an incomplete thread, but we still process it

    // Add all thread tweets in chronological order
    result.addAll(sortedThread)
}
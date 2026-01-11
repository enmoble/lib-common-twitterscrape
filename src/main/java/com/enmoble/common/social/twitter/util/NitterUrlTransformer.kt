package com.enmoble.common.social.twitter.util

import android.util.Log
import com.enmoble.common.social.twitter.data.model.TwitterMedia
import com.enmoble.common.social.twitter.util.Constants.Network.NITTER_INSTANCES
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Utilities for converting between Nitter URLs and Twitter/X URLs.
 *
 * Nitter instances often wrap or proxy Twitter CDN URLs (media, thumbnails, videos). This helper
 * provides **best-effort** conversions in both directions, plus small type-detection helpers.
 *
 * Note: URL formats may change across Nitter/Twitter versions; treat these conversions as heuristics.
 */
object NitterUrlTransformer {
    private const val LOGTAG = "#NitterUrlTransformer"

    /**
     * Transforms a Nitter media URL (image, video, or GIF) into its equivalent Twitter CDN URL.
     *
     * Supported patterns include:
     * - `/video/<urlencoded https://video.twimg.com/...>` → decoded `https://video.twimg.com/...`
     * - `/pic/media%2F...` → `https://pbs.twimg.com/media/...`
     * - `/pic/(ext_tw_video_thumb|tweet_video_thumb|amplify_video_thumb...)` → `https://pbs.twimg.com/...`
     *
     * Examples:
     * - `https://nitter.net/pic/media/Gtk8HilbUAAUYM8.jpg?name=small&format=webp`
     *   → `https://pbs.twimg.com/media/Gtk8HilbUAAUYM8.jpg?name=small&format=webp`
     * - `https://nitter.net/video/https%3A//video.twimg.com/tweet_video/filename.mp4`
     *   → `https://video.twimg.com/tweet_video/filename.mp4`
     *
     * @param nitterUrl Nitter media URL.
     * @return Twitter CDN URL, or null if the URL cannot be recognized/decoded.
     */
    fun nitterMediaToTwitter(nitterUrl: String): String? {
        if (nitterUrl.isBlank()) return null

        try {
            // Pattern 1: Video URLs - /video/https%3A//video.twimg.com/...
            val videoPattern = """https?://[^/]+/video/(.+)""".toRegex()
            val videoMatch = videoPattern.find(nitterUrl)

            if (videoMatch != null) {
                val encodedVideoUrl = videoMatch.groupValues[1]
                val decodedVideoUrl = URLDecoder.decode(encodedVideoUrl, "UTF-8")
                Log.d(LOGTAG, "Transformed video URL: $nitterUrl -> $decodedVideoUrl")
                return decodedVideoUrl
            }

            // Pattern 2: URL-encoded pbs.twimg.com URLs - /pic/pbs.twimg.com%2F...
            val pbsEncodedPattern = """https?://[^/]+/pic/pbs\.twimg\.com%2F(.+)""".toRegex()
            val pbsEncodedMatch = pbsEncodedPattern.find(nitterUrl)

            if (pbsEncodedMatch != null) {
                val encodedPath = pbsEncodedMatch.groupValues[1]
                val decodedPath = URLDecoder.decode(encodedPath, "UTF-8")
                return "https://pbs.twimg.com/$decodedPath"
            }

            // Pattern 3: URL-encoded video.twimg.com URLs - /pic/video.twimg.com%2F...
            val videoEncodedPattern = """https?://[^/]+/pic/video\.twimg\.com%2F(.+)""".toRegex()
            val videoEncodedMatch = videoEncodedPattern.find(nitterUrl)

            if (videoEncodedMatch != null) {
                val encodedPath = videoEncodedMatch.groupValues[1]
                val decodedPath = URLDecoder.decode(encodedPath, "UTF-8")
                return "https://video.twimg.com/$decodedPath"
            }

            // Pattern 4: URL-encoded media URLs - /pic/media%2F...
            val mediaEncodedPattern = """https?://[^/]+/pic/media%2F(.+)""".toRegex()
            val mediaEncodedMatch = mediaEncodedPattern.find(nitterUrl)

            if (mediaEncodedMatch != null) {
                val encodedPath = mediaEncodedMatch.groupValues[1]
                val decodedPath = URLDecoder.decode(encodedPath, "UTF-8")
                return "https://pbs.twimg.com/media/$decodedPath"
            }

            // Pattern 5: Video thumbnails - /pic/ext_tw_video_thumb/...
            val videoThumbPattern = """https?://[^/]+/pic/(ext_tw_video_thumb/.+)""".toRegex()
            val videoThumbMatch = videoThumbPattern.find(nitterUrl)

            if (videoThumbMatch != null) {
                val videoThumbPath = videoThumbMatch.groupValues[1]
                return "https://pbs.twimg.com/$videoThumbPath"
            }

            // Pattern 6: GIF thumbnails - /pic/tweet_video_thumb/...
            val gifThumbPattern = """https?://[^/]+/pic/(tweet_video_thumb/.+)""".toRegex()
            val gifThumbMatch = gifThumbPattern.find(nitterUrl)

            if (gifThumbMatch != null) {
                val gifThumbPath = gifThumbMatch.groupValues[1]
                return "https://pbs.twimg.com/$gifThumbPath"
            }

            // Pattern 7: Amplify video thumbnails - /pic/amplify_video_thumb%2F...
            val amplifyThumbPattern = """https?://[^/]+/pic/(amplify_video_thumb.+)""".toRegex()
            val amplifyThumbMatch = amplifyThumbPattern.find(nitterUrl)

            if (amplifyThumbMatch != null) {
                val encodedAmplifyPath = amplifyThumbMatch.groupValues[1]
                val decodedAmplifyPath = URLDecoder.decode(encodedAmplifyPath, "UTF-8")
                return "https://pbs.twimg.com/$decodedAmplifyPath"
            }

            // Pattern 8: Regular images - /pic/media/filename (unencoded)
            val mediaPattern = """https?://[^/]+/pic/media/([^?]+)(\?.*)?""".toRegex()
            val mediaMatch = mediaPattern.find(nitterUrl)

            if (mediaMatch != null) {
                val filename = mediaMatch.groupValues[1]
                val queryParams = mediaMatch.groupValues[2]
                return "https://pbs.twimg.com/media/$filename$queryParams"
            }

            Log.w(LOGTAG, "No matching pattern found for URL: $nitterUrl")
            return null

        } catch (e: Exception) {
            Log.e(LOGTAG, "Error transforming Nitter URL: $nitterUrl", e)
            return null
        }
    }

    /**
     * Transforms a Twitter CDN media URL into an equivalent Nitter “wrapped” URL.
     *
     * Supported patterns:
     * - `https://video.twimg.com/...` → `https://<nitterDomain>/video/<urlencoded twitterUrl>`
     * - `https://pbs.twimg.com/...` → `https://<nitterDomain>/pic/pbs.twimg.com%2F<path>`
     *
     * @param twitterUrl Twitter CDN URL.
     * @param nitterDomain Nitter domain host (default `nitter.net`).
     * @return Nitter URL, or null if the input does not match known Twitter CDN patterns.
     */
    fun twitterMediaToNitter(twitterUrl: String, nitterDomain: String = "nitter.net"): String? {
        if (twitterUrl.isBlank()) return null

        try {
            // Pattern 1: Video URLs - https://video.twimg.com/...
            val videoPattern = """https://video\.twimg\.com/(.+)""".toRegex()
            val videoMatch = videoPattern.find(twitterUrl)

            if (videoMatch != null) {
                val encodedUrl = URLEncoder.encode(twitterUrl, "UTF-8")
                return "https://$nitterDomain/video/$encodedUrl"
            }

            // Pattern 2: Image/GIF URLs - https://pbs.twimg.com/...
            val imagePattern = """https://pbs\.twimg\.com/(.+)""".toRegex()
            val imageMatch = imagePattern.find(twitterUrl)

            return if (imageMatch != null) {
                val path = imageMatch.groupValues[1]
                val encodedPath = path.replace("/", "%2F")
                "https://$nitterDomain/pic/pbs.twimg.com%2F$encodedPath"
            } else {
                Log.w(LOGTAG, "No matching Twitter pattern found for URL: $twitterUrl")
                null
            }

        } catch (e: Exception) {
            Log.e(LOGTAG, "Error transforming Twitter URL: $twitterUrl", e)
            return null
        }
    }

    /**
     * Checks whether [url] looks like a Nitter media URL (image/video/GIF wrapper).
     *
     * @param url Any URL string.
     * @return true if the string contains Nitter-style media path markers.
     */
    fun isNitterMediaUrl(url: String): Boolean {
        return (url.contains("/pic/") || url.contains("/video/")) &&
            (url.contains("nitter.") ||
                    url.contains("pic/media/") ||
                    url.contains("pic/pbs.twimg.com") ||
                    url.contains("pic/ext_tw_video_thumb") ||
                    url.contains("pic/tweet_video_thumb") ||
                    url.contains("video/https%3A"))
    }

    /**
     * Checks whether [url] looks like a Twitter CDN media URL.
     *
     * @param url Any URL string.
     * @return true if it targets `pbs.twimg.com`, `video.twimg.com`, or other `twimg.com` domains.
     */
    fun isTwitterMediaUrl(url: String): Boolean {
        return url.contains("pbs.twimg.com") ||
            url.contains("video.twimg.com") ||
            url.contains("twimg.com")
    }

    /**
     * Heuristically determines whether [url] is a Twitter/X video URL.
     *
     * @param url Any URL string.
     * @return true if it matches known video host/path patterns or common video extensions.
     */
    fun isTwitterVideoUrl(url: String): Boolean {
        return url.contains("video.twimg.com") ||
            url.contains("/video/") ||
            url.contains("ext_tw_video") ||
            url.matches(""".*\.(mp4|mov|avi|webm|m4v)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE))
    }

    /**
     * Heuristically determines whether [url] is a Twitter/X GIF URL.
     *
     * @param url Any URL string.
     * @return true if it matches known tweet_video/gif patterns or `.gif`/`.mp4` tweet_video URLs.
     */
    fun isTwitterGifUrl(url: String): Boolean {
        return url.contains("tweet_video") ||
            url.contains("/tweet_video_thumb/") ||
            url.matches(""".*tweet_video.*\.(mp4|gif)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE)) ||
            url.matches(""".*\.(gif)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE))
    }

    /**
     * Heuristically determines whether [url] is a Twitter/X image URL (excluding GIFs/videos).
     *
     * @param url Any URL string.
     * @return true if it matches known image host/path patterns or common image extensions.
     */
    fun isTwitterImageUrl(url: String): Boolean {
        return (url.contains("pbs.twimg.com") ||
            url.contains("/pic/media/")) &&
            !isTwitterGifUrl(url) &&
            !isTwitterVideoUrl(url) ||
            url.matches(""".*\.(jpg|jpeg|png|webp)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE))
    }

    /**
     * Classifies a URL into a [`TwitterMedia.MediaType`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/model/TwitterMedia.kt:35).
     *
     * @param url Any URL string.
     * @return Detected media type.
     */
    fun getMediaType(url: String): TwitterMedia.MediaType {
        return when {
            isTwitterGifUrl(url) -> TwitterMedia.MediaType.GIF
            isTwitterVideoUrl(url) -> TwitterMedia.MediaType.VIDEO
            isTwitterImageUrl(url) -> TwitterMedia.MediaType.IMAGE
            else -> TwitterMedia.MediaType.UNKNOWN
        }
    }

    /**
     * Adds/overwrites Twitter CDN query parameters to request a specific image size.
     *
     * This is a convenience wrapper for building `?name=<size>&format=webp` URLs.
     *
     * @param baseUrl Twitter image base URL (may already contain query params).
     * @param size Desired size variant.
     * @return URL with normalized query parameters.
     */
    fun getOptimalImageUrl(baseUrl: String, size: ImageSize = ImageSize.MEDIUM): String {
        val cleanUrl = baseUrl.split("?")[0] // Remove existing params
        return when (size) {
            ImageSize.THUMB -> "$cleanUrl?name=thumb&format=webp"
            ImageSize.SMALL -> "$cleanUrl?name=small&format=webp"
            ImageSize.MEDIUM -> "$cleanUrl?name=medium&format=webp"
            ImageSize.LARGE -> "$cleanUrl?name=large&format=webp"
            ImageSize.ORIGINAL -> "$cleanUrl?name=orig&format=webp"
        }
    }

    /**
     * Converts a Nitter URL to the corresponding Twitter URL.
     *
     * @param nitterUrl The Nitter URL to convert
     * @return The corresponding Twitter URL, or null if the URL is invalid
     *
     * Example:
     * Input: "https://nitter.net/dotkrueger/status/1935400099297014166#m"
     * Output: "https://x.com/dotkrueger/status/1935400099297014166"
     */
    fun nitterTweetUrlToTwitter(nitterUrl: String): String? {
        return try {
            val url = URL(nitterUrl)
            val host = url.host.lowercase()

            // Check if this is actually a Nitter domain
            if (!NITTER_INSTANCES.any { it.baseUrl.lowercase().contains(host) }) return null
            /* var found = false
            NITTER_INSTANCES.map { if(it.baseUrl.lowercase().contains(host)) found = true }
            if(!found) return null */
            val path = url.path
            val fragment = url.ref // This gets the part after #

            // Build the Twitter URL
            val twitterUrl = StringBuilder("https://x.com")

            // Add the path (everything after the domain)
            twitterUrl.append(path)

            // Add query parameters if they exist (but usually we don't need them for Twitter)
            // We typically skip query params when converting to Twitter

            // Note: We don't include fragments (#m, etc.) in Twitter URLs as they're Nitter-specific

            twitterUrl.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Converts a Twitter URL to a Nitter URL using the specified Nitter instance.
     *
     * @param twitterUrl The Twitter URL to convert
     * @param nitterDomain The Nitter domain to use (default: nitter.net)
     * @return The corresponding Nitter URL, or null if the URL is invalid
     *
     * Example:
     * Input: "https://twitter.com/dotkrueger/status/1935400099297014166"
     * Output: "https://nitter.net/dotkrueger/status/1935400099297014166"
     */
    fun twitterToNitter(twitterUrl: String, nitterDomain: String = "nitter.net"): String? {
        return try {
            val url = URL(twitterUrl)
            val host = url.host.lowercase()

            // Check if this is actually a Twitter domain
            if (!host.contains("twitter.com") && !host.contains("x.com")) {
                return null
            }

            val path = url.path
            val query = url.query

            // Build the Nitter URL
            val nitterUrl = StringBuilder("https://$nitterDomain")

            // Add the path
            nitterUrl.append(path)

            // Add query parameters if they exist
            if (!query.isNullOrEmpty()) {
                nitterUrl.append("?").append(query)
            }

            nitterUrl.toString()
        } catch (e: Exception) {
            null
        }
    }

    enum class ImageSize {
        THUMB,    // Very small thumbnail
        SMALL,    // Small image
        MEDIUM,   // Medium image (default)
        LARGE,    // Large image
        ORIGINAL  // Original size
    }
}

/**
 * Extension functions for easier use.
 *
 * Note: Some "isTwitter*" extensions live in [`TwitterMediaDetectorUtil.kt`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:372).
 * To avoid package-level signature conflicts, the NitterUrlTransformer-flavored helpers use
 * distinct names here.
 */

/** @see NitterUrlTransformer.nitterMediaToTwitter */
fun String.nitterMediaUrlToTwitter(): String? = NitterUrlTransformer.nitterMediaToTwitter(this)

/** @see NitterUrlTransformer.twitterMediaToNitter */
fun String.twitterMediaUrlToNitter(): String? = NitterUrlTransformer.twitterMediaToNitter(this)

/** @see NitterUrlTransformer.isNitterMediaUrl */
fun String.isNitterMedia(): Boolean = NitterUrlTransformer.isNitterMediaUrl(this)

/** @see NitterUrlTransformer.isTwitterMediaUrl */
fun String.isTwitterMedia(): Boolean = NitterUrlTransformer.isTwitterMediaUrl(this)

/** @see NitterUrlTransformer.isTwitterVideoUrl */
fun String.isTwitterVideoUrl(): Boolean = NitterUrlTransformer.isTwitterVideoUrl(this)

/** @see NitterUrlTransformer.isTwitterImageUrl */
fun String.isTwitterImageUrl(): Boolean = NitterUrlTransformer.isTwitterImageUrl(this)

/** @see NitterUrlTransformer.isTwitterGifUrl */
fun String.isTwitterGifUrl(): Boolean = NitterUrlTransformer.isTwitterGifUrl(this)

/** @see NitterUrlTransformer.getMediaType */
fun String.getTwitterMediaTypeSimple(): TwitterMedia.MediaType = NitterUrlTransformer.getMediaType(this)
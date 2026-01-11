package com.enmoble.common.social.twitter.util

/**
 * Enum representing different types of Twitter/X media that can be inferred from a URL.
 *
 * This is a URL-shape heuristic used by [`TwitterMediaDetector.getMediaType()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:79).
 */
enum class TwitterMediaType {
    IMAGE,
    VIDEO,
    GIF,
    AUDIO,
    VIDEO_THUMBNAIL,
    LIVE_STREAM,
    UNKNOWN
}

/**
 * Detailed, heuristic media classification for a Twitter/X CDN URL.
 *
 * @property type Detected media category.
 * @property isAnimated True when the content is expected to be animated (e.g., Twitter “GIFs”).
 * @property hasAudio True when the content is expected to contain audio.
 * @property isLiveStream True when the URL looks like a live stream.
 * @property thumbnailUrl Best-effort derived thumbnail URL (mainly for videos/GIFs), if available.
 * @property fileExtension File extension inferred from the URL path (e.g., "mp4", "jpg"), if any.
 * @property estimatedFormat Best-effort format label (e.g., "twitter_video", "webp"), if any.
 */
data class TwitterMediaInfo(
    val type: TwitterMediaType,
    val isAnimated: Boolean = false,
    val hasAudio: Boolean = false,
    val isLiveStream: Boolean = false,
    val thumbnailUrl: String? = null,
    val fileExtension: String? = null,
    val estimatedFormat: String? = null
)

/**
 * Heuristic detector for Twitter/X media URLs.
 *
 * This utility inspects a URL string for known hostnames, path fragments, and/or file extensions
 * commonly used by Twitter's CDN, and produces a [`TwitterMediaInfo`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:27).
 *
 * Notes:
 * - Twitter “GIFs” are frequently served as MP4 videos; detection relies on known path patterns.
 * - URL formats can evolve; treat results as best-effort.
 */
object TwitterMediaDetector {
    
    // Video file extensions
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "avi", "mkv")
    
    // Image file extensions
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")
    
    // Audio file extensions
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac")
    
    // Video thumbnail patterns
    private val VIDEO_THUMBNAIL_PATTERNS = listOf(
        "ext_tw_video_thumb",
        "amplify_video_thumb", 
        "tweet_video_thumb"
    )
    
    // Video URL patterns
    private val VIDEO_URL_PATTERNS = listOf(
        "video.twimg.com",
        "ext_tw_video",
        "amplify_video"
    )
    
    // Live stream patterns
    private val LIVE_STREAM_PATTERNS = listOf(
        "live_video",
        "periscope",
        "broadcast"
    )
    
    // GIF patterns (Twitter treats animated GIFs as videos)
    private val GIF_PATTERNS = listOf(
        "tweet_video",
        "/tweet_video/",
        "/tweet_video_thumb/"
    )
    
    /**
     * Detects Twitter/X media type information from a URL string.
     *
     * Detection order matters; e.g. video thumbnails are detected before generic images.
     *
     * @param url The media URL to analyze (Twitter CDN, Nitter-converted CDN, etc.).
     * @return A [`TwitterMediaInfo`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:27) describing the detected media type.
     */
    fun getMediaType(url: String): TwitterMediaInfo {
        if (url.isBlank()) {
            return TwitterMediaInfo(TwitterMediaType.UNKNOWN)
        }
        
        val cleanUrl = url.lowercase().trim()
        val fileExtension = extractFileExtension(cleanUrl)
        
        return when {
            // Check for video thumbnails first
            isVideoThumbnail(cleanUrl) -> TwitterMediaInfo(
                type = TwitterMediaType.VIDEO_THUMBNAIL,
                thumbnailUrl = url,
                fileExtension = fileExtension,
                estimatedFormat = "thumbnail"
            )
            
            // Check for live streams
            isLiveStream(cleanUrl) -> TwitterMediaInfo(
                type = TwitterMediaType.LIVE_STREAM,
                isLiveStream = true,
                hasAudio = true,
                fileExtension = fileExtension,
                estimatedFormat = "live"
            )
            
            // Check for GIFs (Twitter's animated content)
            isGif(cleanUrl) -> TwitterMediaInfo(
                type = TwitterMediaType.GIF,
                isAnimated = true,
                hasAudio = false, // Twitter GIFs typically don't have audio
                thumbnailUrl = generateGifThumbnail(url),
                fileExtension = fileExtension,
                estimatedFormat = "gif"
            )
            
            // Check for videos
            isVideo(cleanUrl) -> TwitterMediaInfo(
                type = TwitterMediaType.VIDEO,
                hasAudio = true, // Assume videos have audio unless proven otherwise
                thumbnailUrl = getVideoThumbnailUrl(url),
                fileExtension = fileExtension,
                estimatedFormat = determineVideoFormat(cleanUrl)
            )
            
            // Check for audio
            isAudio(cleanUrl) -> TwitterMediaInfo(
                type = TwitterMediaType.AUDIO,
                hasAudio = true,
                fileExtension = fileExtension,
                estimatedFormat = determineAudioFormat(cleanUrl)
            )
            
            // Check for images
            isImage(cleanUrl) -> TwitterMediaInfo(
                type = TwitterMediaType.IMAGE,
                fileExtension = fileExtension,
                estimatedFormat = determineImageFormat(cleanUrl)
            )
            
            else -> TwitterMediaInfo(
                type = TwitterMediaType.UNKNOWN,
                fileExtension = fileExtension
            )
        }
    }
    
    /**
     * Checks if [url] looks like a Twitter video thumbnail URL.
     *
     * @param url URL to check.
     * @return true if the URL contains known thumbnail path fragments.
     */
    fun isVideoThumbnail(url: String): Boolean {
        return VIDEO_THUMBNAIL_PATTERNS.any { pattern ->
            url.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * Checks if [url] looks like a Twitter/X video URL.
     *
     * @param url URL to check.
     * @return true if the URL contains known video host/path fragments or a known video extension.
     */
    fun isVideo(url: String): Boolean {
        val fileExt = extractFileExtension(url)
        return VIDEO_URL_PATTERNS.any { pattern ->
            url.contains(pattern, ignoreCase = true)
        } || (fileExt != null && VIDEO_EXTENSIONS.contains(fileExt))
    }
    
    /**
     * Checks if [url] looks like a Twitter/X “GIF”.
     *
     * Twitter often serves animated GIFs via video endpoints; this checks common tweet_video patterns
     * and `.gif` extensions.
     *
     * @param url URL to check.
     * @return true if the URL matches known GIF patterns.
     */
    fun isGif(url: String): Boolean {
        val fileExt = extractFileExtension(url)
        return GIF_PATTERNS.any { pattern ->
            url.contains(pattern, ignoreCase = true)
        } || fileExt == "gif"
    }
    
    /**
     * Checks if [url] looks like a Twitter/X image URL (non-animated).
     *
     * @param url URL to check.
     * @return true if the URL matches known image host/path fragments or a known image extension.
     */
    fun isImage(url: String): Boolean {
        val fileExt = extractFileExtension(url)
        return (url.contains("pbs.twimg.com", ignoreCase = true) && 
                !isGif(url) && !isVideoThumbnail(url)) ||
               (fileExt != null && IMAGE_EXTENSIONS.contains(fileExt))
    }
    
    /**
     * Checks if [url] looks like an audio URL.
     *
     * @param url URL to check.
     * @return true if the URL matches known audio extensions or contains "audio".
     */
    fun isAudio(url: String): Boolean {
        val fileExt = extractFileExtension(url)
        return url.contains("audio", ignoreCase = true) ||
               (fileExt != null && AUDIO_EXTENSIONS.contains(fileExt))
    }
    
    /**
     * Checks if [url] looks like a live stream URL.
     *
     * @param url URL to check.
     * @return true if the URL contains known live-stream path fragments.
     */
    fun isLiveStream(url: String): Boolean {
        return LIVE_STREAM_PATTERNS.any { pattern ->
            url.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * Attempts to derive a thumbnail URL from a Twitter video URL.
     *
     * Example transformation:
     * `https://video.twimg.com/.../vid/file.mp4` → `https://pbs.twimg.com/.../img/file.jpg?name=small&format=webp`
     *
     * @param videoUrl A `video.twimg.com` URL.
     * @return A best-effort thumbnail URL, or null if [videoUrl] does not look like a Twitter video URL.
     */
    fun getVideoThumbnailUrl(videoUrl: String): String? {
        if (!videoUrl.contains("video.twimg.com")) return null

        return try {
            videoUrl
                .replace("video.twimg.com", "pbs.twimg.com")
                .replace("/vid/", "/img/")
                .replace("/pu/vid/", "/pu/img/")
                .replace(Regex("\\.(mp4|m4v|webm|mov)"), ".jpg")
                .let { url ->
                    if (url.contains("?")) {
                        url.substringBefore("?") + "?name=small&format=webp"
                    } else {
                        "$url?name=small&format=webp"
                    }
                }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Generate GIF thumbnail URL
     */
    private fun generateGifThumbnail(gifUrl: String): String? {
        return if (gifUrl.contains("tweet_video")) {
            gifUrl.replace("/tweet_video/", "/tweet_video_thumb/")
                .replace(Regex("\\.(mp4|gif)"), ".jpg")
                .let { url ->
                    if (url.contains("?")) {
                        url.substringBefore("?") + "?name=small&format=webp"
                    } else {
                        "$url?name=small&format=webp"
                    }
                }
        } else null
    }
    
    /**
     * Extract file extension from URL
     */
    private fun extractFileExtension(url: String): String? {
        return try {
            val cleanUrl = url.substringBefore("?") // Remove query parameters
            val lastDot = cleanUrl.lastIndexOf(".")
            val lastSlash = cleanUrl.lastIndexOf("/")
            
            if (lastDot > lastSlash && lastDot != -1) {
                cleanUrl.substring(lastDot + 1).lowercase()
            } else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Determine video format based on URL patterns
     */
    private fun determineVideoFormat(url: String): String? {
        return when {
            url.contains("ext_tw_video") -> "twitter_video"
            url.contains("amplify_video") -> "amplify_video"
            url.contains("tweet_video") -> "gif_video"
            extractFileExtension(url) != null -> extractFileExtension(url)
            else -> "video"
        }
    }
    
    /**
     * Determine audio format based on URL patterns
     */
    private fun determineAudioFormat(url: String): String? {
        return extractFileExtension(url) ?: "audio"
    }
    
    /**
     * Determine image format based on URL patterns
     */
    private fun determineImageFormat(url: String): String? {
        return when {
            url.contains("format=webp") -> "webp"
            url.contains("format=jpg") || url.contains("format=jpeg") -> "jpeg"
            url.contains("format=png") -> "png"
            extractFileExtension(url) != null -> extractFileExtension(url)
            else -> "image"
        }
    }
    
    /**
     * Builds an “optimized” URL for image/thumbnail media where Twitter supports size/format parameters.
     *
     * For detected images and video thumbnails, this returns:
     * `baseUrl?name=<size>&format=<format>`.
     *
     * For other media types (videos, audio, etc.), it returns [originalUrl] unchanged.
     *
     * @param originalUrl Source URL.
     * @param size Value for the Twitter CDN `name=` parameter (e.g., "small", "medium", "large").
     * @param format Value for the Twitter CDN `format=` parameter (e.g., "webp", "jpg").
     * @return Optimized URL when applicable, else [originalUrl].
     */
    fun getOptimizedMediaUrl(
        originalUrl: String,
        size: String = "medium",
        format: String = "webp"
    ): String {
        val mediaInfo = getMediaType(originalUrl)
        
        return when (mediaInfo.type) {
            TwitterMediaType.IMAGE -> {
                val baseUrl = originalUrl.substringBefore("?")
                "$baseUrl?name=$size&format=$format"
            }
            TwitterMediaType.VIDEO_THUMBNAIL -> {
                val baseUrl = originalUrl.substringBefore("?")
                "$baseUrl?name=$size&format=$format"
            }
            else -> originalUrl // Return original for videos, audio, etc.
        }
    }
}

/** Convenience extension for [`TwitterMediaDetector.getMediaType()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:79). */
fun String.getTwitterMediaType(): TwitterMediaInfo = TwitterMediaDetector.getMediaType(this)

/** Convenience extension for [`TwitterMediaDetector.isVideo()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:171). */
fun String.isTwitterVideo(): Boolean = TwitterMediaDetector.isVideo(this)

/** Convenience extension for [`TwitterMediaDetector.isImage()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:191). */
fun String.isTwitterImage(): Boolean = TwitterMediaDetector.isImage(this)

/** Convenience extension for [`TwitterMediaDetector.isGif()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:181). */
fun String.isTwitterGif(): Boolean = TwitterMediaDetector.isGif(this)

/** Convenience extension for [`TwitterMediaDetector.isAudio()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:201). */
fun String.isTwitterAudio(): Boolean = TwitterMediaDetector.isAudio(this)

/** Convenience extension for [`TwitterMediaDetector.isVideoThumbnail()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:161). */
fun String.isTwitterVideoThumbnail(): Boolean = TwitterMediaDetector.isVideoThumbnail(this)

/** Convenience extension for [`TwitterMediaDetector.isLiveStream()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:210). */
fun String.isTwitterLiveStream(): Boolean = TwitterMediaDetector.isLiveStream(this)

/** Convenience extension for [`TwitterMediaDetector.getVideoThumbnailUrl()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/TwitterMediaDetectorUtil.kt:221). */
fun String.getTwitterVideoThumbnail(): String? = TwitterMediaDetector.getVideoThumbnailUrl(this)

// Usage examples:
/*
fun main() {
    val urls = listOf(
        "https://pbs.twimg.com/media/test.jpg?name=large",
        "https://video.twimg.com/ext_tw_video/123/pu/vid/file.mp4",
        "https://pbs.twimg.com/ext_tw_video_thumb/123/pu/img/file.jpg",
        "https://video.twimg.com/tweet_video/animated.mp4",
        "https://pbs.twimg.com/tweet_video_thumb/gif_thumb.jpg"
    )
    
    urls.forEach { url ->
        val mediaInfo = url.getTwitterMediaType()
        println("URL: $url")
        println("Type: ${mediaInfo.type}")
        println("Has Audio: ${mediaInfo.hasAudio}")
        println("Is Animated: ${mediaInfo.isAnimated}")
        println("Thumbnail: ${mediaInfo.thumbnailUrl}")
        println("---")
    }
}
*/

package com.enmoble.common.social.twitter.util

/**
 * Helper utilities for working with Twitter image/video URLs.
 *
 * These are best-effort transformations primarily targeting Twitter's CDN domains:
 * - `pbs.twimg.com` for images/thumbnails
 * - `video.twimg.com` for videos
 *
 * URL shapes on Twitter/X may change over time; treat these helpers as convenience utilities.
 */
object ImageUtils {

    /**
     * Returns an “optimized” Twitter CDN URL for a requested size variant.
     *
     * For Twitter media URLs, Twitter supports `name=` and `format=` query parameters. This helper:
     * - strips existing query parameters,
     * - applies a `name=` based on [size],
     * - forces `format=webp` to reduce bandwidth in most image loaders.
     *
     * If the URL is not a Twitter CDN URL, it is returned unchanged.
     *
     * @param originalUrl Original media URL.
     * @param size Desired media size variant.
     * @return A rewritten URL when applicable, else [originalUrl].
     */
    fun getOptimizedImageUrl(originalUrl: String, size: ImageSize = ImageSize.MEDIUM): String {
        if (!originalUrl.contains("pbs.twimg.com") && !originalUrl.contains("video.twimg.com")) {
            return originalUrl
        }

        val baseUrl = originalUrl.split("?")[0] // Remove existing params
        return when (size) {
            ImageSize.THUMB -> "$baseUrl?name=thumb&format=webp"
            ImageSize.SMALL -> "$baseUrl?name=small&format=webp"
            ImageSize.MEDIUM -> "$baseUrl?name=medium&format=webp"
            ImageSize.LARGE -> "$baseUrl?name=large&format=webp"
            ImageSize.ORIGINAL -> "$baseUrl?name=orig&format=webp"
        }
    }

    /**
     * Heuristically checks whether [url] looks like a Twitter video thumbnail URL.
     *
     * @return true if [url] contains known Twitter thumbnail path fragments.
     */
    fun isVideoThumbnail(url: String): Boolean {
        return url.contains("ext_tw_video_thumb") ||
                url.contains("amplify_video_thumb") ||
                url.contains("tweet_video_thumb")
    }

    /**
     * Attempts to derive a thumbnail URL from a Twitter video URL.
     *
     * Example transformation:
     * `https://video.twimg.com/ext_tw_video/.../vid/file.mp4` →
     * `https://pbs.twimg.com/ext_tw_video_thumb/.../img/file.jpg?name=small&format=webp`
     *
     * @param videoUrl A `video.twimg.com` URL.
     * @return A best-guess thumbnail URL, or null if [videoUrl] is not a Twitter video URL.
     */
    fun getVideoThumbnailUrl(videoUrl: String): String? {
        if (!videoUrl.contains("video.twimg.com")) return null

        return videoUrl
            .replace("video.twimg.com", "pbs.twimg.com")
            .replace("/vid/", "/img/")
            .replace("/pu/vid/", "/pu/img/")
            .replace(".mp4", ".jpg")
            .replace(".m4v", ".jpg")
            .replace(".webm", ".jpg") + "?name=small&format=webp"
    }

    /**
     * Twitter CDN `name=` variants.
     */
    enum class ImageSize {
        THUMB,    // Very small thumbnail
        SMALL,    // Small image (good for profile pics)
        MEDIUM,   // Medium image (good for media previews)
        LARGE,    // Large image (good for full view)
        ORIGINAL  // Original size
    }
}

/**
 * Convenience extension for calling [`ImageUtils.getOptimizedImageUrl()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/ImageUtils.kt:16).
 *
 * @param size Desired media size variant.
 * @return Optimized URL when applicable, else the original string.
 */
fun String.optimizeForImageLoading(size: ImageUtils.ImageSize = ImageUtils.ImageSize.MEDIUM): String {
    return ImageUtils.getOptimizedImageUrl(this, size)
}

/**
 * Convenience extension for calling [`ImageUtils.getVideoThumbnailUrl()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/ImageUtils.kt:56).
 *
 * @return A best-guess thumbnail URL, or null if this string does not look like a Twitter video URL.
 */
fun String.getVideoThumbnail(): String? {
    return ImageUtils.getVideoThumbnailUrl(this)
}
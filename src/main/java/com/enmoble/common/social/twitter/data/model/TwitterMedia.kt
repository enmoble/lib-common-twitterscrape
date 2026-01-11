package com.enmoble.common.social.twitter.data.model

/**
 * Represents a media attachment (image, video, or GIF) associated with a tweet.
 *
 * This class encapsulates all metadata related to media content in tweets, including
 * URLs, dimensions, and accessibility information.
 *
 * @property url The direct URL to the media file (may be from Nitter or Twitter CDN)
 * @property type The type of media content (IMAGE, VIDEO, GIF, or UNKNOWN)
 * @property altText Alternative text description for accessibility (optional)
 * @property thumbnailUrl URL to a thumbnail/preview image (mainly used for videos, optional)
 * @property width Width of the media in pixels (optional)
 * @property height Height of the media in pixels (optional)
 *
 * @see Tweet
 * @see MediaType
 */
data class TwitterMedia(
    val url: String,
    val type: MediaType,
    val altText: String? = null,
    val thumbnailUrl: String? = null,
    val width: Int? = null,
    val height: Int? = null
) {
    /**
     * Enumeration of supported media types for Twitter content.
     *
     * @property IMAGE Static image content (JPG, PNG, WebP, etc.)
     * @property VIDEO Video content (MP4, etc.)
     * @property GIF Animated GIF content
     * @property UNKNOWN Unknown or unsupported media type
     */
    enum class MediaType {
        IMAGE,
        VIDEO,
        GIF,
        UNKNOWN
    }
}

package com.enmoble.common.social.twitter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.enmoble.common.social.twitter.data.db.converters.DateConverter
import com.enmoble.common.social.twitter.data.db.converters.TwitterMediaListConverter
import com.enmoble.common.social.twitter.util.nitterMediaUrlToTwitter
import java.util.Date

/**
 * Data class representing a tweet fetched from Twitter via Nitter instances.
 *
 * This class is used as both a domain model and a Room database entity, making it
 * convenient for caching tweets locally while maintaining a clean data structure.
 *
 * @property id Unique identifier for the tweet (extracted from the tweet URL)
 * @property username Twitter username who posted the tweet (without @ symbol)
 * @property content Plain text content of the tweet (HTML stripped)
 * @property htmlContent Original HTML content of the tweet as received from Nitter
 * @property timestamp Date and time when the tweet was originally posted
 * @property link Full URL to the tweet on the Nitter instance
 * @property profileUrl URL to the user's profile image
 * @property isThreadStarter Indicates if this tweet starts a thread (has continuation tweets)
 * @property isPartOfThread Indicates if this tweet is part of a thread (either starter or continuation)
 * @property threadId Identifier for the thread (typically the ID of the thread starter tweet)
 * @property isReply Indicates if this tweet is a reply to another tweet
 * @property replyToUsername Username of the tweet being replied to (null if not a reply)
 * @property replyToTweetId ID of the tweet being replied to (null if not a reply)
 * @property media List of media attachments (images, videos, GIFs) in the tweet
 * @property retweetCount Number of retweets this tweet has received
 * @property likeCount Number of likes this tweet has received
 * @property fetchedAt Date and time when this tweet was fetched by the library
 * @property isPermanent Flag indicating whether this tweet should be permanently stored in cache
 * @property contentHash SHA-256 hash of the content for detecting changes when the same tweet is refetched
 *
 * @see TwitterMedia
 * @see com.enmoble.common.social.twitter.data.db.TwitterDatabase
 */
@Entity(tableName = "tweets")
@TypeConverters(DateConverter::class, TwitterMediaListConverter::class)
data class Tweet(
    @PrimaryKey
    val id: String,
    val username: String,
    val content: String,
    val htmlContent: String,
    val timestamp: Date,
    val link: String,
    val profileUrl: String,
    val isThreadStarter: Boolean,
    val isPartOfThread: Boolean,
    val threadId: String? = null,
    val isReply: Boolean,
    val replyToUsername: String? = null,
    val replyToTweetId: String? = null,
    val media: List<TwitterMedia> = emptyList(),
    val retweetCount: Int = 0,
    val likeCount: Int = 0,
    val fetchedAt: Date = Date(),
    val isPermanent: Boolean = false,
    val contentHash: String
) {
    companion object {
        /**
         * Converts all Nitter media URLs in a tweet to official Twitter/X URLs.
         *
         * This is useful when you want to display media using Twitter's CDN instead of
         * the Nitter instance URLs, which may be less reliable or slower.
         *
         * @param tweet The tweet whose media URLs should be converted
         * @return A new Tweet instance with converted media URLs
         *
         * @see com.enmoble.common.social.twitter.util.nitterMediaUrlToTwitter
         */
        fun withTwitterMediaUrls(tweet: Tweet): Tweet {
            val transformedMedia = tweet.media.map { mediaItem ->
                mediaItem.copy(
                    url = mediaItem.url.nitterMediaUrlToTwitter() ?: mediaItem.url,
                    thumbnailUrl = mediaItem.thumbnailUrl?.let {
                        it.nitterMediaUrlToTwitter() ?: it
                    }
                )
            }
            return tweet.copy(media = transformedMedia)
        }
    }

    /**
     * Convenience method to convert this tweet's media URLs to Twitter URLs.
     *
     * @return A new Tweet instance with media URLs converted to Twitter's CDN
     * @see withTwitterMediaUrls
     */
    fun withTwitterMediaUrls(): Tweet = withTwitterMediaUrls(this)

    /**
     * Checks if this tweet has any media attachments.
     *
     * @return true if the tweet contains at least one media item (image, video, or GIF)
     */
    val hasMedia: Boolean
        get() = media.isNotEmpty()

    /**
     * Checks if this tweet contains image attachments.
     *
     * @return true if the tweet contains at least one image
     */
    val hasImages: Boolean
        get() = media.any { it.type == TwitterMedia.MediaType.IMAGE }

    /**
     * Checks if this tweet contains video attachments.
     *
     * @return true if the tweet contains at least one video
     */
    val hasVideos: Boolean
        get() = media.any { it.type == TwitterMedia.MediaType.VIDEO }

    /**
     * Checks if this tweet contains GIF attachments.
     *
     * @return true if the tweet contains at least one GIF
     */
    val hasGifs: Boolean
        get() = media.any { it.type == TwitterMedia.MediaType.GIF }
}

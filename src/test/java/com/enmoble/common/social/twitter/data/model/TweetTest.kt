package com.enmoble.common.social.twitter.data.model

import com.enmoble.common.social.twitter.util.hash
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * Unit tests for the Tweet data class
 */
class TweetTest {

    @Test
    fun `hasMedia returns true when tweet has media`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(
                    url = "https://example.com/image.jpg",
                    type = TwitterMedia.MediaType.IMAGE
                )
            )
        )
        
        assertTrue(tweet.hasMedia)
    }

    @Test
    fun `hasMedia returns false when tweet has no media`() {
        val tweet = createTestTweet(media = emptyList())
        
        assertFalse(tweet.hasMedia)
    }

    @Test
    fun `hasImages returns true when tweet contains images`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = "https://example.com/image1.jpg", type = TwitterMedia.MediaType.IMAGE),
                TwitterMedia(url = "https://example.com/video.mp4", type = TwitterMedia.MediaType.VIDEO)
            )
        )
        
        assertTrue(tweet.hasImages)
    }

    @Test
    fun `hasImages returns false when tweet contains no images`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = "https://example.com/video.mp4", type = TwitterMedia.MediaType.VIDEO)
            )
        )
        
        assertFalse(tweet.hasImages)
    }

    @Test
    fun `hasVideos returns true when tweet contains videos`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = "https://example.com/video.mp4", type = TwitterMedia.MediaType.VIDEO),
                TwitterMedia(url = "https://example.com/image.jpg", type = TwitterMedia.MediaType.IMAGE)
            )
        )
        
        assertTrue(tweet.hasVideos)
    }

    @Test
    fun `hasVideos returns false when tweet contains no videos`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = "https://example.com/image.jpg", type = TwitterMedia.MediaType.IMAGE)
            )
        )
        
        assertFalse(tweet.hasVideos)
    }

    @Test
    fun `hasGifs returns true when tweet contains GIFs`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = "https://example.com/animation.gif", type = TwitterMedia.MediaType.GIF),
                TwitterMedia(url = "https://example.com/image.jpg", type = TwitterMedia.MediaType.IMAGE)
            )
        )
        
        assertTrue(tweet.hasGifs)
    }

    @Test
    fun `hasGifs returns false when tweet contains no GIFs`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = "https://example.com/image.jpg", type = TwitterMedia.MediaType.IMAGE),
                TwitterMedia(url = "https://example.com/video.mp4", type = TwitterMedia.MediaType.VIDEO)
            )
        )
        
        assertFalse(tweet.hasGifs)
    }

    @Test
    fun `withTwitterMediaUrls converts Nitter URLs to Twitter URLs`() {
        val nitterMediaUrl = "https://nitter.net/pic/media%2FFexample.jpg"
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(url = nitterMediaUrl, type = TwitterMedia.MediaType.IMAGE)
            )
        )
        
        val convertedTweet = tweet.withTwitterMediaUrls()
        
        // The URL should be different (converted)
        assertNotEquals(nitterMediaUrl, convertedTweet.media[0].url)
        // Original tweet should be unchanged
        assertEquals(nitterMediaUrl, tweet.media[0].url)
    }

    @Test
    fun `withTwitterMediaUrls handles thumbnailUrl conversion`() {
        val nitterThumbUrl = "https://nitter.net/pic/media%2FFexample_thumb.jpg"
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(
                    url = "https://nitter.net/pic/media%2FFexample.mp4",
                    type = TwitterMedia.MediaType.VIDEO,
                    thumbnailUrl = nitterThumbUrl
                )
            )
        )
        
        val convertedTweet = tweet.withTwitterMediaUrls()
        
        // Thumbnail URL should also be converted
        assertNotNull(convertedTweet.media[0].thumbnailUrl)
        assertNotEquals(nitterThumbUrl, convertedTweet.media[0].thumbnailUrl)
    }

    @Test
    fun `withTwitterMediaUrls companion method works correctly`() {
        val tweet = createTestTweet(
            media = listOf(
                TwitterMedia(
                    url = "https://nitter.net/pic/media%2FFexample.jpg",
                    type = TwitterMedia.MediaType.IMAGE
                )
            )
        )
        
        val convertedTweet = Tweet.withTwitterMediaUrls(tweet)
        
        // Should produce same result as instance method
        assertEquals(tweet.withTwitterMediaUrls(), convertedTweet)
    }

    @Test
    fun `tweet equality works correctly`() {
        val tweet1 = createTestTweet(id = "123")
        val tweet2 = createTestTweet(id = "123")
        val tweet3 = createTestTweet(id = "456")
        
        assertEquals(tweet1, tweet2)
        assertNotEquals(tweet1, tweet3)
    }

    @Test
    fun `tweet copy works correctly`() {
        val original = createTestTweet(id = "123", username = "user1")
        val copied = original.copy(username = "user2")
        
        assertEquals("123", copied.id)
        assertEquals("user2", copied.username)
        assertEquals(original.content, copied.content)
        assertNotEquals(original, copied)
    }

    @Test
    fun `thread properties work correctly`() {
        val threadStarter = createTestTweet(
            id = "1",
            isThreadStarter = true,
            isPartOfThread = true,
            threadId = "1"
        )
        
        val threadContinuation = createTestTweet(
            id = "2",
            isThreadStarter = false,
            isPartOfThread = true,
            threadId = "1"
        )
        
        val regularTweet = createTestTweet(
            id = "3",
            isThreadStarter = false,
            isPartOfThread = false,
            threadId = null
        )
        
        assertTrue(threadStarter.isThreadStarter)
        assertTrue(threadStarter.isPartOfThread)
        assertEquals("1", threadStarter.threadId)
        
        assertFalse(threadContinuation.isThreadStarter)
        assertTrue(threadContinuation.isPartOfThread)
        assertEquals("1", threadContinuation.threadId)
        
        assertFalse(regularTweet.isThreadStarter)
        assertFalse(regularTweet.isPartOfThread)
        assertNull(regularTweet.threadId)
    }

    @Test
    fun `reply properties work correctly`() {
        val reply = createTestTweet(
            id = "2",
            isReply = true,
            replyToUsername = "originalUser",
            replyToTweetId = "1"
        )
        
        val nonReply = createTestTweet(
            id = "3",
            isReply = false,
            replyToUsername = null,
            replyToTweetId = null
        )
        
        assertTrue(reply.isReply)
        assertEquals("originalUser", reply.replyToUsername)
        assertEquals("1", reply.replyToTweetId)
        
        assertFalse(nonReply.isReply)
        assertNull(nonReply.replyToUsername)
        assertNull(nonReply.replyToTweetId)
    }

    // Helper function to create test tweets
    private fun createTestTweet(
        id: String = "123",
        username: String = "testuser",
        content: String = "Test tweet content",
        media: List<TwitterMedia> = emptyList(),
        isThreadStarter: Boolean = false,
        isPartOfThread: Boolean = false,
        threadId: String? = null,
        isReply: Boolean = false,
        replyToUsername: String? = null,
        replyToTweetId: String? = null
    ): Tweet {
        return Tweet(
            id = id,
            username = username,
            content = content,
            htmlContent = "<p>$content</p>",
            timestamp = Date(),
            link = "https://test.com/status/$id",
            profileUrl = "https://test.com/$username",
            isThreadStarter = isThreadStarter,
            isPartOfThread = isPartOfThread,
            threadId = threadId,
            isReply = isReply,
            replyToUsername = replyToUsername,
            replyToTweetId = replyToTweetId,
            media = media,
            retweetCount = 0,
            likeCount = 0,
            fetchedAt = Date(),
            isPermanent = false,
            contentHash = content.hash()
        )
    }
}
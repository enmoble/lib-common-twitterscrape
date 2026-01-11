package com.enmoble.common.social.twitter.util

import com.enmoble.common.social.twitter.data.model.Tweet
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

/**
 * Unit tests for extension functions in Extensions.kt
 */
class ExtensionsTest {

    @Test
    fun `hash generates consistent SHA-256 hash`() {
        val text = "Hello, World!"
        val hash1 = text.hash()
        val hash2 = text.hash()
        
        // Same input should produce same hash
        assertEquals(hash1, hash2)
        
        // Hash should be 64 characters (SHA-256 in hex)
        assertEquals(64, hash1.length)
        
        // Hash should be hexadecimal
        assertTrue(hash1.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `hash produces different hashes for different inputs`() {
        val text1 = "Hello, World!"
        val text2 = "Hello, World"
        
        val hash1 = text1.hash()
        val hash2 = text2.hash()
        
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `hash handles empty string`() {
        val emptyHash = "".hash()
        
        assertEquals(64, emptyHash.length)
        assertTrue(emptyHash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `orderThreadsChronologically handles empty list`() {
        val result = orderThreadsChronologically(emptyList())
        
        assertTrue(result.isEmpty())
    }

    @Test
    fun `orderThreadsChronologically preserves non-threaded tweets`() {
        val now = System.currentTimeMillis()
        val tweets = listOf(
            createTestTweet("1", now, isPartOfThread = false),
            createTestTweet("2", now - 1000, isPartOfThread = false),
            createTestTweet("3", now - 2000, isPartOfThread = false)
        )
        
        val result = orderThreadsChronologically(tweets)
        
        // Non-threaded tweets should remain in reverse chronological order
        assertEquals(3, result.size)
        assertEquals("1", result[0].id)
        assertEquals("2", result[1].id)
        assertEquals("3", result[2].id)
    }

    @Test
    fun `orderThreadsChronologically reorders thread tweets chronologically`() {
        val now = System.currentTimeMillis()
        val threadId = "thread1"
        
        // Create thread tweets in reverse chronological order (newest first)
        val tweets = listOf(
            createTestTweet("3", now, isPartOfThread = true, isThreadStarter = false, threadId = threadId),
            createTestTweet("2", now - 1000, isPartOfThread = true, isThreadStarter = false, threadId = threadId),
            createTestTweet("1", now - 2000, isPartOfThread = true, isThreadStarter = true, threadId = threadId)
        )
        
        val result = orderThreadsChronologically(tweets)
        
        // Thread tweets should be reordered chronologically (oldest first)
        assertEquals(3, result.size)
        assertEquals("1", result[0].id) // Oldest tweet first
        assertTrue(result[0].isThreadStarter)
        assertEquals("2", result[1].id)
        assertEquals("3", result[2].id) // Newest tweet last
    }

    @Test
    fun `orderThreadsChronologically handles mixed threaded and non-threaded tweets`() {
        val now = System.currentTimeMillis()
        val threadId = "thread1"
        
        val tweets = listOf(
            createTestTweet("5", now, isPartOfThread = false), // Regular tweet (newest)
            createTestTweet("4", now - 1000, isPartOfThread = true, threadId = threadId), // Thread tweet 2
            createTestTweet("3", now - 2000, isPartOfThread = true, isThreadStarter = true, threadId = threadId), // Thread tweet 1
            createTestTweet("2", now - 3000, isPartOfThread = false), // Regular tweet
            createTestTweet("1", now - 4000, isPartOfThread = false)  // Regular tweet (oldest)
        )
        
        val result = orderThreadsChronologically(tweets)
        
        assertEquals(5, result.size)
        
        // First regular tweet stays in position
        assertEquals("5", result[0].id)
        assertFalse(result[0].isPartOfThread)
        
        // Thread tweets are reordered chronologically
        assertEquals("3", result[1].id) // Thread starter (older)
        assertTrue(result[1].isThreadStarter)
        assertEquals("4", result[2].id) // Thread continuation (newer)
        
        // Remaining regular tweets maintain their order
        assertEquals("2", result[3].id)
        assertEquals("1", result[4].id)
    }

    @Test
    fun `orderThreadsChronologically handles multiple separate threads`() {
        val now = System.currentTimeMillis()
        val thread1Id = "thread1"
        val thread2Id = "thread2"
        
        val tweets = listOf(
            // Thread 2 (newer)
            createTestTweet("6", now, isPartOfThread = true, threadId = thread2Id),
            createTestTweet("5", now - 1000, isPartOfThread = true, isThreadStarter = true, threadId = thread2Id),
            // Thread 1 (older)
            createTestTweet("4", now - 2000, isPartOfThread = true, threadId = thread1Id),
            createTestTweet("3", now - 3000, isPartOfThread = true, isThreadStarter = true, threadId = thread1Id),
            // Regular tweets
            createTestTweet("2", now - 4000, isPartOfThread = false),
            createTestTweet("1", now - 5000, isPartOfThread = false)
        )
        
        val result = orderThreadsChronologically(tweets)
        
        assertEquals(6, result.size)
        
        // Thread 2 ordered chronologically (oldest first within thread)
        assertEquals("5", result[0].id)
        assertTrue(result[0].isThreadStarter)
        assertEquals("6", result[1].id)
        
        // Thread 1 ordered chronologically
        assertEquals("3", result[2].id)
        assertTrue(result[2].isThreadStarter)
        assertEquals("4", result[3].id)
        
        // Regular tweets maintain order
        assertEquals("2", result[4].id)
        assertEquals("1", result[5].id)
    }

    // Helper function to create test tweets
    private fun createTestTweet(
        id: String,
        timestamp: Long,
        isPartOfThread: Boolean = false,
        isThreadStarter: Boolean = false,
        threadId: String? = null
    ): Tweet {
        return Tweet(
            id = id,
            username = "testuser",
            content = "Test tweet $id",
            htmlContent = "<p>Test tweet $id</p>",
            timestamp = Date(timestamp),
            link = "https://test.com/status/$id",
            profileUrl = "https://test.com/testuser",
            isThreadStarter = isThreadStarter,
            isPartOfThread = isPartOfThread,
            threadId = threadId,
            isReply = false,
            replyToUsername = null,
            replyToTweetId = null,
            media = emptyList(),
            retweetCount = 0,
            likeCount = 0,
            fetchedAt = Date(),
            isPermanent = false,
            contentHash = "Test tweet $id".hash()
        )
    }
}
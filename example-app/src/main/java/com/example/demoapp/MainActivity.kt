package com.example.twitterscrape.demo

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twitterscrape.demo.databinding.ActivityMainBinding
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.repository.TwitterRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main activity demonstrating how to use the Twitter RSS+Webscrape library.
 *
 * This example shows:
 * - Dependency injection with Hilt
 * - Fetching tweets for a specific user
 * - Displaying tweets in a RecyclerView
 * - Error handling
 * - Loading states
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var tweetAdapter: TweetAdapter
    
    // Inject the TwitterRepository using Hilt
    @Inject
    lateinit var twitterRepository: TwitterRepository
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupRecyclerView()
        setupClickListeners()
        
        // Load initial tweets
        loadTweets("elonmusk")
    }
    
    private fun setupRecyclerView() {
        tweetAdapter = TweetAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = tweetAdapter
        }
    }
    
    private fun setupClickListeners() {
        binding.buttonLoad.setOnClickListener {
            val username = binding.editTextUsername.text.toString().trim()
            if (username.isNotEmpty()) {
                loadTweets(username)
            } else {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show()
            }
        }
        
        binding.buttonClearCache.setOnClickListener {
            lifecycleScope.launch {
                try {
                    twitterRepository.clearNonPermanentCache()
                    Toast.makeText(
                        this@MainActivity,
                        "Cache cleared",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    showError("Failed to clear cache: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Fetches tweets for the specified username and displays them.
     *
     * This method demonstrates:
     * - Using coroutines with lifecycleScope
     * - Handling loading states
     * - Error handling with Result
     * - Updating UI based on results
     */
    private fun loadTweets(username: String) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                // Fetch tweets from the last 7 days
                val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                
                val result = twitterRepository.getTweets(
                    username = username,
                    sinceTime = sevenDaysAgo,
                    cachedNetworkStorage = null, // Use local cache only
                    useRoomDbCache = true,
                    localCacheOnly = false,
                    maxResults = 20
                )
                
                result.fold(
                    onSuccess = { tweets ->
                        showTweets(tweets)
                        showMessage("Loaded ${tweets.size} tweets for @$username")
                    },
                    onFailure = { error ->
                        showError("Failed to load tweets: ${error.message}")
                        // Try loading from cache anyway
                        loadFromCacheOnly(username)
                    }
                )
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                showLoading(false)
            }
        }
    }
    
    /**
     * Attempts to load tweets from cache only (offline mode).
     */
    private suspend fun loadFromCacheOnly(username: String) {
        val cacheResult = twitterRepository.getTweets(
            username = username,
            sinceTime = 0,
            cachedNetworkStorage = null,
            useRoomDbCache = true,
            localCacheOnly = true, // Cache only
            maxResults = 20
        )
        
        cacheResult.fold(
            onSuccess = { tweets ->
                if (tweets.isNotEmpty()) {
                    showTweets(tweets)
                    showMessage("Showing ${tweets.size} cached tweets")
                }
            },
            onFailure = { /* Already showed error */ }
        )
    }
    
    private fun showTweets(tweets: List<Tweet>) {
        tweetAdapter.submitList(tweets)
        binding.textViewStatus.text = if (tweets.isEmpty()) {
            "No tweets found"
        } else {
            "${tweets.size} tweets"
        }
    }
    
    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.buttonLoad.isEnabled = !isLoading
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        binding.textViewStatus.text = message
    }
}
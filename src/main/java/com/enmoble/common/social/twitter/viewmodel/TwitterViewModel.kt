package com.enmoble.common.social.twitter.hilt.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.enmoble.common.social.twitter.background.TwitterFeedFetchWorker
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.repository.TwitterRepoReactive
import com.enmoble.common.social.twitter.data.repository.TwitterRepository
import com.enmoble.common.social.twitter.util.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * ViewModel for Twitter feed functionality using Hilt for dependency injection.
 *
 * This is intended as an optional demo-style ViewModel for [`TwitterFeedScreen`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/ui/TwitterFeedScreen.kt:79),
 * showing:
 * - one-shot tweet loading via [`TwitterRepository.getTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:180)
 * - cache observation via [`TwitterRepoReactive.observeTweetsFromCache()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepoReactive.kt:197)
 * - periodic background updates via WorkManager / [`TwitterFeedFetchWorker`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:25)
 */
@HiltViewModel
class TwitterViewModel @Inject constructor(
    private val reactiveRepo: TwitterRepoReactive,
    private val repository: TwitterRepository,
    private val workManager: WorkManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    // UI state
    private val _tweets = MutableStateFlow<List<Tweet>>(emptyList())
    val tweets: StateFlow<List<Tweet>> = _tweets
    
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    
    private val _isScheduled = MutableStateFlow(false)
    val isScheduled: StateFlow<Boolean> = _isScheduled
    
    init {
        // Check if we have an active worker
        viewModelScope.launch {
            val workInfos = workManager.getWorkInfosForUniqueWork(Constants.Worker.FEED_WORKER_NAME).get()
            _isScheduled.value = workInfos.isNotEmpty() && workInfos.any { !it.state.isFinished }
        }
    }
    
    /**
     * Loads tweets for the specified username.
     *
     * @param username Twitter username to load tweets for (without "@").
     * @param sinceTime Only fetch tweets posted at or after this timestamp (ms). Use 0 for no filter.
     * @param maxResults Maximum number of tweets to load.
     * @param useCache When true, uses Room cache if available and not stale.
     * @param storePermanently When true, upserts into cache instead of replacing.
     */
    fun loadTweets(
        username: String,
        sinceTime: Long = 0,
        maxResults: Int = 200,
        useCache: Boolean = false,          //TODO: change to true later
        storePermanently: Boolean = false   //TODO: change to true later
    ) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            
            try {
                val result = repository.getTweets(
                    username = username,
                    sinceTime = sinceTime,
                    cachedNetworkStorage = null,
                    maxResults = maxResults,
                    useRoomDbCache = useCache,
                    updateNotReplace = storePermanently
                )
                
                result.fold(
                    onSuccess = { tweetList ->
                        _tweets.value = tweetList
                    },
                    onFailure = { throwable ->
                        _error.value = throwable.message ?: "Unknown error"
                        
                        // Try cache as fallback
                        loadFromCache(username)
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error"
                loadFromCache(username)
            } finally {
                _loading.value = false
            }
        }
    }
    
    /**
     * Loads tweets from cache only.
     *
     * @param username Twitter username to load tweets for
     */
    private suspend fun loadFromCache(username: String) {
        val cacheResult = repository.getTweets(
            username = username,
            cachedNetworkStorage = null,
            useRoomDbCache = true,
            localCacheOnly = true
        )
        
        cacheResult.fold(
            onSuccess = { cacheTweets ->
                if (cacheTweets.isNotEmpty()) {
                    _tweets.value = cacheTweets
                }
            },
            onFailure = { /* Already showing error */ }
        )
    }
    
    /**
     * Observes cached tweets for a username and updates the UI state when changes happen.
     *
     * @param username Twitter username to observe.
     */
    fun observeTweets(username: String) {
        viewModelScope.launch {
            reactiveRepo.observeTweetsFromCache(username)
                .catch { e ->
                    _error.value = e.message ?: "Error observing tweets"
                }
                .collectLatest { tweets ->
                    // Only update if we have tweets and we're not loading
                    if (tweets.isNotEmpty() && !_loading.value) {
                        _tweets.value = tweets
                    }
                }
        }
    }
    
    /**
     * Loads tweets for multiple users in the background.
     *
     * @param usernames List of Twitter usernames to load tweets for
     * @param maxTweetsPerUser Maximum number of tweets per user
     */
    fun loadMultipleUsers(
        usernames: List<String>,
        maxTweetsPerUser: Int = 10
    ) {
        viewModelScope.launch {
            val results = repository.getMultipleUserTweets(
                usernames = usernames,
                maxTweetsPerUser = maxTweetsPerUser,
                storePermanently = true
            )
            
            // Process results if needed
            val allTweets = mutableListOf<Tweet>()
            
            results.forEach { (_, result) ->
                result.fold(
                    onSuccess = { tweets ->
                        allTweets.addAll(tweets)
                    },
                    onFailure = { /* Ignore errors */ }
                )
            }
            
            // If we have primary tweets shown, don't update the UI
            if (_tweets.value.isEmpty() && allTweets.isNotEmpty()) {
                _tweets.value = allTweets
            }
        }
    }
    
    /**
     * Schedules periodic background fetching of tweets.
     *
     * This schedules a periodic WorkManager request using the same input keys as
     * [`TwitterFeedFetchWorker`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:25).
     *
     * @param usernames Usernames to fetch periodically.
     * @param intervalMinutes Interval between runs, in minutes (WorkManager enforces a minimum).
     */
    fun scheduleBackgroundFetching(
        usernames: List<String>,
        intervalMinutes: Int = 15
    ) {
        // Create input data
        val inputData = Data.Builder()
            .putStringArray("usernames", usernames.toTypedArray())
            .putInt("max_tweets_per_user", 20)
            .build()
        
        // Create work request
        val workRequest = PeriodicWorkRequestBuilder<TwitterFeedFetchWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES
        )
            .setInputData(inputData)
            .build()
        
        // Schedule work
        workManager.enqueueUniquePeriodicWork(
            Constants.Worker.FEED_WORKER_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
        
        _isScheduled.value = true
    }
    
    /**
     * Cancels background fetching of tweets.
     */
    fun cancelBackgroundFetching() {
        workManager.cancelUniqueWork(Constants.Worker.FEED_WORKER_NAME)
        _isScheduled.value = false
    }
    
    /**
     * Toggles background fetching on/off.
     *
     * @param usernames Usernames to fetch periodically.
     * @param intervalMinutes Interval between fetches in minutes.
     * @return The new schedule state (true = scheduled, false = cancelled).
     */
    fun toggleBackgroundFetching(
        usernames: List<String>,
        intervalMinutes: Int = 15
    ): Boolean {
        if (_isScheduled.value) {
            cancelBackgroundFetching()
            return false
        } else {
            scheduleBackgroundFetching(usernames, intervalMinutes)
            return true
        }
    }
}

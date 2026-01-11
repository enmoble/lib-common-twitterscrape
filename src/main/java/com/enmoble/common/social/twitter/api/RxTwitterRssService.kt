package com.enmoble.common.social.twitter.api.rx

import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.repository.CachedNetworkStoreTweets
import com.enmoble.common.social.twitter.data.repository.TwitterRepoReactive
import com.enmoble.common.social.twitter.data.repository.TwitterRepository
import com.enmoble.common.social.twitter.util.Constants
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject


/**
 * RxJava wrapper around the coroutine-based repository APIs.
 *
 * This provides [`Single`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/RxTwitterRssService.kt:9)-based access to
 * [`TwitterRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:132)
 * and a simple polling-based [`Observable`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/RxTwitterRssService.kt:8) for cache observation.
 *
 * WARNING: Some RxJava functions are untested; treat as experimental and validate in your app.
 */
class RxTwitterRssService @Inject constructor(
    private val repository: TwitterRepository,
    private val reactiveRepo: TwitterRepoReactive,
    ) {
    
    /**
     * Gets tweets for a user as an RxJava Single.
     *
     * @param username Twitter username to fetch tweets for
     * @param useCache Whether to use the cache (if available and not stale)
     * @param cacheOnly Whether to only fetch from cache and not from network
     * @param storePermanently Whether to store the fetched tweets permanently
     * @param maxResults Maximum number of tweets to return
     * @param maxCacheAgeMs Maximum age of cache in milliseconds before it's considered stale
     * @return Single emitting a list of tweets or an error
     */
    fun getTweets(
        username: String,
        sinceTime: Long = 0,
        cachedNetworkStorage: CachedNetworkStoreTweets?,
        useCache: Boolean = true,
        cacheOnly: Boolean = false,
        storePermanently: Boolean = false,
        maxResults: Int = 200,
        maxCacheAgeMs: Long = Constants.Cache.DEFAULT_CACHE_EXPIRY_MS
    ): Single<List<Tweet>> {
        return Single.fromCallable {
            runBlocking {
                val result = repository.getTweets(
                    username = username,
                    sinceTime = sinceTime,
                    cachedNetworkStorage = cachedNetworkStorage,
                    useRoomDbCache = useCache,
                    localCacheOnly = cacheOnly,
                    updateNotReplace = storePermanently,
                    maxResults = maxResults,
                    maxCacheAgeMs = maxCacheAgeMs
                )
                
                result.getOrThrow()
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Observes tweets for a user as an RxJava Observable by polling the underlying Flow.
     *
     * Implementation note: this is currently implemented as a simple background thread that polls
     * [`TwitterRepoReactive.observeTweetsFromCache()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepoReactive.kt:181)
     * once per second and emits the latest list.
     *
     * @param username Twitter username to observe tweets for.
     * @param sinceTime Currently unused (cache observation does not filter by time yet).
     *
     * @return Observable emitting lists of tweets when they change.
     */
    fun observeTweets(
        username: String,
        sinceTime: Long = 0
    ): Observable<List<Tweet>> {
        return Observable.create { emitter ->
            val flow = reactiveRepo.observeTweetsFromCache(username = username)
            
            // Use a simple polling approach since we can't directly convert Flow to Observable
            // In a production app, you might want to use a more sophisticated approach
            val thread = Thread {
                try {
                    while (!emitter.isDisposed) {
                        runBlocking {
                            val tweets = flow.first()
                            emitter.onNext(tweets)
                        }
                        Thread.sleep(1000) // Poll every second
                    }
                } catch (e: Exception) {
                    if (!emitter.isDisposed) {
                        emitter.onError(e)
                    }
                }
            }
            
            thread.start()
            
            // Clean up when disposed
            emitter.setCancellable {
                thread.interrupt()
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Gets tweets for multiple users as an RxJava Single.
     *
     * @param usernames List of Twitter usernames to fetch tweets for
     * @param maxTweetsPerUser Maximum number of tweets to return per user
     * @param useCache Whether to use the cache (if available and not stale)
     * @param cacheOnly Whether to only fetch from cache and not from network
     * @param storePermanently Whether to store the fetched tweets permanently
     * @return Single emitting a map of username to list of tweets or error
     */
    fun getMultipleUserTweets(
        usernames: List<String>,
        maxTweetsPerUser: Int = 20,
        useCache: Boolean = true,
        cacheOnly: Boolean = false,
        storePermanently: Boolean = false
    ): Single<Map<String, List<Tweet>>> {
        return Single.fromCallable {
            runBlocking {
                val results = repository.getMultipleUserTweets(
                    usernames = usernames,
                    maxTweetsPerUser = maxTweetsPerUser,
                    useCache = useCache,
                    cacheOnly = cacheOnly,
                    storePermanently = storePermanently
                )
                
                // Convert the map of Results to a map of values or throw
                results.mapValues { (_, result) ->
                    result.getOrThrow()
                }
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Marks tweets as “permanent” in the local cache.
     *
     * This delegates to [`TwitterRepository.markTweetsAsPermanent()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:390).
     *
     * @param tweets Tweets to mark as permanent.
     * @return Single that completes when the operation is done.
     */
    fun markTweetsAsPermanent(tweets: List<Tweet>): Single<Unit> {
        return Single.fromCallable {
            runBlocking {
                repository.markTweetsAsPermanent(tweets)
            }
        }.subscribeOn(Schedulers.io())
    }
    
    /**
     * Clears all non-permanent tweets from the cache.
     *
     * This delegates to [`TwitterRepository.clearNonPermanentCache()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:397).
     *
     * @return Single that completes when the operation is done.
     */
    fun clearNonPermanentCache(): Single<Unit> {
        return Single.fromCallable {
            runBlocking {
                repository.clearNonPermanentCache()
            }
        }.subscribeOn(Schedulers.io())
    }
}

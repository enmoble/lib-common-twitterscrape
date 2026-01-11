# Twitter RSS+Webscrape Library Architecture

## Module Structure (as implemented)

Source root: [`src/main/java/com/enmoble/common/social/twitter`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter:1)

```
com.enmoble.common.social.twitter
├── api
│   ├── TwitterRssService.kt            # Fetch via RSS (with fallback); extends web-scrape service
│   ├── TwitterWebScrapeService.kt      # HTML scraping implementation + thread processing helpers
│   ├── FeederRSSClientOkHttpFactory.kt # OkHttp client factory for RSS-friendly settings
│   └── RxTwitterRssService.kt          # RxJava wrapper (package: com.enmoble.common.social.twitter.api.rx)
├── background
│   └── TwitterFeedFetchWorker.kt       # WorkManager periodic fetch + schedule/cancel helpers
├── data
│   ├── db
│   │   ├── TwitterDatabase.kt
│   │   ├── TweetDao.kt
│   │   └── Convertors.kt
│   ├── model
│   │   ├── Tweet.kt
│   │   └── TwitterMedia.kt
│   └── repo
│       ├── TwitterCacheRepository.kt   # Room cache wrapper
│       ├── TwitterRepository.kt        # Main repository API (package: com.enmoble.common.social.twitter.data.repository)
│       └── TwitterRepoReactive.kt      # Flow-based wrapper
├── di
│   └── TwitterHiltModule.kt            # Hilt providers (services, repos, ImageLoader, WorkManager)
├── ui
│   └── TwitterFeedScreen.kt            # Optional Compose demo UI (in a hilt/ui package)
├── util
│   ├── Constants.kt
│   ├── Extensions.kt                   # RSS parsing + hashing + thread ordering helpers
│   ├── ImageUtils.kt
│   ├── NitterUrlTransformer.kt
│   └── TwitterMediaDetectorUtil.kt
└── viewmodel
    └── TwitterViewModel.kt             # Optional ViewModel for Compose screen
```

## Core Components

### 1) Data models
- [`Tweet`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/model/Tweet.kt:1): domain + Room entity.
- [`TwitterMedia`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/model/TwitterMedia.kt:1): media attachments.

### 2) Fetch layer (network)
- [`TwitterRssService`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterRssService.kt:1): iterates configured Nitter instances and fetches via RSS or HTML depending on instance config.
- [`TwitterWebScrapeService`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterWebScrapeService.kt:1): HTML scraping + thread detection helpers.

### 3) Repository + cache
- [`TwitterRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:1): coordinates network + cache; optional “cached network store” integration via `CachedNetworkStoreTweets`.
- [`TwitterCacheRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterCacheRepository.kt:1): Room-backed cache access.
- [`TwitterRepoReactive`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepoReactive.kt:1): Flow wrappers and cache observation helpers.

### 4) Background processing
- [`TwitterFeedFetchWorker`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/background/TwitterFeedFetchWorker.kt:1): periodic fetch worker + `schedule()` / `cancel()` convenience methods.

### 5) DI + optional UI
- [`TwitterHiltModule`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/di/TwitterHiltModule.kt:1): Hilt providers (including `CookieJar`, `ImageLoader`, `WorkManager`).
- [`TwitterFeedScreen`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/ui/TwitterFeedScreen.kt:1) and [`TwitterViewModel`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/viewmodel/TwitterViewModel.kt:1) are optional “demo style” pieces living in the library module.

## Data flow (typical)
1. Client calls [`TwitterRepository.getTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepository.kt:180).
2. Repository checks either:
   - a provided `CachedNetworkStoreTweets`, or
   - local Room cache via [`TwitterCacheRepository`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterCacheRepository.kt:1).
3. If needed and allowed, repository fetches from network via [`TwitterRssService.getUserTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/TwitterRssService.kt:107).
4. Repository writes results back to cache/storage.
5. UI can observe cache via [`TwitterRepoReactive.observeTweetsFromCache()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/data/repo/TwitterRepoReactive.kt:181) (Flow) or [`RxTwitterRssService.observeTweets()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/api/RxTwitterRssService.kt:70) (Rx).

## Error handling (high level)
- Fetch layer: instance failover + IO exception handling.
- Repository: cache fallback when network fetch fails (if cached data exists).
- Worker: retries via WorkManager (`Result.retry()` on failures).

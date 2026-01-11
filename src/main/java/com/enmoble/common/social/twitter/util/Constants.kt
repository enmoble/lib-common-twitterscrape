package com.enmoble.common.social.twitter.util

/**
 * Constants used throughout the Twitter RSS+Webscrape library.
 */
object Constants {
    /**
     * Network related constants.
     */
    object Network {
        /**
         * Data class representing a Nitter instance with its URL and type.
         */
        data class NitterInstance(
            val baseUrl: String,
            val isRss: Boolean
        )

        /**
         * List of Nitter instances to use for RSS feeds, in order of preference.
         * The flag indicates whether to use RSS or HTML. For some instances, both methods should be tried.
         * We'll try each one in sequence if earlier ones fail.
         * CHECK HERE for Nitter Servers List & Operational Status:
         *      https://github.com/zedeus/nitter/wiki/Instances
         */
        val NITTER_INSTANCES = listOf(
            NitterInstance("https://nitter.net", false),                // Works for HTML
            NitterInstance("https://nitter.poast.org", true),           // Great for RSS - INDIAN IPs disallowed (US / Euro only)
            NitterInstance("https://nuku.trabun.org", true),            // RSS
            NitterInstance("https://nuku.trabun.org", false),           // HTML
            NitterInstance("https://nitter.kareem.one", true),          // RSS
            NitterInstance("https://nitter.kareem.one", false),         // HTML
            NitterInstance("https://nitter.tiekoetter.com", false),     // This is HTML only but shows all historical tweets
            NitterInstance("https://nitter.poast.org", false),          // HTML version if RSS fails - INDIAN IPs disallowed (US / Euro only)
            NitterInstance("https://nitter.privacyredirect.com", false), // HTML - has human browser detection
            NitterInstance("https://lightbrd.com", true),               // RSS
            NitterInstance("https://lightbrd.com", false),              // HTML
            NitterInstance("https://nitter.space", false),              // HTML
            NitterInstance("https://nitter.space", true),               // RSS
            NitterInstance("https://rss.xcancel.com", true),            // Says "Only allowed from an RSS parser" - TODO: Use non-browser HttpClient for RSS requests
            //NitterInstance("https://nitter.42l.fr", false),           // Service closed
            //NitterInstance("https://nitter.unixfox.eu", false),       // Doesn't exist
            //NitterInstance("https://nitter.pussthecat.org", false),   // Doesn't exist
        )
        
        /**
         * Default timeout for network requests in milliseconds.
         */
        const val DEFAULT_TIMEOUT_MS = 20000L
        
        /**
         * User agent to use for network requests.
         */
        const val USER_AGENT = "Mozilla/5.0 (Android) TwitterRssLib/1.0"
    }
    
    /**
     * Cache related constants.
     */
    object Cache {
        /**
         * Default cache expiration time in milliseconds (2 minutes).
         */
        const val DEFAULT_CACHE_EXPIRY_MS = 2 * 60 * 1000L
        
        /**
         * Maximum cache size in bytes (10 MB).
         */
        const val MAX_CACHE_SIZE_BYTES = 10 * 1024 * 1024L
    }
    
    /**
     * Worker related constants.
     */
    object Worker {
        /**
         * Default minimum interval between worker runs in seconds (15 minutes).
         */
        const val DEFAULT_MIN_INTERVAL_SECONDS = 15 * 60L
        
        /**
         * Unique name for the twitter feed worker.
         */
        const val FEED_WORKER_NAME = "twitter_feed_worker"
    }
}

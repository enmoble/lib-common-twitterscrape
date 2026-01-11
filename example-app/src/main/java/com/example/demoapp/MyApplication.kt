package com.example.twitterscrape.demo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for the Twitter RSS+Webscrape Library Demo App.
 *
 * This class demonstrates:
 * - Setting up Hilt for dependency injection
 * - Configuring WorkManager with Hilt for background tweet fetching
 *
 * Required in AndroidManifest.xml:
 * ```xml
 * <application
 *     android:name=".MyApplication"
 *     ...>
 * ```
 */
@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {
    
    /**
     * Injected worker factory that enables Hilt dependency injection in Workers.
     * This is required for TwitterFeedFetchWorker to receive its dependencies.
     */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    /**
     * Provides the WorkManager configuration.
     *
     * This implementation allows WorkManager to use Hilt for creating Worker instances,
     * enabling dependency injection in background workers like TwitterFeedFetchWorker.
     *
     * @return WorkManager configuration with Hilt-enabled worker factory
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

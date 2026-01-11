# Additional Resources for Hilt Integration

This document provides additional resources and information for integrating the Twitter RSS+Webscrape library with Hilt.

## Official Documentation

- [Hilt Documentation](https://developer.android.com/training/dependency-injection/hilt-android)
- [Using Hilt with WorkManager](https://developer.android.com/training/dependency-injection/hilt-jetpack#workmanager)
- [Hilt with Jetpack Compose](https://developer.android.com/jetpack/compose/libraries#hilt)

## Dependencies

Make sure you have the following dependencies in your app-level `build.gradle` file:

```gradle
// Hilt
implementation "com.google.dagger:hilt-android:2.44"
kapt "com.google.dagger:hilt-android-compiler:2.44"
implementation "androidx.hilt:hilt-navigation-compose:1.2.0"
implementation 'androidx.hilt:hilt-work:1.2.0'
kapt 'androidx.hilt:hilt-compiler:1.2.0'

// WorkManager
implementation 'androidx.work:work-runtime-ktx:2.8.1'
```

And in your project-level `build.gradle`:

```gradle
plugins {
    id 'com.android.application' version '8.0.2' apply false
    id 'com.android.library' version '8.0.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.8.10' apply false
    id 'com.google.dagger.hilt.android' version '2.44' apply false
}
```

## Setting Up WorkManager with Hilt

To use WorkManager with Hilt, you need to:

1. Add the `HiltWorkerFactory` to your application class:

```kotlin
@HiltAndroidApp
class MyApplication : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    }
}
```

2. Add the `@HiltAndroidApp` annotation to your Application class.

3. Add the following to your AndroidManifest.xml to disable automatic WorkManager initialization:

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

## Best Practices for Using This Library with Hilt

1. **Single Instance**: Use the `@Singleton` scope for all components that should be shared across the app, such as the `TwitterRepository`.

2. **Testability**: Create interfaces for dependencies to make testing easier. For example, create a `TwitterRepositoryInterface` that can be mocked in tests.

3. **Module Organization**: If your app grows large, consider creating separate Hilt modules for different features or components.

4. **Error Handling**: Implement proper error handling in your ViewModels to handle network failures and other errors.

5. **Custom Scopes**: Consider creating custom Hilt scopes if you need more fine-grained control over component lifecycles.

## Common Issues and Solutions

### 1. Circular Dependencies

If you encounter circular dependency issues, consider using `@Lazy` injection or creating a provider method to break the cycle.

### 2. WorkManager Initialization Errors

If you see errors related to WorkManager initialization, ensure you've correctly set up the `Configuration.Provider` in your Application class and removed the default initializer from the manifest.

### 3. Missing Dependencies in Workers

If workers can't access injected dependencies, ensure your custom `WorkerFactory` is correctly binding the dependencies when creating the worker.

### 4. Proguard/R8 Issues

If you encounter issues with Hilt in release builds, add the following to your ProGuard rules:

```
-keepclasseswithmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context,androidx.work.WorkerParameters);
}
```

## Need More Help?

If you encounter issues not covered in this document, check:

- [Hilt GitHub Issues](https://github.com/google/dagger/issues)
- [Stack Overflow - Hilt Tag](https://stackoverflow.com/questions/tagged/dagger-hilt)
- [Android Issue Tracker](https://issuetracker.google.com/)

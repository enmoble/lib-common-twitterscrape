package com.enmoble.common.social.twitter.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.enmoble.common.social.twitter.data.db.converters.DateConverter
import com.enmoble.common.social.twitter.data.db.converters.TwitterMediaListConverter
import com.enmoble.common.social.twitter.data.db.dao.TweetDao
import com.enmoble.common.social.twitter.data.model.Tweet

/**
 * Room database for caching Twitter data.
 */
@Database(
    entities = [Tweet::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(DateConverter::class, TwitterMediaListConverter::class)
abstract class TwitterDatabase : RoomDatabase() {
    abstract fun tweetDao(): TweetDao
}

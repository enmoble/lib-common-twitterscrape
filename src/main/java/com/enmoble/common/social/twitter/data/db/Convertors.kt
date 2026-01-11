package com.enmoble.common.social.twitter.data.db.converters

import androidx.room.TypeConverter
import com.enmoble.common.social.twitter.data.model.TwitterMedia
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

/**
 * Room TypeConverter for Date objects.
 */
class DateConverter {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }
}

/**
 * Room TypeConverter for TwitterMedia lists.
 */
class TwitterMediaListConverter {
    private val gson = Gson()
    
    @TypeConverter
    fun fromTwitterMediaList(value: List<TwitterMedia>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toTwitterMediaList(value: String): List<TwitterMedia> {
        val listType = object : TypeToken<List<TwitterMedia>>() {}.type
        return gson.fromJson(value, listType)
    }
}

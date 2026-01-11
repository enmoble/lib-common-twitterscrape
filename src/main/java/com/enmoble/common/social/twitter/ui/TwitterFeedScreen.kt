package com.enmoble.common.social.twitter.hilt.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.hilt.ui.viewmodel.TwitterViewModel
import com.enmoble.common.social.twitter.util.orderThreadsChronologically
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_TWEETS = 1000

/**
 * Main Compose screen for displaying Twitter feeds.
 *
 * This is an optional demo-style UI that shows how to:
 * - inject [`TwitterViewModel`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/viewmodel/TwitterViewModel.kt:29) via Hilt,
 * - fetch tweets and observe cache updates,
 * - render thread tweets chronologically using [`orderThreadsChronologically()`](lib-common-twitterscrape/src/main/java/com/enmoble/common/social/twitter/util/Extensions.kt:201).
 *
 * @param userName Initial Twitter username to display tweets for.
 * @param viewModel ViewModel (injected by Hilt by default).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwitterFeedScreen(
    userName: String = "milesdeutscher",
    viewModel: TwitterViewModel = hiltViewModel()
) {
    // State for editable username
    var username by remember { mutableStateOf(userName) }
    val tweets by viewModel.tweets.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val isScheduled by viewModel.isScheduled.collectAsState()
    val focusManager = LocalFocusManager.current

    // State for the date/time picker
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Default to 24 hours ago
    val calendar = remember { Calendar.getInstance() }
    calendar.add(Calendar.DAY_OF_YEAR, -1)

    var sinceTime by remember { mutableStateOf(calendar.timeInMillis) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()) }

    // Process tweets to order threads chronologically while keeping non-threaded tweets in reverse chronological order
    val orderedTweets = remember(tweets) {
        if (tweets.isEmpty()) {
            emptyList()
        } else {
            // First sort all tweets by timestamp (newest first) to ensure consistent ordering
            val sortedTweets = tweets.sortedByDescending { it.timestamp }
            // Then apply chronological ordering for threads
            orderThreadsChronologically(sortedTweets, endOfInputIsThreadEnd = true)
        }
    }

    // Load tweets when the screen is first displayed
    LaunchedEffect(Unit) {
        viewModel.loadTweets(username, sinceTime, MAX_TWEETS)
        viewModel.observeTweets(username)
    }

    // Date picker state
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = sinceTime)

    // Time picker state (initialized with current time from sinceTime)
    val timeCalendar = remember { Calendar.getInstance() }
    timeCalendar.timeInMillis = sinceTime

    val timePickerState = rememberTimePickerState(
        initialHour = timeCalendar.get(Calendar.HOUR_OF_DAY),
        initialMinute = timeCalendar.get(Calendar.MINUTE)
    )

    // Show date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let {
                        // Keep the time component from the current sinceTime
                        val newCalendar = Calendar.getInstance()
                        newCalendar.timeInMillis = it

                        val oldCalendar = Calendar.getInstance()
                        oldCalendar.timeInMillis = sinceTime

                        newCalendar.set(Calendar.HOUR_OF_DAY, oldCalendar.get(Calendar.HOUR_OF_DAY))
                        newCalendar.set(Calendar.MINUTE, oldCalendar.get(Calendar.MINUTE))
                        newCalendar.set(Calendar.SECOND, 0)

                        sinceTime = newCalendar.timeInMillis
                    }
                    showDatePicker = false
                    showTimePicker = true
                })
                {
                    Text("Next")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Show time picker dialog
    if (showTimePicker) {
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Select Time", style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(16.dp))
                    TimePicker(state = timePickerState)
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text("Cancel")
                        }
                        TextButton(onClick = {
                            // Combine the selected date with the selected time
                            val newCalendar = Calendar.getInstance()
                            newCalendar.timeInMillis = datePickerState.selectedDateMillis ?: sinceTime
                            newCalendar.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                            newCalendar.set(Calendar.MINUTE, timePickerState.minute)
                            newCalendar.set(Calendar.SECOND, 0)

                            sinceTime = newCalendar.timeInMillis
                            showTimePicker = false
                        }) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Username input field
        OutlinedTextField(
            value = username,
            onValueChange = { newUsername ->
                // Remove @ prefix if user types it
                username = newUsername.replace("@", "")
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            label = { Text("Twitter Username") },
            leadingIcon = { Text("@", style = MaterialTheme.typography.bodyLarge) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    viewModel.loadTweets(username, sinceTime, MAX_TWEETS)
                    viewModel.observeTweets(username)
                }
            )
        )

        // Buttons in one row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Fetch Tweets button
            Button(
                onClick = {
                    viewModel.loadTweets(username, sinceTime, MAX_TWEETS)
                    viewModel.observeTweets(username)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Fetch Tweets")
            }

            // Schedule Updates button
            Button(
                onClick = {
                    viewModel.toggleBackgroundFetching(
                        usernames = listOf(username, "elonmusk", "milesdeutscher"),
                        intervalMinutes = 15
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(if (isScheduled) "Cancel Updates" else "Schedule Updates")
            }
        }

        // Since Date/Time selection in its own row with larger text
        TextButton(
            onClick = { showDatePicker = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = "Select date",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Since: ${dateFormat.format(Date(sinceTime))}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Error message
        error?.let {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFDE7E7))
            ) {
                Text(
                    text = "Error: $it",
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Loading indicator
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(16.dp)
            )
        }

        // Tweet list with chronologically ordered threads
        if (orderedTweets.isNotEmpty()) {
            LazyColumn {
                items(orderedTweets) { tweet ->
                    TweetItem(tweet = tweet)
                    Divider()
                }
            }
        } else if (!loading) {
            // Empty state
            Text(
                text = "No tweets found for @$username",
                modifier = Modifier
                    .padding(32.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }
    }
}

/**
 * Renders a single tweet row with simple thread indicators.
 *
 * @param tweet The tweet to display.
 */
@Composable
fun TweetItem(tweet: Tweet) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // Profile image section with thread line
        Column(
            modifier = Modifier
                .width(48.dp)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile image
            if (tweet.isPartOfThread && !tweet.isThreadStarter) {
                // Show thread connector line above smaller profile image
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .background(
                            color = Color(0xFF1DA1F2),
                            shape = RoundedCornerShape(1.5.dp)
                        )
                )

                // Smaller profile image for non-starter thread tweets
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1DA1F2)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tweet.username.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Show thread connector line below profile image
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(100.dp) // Adjust this height as needed
                        .background(
                            color = Color(0xFF1DA1F2),
                            shape = RoundedCornerShape(1.5.dp)
                        )
                )
            } else {
                // Regular profile image for thread starters and non-threaded tweets
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1DA1F2)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tweet.username.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }

                // Show thread connector line below thread starter
                if (tweet.isThreadStarter) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(100.dp) // Adjust this height as needed
                            .background(
                                color = Color(0xFF1DA1F2),
                                shape = RoundedCornerShape(1.5.dp)
                            )
                    )
                }
            }
        }

        // Tweet content
        Column(
            modifier = Modifier.weight(1f)
        ) {
            // User info and timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "@${tweet.username}",
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = formatRelativeTime(tweet.timestamp),
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Tweet content
            Text(
                text = tweet.content,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Bottom row with stats and thread indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Stats
                Row {
                    if (tweet.retweetCount > 0) {
                        Text(
                            text = "${tweet.retweetCount} RT",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    if (tweet.likeCount > 0) {
                        Text(
                            text = "${tweet.likeCount} ♥",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }

                // Thread indicator
                if (tweet.isThreadStarter) {
                    Text(
                        text = "🧵 Thread",
                        color = Color(0xFF1DA1F2),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                } else if (tweet.isPartOfThread) {
                    Text(
                        text = "↳ Thread",
                        color = Color(0xFF1DA1F2),
                        fontSize = 12.sp
                    )
                } else if (tweet.isReply) {
                    Text(
                        text = "Reply" + (tweet.replyToUsername?.let { " to @$it" } ?: ""),
                        color = Color(0xFF6200EE),
                        fontSize = 12.sp
                    )
                }
            }

            // Show media info if available
            if (tweet.media.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📷 ${tweet.media.size} media attachment(s)",
                    color = Color(0xFF018786),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Formats a date for relative time display in the UI.
 *
 * @param date The date to format
 * @return A formatted relative time string (e.g., "2h", "3d", "1w")
 */
private fun formatRelativeTime(date: Date): String {
    val now = System.currentTimeMillis()
    val diff = now - date.time

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}h"
        diff < TimeUnit.DAYS.toMillis(7) -> "${diff / TimeUnit.DAYS.toMillis(1)}d"
        diff < TimeUnit.DAYS.toMillis(30) -> "${diff / TimeUnit.DAYS.toMillis(7)}w"
        diff < TimeUnit.DAYS.toMillis(365) -> "${diff / TimeUnit.DAYS.toMillis(30)}mo"
        else -> "${diff / TimeUnit.DAYS.toMillis(365)}y"
    }
}
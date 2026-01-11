package com.example.twitterscrape.demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.enmoble.common.social.twitter.data.model.Tweet
import com.enmoble.common.social.twitter.data.model.TwitterMedia
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * RecyclerView adapter for displaying tweets.
 * 
 * This adapter demonstrates:
 * - Using ListAdapter with DiffUtil for efficient updates
 * - Displaying tweet content, metadata, and media
 * - Handling thread indicators
 * - Image loading with Coil
 */
class TweetAdapter : ListAdapter<Tweet, TweetAdapter.TweetViewHolder>(TweetDiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TweetViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tweet, parent, false)
        return TweetViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: TweetViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class TweetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textUsername: TextView = itemView.findViewById(R.id.textUsername)
        private val textContent: TextView = itemView.findViewById(R.id.textContent)
        private val textTimestamp: TextView = itemView.findViewById(R.id.textTimestamp)
        private val textStats: TextView = itemView.findViewById(R.id.textStats)
        private val imageMedia: ImageView = itemView.findViewById(R.id.imageMedia)
        private val textThreadIndicator: TextView = itemView.findViewById(R.id.textThreadIndicator)
        
        private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        
        fun bind(tweet: Tweet) {
            textUsername.text = "@${tweet.username}"
            textContent.text = tweet.content
            textTimestamp.text = dateFormat.format(tweet.timestamp)
            
            // Show stats
            val stats = buildString {
                if (tweet.retweetCount > 0) append("♻️ ${tweet.retweetCount} ")
                if (tweet.likeCount > 0) append("❤️ ${tweet.likeCount}")
            }
            textStats.text = stats
            textStats.visibility = if (stats.isNotEmpty()) View.VISIBLE else View.GONE
            
            // Show thread indicator
            if (tweet.isThreadStarter) {
                textThreadIndicator.visibility = View.VISIBLE
                textThreadIndicator.text = "🧵 Thread"
            } else if (tweet.isPartOfThread) {
                textThreadIndicator.visibility = View.VISIBLE
                textThreadIndicator.text = "🧵"
            } else {
                textThreadIndicator.visibility = View.GONE
            }
            
            // Load first image if available
            val firstImage = tweet.media.firstOrNull { it.type == TwitterMedia.MediaType.IMAGE }
            if (firstImage != null) {
                imageMedia.visibility = View.VISIBLE
                imageMedia.load(firstImage.url) {
                    crossfade(true)
                    placeholder(android.R.drawable.ic_menu_gallery)
                    error(android.R.drawable.ic_menu_report_image)
                }
            } else {
                imageMedia.visibility = View.GONE
            }
        }
    }
    
    /**
     * DiffUtil callback for efficient list updates.
     */
    class TweetDiffCallback : DiffUtil.ItemCallback<Tweet>() {
        override fun areItemsTheSame(oldItem: Tweet, newItem: Tweet): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Tweet, newItem: Tweet): Boolean {
            return oldItem == newItem
        }
    }
}
package app.redlib.now.model

data class Post(
    val id: String,
    val subreddit: String,
    val author: String?,
    val title: String,
    val permalink: String,          // instance-relative, starts with /
    val flair: String?,
    val selfTextPreview: String?,
    val imageUrl: String?,          // absolutized against the serving instance
    val videoUrl: String?,          // HLS playlist or mp4, if this is a video post
    val isVideo: Boolean,
    val score: Long?,
    val commentCount: Long?,
    val timeAgo: String?,           // e.g. "3h ago" as rendered by the instance
    val nsfw: Boolean,
)

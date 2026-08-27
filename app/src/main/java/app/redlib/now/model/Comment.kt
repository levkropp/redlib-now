package app.redlib.now.model

data class Comment(
    val id: String,
    val author: String?,
    val score: Long?,
    val timeAgo: String?,
    val body: String,          // plain text, paragraphs separated by \n\n
    val isOp: Boolean,
    val isMod: Boolean,
    val replies: List<Comment>,
)

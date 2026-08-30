package app.redlib.now.model

data class Comment(
    val id: String,
    val author: String?,
    val score: Long?,
    val timeAgo: String?,
    val body: String,          // plain text, paragraphs separated by \n\n
    val isOp: Boolean,
    val isMod: Boolean,
    val parentId: String?,     // id of the parent comment, null for top-level
    val imageUrl: String? = null,  // inline media extracted from the body (instance-proxy)
    val replies: List<Comment>,
)

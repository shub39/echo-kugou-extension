package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable

@Serializable
class KugouHashSearchResponse(
    val status: Int,
    val errmsg: String,
    val candidates: List<Candidate>
) {
    @Serializable
    data class Candidate(
        val id: String,
        val accesskey: String
    )

    fun getBestCandidate(): Candidate? = candidates.firstOrNull()
}

@Serializable
class KugouSearchCandidateDownloadResponse(
    val status: Int,
    val info: String,
    val charset: String,
    val content: String
)

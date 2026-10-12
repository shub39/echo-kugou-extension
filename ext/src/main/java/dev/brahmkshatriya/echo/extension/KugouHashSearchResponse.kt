package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KugouHashSearchResponse(
    val status: Int = 0,
    val errmsg: String? = null,
    val errcode: Int? = null,
    val info: String? = null,
    val candidates: List<Candidate> = emptyList()
) {
    @Serializable
    data class Candidate(
        val id: String = "",
        val accesskey: String = "",
        val singer: String? = null,
        val song: String? = null,
        val duration: Long = 0,
        val score: Int = 0,
        @SerialName("krctype") val krcType: Int = 0,
        @SerialName("content_format") val contentFormat: Int = 0,
        @SerialName("product_from") val productFrom: String? = null
    )

    fun getBestCandidate(): Candidate? = candidates.maxByOrNull { it.score } ?: candidates.firstOrNull()
}

@Serializable
data class KugouSearchCandidateDownloadResponse(
    val status: Int = 0,
    val info: String? = null,
    val charset: String? = null,
    val content: String = "",
    val fmt: String? = null,
    @SerialName("error_code") val errorCode: Int? = null
)

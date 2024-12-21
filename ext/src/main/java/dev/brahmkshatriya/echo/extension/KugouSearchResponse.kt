package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable

@Serializable
data class KugouSearchResponse(
    val status: Int,
    val data: Data
) {
    @Serializable
    data class Data(val info: List<Info>)
    @Serializable
    data class Info(val hash: String, val songname: String, val singername: String?, val albumName: String? = null)
}
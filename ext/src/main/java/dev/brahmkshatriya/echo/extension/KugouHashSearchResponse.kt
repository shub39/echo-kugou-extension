package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KugouSearchResponse(
    val status: Int = 0,
    val data: Data = Data(),
    val error: String = ""
) {
    @Serializable
    data class Data(
        val total: Int = 0,
        val info: List<Info> = emptyList()
    )

    @Serializable
    data class Info(
        val hash: String = "",
        val songname: String = "",
        val singername: String? = null,
        @SerialName("album_name") val albumName: String? = null,
        val duration: Int = 0,
        val filesize: Long = 0,
        @SerialName("sqhash") val sqHash: String? = null
    )
}

package com.example.data

import com.google.gson.annotations.SerializedName

data class SearchResponse(
    val data: SearchData?
)

data class SearchData(
    val results: List<SongDto>?
)

data class SongDto(
    val id: String,
    val name: String,
    val artists: ArtistsDto?,
    val image: List<ImageDto>?,
    val downloadUrl: List<DownloadUrlDto>?
)

data class ArtistsDto(
    val primary: List<ArtistDto>?
)

data class ArtistDto(
    val id: String,
    val name: String
)

data class ImageDto(
    val quality: String,
    @SerializedName(value = "url", alternate = ["link"]) val url: String
)

data class DownloadUrlDto(
    val quality: String,
    @SerializedName(value = "url", alternate = ["link"]) val url: String
)

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val imageUrl: String,
    val streamUrl: String
)

fun SongDto.toDomain(): Song {
    val highestResImage = image?.maxByOrNull {
        it.quality.split("x").firstOrNull()?.toIntOrNull() ?: 0
    }?.url ?: ""
    
    val stream320 = downloadUrl?.find { it.quality == "320kbps" }?.url
        ?: downloadUrl?.lastOrNull()?.url ?: ""
    val primaryArtistName = artists?.primary?.firstOrNull()?.name ?: "Unknown Artist"
        
    return Song(
        id = id,
        title = name,
        artist = primaryArtistName,
        imageUrl = highestResImage,
        streamUrl = stream320
    )
}

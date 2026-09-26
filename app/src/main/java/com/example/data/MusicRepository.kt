package com.example.data

class MusicRepository {
    suspend fun searchSongs(query: String): Result<List<Song>> {
        return try {
            val response = NetworkClient.api.searchSongs(query)
            if (response.isSuccessful) {
                val results = response.body()?.data?.results?.map { it.toDomain() } ?: emptyList()
                Result.success(results)
            } else {
                Result.failure(Exception("API Error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

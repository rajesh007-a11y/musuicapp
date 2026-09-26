package com.example.data

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface JioSaavnApi {
    @GET("search/songs")
    suspend fun searchSongs(@Query("query") query: String): Response<SearchResponse>
}

object NetworkClient {
    private const val BASE_URL = "https://jiosaavn-api-jnlz.onrender.com/api/"

    val api: JioSaavnApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(JioSaavnApi::class.java)
    }
}

// ApiService.kt

package com.example.seatmonitorapp.network

import com.example.seatmonitorapp.model.ApiResponse
import com.example.seatmonitorapp.model.SeatState
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @POST("api/seat-state") // Correct endpoint path
    suspend fun sendSeatState(@Body seatState: SeatState): Response<ApiResponse>

    @GET("api/seat-states")
    suspend fun getSeatStates(): Response<List<SeatState>>
}

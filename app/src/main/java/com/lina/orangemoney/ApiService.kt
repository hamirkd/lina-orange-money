package com.lina.orangemoney

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

interface ApiService {

    @POST
    fun sendSms(@Url url: String, @Body smsData: SmsData): Call<Void>
}
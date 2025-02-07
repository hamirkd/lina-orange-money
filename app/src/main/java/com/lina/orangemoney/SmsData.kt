package com.lina.orangemoney

data class SmsData(
    val address: String,
    val body: String,
    val time: Long,
    var expediteur: String
)
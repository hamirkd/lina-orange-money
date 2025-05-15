package com.lina.orangemoney

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.media.AudioManager
import android.media.ToneGenerator

class ReadSmsWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val uri = Uri.parse("content://sms/inbox")
            val selection = "address LIKE ? AND body like ?"
            val selectionArgs = arrayOf("%OrangeMoney%", "%recu%")

            val cursor = applicationContext.contentResolver.query(uri, null, selection, selectionArgs, "date DESC LIMIT 50")
            val smsListToSend = mutableListOf<SmsData>()
            cursor?.use {
                val senderColumn = it.getColumnIndex("address")
                val messageColumn = it.getColumnIndex("body")
                val timeColumn = it.getColumnIndex("date")
                val subscriptionColumn = it.getColumnIndex("sub_id")

                while (it.moveToNext()) {
                    val sender = it.getString(senderColumn)
                    val message = it.getString(messageColumn)
                    val time = it.getLong(timeColumn)
                    val subid = it.getInt(subscriptionColumn)

                    val number = (applicationContext as MainActivity).getSimNumberBySubscriptionId(subid)
                    if (sender != null && message != null) {
                        val smsData = SmsData(sender, message, time, number, false)
                        smsListToSend.add(smsData)
                    }
                }
            }

            // ✅ Émettre le bip sur le thread principal
            withContext(Dispatchers.Main) {
                val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 200)
            }
            withContext(Dispatchers.IO) {
                smsListToSend.forEach { sms ->
                    SmsUtils.sendSmsToServer(applicationContext, sms)
                }
            }

            Log.d("Worker", "SMS traités avec succès")
            Result.success()
        } catch (e: Exception) {
            Log.e("Worker", "Erreur pendant le traitement des SMS", e)
            Result.failure()
        }
    }
}

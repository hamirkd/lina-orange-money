package com.lina.orangemoney

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SmsForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "SmsServiceChannel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Service")
            .setContentText("Surveillance des SMS en cours…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "SMS Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ici, tu peux enregistrer un BroadcastReceiver pour écouter les SMS
        Log.d("SMSForegroundService", "Ecouteur actif")

        val bundle = intent?.extras
        Log.d("SMSForegroundService", "Contenu bundle  ${bundle}")

        if (bundle != null) {
            val pdus = bundle.get("pdus") as Array<*>?
            pdus?.forEach { pdu ->
                val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                val sender = smsMessage.displayOriginatingAddress
                val message = smsMessage.messageBody
                val time = smsMessage.timestampMillis
                // Récupérer le numéro de la SIM à partir de SubscriptionManager
                val subscriptionId = bundle.getInt("subscription", -1) // Récupération du Subscription ID

                val simNumber = smsMessage.indexOnSim;
                // Envoyer chaque SMS sur le serveur
                val smsData = SmsData(sender, message, time, simNumber.toString(), false)
                sendSmsToServer(smsData)
                val number = (applicationContext as MainActivity).getSimNumberBySubscriptionId(subscriptionId)
                if (sender != null && message != null) {
                    (applicationContext as MainActivity).sendSmsToServer(smsData)
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    fun sendSmsToServer(smsData: SmsData) {
        Log.d("SMSForegroundService", "Message envoyé avec succès  ${smsData.body}")

       val url = "lina/app/core/paiementFromMobile.class.php?x=savePaiementFromMobile" // Remplace avec l'URL appropriée
        //if (smsData.address.uppercase().compareTo("ORANGEMONEY")==0)
        RetrofitClient.apiService.sendSms(url, smsData).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d("SMS", "Message envoyé avec succès  ${response}")
                } else {
                    Log.e("SMS", "Erreur lors de l'envoi du message: ${response.message()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("SMS", "Erreur de connexion: ${t.message}")
            }
        })
    }

}
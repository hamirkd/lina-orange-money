package com.lina.orangemoney

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.app.ActivityCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.lifecycle.lifecycleScope


class MainActivity : ComponentActivity() {

    private val TAG = "SMSReader"
    private val smsList = mutableStateListOf<Pair<String, SmsData>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Vérifier et demander la permission READ_PHONE_STATE

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)

        }

        val serviceIntent = Intent(this, SmsForegroundService::class.java)
        startService(serviceIntent)
        setContent {
            SMSReaderScreen(onReadSmsClick = {
                checkAndRequestReadSmsPermission()
            })
        }

        checkAndRequestPermissions()
        checkAndRequestReadSmsPermission5Seconde()

        // Register the BroadcastReceiver to listen for new SMS
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsReceiver, filter, RECEIVER_EXPORTED)
        val workRequest = PeriodicWorkRequestBuilder<ReadSmsWorker>(15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "read_sms_work",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(smsReceiver)
    }

    // Check and request necessary permissions
    private fun checkAndRequestPermissions() {
        val permission = Manifest.permission.RECEIVE_SMS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d(TAG, "Permission granted")
        } else {
            Log.d(TAG, "Permission denied")
        }
    }
    private val readSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            CoroutineScope(Dispatchers.IO).launch {
                readSmsFromInbox()
            }
        } else {
            Log.d(TAG, "Permission READ_SMS refusée")
        }
    }

    private fun checkAndRequestReadSmsPermission() {
        // Vérifier et demander la permission READ_PHONE_STATE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                readSmsFromInbox()
            }
        }
    }

    private fun checkAndRequestReadSmsPermission5Seconde() {
        // Vérifier et demander la permission READ_PHONE_STATE
        val permission = Manifest.permission.READ_SMS
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(permission)
        } else {
//            CoroutineScope(Dispatchers.IO).launch {
//                while (true) {
//                    readSmsFromInbox5Seconds()
//                    delay(60000) // 10 secondes
//                }
//            }
            val isActive = true
            lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    readSmsFromInbox5Seconds()
                    delay(60_000) // toutes les 60 secondes
                }
            }
        }
    }
    // BroadcastReceiver to update the list of received SMS
    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val bundle = intent?.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as Array<*>?
                pdus?.forEach { pdu ->
                    val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
                    val sender = smsMessage.displayOriginatingAddress
                    val message = smsMessage.messageBody
                    val time = smsMessage.timestampMillis
                    // Récupérer le numéro de la SIM à partir de SubscriptionManager
                    val subscriptionId = bundle.getInt("subscription", -1) // Récupération du Subscription ID

                    val simNumber = getSimNumberBySubscriptionId(subscriptionId)
                    val smsData = SmsData(sender, message, time, simNumber, false)
                    // Envoyer chaque SMS sur le serveur
                    smsList.add(0, Pair(sender + time, smsData))
                    sendSmsToServer(smsList.get(0).second)
                }
            }
        }
    }

    private suspend fun readSmsFromInbox5Seconds() {

        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 200) // 200ms de bip
        val uri = Uri.parse("content://sms/inbox")
        // Filtre pour récupérer seulement les messages envoyés par un numéro spécifique
        val selection = "address LIKE ? AND body like ?"
        val selectionArgs = arrayOf("%OrangeMoney%", "%recu%")

        val cursor = contentResolver.query(uri, null, selection, selectionArgs, "date DESC LIMIT 10")
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
                val number = getSimNumberBySubscriptionId(subid)
                if (sender != null && message != null) {

                    val smsData = SmsData(sender, message, time, number, false)
                    // Envoyer chaque SMS sur le serveur
                    val index = smsList.indexOfFirst { px -> px.first == sender + time }
                    if (index == -1) {
                        Log.d("Ajout dans la liste", "" + index + "" )
                        smsList.add(0, Pair(sender + time, smsData)) // Ajouter en haut de la liste
                    }

                 }
            }
        }


        // Met à jour l'UI en repassant sur le thread principal
        withContext(Dispatchers.Main) {
            for ((key, smsData) in smsList) {
                sendSmsToServer(smsData)
            }

        }

    }
    private suspend fun readSmsFromInbox() {

        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_PIP, 200) // 200ms de bip
        val uri = Uri.parse("content://sms/inbox")
        // Filtre pour récupérer seulement les messages envoyés par un numéro spécifique
        val selection = "address LIKE ? AND body like ?"
        val selectionArgs = arrayOf("%OrangeMoney%", "%recu%")

        val cursor = contentResolver.query(uri, null, selection, selectionArgs, "date DESC LIMIT 200")
        cursor?.use {
            val senderColumn = it.getColumnIndex("address")
            val messageColumn = it.getColumnIndex("body")
            val timeColumn = it.getColumnIndex("date")
            val subscriptionColumn = it.getColumnIndex("sub_id")
            smsList.clear()
            Log.d("Afficher", "" + smsList.toSet().size)
            while (it.moveToNext()) {
                val sender = it.getString(senderColumn)
                val message = it.getString(messageColumn)
                val time = it.getLong(timeColumn)
                val subid = it.getInt(subscriptionColumn)
                val number = getSimNumberBySubscriptionId(subid)
                if (sender != null && message != null) {
                    val smsData = SmsData(sender, message, time, number, false)
                    smsList.add(0, Pair(sender + time, smsData))
                }
            }
        }


        // Met à jour l'UI en repassant sur le thread principal
            withContext(Dispatchers.Main) {
                for ((key, smsData) in smsList) {
                   sendSmsToServer(smsData)
                }

        }

    }
    fun sendSmsToServer(smsData: SmsData) {
        val url = "lina/app/core/paiementFromMobile.class.php?x=savePaiementFromMobile" // Remplace avec l'URL appropriée
        //if (smsData.address.uppercase().compareTo("ORANGEMONEY")==0)
        if (smsData.envoyer) return;
        Log.d("ENVOI_DE_MESSAGE", "Préparation de l'envoie  ${smsData.body}")
        RetrofitClient.apiService.sendSms(url, smsData).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                if (response.isSuccessful) {
                    Log.d("SMS", "Message envoyé avec succès  ${response}")
                    smsData.envoyer = true;
                    val index = smsList.indexOfFirst { it.second == smsData }
                    if (index != -1) {
                        val updatedSms = smsData.copy(envoyer = true)
                        smsList[index] = smsList[index].copy(second = updatedSms)
                    }
                } else {
                    Log.e("SMS", "Erreur lors de l'envoi du message: ${response.message()}")
                }
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("SMS", "Erreur de connexion: ${t.message}")
            }
        })
    }

    @Composable
    fun SMSReaderScreen(onReadSmsClick: () -> Unit) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Messages reçus :", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            // Bouton pour lire les SMS
            Button(onClick = onReadSmsClick) {
                Text(text = "Recupérer tous les messages")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Liste des SMS
            LazyColumn {
                items(smsList) { sms ->
                    Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = "Expéditeur: ${sms.first}", style = MaterialTheme.typography.titleMedium)
                            Text(text = "Message: ${sms.second.body}", style = MaterialTheme.typography.bodyMedium)
                            Text(text = "${formatTimestamp(sms.second.time)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    fun formatTimestamp(timeInMillis: Long): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val date = Date(timeInMillis)
        return sdf.format(date)
    }
    fun getSimNumberBySubscriptionId(id: Int): String {
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE), 100)

        }

        val subscriptionInfoList: List<SubscriptionInfo>? =  subscriptionManager.activeSubscriptionInfoList

        subscriptionInfoList?.forEach { info ->
            if (info.subscriptionId == id) {
                // return info.number ?: "Numéro inconnu"
                Log.d("Numero", "Numéro inconnu : ")
            }
        }
        subscriptionInfoList?.forEach { info ->
            if (info.displayName.toString().uppercase().contains("ORANGEMONEY")) {
                return info.displayName.toString() ?: "Numéro inconnu"
            } else Log.d("Numero", "Numero : "+info)
        }

        return "SIM non trouvéer"

    }
}

object SmsUtils {
    fun sendSmsToServer(context: Context, smsData: SmsData) {
        val url = "lina/app/core/paiementFromMobile.class.php?x=savePaiementFromMobile"
        RetrofitClient.apiService.sendSms(url, smsData).enqueue(object : Callback<Void> {
            override fun onResponse(call: Call<Void>, response: Response<Void>) {
                Log.d("SMS", "Message envoyé avec succès  ${response}")
            }

            override fun onFailure(call: Call<Void>, t: Throwable) {
                Log.e("SMS", "Erreur de connexion: ${t.message}")
            }
        })
    }
}
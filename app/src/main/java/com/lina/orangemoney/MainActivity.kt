package com.lina.orangemoney

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

class MainActivity : ComponentActivity() {

    private val TAG = "SMSReader"
    private val smsList = mutableStateListOf<Pair<String, String>>()

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
        setContent {
            SMSReaderScreen(onReadSmsClick = {
                checkAndRequestReadSmsPermission()
            })
        }

        checkAndRequestPermissions()

        // Register the BroadcastReceiver to listen for new SMS
        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        registerReceiver(smsReceiver, filter)
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
            readSmsFromInbox()
        } else {
            Log.d(TAG, "Permission READ_SMS refusée")
        }
    }

    private fun checkAndRequestReadSmsPermission() {
        // Vérifier et demander la permission READ_PHONE_STATE

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            readSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
        } else {
            readSmsFromInbox()
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
                    val simNumber = getSimNumberBySubscriptionId()
                    smsList.add(0, Pair(simNumber ?: "Unknown", message ?: "No message"))
                    // Envoyer chaque SMS sur le serveur
                    val smsData = SmsData(sender, message, time, simNumber)
                    sendSmsToServer(smsData)
                }
            }
        }
    }
    private fun readSmsFromInbox() {
        val uri = Uri.parse("content://sms/inbox")
        // Filtre pour récupérer seulement les messages envoyés par un numéro spécifique
        val selection = "address LIKE ?"
        val selectionArgs = arrayOf("%OrangeMoney%") // Remplace "703" par le vrai numéro Orange Money

        val cursor = contentResolver.query(uri, null, selection, selectionArgs, "date DESC LIMIT 200")

        cursor?.use {
            val senderColumn = it.getColumnIndex("address")
            val messageColumn = it.getColumnIndex("body")
            val timeColumn = it.getColumnIndex("date")
            val subscriptionColumn = it.getColumnIndex("sub_id")
            smsList.clear()
            while (it.moveToNext()) {
                val sender = it.getString(senderColumn)
                val message = it.getString(messageColumn)
                val time = it.getLong(timeColumn)
                if (sender != null && message != null) {
                    val number = getSimNumberBySubscriptionId()

                    smsList.add(0, Pair(number + sender, message)) // Ajouter en haut de la liste
                    // Envoyer chaque SMS sur le serveur
                    val smsData = SmsData(sender, message, time, number)
                    sendSmsToServer(smsData)
                }
            }
        }
    }
    fun sendSmsToServer(smsData: SmsData) {
       val url = "linanew202501/app/core/paiementFromMobile.class.php?x=savePaiementFromMobile" // Remplace avec l'URL appropriée
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

    @Composable
    fun SMSReaderScreen(onReadSmsClick: () -> Unit) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Messages reçus :", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            // Bouton pour lire les SMS
            Button(onClick = onReadSmsClick) {
                Text(text = "Lire les SMS")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Liste des SMS
            LazyColumn {
                items(smsList) { sms ->
                    Card(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(text = "Expéditeur: ${sms.first}", style = MaterialTheme.typography.titleMedium)
                            Text(text = "Message: ${sms.second}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    private fun getSimNumberBySubscriptionId(): String {
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
            if (info.displayName.toString().uppercase().contains("ORANGEMONEY")) {
                return info.displayName.toString() ?: "Numéro inconnu"
            } else Log.d("Numero", "Numero : "+info)
        }

        return "SIM non trouvéer"

    }
}

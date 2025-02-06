package com.lina.orangemoney

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

class SMSReceiver : BroadcastReceiver() {

    private val TAG = "SMSReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
            val bundle: Bundle? = intent.extras
            if (bundle != null) {
                val pdus = bundle.get("pdus") as? Array<*>
                if (pdus != null) {
                    for (pdu in pdus) {
                        val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                        val sender = sms.originatingAddress
                        val message = sms.messageBody

                        Log.d(TAG, "Nouveau SMS reçu - Expéditeur: $sender, Message: $message")

                        // Envoyer les données au MainActivity via un Intent
                        val newIntent = Intent("SMS_RECEIVED_ACTION")
                        newIntent.putExtra("sender", sender)
                        newIntent.putExtra("message", message)
                        context?.sendBroadcast(newIntent)
                    }
                }
            }
        }
    }
}

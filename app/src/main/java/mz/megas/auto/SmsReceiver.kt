package mz.megas.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)
        if (msgs.isNullOrEmpty()) return
        val from = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val app = c.applicationContext
        val text = when {
            Config.trusted(from) -> body                                   // SMS oficial neste telefone
            Config.FORWARD_SECRET.isNotEmpty() && Config.forwarder(from) &&
                body.startsWith(Config.FORWARD_SECRET) -> body.removePrefix(Config.FORWARD_SECRET) // reencaminhada
            else -> { Orders.onCustomerSms(app, from, body); return }       // SMS de um cliente
        }
        val p = Parser.parse(text) ?: return
        val db = Db(app); db.rollover()
        if (db.exists(p.id)) { db.log("duplicado ignorado ${p.id}"); return } // anti-duplicado
        Orders.onPayment(app, p)
    }
}

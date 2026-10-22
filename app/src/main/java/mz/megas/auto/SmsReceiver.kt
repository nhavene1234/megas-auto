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
        if (!Config.trusted(from)) return                       // anti-fraude: so remetentes oficiais
        val p = Parser.parse(msgs.joinToString("") { it.messageBody ?: "" }) ?: return  // formato exato

        val db = Db(c); db.rollover()
        val mb = Config.PACKAGES[p.amount]
        val status = when { p.dest == null -> "REVIEW"; mb == null -> "INVALID"; else -> "PENDING" }

        // O ID e gravado ANTES de qualquer acao. PRIMARY KEY => duplicados sao ignorados.
        if (!db.add(p.id, p.source, p.amount, p.payer, p.dest, mb, status)) return

        when (status) {
            "PENDING" -> UssdService.poke()
            "INVALID" -> sendSms(c, p.payer, "Valor de ${p.amount}MT invalido. Pacotes: " +
                    Config.PACKAGES.entries.joinToString { "${it.key}MT=${it.value / 1024}GB" } + ". " + Config.PAY_INFO + " Ref: ${p.id}")
            "REVIEW" -> sendSms(c, p.payer, "Recebemos ${p.amount}MT (ref ${p.id}). Envie o numero Vodacom que vai receber os megas.")
        }
    }
}

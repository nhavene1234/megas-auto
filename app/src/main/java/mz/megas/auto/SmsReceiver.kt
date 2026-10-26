package mz.megas.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {
    private val vodaNumber = Regex("""(?<!\d)(?:258)?(8[45]\d{7})(?!\d)""")
    private val anyNumber = Regex("""(?<!\d)(?:258)?8\d{8}(?!\d)""")

    override fun onReceive(c: Context, i: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)
        if (msgs.isNullOrEmpty()) return
        val from = msgs[0].originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        val text = when {
            Config.trusted(from) -> body                                   // SMS oficial neste telefone
            Config.FORWARD_SECRET.isNotEmpty() && Config.forwarder(from) &&
                body.startsWith(Config.FORWARD_SECRET) -> body.removePrefix(Config.FORWARD_SECRET) // reencaminhada
            else -> { customerSms(c, from, body); return }                  // SMS de cliente com o numero de destino
        }
        val p = Parser.parse(text) ?: return

        val db = Db(c); db.rollover()
        if (db.exists(p.id)) { db.log("duplicado ignorado ${p.id}"); return }

        // Se o cliente enviou o numero ANTES de pagar, usa-o. Caso contrario, pede-o (ALWAYS_ASK).
        val registered = if (!Config.ALWAYS_ASK && p.explicit) null else db.takeDest(p.payer.takeLast(9))
        val dest = if (Config.ALWAYS_ASK) registered else registered ?: p.dest
        val mb = Config.PACKAGES[p.amount]
        val status = when { mb == null -> "INVALID"; dest == null -> "REVIEW"; else -> "PENDING" }

        // O ID e gravado ANTES de qualquer acao. PRIMARY KEY => duplicados sao ignorados.
        if (!db.add(p.id, p.source, p.amount, p.payer, dest, mb, status)) { db.log("duplicado ignorado ${p.id}"); return }
        db.log("SMS ${p.source} ${p.id} ${p.amount}MT de ${p.name} -> $dest [$status]")

        when (status) {
            "PENDING" -> UssdService.poke()
            "INVALID" -> sendSms(c, p.payer, "Valor de ${p.amount}MT invalido. Pacotes: " +
                    Config.PACKAGES.entries.joinToString { "${it.key}MT=${it.value / 1024}GB" } + ". " + Config.PAY_INFO + " Ref: ${p.id}")
            "REVIEW" -> {
                val who = if (p.name.isEmpty()) "" else " ${p.name}"
                sendSms(c, p.payer, "Ola$who! Recebemos o seu pagamento de ${String.format(Locale.US, "%.2f", p.amount.toDouble())}MT. " +
                        "Agora envie o numero que vai receber os megabytes.")
            }
        }
    }

    /** Cliente responde com o numero de destino (so de numeros normais; ignora operadoras). */
    private fun customerSms(c: Context, from: String, body: String) {
        val digits = from.filter { it.isDigit() }
        if (digits.length < 9 || Config.forwarder(from)) return
        val payer9 = digits.takeLast(9)
        val db = Db(c)
        val dest = vodaNumber.find(body)?.groupValues?.get(1)
        if (dest == null) {
            if (anyNumber.containsMatchIn(body) && db.hasReview(payer9))
                sendSms(c, from, "Numero invalido. Envie um numero Vodacom (comeca por 84 ou 85).")
            return
        }
        val sale = db.releaseReview(payer9, dest)
        if (sale != null) {
            db.log("destino $dest recebido de $payer9 -> ${sale.id}")
            sendSms(c, from, "Recebido! A enviar ${sale.mb / 1024}GB para $dest.")
            UssdService.poke()
        } else {
            db.setDest(payer9, dest)
            db.log("destino $dest guardado para $payer9")
            sendSms(c, from, "Numero $dest guardado por ${Config.DEST_VALID_MIN} minutos. Agora faca o pagamento.")
        }
    }
}

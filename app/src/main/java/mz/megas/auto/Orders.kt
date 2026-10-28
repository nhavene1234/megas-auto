package mz.megas.auto

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/** Pedido lido de uma SMS do cliente. gb = -1 significa "todo o saldo". */
data class Item(val dest: String, val gb: Int)

object Orders {
    private sealed class Tok
    private class G(val gb: Int) : Tok()
    private class N(val num: String) : Tok()

    private val tok = Regex("""(?<![\d.,])(\d{1,2})\s*(?:gb|gigas?|gigabytes?)\b|(?<!\d)(?:258)?(8[45]\d{7})(?!\d)""", RegexOption.IGNORE_CASE)
    private val vodaNum = Regex("""(?<!\d)(?:258)?(8[45]\d{7})(?!\d)""")
    private val lastInfo = HashMap<String, Long>()

    // IDs de transacao: e-Mola (PP261020.1935.F25826), M-Pesa (DJG6LYWOPGE)
    private val emolaId = Regex("""\bPP\d{6}\.\d{4}\.[A-Z0-9]+""", RegexOption.IGNORE_CASE)
    private val mpesaWord = Regex("""\bconfirmado\s+([A-Z0-9]{8,14})""", RegexOption.IGNORE_CASE)
    private val plainId = Regex("""\b(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{10,12}\b""")

    fun extractIds(body: String): List<String> {
        val out = LinkedHashSet<String>()
        emolaId.findAll(body).forEach { out.add(it.value.trimEnd('.')) }
        mpesaWord.findAll(body).forEach { out.add(it.groupValues[1]) }
        plainId.findAll(body).forEach { out.add(it.value) }
        return out.toList()
    }
    private fun proofHint(noCredit: Boolean) =
        if (noCredit) " Se ja pagou de outro numero (ex: e-Mola), envie o comprovativo com o ID da transacao." else ""

    fun fmt(mb: Int) = if (mb % 1024 == 0) "${mb / 1024}GB" else "${mb}MB"
    private fun price(mb: Int) = (mb + 1023) / 1024 * Config.PRICE_PER_GB
    private fun cooling(key: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (lastInfo[key] ?: 0L) < Config.COOLDOWN_MIN * 60_000L) return true
        lastInfo[key] = now; return false
    }
    private fun later(c: Context, to: String, msg: String) {
        Handler(Looper.getMainLooper()).postDelayed({ sendSms(c, to, msg) }, 5000)
    }

    /**
     * Le "1gb para 841234567 e 3gb para 851234567", "841234567 2gb", ou so "841234567".
     * Devolve lista vazia se nao houver nada, ou null se o pedido for ambiguo.
     */
    fun parse(body: String): List<Item>? {
        val items = ArrayList<Item>()
        var pg: Int? = null
        var pn: String? = null
        for (m in tok.findAll(body)) {
            if (m.groups[1] == null && m.groupValues[2] in Config.OWN_NUMBERS) continue   // ignora numeros da loja
            val t: Tok = if (m.groups[1] != null) G(m.groupValues[1].toInt()) else N(m.groupValues[2])
            when (t) {
                is G -> {
                    val n = pn
                    if (n != null) { items.add(Item(n, t.gb)); pn = null }
                    else { if (pg != null) return null; pg = t.gb }
                }
                is N -> {
                    val g = pg
                    if (g != null) { items.add(Item(t.num, g)); pg = null }
                    else { pn?.let { items.add(Item(it, -1)) }; pn = t.num }
                }
            }
        }
        if (pg != null) return null
        pn?.let { items.add(Item(it, -1)) }
        if (items.count { it.gb < 0 } > 1) return null
        return items
    }

    // ------------------------------------------------------------ pagamento recebido
    fun onPayment(c: Context, p: Payment) {
        val db = Db(c)
        val p9 = p.payer.takeLast(9)
        val who = if (p.name.isEmpty()) "" else " ${p.name}"
        val amt = String.format(Locale.US, "%.2f", p.amount.toDouble())
        val gb = if (p.amount > 0 && p.amount % Config.PRICE_PER_GB == 0) p.amount / Config.PRICE_PER_GB else 0

        when {
            gb in 1..Config.MAX_GB -> {                                   // megas diarios
                val hadWishes = db.wishes(p9).isNotEmpty()
                if (!db.addPay(p.id, p.source, p.amount, p.payer, p.name, gb * 1024, "OK")) return
                db.log("PAGAMENTO ${p.source} ${p.id} ${p.amount}MT de ${p.name} = ${gb}GB diarios")
                allocate(c, p9)
                if (hadWishes) sendSms(c, p.payer, "Ola$who! Recebemos o seu pagamento de ${amt}MT. A processar o seu pedido.")
                else sendSms(c, p.payer, "Ola$who! Recebemos o seu pagamento de ${amt}MT. Agora envie o numero que vai receber os megabytes.")
            }
            p.amount in Config.MANUAL_PRICES -> {                         // semanal / mensal / ilimitado: voce faz a transferencia
                if (!db.addPay(p.id, p.source, p.amount, p.payer, p.name, 0, "MANUAL")) return
                val dests = db.manualDests(p9)
                db.log("PAGAMENTO MANUAL ${p.id} ${p.amount}MT de ${p.name} ${dests.joinToString()}")
                sendSms(c, p.payer, "Ola$who! Recebemos o seu pagamento de ${amt}MT. " +
                        if (dests.isEmpty()) "Envie o numero que vai receber o pacote e aguarde um momento." else "Aguarde um momento, por favor.")
                sendSms(c, Config.OWNER_PHONE, "PACOTE MANUAL: ${p.amount}MT de ${p.name} (${p.payer}) " +
                        (if (dests.isEmpty()) "ainda sem numero" else "para " + dests.joinToString()) + " ID ${p.id}")
            }
            else -> {                                                      // valor invalido
                if (!db.addPay(p.id, p.source, p.amount, p.payer, p.name, 0, "INVALID")) return
                db.log("PAGAMENTO INVALIDO ${p.id} ${p.amount}MT de ${p.name}")
                sendSms(c, p.payer, "Valor de ${amt}MT invalido. Cada 1GB diario custa ${Config.PRICE_PER_GB}MT (ex: 50MT = 2GB, maximo ${Config.MAX_GB * Config.PRICE_PER_GB}MT). " +
                        Config.PAY_INFO + " Ref: ${p.id}")
                sendSms(c, Config.OWNER_PHONE, "VALOR INVALIDO: ${amt}MT de ${p.name} (${p.payer}) ID ${p.id}")
            }
        }
    }

    // ------------------------------------------------------------ SMS de um cliente
    fun onCustomerSms(c: Context, from: String, body: String) {
        val digits = from.filter { it.isDigit() }
        if (digits.length < 9 || Config.forwarder(from)) return          // ignora operadoras e remetentes de texto
        val p9 = digits.takeLast(9)
        val db = Db(c); db.rollover()
        val numbers = vodaNum.findAll(body).map { it.groupValues[1] }.filter { it !in Config.OWN_NUMBERS }.toList()

        // 0) comprovativo: valida o ID da transacao e associa o pagamento a ESTE numero
        //    (para quem paga por e-Mola/M-Pesa de um numero e pede por outro)
        val ids = extractIds(body)
        if (ids.isNotEmpty()) {
            val row = ids.firstNotNullOfOrNull { db.findPay(it) }
            if (row == null) {
                sendSms(c, from, "Nao encontramos essa transacao. Confirme o ID da transacao ou aguarde um minuto e envie de novo.")
                return
            }
            if (row.status == "INVALID") {
                sendSms(c, from, "O valor desse pagamento e invalido. Cada 1GB diario custa ${Config.PRICE_PER_GB}MT."); return
            }
            if (!db.claim(row.id, digits)) {
                db.log("comprovativo recusado: ${row.id} ja validado por outro numero ($p9)")
                sendSms(c, from, "Esta transacao ja foi validada por outro numero."); return
            }
            db.log("comprovativo validado: ${row.id} -> $p9")
            if (row.status == "MANUAL") {
                for (n in numbers) db.addWish(p9, n, 0)
                sendSms(c, from, "Pagamento validado! Aguarde um momento.")
                sendSms(c, Config.OWNER_PHONE, "PACOTE MANUAL validado: ${row.id} de $from " + (if (numbers.isEmpty()) "sem numero" else "para " + numbers.joinToString()))
                return
            }
            allocate(c, p9)                                              // pedidos que ja estavam guardados
            val first = parse(body)
            if (first != null && first.isNotEmpty()) { handleItems(c, from, p9, first); return }
            val left = db.credit(p9)
            sendSms(c, from, if (left < 100) "Pagamento validado. Os seus megas ja foram enviados ou estao em processamento."
                             else "Pagamento validado: ${fmt(left)} de megas diarios. Envie o numero que vai receber os megas.")
            return
        }

        // 1) pacotes semanais / mensais / ilimitados: guarda o numero e envia a tabela
        if (Config.MANUAL_KEYWORD.containsMatchIn(body)) {
            for (n in numbers) db.addWish(p9, n, 0)
            if (cooling("T$p9")) return
            sendSms(c, from, tablesFor(body))
            later(c.applicationContext, from, Config.PAY_MESSAGE)
            return
        }
        // 2) cliente com pagamento manual a espera que envia o numero: avisa-o
        if (numbers.isNotEmpty() && db.hasManualPay(p9) && db.credit(p9) < 100) {
            for (n in numbers) db.addWish(p9, n, 0)
            db.log("numero para pacote manual de $p9: ${numbers.joinToString()}")
            sendSms(c, from, "Recebido! Aguarde um momento.")
            sendSms(c, Config.OWNER_PHONE, "NUMERO para pacote manual de $from: ${numbers.joinToString()}")
            return
        }
        // 3) megas diarios: "2gb para 84..." ou so o numero
        val items = parse(body)
        if (items == null) { sendSms(c, from, "Nao percebi o pedido. Exemplo: 2gb para 841234567"); return }
        if (items.isNotEmpty()) { handleItems(c, from, p9, items); return }
        // 4) so a palavra-chave
        if (Config.KEYWORD.containsMatchIn(body) && !cooling(p9)) {
            sendSms(c, from, Config.PAY_MESSAGE)
            later(c.applicationContext, from, Config.DAILY_MESSAGE)
        }
    }

    private fun tablesFor(body: String): String {
        val l = ArrayList<String>()
        if (Regex("seman", RegexOption.IGNORE_CASE).containsMatchIn(body)) l.add(Config.WEEKLY_MESSAGE)
        if (Regex("mensa", RegexOption.IGNORE_CASE).containsMatchIn(body)) l.add(Config.MONTHLY_MESSAGE)
        if (Regex("ilimitad|chamadas", RegexOption.IGNORE_CASE).containsMatchIn(body)) l.add(Config.UNLIMITED_MESSAGE)
        return l.joinToString("\n\n")
    }

    private fun handleItems(c: Context, from: String, p9: String, items: List<Item>) {
        if (items.any { it.gb == 0 || it.gb > Config.MAX_GB }) {
            sendSms(c, from, "Indique entre 1GB e ${Config.MAX_GB}GB por numero. Exemplo: 2gb para 841234567"); return
        }
        val db = Db(c)
        for (it in items) db.addWish(p9, it.dest, if (it.gb < 0) -1 else it.gb * 1024)
        db.log("pedido de $p9: " + items.joinToString { "${it.gb}GB->${it.dest}" })
        allocate(c, p9)

        val pending = db.wishes(p9)
        val rest = db.wishMb(p9)
        val noCredit = db.credit(p9) < 100
        if (rest > 0) {
            val lines = pending.filter { it.mb > 0 }.joinToString("; ") { "${fmt(it.mb)} para ${it.dest}" }
            sendSms(c, from, "Pedido registado (megas diarios): $lines. Faltam ${price(rest)}MT. ${Config.PAY_INFO} " +
                    "Os megas serao enviados apos o pagamento." + proofHint(noCredit))
        } else if (noCredit && pending.any { it.mb < 0 }) {
            sendSms(c, from, "Numero guardado. Faca o pagamento (megas diarios). ${Config.PAY_INFO}" + proofHint(true))
        }
    }

    // ------------------------------------------------------------ distribui o saldo pelos pedidos
    fun allocate(c: Context, p9: String) {
        val db = Db(c)
        var made = 0
        for (w in db.wishes(p9)) {
            val credit = db.credit(p9)
            if (credit < 100) break                                        // minimo da Vodacom: 100 MB
            val want = if (w.mb < 0) credit else w.mb
            val grant = minOf(want, credit, 10240)
            if (grant < 100) continue
            val src = db.consume(p9, grant) ?: break
            val id = db.addTransfer(src.first, src.second, w.dest, grant)
            db.log("$id: ${fmt(grant)} -> ${w.dest} (na fila)")
            val remaining = if (w.mb < 0) (if (credit - grant >= 100) -1 else 0) else w.mb - grant
            db.setWish(w.rid, remaining)
            made++
        }
        if (made > 0) UssdService.poke()
    }

    /** Chamado quando um envio termina com sucesso: confirma e avisa do saldo que sobra. */
    fun afterTransfer(c: Context, s: Sale) {
        val db = Db(c)
        val p9 = s.payer.takeLast(9)
        allocate(c, p9)                                                    // pode haver pedidos a espera
        val credit = db.credit(p9)
        val busy = db.openTransfers(p9)
        val rest = db.wishMb(p9)
        val sb = StringBuilder("Megas diarios enviados: ${fmt(s.mb)} para ${s.dest}. Obrigado!")
        if (busy == 0 && credit >= 100) sb.append(" Ficou com saldo para ${fmt(credit)}. Envie o numero onde deseja receber os outros megas.")
        if (busy == 0 && rest > 0) sb.append(" Faltam ${price(rest)}MT para completar o seu pedido.")
        sendSms(c, s.payer, sb.toString())
    }
}

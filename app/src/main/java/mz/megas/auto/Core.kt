package mz.megas.auto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.*

object Config {
    // Nome do remetente das SMS (sem hifens/espacos, maiusculas). Confirme no seu telefone.
    val TRUSTED = setOf("MPESA", "EMOLA")

    // Se os pagamentos chegam a OUTRO telefone que reencaminha a SMS para este:
    // ponha aqui o numero desse telefone (so digitos, ex: "84xxxxxxx") e uma palavra secreta.
    // O outro telefone deve enviar:  PALAVRASECRETA + texto da SMS original
    val FORWARDERS = setOf<String>()
    const val FORWARD_SECRET = ""

    // Minutos que um numero de destino, enviado por SMS antes do pagamento, fica valido
    const val DEST_VALID_MIN = 60
    fun forwarder(sender: String) = sender.filter { it.isDigit() }.takeLast(9) in FORWARDERS

    // Tabela diaria: valor pago (MT) -> megas (MB)
    val PACKAGES = mapOf(25 to 1024, 50 to 2048, 125 to 5120, 250 to 10240)

    // Formas de pagamento mostradas ao cliente quando o valor e invalido
    const val PAY_INFO = "Pague por e-Mola 873505758 ou M-Pesa 852980738 (Nelson Francisco Nhavene)."

    // Stock com que comeca cada dia (MB). Use 0 para desligar o reinicio diario.
    const val DAILY_RESET_MB = 10240

    // Limite de transferencias por dia imposto pela Vodacom ("ate 10X/dia"). Se tambem transfere
    // megas a mao, ponha aqui um valor mais baixo.
    const val MAX_PER_DAY = 10

    // true = depois de cada pagamento valido, pede SEMPRE o numero de destino por SMS
    // (mesmo que o cliente o tenha escrito no "Conteudo"). false = usa o Conteudo/numero de quem pagou.
    const val ALWAYS_ASK = true

    // Palavra-chave: se um cliente enviar uma SMS com "megas", recebe as formas de pagamento
    val KEYWORD = Regex("""\bmegas?\b""", RegexOption.IGNORE_CASE)
    const val PAY_MESSAGE = "\uD83D\uDCF1*Formas de Pagamento Atualizadas*\uD83D\uDCF1 \uD83D\uDCB3\n" +
            "Nome: *Nelson Francisco Nhavene*\n\n" +
            "1. *M-PESA* \uD83D\uDCF1\n   - N\u00famero: 852980738\n\n" +
            "2. *E-MOLA* \uD83D\uDCB8\n   - N\u00famero: 873505758\n" +
            "Apos o pagamento mande o numero onde deseja receber os megas"
    val PACKAGE_MESSAGE: String
        get() = "Escolha o pacote que deseja e efetue o pagamento:\n" +
                PACKAGES.entries.joinToString("\n") { "${it.key}MT = ${it.value / 1024}GB" }

    // Seu numero para receber alertas por SMS (ex: "84xxxxxxx"). Vazio = sem alertas.
    const val OWNER_PHONE = ""

    val DIALER_HINTS = listOf("phone")                              // pacote que mostra o USSD (com.android.phone)
    val NOT_USSD = listOf("dialer", "incallui", "telecom")          // janelas a ignorar (teclado de chamadas, escolha de SIM)
    val SEND = setOf("send", "enviar", "ok", "responder")
    val DISMISS = setOf("ok", "cancel", "cancelar")
    // Palavras que indicam erro na resposta do USSD. AJUSTE depois de testar.
    val ERROR = Regex("sem sucesso|nao podes|n.o podes|invalid|inv.lid|insuficiente|erro|falh|indispon|n.o foi|excedeu", RegexOption.IGNORE_CASE)
    // Palavras que indicam sucesso no ultimo ecra. Se nenhuma aparecer, o pedido fica em CHECK.
    val SUCCESS = Regex("sucesso|efectuad|efetuad|enviad|transferid|concluid", RegexOption.IGNORE_CASE)

    fun trusted(sender: String) = TRUSTED.contains(sender.uppercase().filter { it.isLetterOrDigit() })
}

data class Payment(val id: String, val source: String, val amount: Int, val payer: String, val dest: String?, val explicit: Boolean = false, val name: String = "")
data class Sale(val id: String, val payer: String, val dest: String, val mb: Int)

object Parser {
    private val mpesa = Regex("""Confirmado\s+([A-Z0-9]+)\.\s*Recebeste\s+([\d.,]+)\s*MT\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val emola = Regex("""ID Trans:\s*([A-Z0-9.]+?)\.?\s*Recebeu\s+([\d.,]+)\s*MT\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val content = Regex("""Conteudo:\s*(.*?)\.\s*O seu""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val inContent = Regex("""\b(?:258)?(8[45]\d{7})\b""")
    private val payerNum = Regex("""(?:258)?(8[45]\d{7})""")

    private val nameRx = Regex("""\bde\s+\d+\s*[-,]\s*(.+?)\s+(?:aos?|as|\u00e0s)\s""", RegexOption.IGNORE_CASE)

    fun parse(body: String): Payment? {
        val (src, m) = mpesa.find(body)?.let { "MPESA" to it } ?: emola.find(body)?.let { "EMOLA" to it } ?: return null
        val amt = m.groupValues[2].replace(',', '.').toDouble()
        val amount = if (amt % 1.0 == 0.0) amt.toInt() else -1
        val payer = m.groupValues[3]
        val note = if (src == "EMOLA") content.find(body)?.groupValues?.get(1) else null
        val fromNote = note?.let { inContent.find(it)?.groupValues?.get(1) }
        val dest = fromNote ?: payerNum.matchEntire(payer)?.groupValues?.get(1)
        val name = nameRx.find(body)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
        return Payment(m.groupValues[1].trimEnd('.'), src, amount, payer, dest, fromNote != null, name)
    }
}

class Db(c: Context) : SQLiteOpenHelper(c, "vendas.db", null, 3) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE sales(id TEXT PRIMARY KEY, source TEXT, amount INT, payer TEXT, dest TEXT, mb INT, status TEXT, ts INT)")
        d.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v INT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS log(ts INT, t TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS dest(payer TEXT PRIMARY KEY, dest TEXT, ts INT)")
        d.execSQL("INSERT INTO kv VALUES('stock',${Config.DAILY_RESET_MB}),('day',0)")
    }
    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {
        d.execSQL("CREATE TABLE IF NOT EXISTS log(ts INT, t TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS dest(payer TEXT PRIMARY KEY, dest TEXT, ts INT)")
    }

    fun log(t: String) { writableDatabase.execSQL("INSERT INTO log VALUES(?,?)", arrayOf(System.currentTimeMillis(), t)) }
    fun logs(n: Int): List<String> = readableDatabase.rawQuery("SELECT ts,t FROM log ORDER BY ts DESC LIMIT $n", null).use { c ->
        val f = SimpleDateFormat("HH:mm:ss", Locale.US); val out = mutableListOf<String>()
        while (c.moveToNext()) out.add("${f.format(Date(c.getLong(0)))} ${c.getString(1)}")
        out
    }
    /** Volta a por na fila os pedidos parados por falta de stock ou de SIM. */
    fun retry() { writableDatabase.execSQL("UPDATE sales SET status='PENDING' WHERE status IN ('NO_STOCK','NO_SIM','LIMIT')") }

    /** false se o ID ja existe (duplicado ou SMS repetida). */
    fun add(id: String, src: String, amount: Int, payer: String, dest: String?, mb: Int?, status: String): Boolean {
        val v = ContentValues().apply {
            put("id", id); put("source", src); put("amount", amount); put("payer", payer)
            put("dest", dest); put("mb", mb); put("status", status); put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("sales", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun status(id: String, s: String) { writableDatabase.execSQL("UPDATE sales SET status=? WHERE id=?", arrayOf(s, id)) }
    fun sentToday(): Int = kv("sent")
    fun incSent() { setKv("sent", kv("sent") + 1) }
    fun hasReview(payer9: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM sales WHERE status='REVIEW' AND substr(payer,-9)=?", arrayOf(payer9)).use { it.moveToFirst() }

    fun exists(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM sales WHERE id=?", arrayOf(id)).use { it.moveToFirst() }

    /** Guarda o destino que o cliente enviou por SMS ANTES de pagar (ultimos 9 digitos do numero dele). */
    fun setDest(payer9: String, dest: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO dest VALUES(?,?,?)", arrayOf(payer9, dest, System.currentTimeMillis()))
    }
    /** Usa (uma so vez) o destino guardado, se ainda estiver valido. */
    fun takeDest(payer9: String): String? {
        val limit = (System.currentTimeMillis() - Config.DEST_VALID_MIN * 60_000L).toString()
        val d = readableDatabase.rawQuery("SELECT dest FROM dest WHERE payer=? AND ts>?", arrayOf(payer9, limit)).use { if (it.moveToFirst()) it.getString(0) else null }
        if (d != null) writableDatabase.execSQL("DELETE FROM dest WHERE payer=?", arrayOf(payer9))
        return d
    }
    /** Cliente pagou sem destino e agora enviou o numero: liberta o pedido parado em REVIEW. */
    fun releaseReview(payer9: String, dest: String): Sale? {
        val since = (System.currentTimeMillis() - 24 * 3_600_000L).toString()
        val s = readableDatabase.rawQuery(
            "SELECT id,payer,mb FROM sales WHERE status='REVIEW' AND mb IS NOT NULL AND substr(payer,-9)=? AND ts>? ORDER BY ts DESC LIMIT 1",
            arrayOf(payer9, since)
        ).use { if (it.moveToFirst()) Sale(it.getString(0), it.getString(1), dest, it.getInt(2)) else null } ?: return null
        writableDatabase.execSQL("UPDATE sales SET dest=?, status='PENDING' WHERE id=?", arrayOf(dest, s.id))
        return s
    }

    fun markStuck() { writableDatabase.execSQL("UPDATE sales SET status='CHECK' WHERE status='RUNNING'") }

    private fun kv(k: String): Int = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun setKv(k: String, v: Int) { writableDatabase.execSQL("INSERT OR REPLACE INTO kv VALUES(?,?)", arrayOf(k, v)) }
    fun stock() = kv("stock")

    /** Desconta do stock de forma atomica; false se nao houver megas suficientes. */
    /** Desconta do stock mostrado. A verificacao real faz-se no ecra do USSD, que mostra o saldo de dados. */
    fun reserve(mb: Int): Boolean { writableDatabase.execSQL("UPDATE kv SET v=v-? WHERE k='stock'", arrayOf(mb)); return true }
    fun refund(mb: Int) { writableDatabase.execSQL("UPDATE kv SET v=v+? WHERE k='stock'", arrayOf(mb)) }

    /** Novo dia: zera o contador de envios, repoe o stock e liberta os pedidos que esperavam pelo limite diario. */
    fun rollover() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        if (kv("day") == today) return
        setKv("day", today); setKv("sent", 0)
        if (Config.DAILY_RESET_MB > 0) setKv("stock", Config.DAILY_RESET_MB)
        writableDatabase.execSQL("UPDATE sales SET status='PENDING' WHERE status='LIMIT'")
    }

    fun nextPending(): Sale? = readableDatabase.rawQuery(
        "SELECT id,payer,dest,mb FROM sales WHERE status='PENDING' ORDER BY ts LIMIT 1", null
    ).use { if (it.moveToFirst()) Sale(it.getString(0), it.getString(1), it.getString(2), it.getInt(3)) else null }

    fun recent(n: Int): List<String> = readableDatabase.rawQuery(
        "SELECT status,amount,dest,ts,id FROM sales ORDER BY ts DESC LIMIT $n", null
    ).use { c ->
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.US); val out = mutableListOf<String>()
        while (c.moveToNext()) out.add("${f.format(Date(c.getLong(3)))} | ${c.getInt(1)}MT | ${c.getString(2)} | ${c.getString(0)} | ${c.getString(4)}")
        out
    }
}

/** Encontra a SIM Vodacom (MCC/MNC 64304) para o USSD e as SMS, sem perguntar qual usar. */
object Sim {
    private fun vodacom(t: TelephonyManager?) = t != null && (t.simOperator == "64304" || t.simOperatorName.contains("vodacom", true))

    @Suppress("MissingPermission")
    fun handle(c: Context): PhoneAccountHandle? = try {
        val tele = c.getSystemService(TelecomManager::class.java)
        val base = c.getSystemService(TelephonyManager::class.java)
        tele.callCapablePhoneAccounts.firstOrNull { vodacom(base.createForPhoneAccountHandle(it)) }
    } catch (e: Exception) { null }

    fun subId(c: Context): Int? {
        if (Build.VERSION.SDK_INT < 30) return null
        val h = handle(c) ?: return null
        val id = c.getSystemService(TelephonyManager::class.java).createForPhoneAccountHandle(h)?.subscriptionId
        return if (id == null || id < 0) null else id
    }
}

@Suppress("DEPRECATION")
fun sendSms(c: Context, to: String, text: String) {
    if (to.isBlank()) return
    val num = if (to.startsWith("258") && to.length == 12) "+$to" else to
    try {
        val sub = Sim.subId(c)
        val sm = when {
            sub != null -> SmsManager.getSmsManagerForSubscriptionId(sub)
            Build.VERSION.SDK_INT >= 31 -> c.getSystemService(SmsManager::class.java)
            else -> SmsManager.getDefault()
        }
        sm.sendMultipartTextMessage(num, null, sm.divideMessage(text), null, null)
    } catch (_: Exception) {}
}

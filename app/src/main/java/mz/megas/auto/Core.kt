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
    fun forwarder(sender: String) = sender.filter { it.isDigit() }.takeLast(9) in FORWARDERS

    // Tabela diaria: valor pago (MT) -> megas (MB)
    val PACKAGES = mapOf(25 to 1024, 50 to 2048, 125 to 5120, 250 to 10240)

    // Formas de pagamento mostradas ao cliente quando o valor e invalido
    const val PAY_INFO = "Pague por e-Mola 873505758 ou M-Pesa 852980738 (Nelson Francisco Nhavene)."

    // Stock com que comeca cada dia (MB). Use 0 para desligar o reinicio diario.
    const val DAILY_RESET_MB = 10240

    // Seu numero para receber alertas por SMS (ex: "84xxxxxxx"). Vazio = sem alertas.
    const val OWNER_PHONE = ""

    val DIALER_HINTS = listOf("phone", "dialer", "telecom", "incallui")
    val SEND = setOf("send", "enviar", "ok", "responder")
    val DISMISS = setOf("ok", "cancel", "cancelar")
    // Palavras que indicam erro na resposta do USSD. AJUSTE depois de testar.
    val ERROR = Regex("invalid|inv.lid|insuficiente|erro|falh|indispon|n.o foi", RegexOption.IGNORE_CASE)

    fun trusted(sender: String) = TRUSTED.contains(sender.uppercase().filter { it.isLetterOrDigit() })
}

data class Payment(val id: String, val source: String, val amount: Int, val payer: String, val dest: String?)
data class Sale(val id: String, val payer: String, val dest: String, val mb: Int)

object Parser {
    private val mpesa = Regex("""Confirmado\s+([A-Z0-9]+)\.\s*Recebeste\s+([\d.,]+)\s*MT\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val emola = Regex("""ID Trans:\s*([A-Z0-9.]+?)\.?\s*Recebeu\s+([\d.,]+)\s*MT\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val content = Regex("""Conteudo:\s*(.*?)\.\s*O seu""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val inContent = Regex("""\b(?:258)?(8[45]\d{7})\b""")
    private val payerNum = Regex("""(?:258)?(8[45]\d{7})""")

    fun parse(body: String): Payment? {
        val (src, m) = mpesa.find(body)?.let { "MPESA" to it } ?: emola.find(body)?.let { "EMOLA" to it } ?: return null
        val amt = m.groupValues[2].replace(',', '.').toDouble()
        val amount = if (amt % 1.0 == 0.0) amt.toInt() else -1
        val payer = m.groupValues[3]
        val note = if (src == "EMOLA") content.find(body)?.groupValues?.get(1) else null
        val dest = note?.let { inContent.find(it)?.groupValues?.get(1) }
            ?: payerNum.matchEntire(payer)?.groupValues?.get(1)
        return Payment(m.groupValues[1].trimEnd('.'), src, amount, payer, dest)
    }
}

class Db(c: Context) : SQLiteOpenHelper(c, "vendas.db", null, 2) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE sales(id TEXT PRIMARY KEY, source TEXT, amount INT, payer TEXT, dest TEXT, mb INT, status TEXT, ts INT)")
        d.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v INT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS log(ts INT, t TEXT)")
        d.execSQL("INSERT INTO kv VALUES('stock',${Config.DAILY_RESET_MB}),('day',0)")
    }
    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) { d.execSQL("CREATE TABLE IF NOT EXISTS log(ts INT, t TEXT)") }

    fun log(t: String) { writableDatabase.execSQL("INSERT INTO log VALUES(?,?)", arrayOf(System.currentTimeMillis(), t)) }
    fun logs(n: Int): List<String> = readableDatabase.rawQuery("SELECT ts,t FROM log ORDER BY ts DESC LIMIT $n", null).use { c ->
        val f = SimpleDateFormat("HH:mm:ss", Locale.US); val out = mutableListOf<String>()
        while (c.moveToNext()) out.add("${f.format(Date(c.getLong(0)))} ${c.getString(1)}")
        out
    }
    /** Volta a por na fila os pedidos parados por falta de stock ou de SIM. */
    fun retry() { writableDatabase.execSQL("UPDATE sales SET status='PENDING' WHERE status IN ('NO_STOCK','NO_SIM')") }

    /** false se o ID ja existe (duplicado ou SMS repetida). */
    fun add(id: String, src: String, amount: Int, payer: String, dest: String?, mb: Int?, status: String): Boolean {
        val v = ContentValues().apply {
            put("id", id); put("source", src); put("amount", amount); put("payer", payer)
            put("dest", dest); put("mb", mb); put("status", status); put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("sales", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun status(id: String, s: String) { writableDatabase.execSQL("UPDATE sales SET status=? WHERE id=?", arrayOf(s, id)) }
    fun markStuck() { writableDatabase.execSQL("UPDATE sales SET status='CHECK' WHERE status='RUNNING'") }

    private fun kv(k: String): Int = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun setKv(k: String, v: Int) { writableDatabase.execSQL("INSERT OR REPLACE INTO kv VALUES(?,?)", arrayOf(k, v)) }
    fun stock() = kv("stock")

    /** Desconta do stock de forma atomica; false se nao houver megas suficientes. */
    fun reserve(mb: Int): Boolean = writableDatabase.compileStatement("UPDATE kv SET v=v-? WHERE k='stock' AND v>=?")
        .apply { bindLong(1, mb.toLong()); bindLong(2, mb.toLong()) }.executeUpdateDelete() > 0
    fun refund(mb: Int) { writableDatabase.execSQL("UPDATE kv SET v=v+? WHERE k='stock'", arrayOf(mb)) }

    fun rollover() {
        if (Config.DAILY_RESET_MB <= 0) return
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        if (kv("day") != today) { setKv("day", today); setKv("stock", Config.DAILY_RESET_MB) }
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
    try {
        val sub = Sim.subId(c)
        val sm = when {
            sub != null -> SmsManager.getSmsManagerForSubscriptionId(sub)
            Build.VERSION.SDK_INT >= 31 -> c.getSystemService(SmsManager::class.java)
            else -> SmsManager.getDefault()
        }
        sm.sendMultipartTextMessage(to, null, sm.divideMessage(text), null, null)
    } catch (_: Exception) {}
}

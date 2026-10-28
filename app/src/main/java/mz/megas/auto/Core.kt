package mz.megas.auto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import java.text.SimpleDateFormat
import java.util.*

object Config {
    // Nome do remetente das SMS oficiais (sem hifens/espacos, maiusculas).
    val TRUSTED = setOf("MPESA", "EMOLA")

    // Se os pagamentos chegam a OUTRO telefone que reencaminha a SMS para este:
    // numero desse telefone (so digitos) e uma palavra secreta. O outro telefone envia: PALAVRA + texto da SMS.
    val FORWARDERS = setOf<String>()
    const val FORWARD_SECRET = ""
    fun forwarder(sender: String) = sender.filter { it.isDigit() }.takeLast(9) in FORWARDERS

    // ---- MEGAS DIARIOS: cada GB custa 25 MT (1GB=25, 2GB=50, 3GB=75 ... 10GB=250) ----
    const val PRICE_PER_GB = 25
    const val MAX_GB = 10                // maximo por transferencia (limite da Vodacom: 10240 MB)

    // ---- Pacotes SEMANAIS / MENSAIS / ILIMITADOS: valores (MT) que voce transfere a mao ----
    val MANUAL_PRICES = setOf(95, 140, 190, 280, 380, 135, 290, 480, 900, 450, 580, 850)

    const val PAY_INFO = "Pague por e-Mola 873505758 ou M-Pesa 852980738 (Nelson Francisco Nhavene)."

    // Stock mostrado na app; reinicia a cada dia (0 = nao reiniciar). O saldo real e lido no USSD.
    const val DAILY_RESET_MB = 10240

    // Limite de transferencias por dia imposto pela Vodacom ("ate 10X/dia").
    const val MAX_PER_DAY = 10

    // O seu numero para receber alertas por SMS (ex: "84xxxxxxx"). Vazio = sem alertas.
    const val OWNER_PHONE = ""

    // Minutos antes de a app voltar a enviar a mesma informacao (formas de pagamento, tabelas) ao mesmo cliente
    const val COOLDOWN_MIN = 2

    // Numeros da loja: nunca sao tratados como destino de megas (aparecem nos comprovativos)
    val OWN_NUMBERS = setOf("852980738", "873505758", "850604771")

    // Minutos... (mantido por compatibilidade)
    const val WHATSAPP_ENABLED = false

    // Palavras que disparam a resposta com as formas de pagamento
    val KEYWORD = Regex("""\bmegas?\b|\bgb\b|\bgigas?\b""", RegexOption.IGNORE_CASE)
    // Palavras que pedem pacotes semanais / mensais / ilimitados
    val MANUAL_KEYWORD = Regex("""\b(semanal|semanais|mensal|mensais|ilimitad[oa]s?|chamadas)\b""", RegexOption.IGNORE_CASE)

    const val PAY_MESSAGE = "\uD83D\uDCF1*Formas de Pagamento Atualizadas*\uD83D\uDCF1 \uD83D\uDCB3\n" +
            "Nome: *Nelson Francisco Nhavene*\n\n" +
            "1. *M-PESA* \uD83D\uDCF1\n   - N\u00famero: 852980738\n\n" +
            "2. *E-MOLA* \uD83D\uDCB8\n   - N\u00famero: 873505758\n" +
            "Apos o pagamento mande o numero onde deseja receber os megas"

    val DAILY_MESSAGE: String
        get() = "MEGAS DIARIOS\nEscolha o pacote que deseja e efetue o pagamento:\n" +
                (1..MAX_GB).joinToString("\n") { "${it}GB = ${it * PRICE_PER_GB}MT" } +
                "\nPara pedir escreva, por exemplo: 2gb para 84xxxxxxx" +
                "\nPara semanal, mensal ou ilimitado escreva: semanal, mensal ou ilimitado"
    val PACKAGE_MESSAGE: String get() = DAILY_MESSAGE

    const val WEEKLY_MESSAGE = "MEGAS SEMANAIS\n3.4GB = 95MT\n5.3GB = 140MT\n7.2GB = 190MT\n10.9GB = 280MT\n14.6GB = 380MT"
    const val MONTHLY_MESSAGE = "MEGAS MENSAIS\n4GB = 135MT\n7.1GB = 190MT\n10.7GB = 290MT\n18.3GB = 480MT\n36.6GB = 900MT"
    const val UNLIMITED_MESSAGE = "ILIMITADO 30 DIAS (chamadas e SMS ilimitadas para todas as redes)\n450MT + 11GB\n580MT + 15GB\n850MT + 22GB"

    val DIALER_HINTS = listOf("phone")                              // pacote que mostra o USSD (com.android.phone)
    val NOT_USSD = listOf("dialer", "incallui", "telecom")          // janelas a ignorar
    val SEND = setOf("send", "enviar", "ok", "responder")
    val DISMISS = setOf("ok", "cancel", "cancelar")
    val ERROR = Regex("sem sucesso|nao podes|n.o podes|invalid|inv.lid|insuficiente|erro|falh|indispon|n.o foi|excedeu", RegexOption.IGNORE_CASE)
    val SUCCESS = Regex("sucesso|efectuad|efetuad|enviad|transferid|concluid", RegexOption.IGNORE_CASE)

    fun trusted(sender: String) = TRUSTED.contains(sender.uppercase().filter { it.isLetterOrDigit() })
}

data class Payment(val id: String, val source: String, val amount: Int, val payer: String, val name: String)
data class Sale(val id: String, val payer: String, val dest: String, val mb: Int)
data class Wish(val rid: Long, val dest: String, val mb: Int)
data class PayRow(val id: String, val payer: String, val status: String)

object Parser {
    private val mpesa = Regex("""Confirmado\s+([A-Z0-9]+)\.\s*Recebeste\s+([\d.,]+)\s*MT\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val emola = Regex("""ID Trans:\s*([A-Z0-9.]+?)\.?\s*Recebeu\s+([\d.,]+)\s*MT\s+de\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val nameRx = Regex("""\bde\s+\d+\s*[-,]\s*(.+?)\s+(?:aos?|as|\u00e0s)\s""", RegexOption.IGNORE_CASE)

    fun parse(body: String): Payment? {
        val (src, m) = mpesa.find(body)?.let { "MPESA" to it } ?: emola.find(body)?.let { "EMOLA" to it } ?: return null
        val amt = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        val amount = if (amt % 1.0 == 0.0) amt.toInt() else -1
        val name = nameRx.find(body)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
        return Payment(m.groupValues[1].trimEnd('.'), src, amount, m.groupValues[3], name)
    }
}

class Db(c: Context) : SQLiteOpenHelper(c, "vendas.db", null, 5) {
    override fun onCreate(d: SQLiteDatabase) {
        // "sales" = fila de TRANSFERENCIAS (uma linha por envio de megas)
        d.execSQL("CREATE TABLE sales(id TEXT PRIMARY KEY, source TEXT, amount INT, payer TEXT, dest TEXT, mb INT, status TEXT, ts INT)")
        d.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v INT)")
        d.execSQL("INSERT INTO kv VALUES('stock',${Config.DAILY_RESET_MB}),('day',0)")
        extra(d)
    }
    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) { extra(d) }
    private fun extra(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE IF NOT EXISTS log(ts INT, t TEXT)")
        // "claim" = transacoes validadas por comprovativo e o numero que as reclamou
        d.execSQL("CREATE TABLE IF NOT EXISTS claim(id TEXT PRIMARY KEY, who TEXT, ts INT)")
        // "pay" = PAGAMENTOS recebidos (ID unico) e o saldo de megas que ainda tem para enviar
        d.execSQL("CREATE TABLE IF NOT EXISTS pay(id TEXT PRIMARY KEY, source TEXT, amount INT, payer TEXT, name TEXT, mbleft INT, status TEXT, ts INT)")
        // "wish" = pedidos do cliente (numero + GB). mb=-1: todo o saldo; mb=0: pacote manual (so guarda o numero)
        d.execSQL("CREATE TABLE IF NOT EXISTS wish(rid INTEGER PRIMARY KEY AUTOINCREMENT, payer TEXT, dest TEXT, mb INT, ts INT)")
    }

    private fun since(h: Int) = (System.currentTimeMillis() - h * 3_600_000L).toString()

    // ---------- registo ----------
    fun log(t: String) { writableDatabase.execSQL("INSERT INTO log VALUES(?,?)", arrayOf(System.currentTimeMillis(), t)) }
    fun logs(n: Int): List<String> = readableDatabase.rawQuery("SELECT ts,t FROM log ORDER BY ts DESC LIMIT $n", null).use { c ->
        val f = SimpleDateFormat("HH:mm:ss", Locale.US); val out = mutableListOf<String>()
        while (c.moveToNext()) out.add("${f.format(Date(c.getLong(0)))} ${c.getString(1)}")
        out
    }

    // ---------- transferencias (fila do USSD) ----------
    fun add(id: String, src: String, amount: Int, payer: String, dest: String?, mb: Int?, status: String): Boolean {
        val v = ContentValues().apply {
            put("id", id); put("source", src); put("amount", amount); put("payer", payer)
            put("dest", dest); put("mb", mb); put("status", status); put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("sales", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun addTransfer(payId: String, payer: String, dest: String, mb: Int): String {
        val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM sales WHERE id LIKE ?", arrayOf("$payId-%")).use { it.moveToFirst(); it.getInt(0) } + 1
        val id = "$payId-$n"
        add(id, "DAILY", mb / 1024 * Config.PRICE_PER_GB, payer, dest, mb, "PENDING")
        return id
    }
    fun status(id: String, s: String) { writableDatabase.execSQL("UPDATE sales SET status=? WHERE id=?", arrayOf(s, id)) }
    fun markStuck() { writableDatabase.execSQL("UPDATE sales SET status='CHECK' WHERE status='RUNNING'") }
    /** Volta a por na fila os pedidos parados por falta de stock, de SIM ou pelo limite diario. */
    fun retry() { writableDatabase.execSQL("UPDATE sales SET status='PENDING' WHERE status IN ('NO_STOCK','NO_SIM','LIMIT')") }
    fun nextPending(): Sale? = readableDatabase.rawQuery(
        "SELECT id,payer,dest,mb FROM sales WHERE status='PENDING' ORDER BY ts LIMIT 1", null
    ).use { if (it.moveToFirst()) Sale(it.getString(0), it.getString(1), it.getString(2), it.getInt(3)) else null }
    fun openTransfers(p9: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM sales WHERE status IN ('PENDING','RUNNING') AND substr(payer,-9)=?", arrayOf(p9)).use { it.moveToFirst(); it.getInt(0) }

    fun recent(n: Int): List<String> = readableDatabase.rawQuery(
        "SELECT status,amount,dest,ts,id FROM sales UNION ALL " +
        "SELECT status,amount,payer,ts,id FROM pay WHERE status IN ('MANUAL','INVALID') ORDER BY ts DESC LIMIT $n", null
    ).use { c ->
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.US); val out = mutableListOf<String>()
        while (c.moveToNext()) out.add("${f.format(Date(c.getLong(3)))} | ${c.getInt(1)}MT | ${c.getString(2)} | ${c.getString(0)} | ${c.getString(4)}")
        out
    }

    // ---------- pagamentos e saldo do cliente ----------
    fun exists(id: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM pay WHERE id=? UNION SELECT 1 FROM sales WHERE id=?", arrayOf(id, id)).use { it.moveToFirst() }
    fun addPay(id: String, src: String, amount: Int, payer: String, name: String, mbleft: Int, status: String): Boolean {
        val v = ContentValues().apply {
            put("id", id); put("source", src); put("amount", amount); put("payer", payer)
            put("name", name); put("mbleft", mbleft); put("status", status); put("ts", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("pay", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun credit(p9: String): Int = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(mbleft),0) FROM pay WHERE status='OK' AND mbleft>0 AND substr(payer,-9)=?", arrayOf(p9)).use { it.moveToFirst(); it.getInt(0) }

    /** Gasta `mb` do saldo do cliente (do pagamento mais antigo). Devolve (id do pagamento, numero de quem pagou) ou null. */
    fun consume(p9: String, mb: Int): Pair<String, String>? {
        val rows = readableDatabase.rawQuery(
            "SELECT id,payer,mbleft FROM pay WHERE status='OK' AND mbleft>0 AND substr(payer,-9)=? ORDER BY ts", arrayOf(p9)
        ).use { c -> val l = ArrayList<Triple<String, String, Int>>(); while (c.moveToNext()) l.add(Triple(c.getString(0), c.getString(1), c.getInt(2))); l }
        var need = mb; var first: Pair<String, String>? = null
        for ((id, payer, left) in rows) {
            if (need <= 0) break
            val take = minOf(left, need)
            writableDatabase.execSQL("UPDATE pay SET mbleft=mbleft-? WHERE id=?", arrayOf(take, id))
            if (first == null) first = id to payer
            need -= take
        }
        return if (need <= 0) first else null
    }
    /** Devolve megas ao saldo do cliente (quando o USSD falha). */
    fun restoreCredit(p9: String, mb: Int) {
        writableDatabase.execSQL("UPDATE pay SET mbleft=mbleft+? WHERE id=(SELECT id FROM pay WHERE status='OK' AND substr(payer,-9)=? ORDER BY ts DESC LIMIT 1)", arrayOf(mb, p9))
    }
    fun hasManualPay(p9: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM pay WHERE status='MANUAL' AND substr(payer,-9)=? AND ts>?", arrayOf(p9, since(24))).use { it.moveToFirst() }

    // ---------- comprovativo: liga um pagamento ao numero que o apresenta ----------
    fun findPay(id: String): PayRow? = readableDatabase.rawQuery(
        "SELECT id,payer,status FROM pay WHERE UPPER(id)=UPPER(?)", arrayOf(id)
    ).use { if (it.moveToFirst()) PayRow(it.getString(0), it.getString(1), it.getString(2)) else null }

    /** true se a transacao ficou (ou ja estava) associada a este numero; false se outro numero ja a validou. */
    fun claim(id: String, who: String): Boolean {
        val prev = readableDatabase.rawQuery("SELECT who FROM claim WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
        if (prev != null && prev.takeLast(9) != who.takeLast(9)) return false
        val payer = readableDatabase.rawQuery("SELECT payer FROM pay WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        writableDatabase.execSQL("INSERT OR REPLACE INTO claim VALUES(?,?,?)", arrayOf(id, who, System.currentTimeMillis()))
        if (payer.takeLast(9) != who.takeLast(9)) writableDatabase.execSQL("UPDATE pay SET payer=? WHERE id=?", arrayOf(who, id))
        return true
    }

    // ---------- pedidos (numero + GB) ----------
    fun addWish(p9: String, dest: String, mb: Int) {
        writableDatabase.execSQL("INSERT INTO wish(payer,dest,mb,ts) VALUES(?,?,?,?)", arrayOf(p9, dest, mb, System.currentTimeMillis()))
    }
    fun wishes(p9: String): List<Wish> = readableDatabase.rawQuery(
        "SELECT rid,dest,mb FROM wish WHERE payer=? AND mb<>0 AND ts>? ORDER BY rid", arrayOf(p9, since(24))
    ).use { c -> val l = ArrayList<Wish>(); while (c.moveToNext()) l.add(Wish(c.getLong(0), c.getString(1), c.getInt(2))); l }
    fun setWish(rid: Long, mb: Int) {
        if (mb == 0) writableDatabase.execSQL("DELETE FROM wish WHERE rid=?", arrayOf(rid))
        else writableDatabase.execSQL("UPDATE wish SET mb=? WHERE rid=?", arrayOf(mb, rid))
    }
    fun wishMb(p9: String): Int = readableDatabase.rawQuery(
        "SELECT COALESCE(SUM(mb),0) FROM wish WHERE payer=? AND mb>0 AND ts>?", arrayOf(p9, since(24))).use { it.moveToFirst(); it.getInt(0) }
    fun manualDests(p9: String): List<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT dest FROM wish WHERE payer=? AND mb=0 AND ts>?", arrayOf(p9, since(24))
    ).use { c -> val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(0)); l }

    // ---------- stock mostrado, limite diario ----------
    private fun kv(k: String): Int = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun setKv(k: String, v: Int) { writableDatabase.execSQL("INSERT OR REPLACE INTO kv VALUES(?,?)", arrayOf(k, v)) }
    fun stock() = kv("stock")
    fun reserve(mb: Int): Boolean { writableDatabase.execSQL("UPDATE kv SET v=v-? WHERE k='stock'", arrayOf(mb)); return true }
    fun refund(mb: Int) { writableDatabase.execSQL("UPDATE kv SET v=v+? WHERE k='stock'", arrayOf(mb)) }
    fun sentToday(): Int = kv("sent")
    fun incSent() { setKv("sent", kv("sent") + 1) }

    /** Novo dia: zera o contador de envios, repoe o stock e liberta os pedidos que esperavam pelo limite diario. */
    fun rollover() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        if (kv("day") == today) return
        setKv("day", today); setKv("sent", 0)
        if (Config.DAILY_RESET_MB > 0) setKv("stock", Config.DAILY_RESET_MB)
        writableDatabase.execSQL("UPDATE sales SET status='PENDING' WHERE status='LIMIT'")
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

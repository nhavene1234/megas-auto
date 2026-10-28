package mz.megas.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Executa a fila de vendas: *162# -> 8 -> 2 -> MB -> numero, um pedido de cada vez. */
class UssdService : AccessibilityService() {
    companion object {
        @Volatile var instance: UssdService? = null
        fun poke() { instance?.let { s -> s.h.post { s.next() } } }
    }

    val h = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private var cur: Sale? = null
    private var step = 0
    private var retries = 0
    private var steps = listOf<String>()
    private var lastText = ""
    private var lastNote = ""
    private var wl: PowerManager.WakeLock? = null
    private val balanceRx = Regex("""Saldo de dados:\s*([\d.,]+)\s*(MB|GB)""", RegexOption.IGNORE_CASE)

    private val tick: Runnable = object : Runnable { override fun run() { next(); h.postDelayed(this, 600_000) } }

    override fun onServiceConnected() { instance = this; db = Db(this); db.markStuck(); h.post(tick) }
    override fun onDestroy() { h.removeCallbacks(tick); instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    fun next() {
        if (cur != null) return
        db.rollover()
        val s = db.nextPending() ?: return
        val acc = Sim.handle(this)
        if (acc == null) {
            db.status(s.id, "NO_SIM"); db.log("${s.id}: SIM Vodacom nao encontrada")
            sendSms(this, Config.OWNER_PHONE, "SEM SIM VODACOM para ${s.id}")
            return next()
        }
        if (db.sentToday() >= Config.MAX_PER_DAY) {
            db.status(s.id, "LIMIT"); db.log("${s.id}: limite diario de ${Config.MAX_PER_DAY} envios atingido")
            sendSms(this, s.payer, "Limite diario de envios atingido. Os seus megas (ref ${s.id}) serao enviados amanha.")
            sendSms(this, Config.OWNER_PHONE, "LIMITE DIARIO atingido: ${s.id}")
            return next()
        }
        db.reserve(s.mb)
        cur = s; step = 0; retries = 0; lastText = ""
        steps = listOf("8", "2", s.mb.toString(), s.dest)
        db.status(s.id, "RUNNING")
        db.log("${s.id}: a iniciar USSD ${s.mb}MB -> ${s.dest}")
        wakeScreen()
        h.postDelayed(timeout, 90_000)
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode("*162#")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, acc))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (cur == null) return
        h.removeCallbacks(react); h.postDelayed(react, 500)
    }

    private val react: Runnable = Runnable {
        val s = cur ?: return@Runnable
        val sn = snap() ?: return@Runnable
        val nodes = sn.nodes
        val input = sn.input
        val text = sn.text
        // Caixa "USSD code running..." (a carregar): espera. NAO e a resposta final.
        if (input == null && sn.loading) {
            if (step > 0) { h.removeCallbacks(stall); h.postDelayed(stall, 20_000) }
            return@Runnable
        }
        if (text.isEmpty() || text == lastText) return@Runnable
        lastText = text; h.removeCallbacks(stall); retries = 0
        db.log("passo $step/${steps.size}: " + text.take(70))
        if (step == 0) db.log("nos: " + nodes.joinToString { n ->
            (n.className?.toString()?.substringAfterLast('.') ?: "?") + ":" + (n.text?.toString()?.take(8) ?: "") +
                    ":c" + (if (n.isClickable) 1 else 0) + "e" + (if (n.isEditable) 1 else 0) })

        // Resposta final, erro a meio, ou ecra sem caixa de texto
        if (input == null || step >= steps.size || Config.ERROR.containsMatchIn(text)) {
            val result: Boolean? = when {
                Config.ERROR.containsMatchIn(text) -> false
                step >= steps.size && Config.SUCCESS.containsMatchIn(text) -> true
                else -> null
            }
            dismiss(nodes); finish(result, text); return@Runnable
        }

        // Ecra "Quantos megas": le o saldo real e cancela se nao chegar
        if (step == 2) {
            val m = balanceRx.find(text)
            if (m != null) {
                val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
                val bal = (if (m.groupValues[2].equals("GB", true)) v * 1024 else v).toInt()
                if (bal < s.mb) { dismiss(nodes); noStock(s, bal); return@Runnable }
                db.setKv("stock", bal - s.mb)
            }
        }

        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, steps[step]) }
        val setOk = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val sendNode = findSend(nodes) ?: run { dismiss(nodes); finish(null, "botao Send nao encontrado"); return@Runnable }
        val sent = steps[step]
        step++
        h.postDelayed({
            sendNode.refresh()
            val ok = sendNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            db.log("passo ${step - 1}: enviou '$sent' setText=$setOk clique=$ok")
        }, 400)
        h.postDelayed(stall, 6000)
    }

    /** Se o ecra nao mudou depois de enviar, repete o passo (ate 2 vezes). */
    private val stall: Runnable = Runnable {
        val s = cur ?: return@Runnable
        val now = snap()
        if (now != null && now.text != lastText) { react.run(); return@Runnable }   // o ecra ja mudou: processa-o
        if (retries >= 2) { performGlobalAction(GLOBAL_ACTION_BACK); finish(null, "nao avancou no passo $step"); return@Runnable }
        retries++; step--; lastText = ""
        db.log("${s.id}: sem resposta, a repetir passo $step (tentativa $retries)")
        react.run()
    }

    private val timeout: Runnable = Runnable { performGlobalAction(GLOBAL_ACTION_BACK); finish(null, "tempo esgotado") }

    private fun finish(ok: Boolean?, info: String) {
        val s = cur ?: return
        db.log("${s.id}: resultado=$ok | $info")
        releaseScreen()
        h.removeCallbacks(timeout); h.removeCallbacks(react); h.removeCallbacks(stall)
        when (ok) {
            true -> { db.incSent(); db.status(s.id, "DONE"); Orders.afterTransfer(this, s) }
            false -> {
                db.refund(s.mb); db.restoreCredit(s.payer.takeLast(9), s.mb); db.status(s.id, "FAILED")
                sendSms(this, s.payer, "Nao foi possivel enviar os megas agora. Ref: ${s.id}. Vamos resolver.")
                sendSms(this, Config.OWNER_PHONE, "FALHOU ${s.id}: $info")
            }
            // Resultado incerto: NAO reenvia nem devolve stock (evita enviar megas 2 vezes). Verifique manualmente.
            null -> { db.incSent(); db.status(s.id, "CHECK"); sendSms(this, Config.OWNER_PHONE, "VERIFICAR ${s.id} ${s.mb}MB -> ${s.dest}: $info") }
        }
        cur = null
        h.postDelayed({ next() }, 3000)
    }

    private fun noStock(s: Sale, bal: Int) {
        h.removeCallbacks(timeout); h.removeCallbacks(stall)
        db.setKv("stock", bal); db.status(s.id, "NO_STOCK")
        releaseScreen()
        db.log("${s.id}: saldo $bal MB menor que ${s.mb} MB")
        sendSms(this, s.payer, "Sem megas disponiveis de momento. Ref: ${s.id}. Entraremos em contacto.")
        sendSms(this, Config.OWNER_PHONE, "SALDO INSUFICIENTE (${bal}MB) para ${s.id} ${s.mb}MB")
        cur = null
        h.postDelayed({ next() }, 3000)
    }

    private class Snap(val nodes: List<AccessibilityNodeInfo>, val input: AccessibilityNodeInfo?, val text: String, val loading: Boolean)

    private fun snap(): Snap? {
        val root = dialerRoot() ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>().also { walk(root, it) }
        val input = nodes.firstOrNull { it.isEditable || it.className?.toString()?.contains("EditText") == true }
        val body = nodes.filter { !it.isEditable && !it.isClickable && !it.text.isNullOrBlank() }
            .joinToString(" ") { it.text.toString() }.trim()
        val text = body.ifEmpty { nodes.joinToString(" ") { it.text?.toString() ?: "" }.trim() }
        val loading = nodes.any { it.className?.toString()?.contains("ProgressBar") == true } ||
                Regex("ussd code running|c.digo ussd em execu|a executar|executando", RegexOption.IGNORE_CASE).containsMatchIn(text)
        return Snap(nodes, input, text, loading)
    }

    /** Acorda o ecra durante a venda (so ajuda se o bloqueio estiver em "Nenhum" ou "Deslizar"). */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            releaseScreen()
            val pm = getSystemService(PowerManager::class.java)
            wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "megas:ussd")
            wl?.acquire(120_000L)
        } catch (_: Exception) {}
    }
    private fun releaseScreen() { try { wl?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; wl = null }

    private fun note(t: String) { if (t != lastNote) { lastNote = t; db.log(t) } }
    private fun isUssd(pkg: String?) = pkg != null && Config.DIALER_HINTS.any { pkg.contains(it) } && Config.NOT_USSD.none { pkg.contains(it) }

    /** Janela do USSD: a do telefone (com.android.phone), nunca o teclado de chamadas. */
    private fun dialerRoot(): AccessibilityNodeInfo? {
        val c = ArrayList<Pair<AccessibilityNodeInfo, AccessibilityWindowInfo>>()
        for (w in windows) {
            val r = w.root ?: continue
            if (isUssd(r.packageName?.toString())) c.add(r to w)
        }
        if (c.isEmpty()) for (w in windows) {   // plano B: qualquer janela com caixa de texto e botao Send
            val r = w.root ?: continue
            val pkg = r.packageName?.toString() ?: continue
            if (pkg == packageName || pkg.contains("inputmethod") || pkg.contains("systemui") || Config.NOT_USSD.any { pkg.contains(it) }) continue
            val l = ArrayList<AccessibilityNodeInfo>().also { walk(r, it) }
            if (l.any { it.isEditable } && l.any { it.isClickable && it.text?.toString()?.trim()?.lowercase() == "send" }) c.add(r to w)
        }
        if (c.isNotEmpty()) return (c.firstOrNull { it.second.isActive } ?: c.maxByOrNull { it.second.layer })?.first
        val a = rootInActiveWindow
        if (a != null && isUssd(a.packageName?.toString())) return a
        note("sem janela USSD. janelas: " + windows.joinToString { it.root?.packageName?.toString() ?: "?" } + " | ativa: " + (a?.packageName ?: "nenhuma"))
        return null
    }

    private fun findSend(nodes: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? =
        nodes.firstOrNull { it.viewIdResourceName?.endsWith("button1") == true }
            ?: nodes.firstOrNull { it.isClickable && it.text?.toString()?.trim()?.lowercase() in Config.SEND }
            ?: nodes.firstOrNull { it.className?.toString()?.contains("Button") == true &&
                    it.text?.toString()?.trim()?.lowercase() !in setOf("cancel", "cancelar") }

    private fun dismiss(nodes: List<AccessibilityNodeInfo>) {
        val b = nodes.firstOrNull { it.isClickable && it.text?.toString()?.trim()?.lowercase() in Config.DISMISS }
        if (b != null) b.performAction(AccessibilityNodeInfo.ACTION_CLICK) else performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n); for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }
}

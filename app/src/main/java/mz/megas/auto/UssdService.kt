package mz.megas.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    private var steps = listOf<String>()
    private var lastText = ""

    override fun onServiceConnected() { instance = this; db = Db(this); db.markStuck(); next() }
    override fun onDestroy() { instance = null; super.onDestroy() }
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
        if (!db.reserve(s.mb)) {
            db.status(s.id, "NO_STOCK")
            sendSms(this, s.payer, "Sem megas disponiveis de momento. Ref: ${s.id}. Entraremos em contacto.")
            sendSms(this, Config.OWNER_PHONE, "SEM STOCK: ${s.id} ${s.mb}MB para ${s.dest}")
            return next()
        }
        cur = s; step = 0; lastText = ""
        steps = listOf("8", "2", s.mb.toString(), s.dest)
        db.status(s.id, "RUNNING")
        h.postDelayed(timeout, 60_000)
        db.log("${s.id}: USSD ${s.mb}MB -> ${s.dest}")
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode("*162#")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, acc))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (cur == null) return
        val pkg = e.packageName?.toString() ?: return
        if (Config.DIALER_HINTS.none { pkg.contains(it) }) return
        h.removeCallbacks(react); h.postDelayed(react, 500)
    }

    private val react = Runnable {
        cur ?: return@Runnable
        val root = dialerRoot() ?: return@Runnable
        val nodes = ArrayList<AccessibilityNodeInfo>().also { walk(root, it) }
        val text = nodes.filter { it.className == "android.widget.TextView" }
            .joinToString(" ") { it.text?.toString() ?: "" }.trim()
        if (text.isEmpty() || text == lastText) return@Runnable
        lastText = text

        val input = nodes.firstOrNull { it.className == "android.widget.EditText" }
        if (input == null || step >= steps.size) {          // resposta final (ou erro a meio)
            val result: Boolean? = when {
                Config.ERROR.containsMatchIn(text) -> false
                step >= steps.size -> true
                else -> null
            }
            dismiss(nodes); finish(result, text); return@Runnable
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, steps[step]) }
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val send = nodes.firstOrNull { it.isClickable && it.text?.toString()?.trim()?.lowercase() in Config.SEND }
        if (send == null) { finish(null, "botao enviar nao encontrado"); return@Runnable }
        step++
        send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private val timeout = Runnable { performGlobalAction(GLOBAL_ACTION_BACK); finish(null, "tempo esgotado") }

    private fun finish(ok: Boolean?, info: String) {
        val s = cur ?: return
        db.log("${s.id}: resultado=$ok | $info")
        h.removeCallbacks(timeout); h.removeCallbacks(react)
        when (ok) {
            true -> { db.status(s.id, "DONE"); sendSms(this, s.payer, "Megas enviados: ${s.mb}MB para ${s.dest}. Obrigado!") }
            false -> {
                db.refund(s.mb); db.status(s.id, "FAILED")
                sendSms(this, s.payer, "Nao foi possivel enviar os megas agora. Ref: ${s.id}. Vamos resolver.")
                sendSms(this, Config.OWNER_PHONE, "FALHOU ${s.id}: $info")
            }
            // Resultado incerto: NAO reenvia nem devolve stock (evita enviar megas 2 vezes). Verifique manualmente.
            null -> { db.status(s.id, "CHECK"); sendSms(this, Config.OWNER_PHONE, "VERIFICAR ${s.id} ${s.mb}MB -> ${s.dest}: $info") }
        }
        cur = null
        h.postDelayed({ next() }, 3000)
    }

    /** Procura a janela do USSD mesmo que outra (ex: notificacao de SMS) esteja em primeiro plano. */
    private fun dialerRoot(): AccessibilityNodeInfo? {
        for (w in windows) {
            val r = w.root ?: continue
            if (Config.DIALER_HINTS.any { r.packageName?.toString()?.contains(it) == true }) return r
        }
        return rootInActiveWindow?.takeIf { r -> Config.DIALER_HINTS.any { r.packageName?.toString()?.contains(it) == true } }
    }

    private fun dismiss(nodes: List<AccessibilityNodeInfo>) {
        val b = nodes.firstOrNull { it.isClickable && it.text?.toString()?.trim()?.lowercase() in Config.DISMISS }
        if (b != null) b.performAction(AccessibilityNodeInfo.ACTION_CLICK) else performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun walk(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        out.add(n); for (i in 0 until n.childCount) walk(n.getChild(i), out)
    }
}

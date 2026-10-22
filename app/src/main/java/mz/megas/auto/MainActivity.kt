package mz.megas.auto

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var info: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        info = TextView(this)
        val et = EditText(this).apply { hint = "Stock em MB (ex: 10240)"; inputType = InputType.TYPE_CLASS_NUMBER }
        fun btn(t: String, f: () -> Unit) { l.addView(Button(this).apply { text = t; setOnClickListener { f() } }) }
        l.addView(info); l.addView(et)
        btn("Definir stock") { et.text.toString().toIntOrNull()?.let { Db(this).setKv("stock", it) }; refresh() }
        btn("1. Dar permissoes (SMS e chamadas)") {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
                Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE), 1)
        }
        btn("2. Ativar servico de acessibilidade") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("3. Desativar otimizacao de bateria") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        btn("Atualizar") { refresh() }
        setContentView(ScrollView(this).apply { addView(l) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val d = Db(this); d.rollover()
        info.text = "Stock: ${d.stock()} MB\nServico USSD: ${if (UssdService.instance != null) "ATIVO" else "DESLIGADO"}\n\n" +
                "Ultimas vendas:\n" + d.recent(15).joinToString("\n")
    }
}

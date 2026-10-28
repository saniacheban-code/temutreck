package kz.tracker
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = LinearLayout(this)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(btn("＋ Следить") { track() })
        bar.addView(btn("Список") { showList() })
        bar.addView(btn("Проверить") { checkNow() })
        web = WebView(this)
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = WebViewClient()
        root.addView(bar); root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        web.loadUrl("https://www.temu.com")
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("p", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PriceWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    private fun track() {
        web.evaluateJavascript(Extract.JS) { raw ->
            val r = Extract.parse(raw)
            if (r?.price == null) { toast("Не нашёл цену. Откройте страницу товара."); return@evaluateJavascript }
            val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText((r.price * 0.9).toInt().toString()) }
            AlertDialog.Builder(this).setTitle("Цена сейчас: ${r.price}\nУведомить, когда цена ≤").setView(et)
                .setPositiveButton("Следить") { _, _ ->
                    val l = Store.load(this); val u = web.url ?: return@setPositiveButton
                    l.removeAll { it.url == u }
                    l.add(Item(u, r.title.take(60), r.price, r.price, et.text.toString().toDoubleOrNull() ?: 0.0, r.stock, r.price))
                    Store.save(this, l); toast("Добавлено")
                }.setNegativeButton("Отмена", null).show()
        }
    }

    private fun showList() {
        val l = Store.load(this)
        if (l.isEmpty()) return toast("Список пуст. Откройте товар и нажмите «Следить».")
        val t = l.map { "${it.name}\n${it.price} (мин ${it.min}, цель ${it.target})" + if (it.stock in 0..5) " · осталось ${it.stock}" else "" }
        AlertDialog.Builder(this).setTitle("Отслеживаемые").setItems(t.toTypedArray()) { _, i -> web.loadUrl(l[i].url) }
            .setNeutralButton("Очистить") { _, _ -> Store.save(this, emptyList()) }.setPositiveButton("OK", null).show()
    }

    private fun checkNow() {
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<PriceWorker>().build()); toast("Проверка запущена, придёт уведомление")
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

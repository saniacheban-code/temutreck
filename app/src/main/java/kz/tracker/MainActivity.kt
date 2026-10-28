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
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val scope = MainScope()
    private var job: Job? = null
    private val cats = listOf("Электроника","Одежда","Дом и кухня","Красота","Игрушки","Авто","Спорт","Инструменты","Гаджеты","Для животных")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = LinearLayout(this)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 12f; isAllCaps = false; setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(btn("＋ Следить") { track() })
        bar.addView(btn("Список") { showList() })
        bar.addView(btn("🔥 Остатки") { scanDialog() })
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
    private fun scanDialog() {
        val names = (cats + "Свой запрос…").toTypedArray()
        AlertDialog.Builder(this).setTitle("Где искать остатки за копейки?").setItems(names) { _, i ->
            if (i < cats.size) runScan(cats[i]) else {
                val et = EditText(this)
                AlertDialog.Builder(this).setTitle("Запрос").setView(et)
                    .setPositiveButton("Искать") { _, _ -> if (et.text.isNotBlank()) runScan(et.text.toString().trim()) }.show()
            }
        }.show()
    }

    private fun runScan(q: String) {
        val d = AlertDialog.Builder(this).setTitle("Сканирую «$q»").setMessage("Запускаю…").setCancelable(false)
            .setNegativeButton("Отмена") { _, _ -> job?.cancel() }.show()
        job = scope.launch {
            try {
                val l = Scanner.scan(this@MainActivity, q, 5, 50) { d.setMessage(it) }
                d.dismiss(); showResults(q, l)
            } catch (e: Exception) { d.dismiss(); toast("Скан прерван: ${e.message ?: "отмена"}") }
        }
    }

    private fun showResults(q: String, l: List<Card>) {
        val b = AlertDialog.Builder(this).setTitle("«$q»: найдено ${l.size}")
        if (l.isEmpty()) b.setMessage("Ничего не нашёл. Temu мог не показать остатки в выдаче или изменить вёрстку. Попробуйте другую категорию.")
        else b.setItems(l.map { "${it.title}\n${it.price}" + (if (it.disc > 0) " (−${it.disc}%)" else "") + (if (it.stock >= 0) " · осталось ${it.stock}" else " · почти распродано") }.toTypedArray()) { _, i ->
            val c = l[i]
            AlertDialog.Builder(this).setTitle(c.title).setMessage("Цена ${c.price}")
                .setPositiveButton("Следить") { _, _ ->
                    val s = Store.load(this); s.removeAll { it.url == c.url }
                    s.add(Item(c.url, c.title, c.price, if (c.old > 0) c.old else c.price, c.price, c.stock, c.price)); Store.save(this, s); toast("Добавлено в слежение")
                }.setNegativeButton("Открыть") { _, _ -> web.loadUrl(c.url) }.show()
        }
        b.setNeutralButton("Сканировать ежедневно") { _, _ ->
            getSharedPreferences("t", MODE_PRIVATE).edit().putString("scanq", q).apply()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.REPLACE,
                PeriodicWorkRequestBuilder<ScanWorker>(24, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
            toast("Ежедневный скан «$q» включён")
        }.setPositiveButton("Закрыть", null).show()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

package kz.tracker
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun notify(c: Context, id: Int, title: String, text: String) {
    val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(NotificationChannel("p", "Цены", NotificationManager.IMPORTANCE_HIGH))
    val n = android.app.Notification.Builder(c, "p").setSmallIcon(android.R.drawable.star_on)
        .setContentTitle(title).setContentText(text).setStyle(android.app.Notification.BigTextStyle().bigText(text)).build()
    nm.notify(id, n)
}

class PriceWorker(private val ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val list = Store.load(ctx)
        for ((i, it) in list.withIndex()) {
            val r = withTimeoutOrNull(40_000) { fetch(it.url) } ?: continue
            val np = r.price ?: continue
            val old = it.price
            it.price = np; if (r.stock >= 0) it.stock = r.stock
            if (np < it.min) it.min = np
            val low = it.stock in 0..5
            when {
                it.target > 0 && np <= it.target -> notify(ctx, i, "🎯 Цель достигнута", "${it.name}: $np")
                np < old * 0.95 -> notify(ctx, i, "📉 Цена упала", "${it.name}: $old → $np")
                low && np <= it.first * 0.8 -> notify(ctx, i, "⏳ Мало осталось и дёшево", "${it.name}: ${it.stock} шт., $np")
            }
        }
        Store.save(ctx, list)
        return Result.success()
    }

    private suspend fun fetch(url: String): Extract.R? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val w = WebView(ctx)
            w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        v.evaluateJavascript(Extract.JS) { res -> if (cont.isActive) cont.resume(Extract.parse(res)); v.destroy() }
                    }, 6000)
                }
            }
            cont.invokeOnCancellation { Handler(Looper.getMainLooper()).post { w.destroy() } }
            w.loadUrl(url)
        }
    }
}

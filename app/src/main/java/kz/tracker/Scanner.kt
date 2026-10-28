package kz.tracker
import android.content.Context
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

/** Скрытое окно браузера с вашей сессией Temu: грузит страницу, листает, выполняет JS. */
object Web {
    suspend fun run(ctx: Context, url: String, scrolls: Int, js: String): String? = withContext(Dispatchers.Main) {
        val w = WebView(ctx)
        try {
            w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
            w.layout(0, 0, 1080, 1920)
            suspendCancellableCoroutine<Unit> { c ->
                var done = false
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String) { if (!done) { done = true; if (c.isActive) c.resume(Unit) } }
                }
                w.loadUrl(url)
            }
            delay(5000)
            repeat(scrolls) { w.evaluateJavascript("window.scrollBy(0,1500)", null); delay(2500) }
            suspendCancellableCoroutine<String?> { c -> w.evaluateJavascript(js) { if (c.isActive) c.resume(it) } }
        } finally { w.destroy() }
    }
}

data class Card(val url: String, val title: String, val price: Double, val old: Double, val stock: Int, val flag: Boolean) {
    val disc: Int get() = if (old > price && price > 0) ((1 - price / old) * 100).toInt() else 0
}

object Scanner {
    const val JS = """(function(){
function num(s){var m=String(s).replace(/\s/g,'').match(/(\d+(?:[.,]\d+)?)/);return m?parseFloat(m[1].replace(',','.')):0}
function struck(e,c){while(e&&e!==c.parentElement){if((getComputedStyle(e).textDecorationLine||'').indexOf('line-through')>=0)return true;e=e.parentElement}return false}
var CUR=/[\u0024\u20AC\u20BD\u20B8\u00A3]|тг|руб/i;
var as=document.querySelectorAll('a[href*="-g-"]'),seen={},out=[];
for(var i=0;i<as.length;i++){
 var a=as[i],h=a.href.split('?')[0];if(seen[h])continue;seen[h]=1;
 var c=a;for(var k=0;k<6;k++){var p=c.parentElement;if(!p||p.querySelectorAll('a[href*="-g-"]').length>1)break;c=p}
 var price=0,old=0,best=0,els=c.querySelectorAll('*');
 for(var j=0;j<els.length;j++){var e=els[j];if(e.children.length)continue;var tx=e.textContent.trim();if(tx.length>16||!/\d/.test(tx)||!CUR.test(tx))continue;
  if(struck(e,c)){var o=num(tx);if(o>old)old=o}
  else{var fs=parseFloat(getComputedStyle(e).fontSize);if(fs>best){best=fs;price=num(tx)}}}
 var t=c.innerText||'';
 var m=t.match(/(?:Осталось|осталось|Only)\s*(\d+)/);
 var img=c.querySelector('img');
 var title=a.getAttribute('title')||(img&&img.alt)||t.split('\n')[0];
 out.push({url:h,title:String(title).slice(0,70),price:price,old:old,stock:m?parseInt(m[1]):-1,flag:/почти распродан|almost sold out|last chance/i.test(t)});
}
return JSON.stringify(out);
})()"""

    /** minStock..maxStock — диапазон остатка (maxStock=0 — не фильтровать по остатку); minDisc — минимальная скидка в %. */
    suspend fun scan(ctx: Context, q: String, minStock: Int, maxStock: Int, minDisc: Int, scrolls: Int, st: (String) -> Unit): List<Card> {
        st("Загружаю и листаю выдачу…")
        val raw = withTimeoutOrNull(120_000) { Web.run(ctx, "https://www.temu.com/search_result.html?search_key=" + Uri.encode(q), scrolls, JS) }
            ?: return emptyList()
        val arr = JSONArray(JSONTokener(raw).nextValue() as String)
        val all = List(arr.length()) { i -> arr.getJSONObject(i).let {
            Card(it.getString("url"), it.getString("title"), it.getDouble("price"), it.getDouble("old"), it.getInt("stock"), it.getBoolean("flag")) } }
        val stockMode = maxStock > 0
        val ok = mutableListOf<Card>(); val unk = mutableListOf<Card>()
        for (c in all) {
            if (c.price <= 0) continue
            if (minDisc > 0 && c.disc < minDisc) continue
            if (!stockMode) { ok.add(c); continue }
            if (c.stock in minStock..maxStock) ok.add(c) else if (c.stock < 0) unk.add(c)
        }
        val chk = unk.sortedBy { it.price }.take(15)
        for ((i, c) in chk.withIndex()) {
            st("Проверяю остаток внутри товаров: ${i + 1} из ${chk.size}")
            val r = Extract.parse(withTimeoutOrNull(40_000) { Web.run(ctx, c.url, 0, Extract.JS) })
            if (r != null && r.stock in minStock..maxStock) ok.add(c.copy(stock = r.stock))
        }
        return ok.sortedBy { it.price }
    }
}

class ScanWorker(private val ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val sp = ctx.getSharedPreferences("t", Context.MODE_PRIVATE)
        val q = sp.getString("scanq", null) ?: return Result.success()
        val l = if (sp.getInt("scanm", 0) == 0) Scanner.scan(ctx, q, 1, 10, 0, 8) {} else Scanner.scan(ctx, q, 0, 0, 91, 8) {}
        if (l.isNotEmpty()) notify(ctx, 1000, "🔥 Найдено в «$q»: ${l.size}", "Самый дешёвый: ${l[0].title} — ${l[0].price}")
        return Result.success()
    }
}

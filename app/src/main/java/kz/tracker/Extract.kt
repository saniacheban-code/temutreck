package kz.tracker
import org.json.JSONObject
import org.json.JSONTokener

object Extract {
    const val JS = """(function(){
function num(s){var m=String(s).replace(/\s/g,'').match(/(\d+(?:[.,]\d+)?)/);return m?parseFloat(m[1].replace(',','.')):null}
var p=null;
try{document.querySelectorAll('script[type="application/ld+json"]').forEach(function(s){var j=JSON.parse(s.textContent);var a=Array.isArray(j)?j:[j];a.forEach(function(o){if(o&&o.offers&&!p){var f=Array.isArray(o.offers)?o.offers[0]:o.offers;if(f&&f.price)p=parseFloat(f.price)}})})}catch(e){}
if(!p){var m=document.querySelector('meta[property="product:price:amount"],meta[property="og:price:amount"]');if(m)p=num(m.content)}
if(!p){var best=0,els=document.querySelectorAll('span,div');for(var i=0;i<els.length;i++){var e=els[i];if(e.children.length)continue;var tx=e.textContent.trim();if(tx.length>14||!/\d/.test(tx))continue;if(!/[\u0024\u20AC\u20BD\u20B8\u00A3]|тг|руб/i.test(tx))continue;var r=e.getBoundingClientRect();if(r.top<0||r.top>1000)continue;var fs=parseFloat(getComputedStyle(e).fontSize);if(fs>best){best=fs;p=num(tx)}}}
var st=null;var m2=document.body.innerText.match(/(?:Осталось|осталось|Only)\s*(\d+)/);if(m2)st=parseInt(m2[1]);
return JSON.stringify({price:p,title:document.title,stock:st});
})()"""

    data class R(val price: Double?, val title: String, val stock: Int)
  fun parse(raw: String?): R? {
        return try {
            val s = JSONTokener(raw ?: return null).nextValue() as String
            val o = JSONObject(s)
            R(
                if (o.isNull("price")) null else o.getDouble("price"),
                o.optString("title"),
                if (o.isNull("stock")) -1 else o.getInt("stock")
            )
   } catch (e: Exception) {
        null
    }
}
}

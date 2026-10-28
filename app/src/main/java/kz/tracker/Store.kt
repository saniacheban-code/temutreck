package kz.tracker
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Item(val url: String, var name: String, var price: Double, val first: Double,
                var target: Double, var stock: Int, var min: Double)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("t", Context.MODE_PRIVATE)
    fun load(c: Context): MutableList<Item> {
        val a = JSONArray(sp(c).getString("items", "[]"))
        return MutableList(a.length()) { i -> a.getJSONObject(i).let {
            Item(it.getString("url"), it.getString("name"), it.getDouble("price"), it.getDouble("first"),
                it.getDouble("target"), it.optInt("stock", -1), it.getDouble("min")) } }
    }
    fun save(c: Context, l: List<Item>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("url", it.url).put("name", it.name).put("price", it.price)
            .put("first", it.first).put("target", it.target).put("stock", it.stock).put("min", it.min)) }
        sp(c).edit().putString("items", a.toString()).apply()
    }
}

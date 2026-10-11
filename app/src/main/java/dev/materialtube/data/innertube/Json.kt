package dev.materialtube.data.innertube

import org.json.JSONArray
import org.json.JSONObject

/** Small helpers for walking InnerTube's deeply nested, frequently changing JSON. */
internal fun JSONObject.obj(vararg path: String): JSONObject? {
    var cur: JSONObject = this
    for (key in path) cur = cur.optJSONObject(key) ?: return null
    return cur
}

internal fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) opt(key)?.toString()?.takeIf { it.isNotEmpty() } else null

internal fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * Reads a YouTube text node in any of its shapes: `{simpleText}`, `{runs:[{text}]}`,
 * view-model `{content}` or a plain string.
 */
internal fun text(node: Any?): String? = when (node) {
    null -> null
    is String -> node.takeIf { it.isNotBlank() }
    is JSONObject -> node.str("simpleText")
        ?: node.arr("runs")?.objects()?.joinToString("") { it.optString("text") }?.takeIf { it.isNotBlank() }
        ?: node.str("content")
    else -> null
}

/** Picks the largest thumbnail URL from `{thumbnails:[{url,width}]}` or view-model `{sources:[...]}`. */
internal fun bestThumb(node: JSONObject?): String? {
    if (node == null) return null
    val list = node.arr("thumbnails") ?: node.arr("sources") ?: return null
    val url = list.objects().maxByOrNull { it.optInt("width", 0) }?.str("url") ?: return null
    return if (url.startsWith("//")) "https:$url" else url
}

/** Depth-first visit of every JSONObject in the tree. Return false from [visit] to skip children. */
internal fun walk(node: Any?, visit: (JSONObject) -> Boolean) {
    when (node) {
        is JSONObject -> if (visit(node)) node.keys().forEach { walk(node.opt(it), visit) }
        is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
    }
}

internal fun findFirst(node: Any?, predicate: (JSONObject) -> Boolean): JSONObject? {
    var found: JSONObject? = null
    walk(node) {
        if (found != null) return@walk false
        if (predicate(it)) { found = it; false } else true
    }
    return found
}

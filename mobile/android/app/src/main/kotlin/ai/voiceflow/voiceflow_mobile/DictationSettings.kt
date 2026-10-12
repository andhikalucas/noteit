package ai.voiceflow.voiceflow_mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable settings for the bubble when Flutter is not running. */
object DictationSettings {
    private const val KEY = "dictation_settings_v2"

    fun save(context: Context, values: Map<*, *>) {
        val json = JSONObject()
        json.put("engine", if (values["engine"] == "local") "local" else "gemini")
        json.put("vocabulary", JSONArray(values["vocabulary"] as? List<*> ?: emptyList<Any>()))
        val rules = JSONArray()
        (values["autocorrects"] as? List<*>)?.forEach { value ->
            (value as? Map<*, *>)?.let { rules.put(JSONObject().put("from", it["from"]).put("to", it["to"])) }
        }
        json.put("autocorrects", rules)
        context.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).commit()
    }

    fun load(context: Context): DictationOptions {
        val prefs = context.getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null)
        if (raw == null) {
            // Legacy native vf_overlay "stt_engine" migration; remove once the personal install has saved settings once from the current build.
            val legacy = prefs.getString(OverlayService.KEY_ENGINE, "gemini")
            return DictationOptions(engine = if (legacy == "whisper" || legacy == "local") "local" else "gemini")
        }
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(DictationOptions())
    }

    fun encode(options: DictationOptions): JSONObject = JSONObject().apply {
        put("engine", options.engine)
        put("vocabulary", JSONArray(options.vocabulary))
        put("autocorrects", JSONArray().apply {
            options.autocorrects.forEach { put(JSONObject().put("from", it.from).put("to", it.to)) }
        })
    }

    fun decode(json: JSONObject): DictationOptions {
        val vocabulary = json.optJSONArray("vocabulary") ?: JSONArray()
        val corrections = json.optJSONArray("autocorrects") ?: JSONArray()
        return DictationOptions(
            engine = if (json.optString("engine") == "local") "local" else "gemini",
            vocabulary = (0 until vocabulary.length()).map { vocabulary.getString(it) },
            autocorrects = (0 until corrections.length()).map {
                corrections.getJSONObject(it).let { r -> AutocorrectRule(r.getString("from"), r.getString("to")) }
            },
        )
    }
}

package info.nightscout.androidaps.interfaces

import androidx.collection.ArrayMap
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.utils.JsonHelper
import org.json.JSONException
import org.json.JSONObject
import java.util.*
import javax.inject.Inject

class WordReplacementsStore(val injector: HasAndroidInjector, val data: JSONObject) {
    @Inject lateinit var aapsLogger: AAPSLogger

    init {
        injector.androidInjector().inject(this)
    }

    private val cachedObjects = ArrayMap<String, String>()

    private fun getReplacements(): JSONObject? {
        try {
            if (data.has("replacements")) return data.getJSONObject("replacements")
        } catch (e: JSONException) {
            aapsLogger.error("Unhandled exception", e)
        }
        return null
    }

    fun getWordReplacement(word: String): String? {
        var replacement: String? = null
        getReplacements()?.let { replacementlist ->
            if (replacementlist.has(word)) {
                replacement = cachedObjects[word]
            }
        }
        return replacement
    }
}

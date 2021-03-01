package info.nightscout.androidaps.plugins.general.voiceAssistant.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceAssistantPlugin
import info.nightscout.androidaps.utils.DateUtil
import java.util.*
import javax.inject.Inject

class VoiceResultActivity: NoSplashAppCompatActivity() {

    @Inject lateinit var voiceAssistantPlugin: VoiceAssistantPlugin

    val confirmCarbResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        result: ActivityResult -> if (result.resultCode == Activity.RESULT_OK) { result.data?.let { voiceAssistantPlugin.processCarbs(it) } } }

    @Synchronized
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    fun confirmCarbs(message: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, message)
        confirmCarbResult.launch(intent)
        //startActivityForResult(intent, 2000)
    }

    /*
    override fun onActivityResult(requestCode: Int, resultCode: Int,
                                  data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == 2000) voiceAssistantPlugin.processCarbs(data)
            }
    }

     */
}
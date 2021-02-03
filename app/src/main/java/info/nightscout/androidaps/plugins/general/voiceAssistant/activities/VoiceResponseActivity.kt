package info.nightscout.androidaps.plugins.general.voiceAssistant.activities

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

import android.content.Intent
import android.os.Bundle
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import javax.inject.Inject

class VoiceResponseActivity(_message: String) : NoSplashAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger

    private val message: String = _message

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent().also { intent ->
            intent.setAction("info.nightscout.androidaps.USER_FEEDBACK")
            intent.putExtra("message", message)
            sendBroadcast(intent)
        }
        aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_messagetouser), intent.getStringExtra("message)")))
    }
}


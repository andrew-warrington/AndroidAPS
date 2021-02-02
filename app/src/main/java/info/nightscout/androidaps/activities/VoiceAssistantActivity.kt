package info.nightscout.androidaps.activities

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import info.nightscout.androidaps.R
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceAssistantPlugin
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import javax.inject.Inject
import javax.inject.Singleton

class VoiceAssistantActivity : NoSplashAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var voiceAssistantPlugin: VoiceAssistantPlugin
    @Inject lateinit var activePlugin: ActivePluginProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.dataString == "androidaps://voiceassistant") {
            aapsLogger.debug(LTag.VOICECOMMAND, "Google assistant command received")
            voiceAssistantPlugin.processVoiceCommand(intent)
        }
    }

    fun messageToUser(intent: Intent) {

        if (intent != null) {
            this.sendBroadcast(intent)
            aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_messagetouser), intent.getStringExtra("message)")))
        }
    }
}

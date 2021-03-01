package info.nightscout.androidaps.plugins.general.voiceAssistant.activities

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin

import android.os.Bundle
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceAssistantPlugin
import javax.inject.Inject

class VoiceAssistantActivity : NoSplashAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var voiceAssistantPlugin: VoiceAssistantPlugin
    @Inject lateinit var activePlugin: ActivePluginProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Voice assistant command received")
            voiceAssistantPlugin.processCommand(intent)
        }
        finish()
    }
}


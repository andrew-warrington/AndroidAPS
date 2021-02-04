package info.nightscout.androidaps.plugins.general.voiceAssistant.activities

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

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
            voiceAssistantPlugin.processVoiceCommand(intent)
        }
        finish()
    }
}


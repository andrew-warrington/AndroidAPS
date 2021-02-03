package info.nightscout.androidaps.plugins.general.voiceAssistant.activities

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

import android.content.Intent
import android.os.Bundle
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceAssistantPlugin
import javax.inject.Inject

class VoiceResponseActivity : NoSplashAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activePlugin: ActivePluginProvider

    fun messageToUser(intent: Intent) {

        if (intent != null) {
            this.applicationContext.sendBroadcast(intent)
            aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_messagetouser), intent.getStringExtra("message)")))
        }
    }
}


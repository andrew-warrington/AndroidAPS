package info.nightscout.androidaps.activities

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

//TODO ensure when two or more phones with AAPS can hear the command, only the correct one processes the request
//TODO load preferences to ensure Google assistant can be used
//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml

import android.content.Intent
import android.os.Bundle
import dagger.Provides
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceAssistantPlugin
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceResponseActivity
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.sharedPreferences.SP
import javax.inject.Inject


class VoiceAssistantActivity : NoSplashAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var voiceAssistantPlugin: VoiceAssistantPlugin
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var sp: SP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aapsLogger.debug(LTag.VOICECOMMAND, "Google assistant command received")
        voiceAssistantPlugin.processVoiceCommand(intent)
    }
}

package info.nightscout.androidaps.plugins.general.voiceAssistant

import android.content.Context
import android.content.Intent
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.MyPreferenceFragment
import info.nightscout.androidaps.activities.NoSplashAppCompatActivity
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import javax.inject.Inject

class VoiceResponseActivity : NoSplashAppCompatActivity() {

    @Inject lateinit var aapsLogger: AAPSLogger
    private var myIntent: Intent? = null

   fun messageToUser(message: String) {

        //external voice assistant must implement a receiver to speak these messages back to the user.
        //this is possible via Tasker on Android, for example.

        myIntent = Intent().also {
            it.setAction("info.nightscout.androidaps.CONFIRM_RESULT")
            it.putExtra("message", message)
            sendBroadcast(it)
        }
        aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_messagetouser), message))
    }
}
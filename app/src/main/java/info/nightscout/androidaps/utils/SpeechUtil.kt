package info.nightscout.androidaps.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceAssistantPlugin
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

//imported and refactored from java to Kotlin from XDrip+ Master branch code on Feb 18, 2021. https://github.com/jamorham/xDrip-plus/

@Singleton
class SpeechUtil @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val context: Context,
) {

    lateinit var tts: TextToSpeech

    @JvmOverloads fun say(text: String, delay: Long = 0, retry: Int = 0): Int {
        var result = 0
        Thread(Runnable {
            try {
                initialize()
                try {
                    Thread.sleep(delay)
                } catch (ee: InterruptedException) {
                    //
                }
                if (isOngoingCall) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Cannot speak due to ongoing call: $text")
                    return@Runnable
                }

                // if sound is playing, wait up to 40 seconds to deliver the speech
                val max_wait: Long = System.currentTimeMillis() + 1000 * 40
                while (isMusicPlaying && System.currentTimeMillis() < max_wait) {
                    try {
                        Thread.sleep(500)
                    } catch (ee: InterruptedException) {
                        //
                    }
                }
                try {
                    result = tts.speak(text, TextToSpeech.QUEUE_ADD, null)
                } catch (e: NullPointerException) {
                    result = TextToSpeech.ERROR
                    aapsLogger.debug(LTag.VOICECOMMAND, "Got null pointer trying to speak! concurrency issue")
                }
                aapsLogger.debug(LTag.VOICECOMMAND, "Speak result: $result")

                // speech randomly fails, usually due to the service not being bound so quick after being initialized, so we wait and retry recursively
                if (result != TextToSpeech.SUCCESS && retry < 5) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Failed to speak: retrying in 1s: $retry")
                    say(text, delay + 1000, retry + 1)
                    return@Runnable
                }
                // only get here if retries exceeded
                if (result != TextToSpeech.SUCCESS) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Failed to speak after: $retry retries.")
                } else {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Successfully spoke: $text")
                }
            } finally {
                // nada
            }
        }).start()
        aapsLogger.debug(LTag.VOICECOMMAND, result.toString())
        return result
    }// always try to return something// if we don't have an instance return what we would like to be using// if we have an instance return what we are actually using

    // get the locale that text to speech should be using
    val locale: Locale
        get() = try {
            // if we have an instance return what we are actually using
            tts.getVoice().getLocale()
        } catch (e: Exception) {
            // always try to return something
            chosenLocale()
        }

    // evaluate locale from system and user settings
    private fun chosenLocale(): Locale {
        // first get the default language
        var speech_locale = Locale.getDefault()

        /*
        Code below to be implemented later.

        try {
            val tts_language: String = Pref.getStringDefaultBlank("speak_readings_custom_language").trim()
            // did the user specify another language for speech?
            if (tts_language.length > 1) {
                val lang_components = tts_language.split("_").toTypedArray()
                val country = if (lang_components.size > 1) lang_components[1] else ""
                speech_locale = Locale(lang_components[0], country, "")
            }
        } catch (e: Exception) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Exception trying to use custom language: $e")
        }

        */

        return speech_locale
    }

    // set up an instance to Android TTS with our desired language and settings
    @Synchronized private fun initialize() {
        tts = TextToSpeech(context) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                //aapsLogger.debug(LTag.VOICECOMMAND, "Initializing, successful result code: $status")
                val speech_locale = chosenLocale()
                //aapsLogger.debug(LTag.VOICECOMMAND, "Chosen locale: $speech_locale")
                var set_language_result: Int
                // try setting the language we want
                set_language_result = try {
                    tts.setLanguage(speech_locale)
                } catch (e: IllegalArgumentException) {
                    // can end up here with Locales like "OS"
                    //aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set language error: $e")
                    TextToSpeech.LANG_MISSING_DATA
                } catch (e: Exception) {
                    // can end up here with deep errors from tts system
                    //aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set language deep error: $e")
                    TextToSpeech.LANG_MISSING_DATA
                }

                // try various fallbacks
                if (set_language_result == TextToSpeech.LANG_MISSING_DATA || set_language_result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    //aapsLogger.debug(LTag.VOICECOMMAND, "Default system language is not supported")
                    set_language_result = try {
                        tts.setLanguage(Locale.ENGLISH)
                    } catch (e: IllegalArgumentException) {
                        // can end up here with parcel Locales like "OS"
                        //aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set default language error: $e")
                        TextToSpeech.LANG_MISSING_DATA
                    } catch (e: Exception) {
                        // can end up here with deep errors from tts system
                        //aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set default language deep error: $e")
                        TextToSpeech.LANG_MISSING_DATA
                    }
                }
                //try any english as last resort
                //if (set_language_result == TextToSpeech.LANG_MISSING_DATA
                //    || set_language_result == TextToSpeech.LANG_NOT_SUPPORTED) {
                //    aapsLogger.debug(LTag.VOICECOMMAND, "English is not supported! total failure")
                //}
            } else {
                aapsLogger.debug(LTag.VOICECOMMAND, "Initialize status code indicates failure, code: $status")
            }
        }
    }

    // shutdown existing instance - most useful when changing language or parameters
    @Synchronized fun shutdown() {
            try {
                tts.shutdown()
            } catch (e: IllegalArgumentException) {
                aapsLogger.debug(LTag.VOICECOMMAND, "Got exception shutting down service: $e")
            }
    }

    // this is a duplicate of similar code in JoH utility class but kept here as these methods will be specific for the speech util
    private val isOngoingCall: Boolean
        get() {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                manager.mode == AudioManager.MODE_IN_CALL
            } catch (e: NullPointerException) {
                false
            }
        }

    // this isn't as complete as I would like - does the framework expose anything more generic we can use to detect any sound playing?
    private val isMusicPlaying: Boolean
        get() {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return try {
                manager.isMusicActive
            } catch (e: NullPointerException) {
                false
            }
        }

    // redirect user to android tts data file installation activity
    fun installTTSData(context: Context) {
        try {
            val intent = Intent()
            intent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Could not install TTS data: $e")
        }
    }

}

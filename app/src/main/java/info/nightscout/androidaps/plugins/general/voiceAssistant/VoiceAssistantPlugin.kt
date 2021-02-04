package info.nightscout.androidaps.plugins.general.voiceAssistant

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

//TODO ensure when two or more phones with AAPS can hear the command, only the correct one processes the request (patient identifier)
//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml
//TODO logic to enable certain features based on config (pump control, nsclient, APS)
//TODO write code for setting time for carbs
//TODO write code for meal bolus
//TODO fix icon
//TODO load preferences at start
//TODO Additional commands
//TODO "slowly up" or similar for glucose answer
//TODO rename "status"

import android.content.Intent
import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.Config
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.general.smsCommunicator.Sms
import info.nightscout.androidaps.plugins.general.smsCommunicator.otp.OneTimePassword
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.services.Intents
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.SafeParse
import info.nightscout.androidaps.utils.XdripCalibrations
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import info.nightscout.androidaps.utils.textValidator.ValidatingEditTextPreference
import java.util.ArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceAssistantPlugin @Inject constructor(
        injector: HasAndroidInjector,
        aapsLogger: AAPSLogger,
        resourceHelper: ResourceHelper,
        private val context: Context,
        private val sp: SP,
        private val constraintChecker: ConstraintChecker,
//        private val rxBus: RxBusWrapper,
        private val profileFunction: ProfileFunction,
//        private val fabricPrivacy: FabricPrivacy,
        private val activePlugin: ActivePluginProvider,
        private val commandQueue: CommandQueueProvider,
//        private val loopPlugin: LoopPlugin,
        private val iobCobCalculatorPlugin: IobCobCalculatorPlugin,
//        private val xdripCalibrations: XdripCalibrations,
//        private var otp: OneTimePassword,
//        private val config: Config,
        private val dateUtil: DateUtil,
    ) : PluginBase(PluginDescription()
        .mainType(PluginType.GENERAL)
        .fragmentClass(VoiceAssistantFragment::class.java.name)
        .pluginIcon(R.drawable.ic_voiceassistant)
        .pluginName(R.string.voiceassistant)
        .shortName(R.string.voiceassistant_shortname)
        .preferencesId(R.xml.pref_voiceassistant)
        .description(R.string.description_voiceassistant),
        aapsLogger, resourceHelper, injector
    ) {

    var lastRemoteBolusTime: Long = 0
    var messages = ArrayList<String>()

    fun processVoiceCommand(intent: Intent) {

        val assistantCommandsAllowed = sp.getBoolean(R.string.key_voiceassistant_commandsallowed, false)
        val identifierPinRequired = sp.getBoolean(R.string.key_voiceassistant_requireidentifier, true)

        if (!isEnabled(PluginType.GENERAL)) {
            userFeedback("The voice assistant plugin is disabled. Please enable in the Config Builder.")
            return
        }
        if (!assistantCommandsAllowed) {
            userFeedback("Voice commands are not allowed. Please enable them in Preferences.")
            return
        }

        val spokenWordArray: Array<String> = intent.getStringArrayExtra("wordarray")

        if (identifierPinRequired) {
            if (!identifierMatch(spokenWordArray)) {
                userFeedback("I could not match your identifier pin. Try again?")
                return
            }
        }

        val requestType: String? = intent.getStringExtra("requesttype")
        if (requestType == null) {
            userFeedback("Request type not received. Aborting")
            return
        } else {
            when (requestType) {
                "carb"         ->
                    processCarbs(intent)
                "bolus"        ->
                    processBolus(intent)
                "status"      ->
                    processStatus()
                "glucose"      ->
                    processGlucose()
                "calculate"    ->
                    calculateBolus(intent)
            }
        }
    }

    private fun processCarbs(intent: Intent) {

        if (intent.getStringExtra("amount") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing carb request")
        } else {
            userFeedback("Carb amount not received. Aborting.")
            return
        }
        val splitted = intent.getStringExtra("amount").split(Regex("\\s+")).toTypedArray()
        val gramsRequest = SafeParse.stringToInt(splitted[0])
        val grams = constraintChecker.applyCarbsConstraints(Constraint(gramsRequest)).value()
        if (gramsRequest != grams) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "carb", gramsRequest.toString(), grams.toString()))
            return
        }
        if (grams == 0) {
            userFeedback("Zero grams requested. Aborting.")
            return
        } else {
            val carbslog = String.format(resourceHelper.gs(R.string.voiceassistant_carbslog), grams, DateUtil.now())
            aapsLogger.debug(LTag.VOICECOMMAND, carbslog)
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.carbs = grams.toDouble()
            detailedBolusInfo.source = Source.USER
            detailedBolusInfo.date = DateUtil.now()
            if (activePlugin.activePump.pumpDescription.storesCarbInfo) {
                commandQueue.bolus(detailedBolusInfo, object : Callback() {
                    override fun run() {
                        val replyText: String
                        if (result.success) {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                        } else {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsfailed), grams)
                        }
                        userFeedback(replyText)
                    }
                })
            } else {
                activePlugin.activeTreatments.addToHistoryTreatment(detailedBolusInfo, true)
                userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams))
            }
        }
    }

    private fun processStatus() {
        val actualBG = iobCobCalculatorPlugin.actualBg()
        val lastBG = iobCobCalculatorPlugin.lastBg()
        var reply = ""
        val units = profileFunction.getUnits()
        if (actualBG != null) {
            reply = resourceHelper.gs(R.string.sms_actualbg) + " " + actualBG.valueToUnitsToString(units) + ", "
        } else if (lastBG != null) {
            val agoMsec = System.currentTimeMillis() - lastBG.date
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            reply = resourceHelper.gs(R.string.sms_lastbg) + " " + lastBG.valueToUnitsToString(units) + " " + String.format(resourceHelper.gs(R.string.sms_minago), agoMin) + ", "
        }
        val glucoseStatus = GlucoseStatus(injector).glucoseStatusData
        if (glucoseStatus != null) reply += resourceHelper.gs(R.string.sms_delta) + " " + Profile.toUnitsString(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, units) + " " + units + ", "
        activePlugin.activeTreatments.updateTotalIOBTreatments()
        val bolusIob = activePlugin.activeTreatments.lastCalculationTreatments.round()
        activePlugin.activeTreatments.updateTotalIOBTempBasals()
        val basalIob = activePlugin.activeTreatments.lastCalculationTempBasals.round()
        val cobInfo = iobCobCalculatorPlugin.getCobInfo(false, "SMS COB")
        reply += (resourceHelper.gs(R.string.sms_iob) + " " + DecimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + "U ("
            + resourceHelper.gs(R.string.sms_bolus) + " " + DecimalFormatter.to2Decimal(bolusIob.iob) + "U "
            + resourceHelper.gs(R.string.sms_basal) + " " + DecimalFormatter.to2Decimal(basalIob.basaliob) + "U), "
            + resourceHelper.gs(R.string.cob) + ": " + cobInfo.generateCOBString())
        userFeedback(reply)
    }

    private fun processGlucose() {
        val actualBG = iobCobCalculatorPlugin.actualBg()
        val lastBG = iobCobCalculatorPlugin.lastBg()
        var reply = ""
        val units = profileFunction.getUnits()
        if (actualBG != null) {
            reply = "Your current sensor glucose reading is " + actualBG.valueToUnitsToString(units) + " " + units
        } else if (lastBG != null) {
            val agoMsec = System.currentTimeMillis() - lastBG.date
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            reply = "Your last sensor glucose reading was " + " " + lastBG.valueToUnitsToString(units) + " " + units + ", " + String.format(resourceHelper.gs(R.string.sms_minago), agoMin) + " minutes ago."
        } else {
            reply = "Could not get your most recent glucose reading."
        }
        userFeedback(reply)
    }

    private fun calculateBolus(intent: Intent) {
        return
    }

    private fun processBolus(intent: Intent) {

        //TODO security check

        if (intent.getStringExtra("units") != null && intent.getStringExtra("meal") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing bolus request")
        } else {
            userFeedback("Bolus request received was not complete. Aborting")
            return
        }

        var splitted = intent.getStringExtra("units").split(Regex("\\s+")).toTypedArray()
        val bolusRequest = SafeParse.stringToDouble(splitted[0])
        val bolus = constraintChecker.applyBolusConstraints(Constraint(bolusRequest)).value()
        if (bolusRequest != bolus) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "bolus", bolusRequest.toString(), bolus.toString()))
            return
        }
        splitted = intent.getStringExtra("meal").split(Regex("\\s+")).toTypedArray()
        val meal = SafeParse.stringToInt(splitted[0])
        if (bolus > 0.0) {
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.insulin = bolus
            detailedBolusInfo.source = Source.USER
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    val resultSuccess = result.success
                    val resultBolusDelivered = result.bolusDelivered
                    commandQueue.readStatus("VOICECOMMAND", object : Callback() {
                        override fun run() {
                            if (resultSuccess) {
                                var replyText = if (meal == 1) String.format(resourceHelper.gs(R.string.voiceassistant_mealbolusdelivered), resultBolusDelivered)
                                else String.format(resourceHelper.gs(R.string.voiceassistant_bolusdelivered), resultBolusDelivered)
 //                             replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                lastRemoteBolusTime = DateUtil.now()
                                if (meal == 1) {
                                    profileFunction.getProfile()?.let { currentProfile ->
                                        var eatingSoonTTDuration = sp.getInt(R.string.key_eatingsoon_duration, Constants.defaultEatingSoonTTDuration)
                                        eatingSoonTTDuration =
                                            if (eatingSoonTTDuration > 0) eatingSoonTTDuration
                                            else Constants.defaultEatingSoonTTDuration
                                        var eatingSoonTT = sp.getDouble(R.string.key_eatingsoon_target, if (currentProfile.units == Constants.MMOL) Constants.defaultEatingSoonTTmmol else Constants.defaultEatingSoonTTmgdl)
                                        eatingSoonTT =
                                            if (eatingSoonTT > 0) eatingSoonTT
                                            else if (currentProfile.units == Constants.MMOL) Constants.defaultEatingSoonTTmmol
                                            else Constants.defaultEatingSoonTTmgdl
                                        val tempTarget = TempTarget()
                                            .date(System.currentTimeMillis())
                                            .duration(eatingSoonTTDuration)
                                            .reason(resourceHelper.gs(R.string.eatingsoon))
                                            .source(Source.USER)
                                            .low(Profile.toMgdl(eatingSoonTT, currentProfile.units))
                                            .high(Profile.toMgdl(eatingSoonTT, currentProfile.units))
                                        activePlugin.activeTreatments.addToHistoryTempTarget(tempTarget)
                                        val tt = if (currentProfile.units == Constants.MMOL)
                                            DecimalFormatter.to1Decimal(eatingSoonTT)
                                        else DecimalFormatter.to0Decimal(eatingSoonTT)
                                        replyText += "\n" + String.format(resourceHelper.gs(R.string.voiceassistant_mealbolusdelivered_tt), tt, eatingSoonTTDuration)
                                    }
                                }
                                userFeedback(replyText)
                            }
                            else {
                                userFeedback(resourceHelper.gs(R.string.voiceassistant_bolusfailed))
                            }
                        }
                    })
                }
            })
        } else {
            userFeedback("Zero units requested. Aborting.")
        }
    }

    private fun userFeedback(message: String) {

        messages.add(dateUtil.timeString(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + message + "</b><br>")
        aapsLogger.debug(LTag.VOICECOMMAND, message)

        //requires a 3rd party software such as Tasker to receive the intent with action "info.nightscout.androidaps.USER_FEEDBACK"
        //and speak the message contained in the extras.
        context.sendBroadcast(
            Intent(Intents.USER_FEEDBACK) // "info.nightscout.androidaps.USER_FEEDBACK"
                .putExtra("message", message)
        )
    }

    private fun identifierMatch(wordArray: Array<String>): Boolean {

        var returnCode = false
        val patientName = sp.getString(R.string.key_patient_name, "")
        for (x in 0 until wordArray.size) {
            if (wordArray[x] == patientName) returnCode = true
        }
        return returnCode
    }
}

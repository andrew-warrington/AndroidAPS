package info.nightscout.androidaps.plugins.general.voiceAssistant

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml
//TODO logic to enable certain features based on config (pump control, nsclient, APS)
//TODO write code for setting time for carbs
//TODO fix icon
//TODO Additional commands
//TODO onPreferenceChange listener

import android.content.Intent
import android.content.Context
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
import java.util.*
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
    var fullCommandReceived = false
    var detailedStatus = false
    lateinit var spokenCommandArray: Array<String>

    fun processVoiceCommand(intent: Intent) {

        if (!isEnabled(PluginType.GENERAL)) {
            userFeedback("The voice assistant plugin is disabled. Please enable in the Config Builder.")
            return
        }
        if (!sp.getBoolean(R.string.key_voiceassistant_commandsallowed, false)) {
            userFeedback("Voice commands are not allowed. Please enable them in Preferences.")
            return
        }

        if (intent.getStringExtra("command") != null) {
            spokenCommandArray = intent.getStringExtra("command").split(Regex("\\s+")).toTypedArray()
            fullCommandReceived = true
        }

        if (sp.getBoolean(R.string.key_voiceassistant_requireidentifier, true)) {
            if (fullCommandReceived) {
                if (!patientMatch(spokenCommandArray)) {
                    userFeedback("I could not understand the person's name. Try again?")
                    return
                }
            } else {
                userFeedback("I did not receive the patient name. Try again?")
                return
            }
        }

        val requestType: String? = intent.getStringExtra("requesttype")
        if (requestType == null) {
            userFeedback("I did not receive the request type. Try again?")
            return
        }
        when (requestType) {
            "carb"         ->
                processCarbs(intent)
            "bolus"        ->
                processBolus(intent)
            "inforequest"      ->
                processInfoRequest()
            "quiet"     ->
                userFeedback("Ok.") //this will hopefully stop Google from eating up time by saying "command sent" and repeating the command.
            "calculate"   ->
                calculateBolus(intent)
            }
    }

    private fun processCarbs(intent: Intent) {

        if (intent.getStringExtra("amount") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing carb request")
        } else {
            userFeedback("I did not receive the carb amount. Try again?")
            return
        }
        val splitted = intent.getStringExtra("amount").split(Regex("\\s+")).toTypedArray()
        val gramsRequest = SafeParse.stringToInt(convertToDigit(splitted[0]))
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

    private fun processInfoRequest() {

        var glucoseRequest = false; var IOBRequest = false; var COBRequest = false; var deltaRequest = false;
        var profileRequest = false; var basalRateRequest = false; var lastBolusRequest = false; var trendRequest = false;

        if (fullCommandReceived) {

            for (x in 0 until spokenCommandArray.size) {

                aapsLogger.debug(LTag.VOICECOMMAND, "Command word " + x + " is " + spokenCommandArray[x])
                when (spokenCommandArray[x].toUpperCase(Locale.ROOT)) {
                    "DETAIL"         ->             //need a regex here
                        detailedStatus = true
                    "DETAILED"         ->
                        detailedStatus = true
                    "GLUCOSE"         -> {
                        glucoseRequest = true
                        }
                    "SUGAR"         ->
                        glucoseRequest = true
                    "BG"         ->
                        glucoseRequest = true
                    "IOB"         ->
                        IOBRequest = true
                    "INSULIN"         ->
                        IOBRequest = true
                    "COB"         ->
                        COBRequest = true
                    "CARB"         ->               //need a regex here
                        COBRequest = true
                    "CARBS"         ->
                        COBRequest = true
                    "CARBOHYDRATE"         ->
                        COBRequest = true
                    "CARBOHYDRATES"         ->
                        COBRequest = true
                    "TREND"        ->
                        trendRequest = true
                    "BASAL"        ->
                        basalRateRequest = true
                    "DELTA"        ->
                        deltaRequest = true
                    "STATUS"       -> {
                        glucoseRequest = true
                        deltaRequest = true
                        IOBRequest = true
                        COBRequest = true
                    }
                }
            }
        } else {
            userFeedback("I did not get your full request. Try again?")
            return
        }

        if (!(glucoseRequest || IOBRequest || COBRequest || profileRequest || basalRateRequest || lastBolusRequest || trendRequest || deltaRequest)) {
            userFeedback("I could not understand what you were asking for. Try again?")
            return
        }

        var reply = ""
        if (glucoseRequest) reply = returnGlucose()
        if (deltaRequest) reply += returnDelta()
        if (trendRequest) reply += returnTrend()
        if (IOBRequest) reply += returnIOB()
        if (lastBolusRequest) reply += returnLastBolus()
        if (COBRequest) reply += returnCOB()
        if (basalRateRequest) reply += returnBasalRate()

        userFeedback(reply)
    }

    private fun returnGlucose(): String {
        val actualBG = iobCobCalculatorPlugin.actualBg()
        val lastBG = iobCobCalculatorPlugin.lastBg()
        var reply = ""
        val units = profileFunction.getUnits()
        if (actualBG != null) {
            reply = "Your current sensor glucose reading is " + actualBG.valueToUnitsToString(units) + " " + units + "."
        } else if (lastBG != null) {
            val agoMsec = System.currentTimeMillis() - lastBG.date
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            reply = "Your last sensor glucose reading was " + " " + lastBG.valueToUnitsToString(units) + " " + units + ", " + String.format(resourceHelper.gs(R.string.sms_minago), agoMin) + " minutes ago."
        } else {
            reply = "I could not get your most recent glucose reading."
        }
        return reply
    }

    private fun returnDelta(): String {
        var output = ""
        val units = profileFunction.getUnits()
        val glucoseStatus = GlucoseStatus(injector).glucoseStatusData
        if (glucoseStatus != null) {
            output = "The delta is " + Profile.toUnitsString(glucoseStatus.delta, glucoseStatus.delta * Constants.MGDL_TO_MMOLL, units) + " " + units + "."
        } else {
            output = "Delta could not be read."
        }
        return output
    }

    private fun returnTrend(): String {
        return "I don't do trend requests yet."
    }

    private fun returnIOB(): String {
        var output = ""
        activePlugin.activeTreatments.updateTotalIOBTreatments()
        val bolusIob = activePlugin.activeTreatments.lastCalculationTreatments.round()
        activePlugin.activeTreatments.updateTotalIOBTempBasals()
        val basalIob = activePlugin.activeTreatments.lastCalculationTempBasals.round()
        output = "The insulin on board is " + DecimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + " units."
        if (detailedStatus) {
            output += "The bolus IOB is " + DecimalFormatter.to2Decimal(bolusIob.iob) + " units, and the basal IOB is " + " " + DecimalFormatter.to2Decimal(basalIob.basaliob) + "units."
        }
        return output
    }

    private fun returnLastBolus(): String {
        return "I don't do last bolus requests yet."
    }

    private fun returnCOB(): String {
        val cobInfo = iobCobCalculatorPlugin.getCobInfo(false, "Voice COB")
        return "The carb on board is " + cobInfo.generateCOBString() +"."
    }

    private fun returnBasalRate(): String {
        return "I don't do basal rate requests yet."
    }

    private fun calculateBolus(intent: Intent) {
        userFeedback("I don't do bolus calculations yet.")
        return
    }

    private fun processBolus(intent: Intent) {

        //TODO security check?

        if (intent.getStringExtra("units") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing bolus request")
        } else {
            userFeedback("I did not receive the amount. Try again?")
            return
        }
        val splitted = intent.getStringExtra("units").split(Regex("\\s+")).toTypedArray()
        val bolusRequest = SafeParse.stringToDouble(convertToDigit(splitted[0]))
        val bolus = constraintChecker.applyBolusConstraints(Constraint(bolusRequest)).value()
        if (bolusRequest != bolus) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "bolus", bolusRequest.toString(), bolus.toString()))
            return
        }
        var word = ""
        var meal = false
        for (x in 0 until spokenCommandArray.size) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Command word " + x + " is " + spokenCommandArray[x])
            word = spokenCommandArray[x].toUpperCase(Locale.ROOT)
            if (word == "MEAL" || word == "MEALS") meal = true
        }
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
                                var replyText = if (meal) String.format(resourceHelper.gs(R.string.voiceassistant_mealbolusdelivered), resultBolusDelivered)
                                else String.format(resourceHelper.gs(R.string.voiceassistant_bolusdelivered), resultBolusDelivered)
 //                             replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                lastRemoteBolusTime = DateUtil.now()
                                if (meal) {
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

        //requires a 3rd party software such as Tasker to receive the intent with action "info.nightscout.androidaps.USER_FEEDBACK"
        //and speak the "message" contained in the extras.
        //messages also appear on the "VOICE" fragment in AndroidAPS.

        messages.add(dateUtil.timeString(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + message + "</b><br>")
        aapsLogger.debug(LTag.VOICECOMMAND, message)

        context.sendBroadcast(
            Intent(Intents.USER_FEEDBACK) // "info.nightscout.androidaps.USER_FEEDBACK"
                .putExtra("message", message)
        )
    }

    private fun patientMatch(wordArray: Array<String>): Boolean {

        var returnCode = false
        val patientName = sp.getString(R.string.key_patient_name, "").toUpperCase(Locale.ROOT)
        var word: String
        aapsLogger.debug(LTag.VOICECOMMAND, patientName)
        for (x in 0 until wordArray.size) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Command word " + x + " is " + wordArray[x])
            word = wordArray[x].toUpperCase(Locale.ROOT)
            if (word == patientName || word == patientName + "S" || word == patientName + "'S") returnCode = true
            // above ^^ accept spoken name's possessive form, including in case of poor grammar e.g. "glucose for John" or "John's glucose" or "Johns glucose"
        }
        return returnCode
    }

    private fun convertToDigit(string: String): String {
        var output = string
        when (output.toUpperCase(Locale.ROOT)) {
            "ZERO" -> output = "0"
            "ONE" -> output = "1"
            "TWO" -> output = "2"
            "THREE" -> output = "3"
            "FOUR" -> output = "4"
            "FIVE" -> output = "5"
            "SIX" -> output = "6"
            "SEVEN" -> output = "7"
            "EIGHT" -> output = "8"
            "NINE" -> output = "9"
        }
        return output
    }
}

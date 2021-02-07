package info.nightscout.androidaps.plugins.general.voiceAssistant

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml
//TODO logic to enable certain features based on config (pump control, nsclient, APS)
//TODO write code for setting time for carbs
//TODO fix icon
//TODO Additional commands
//TODO onPreferenceChange listener?

import android.content.Context
import android.content.Intent
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.db.Treatment
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.services.Intents
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.SafeParse
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
    private val profileFunction: ProfileFunction,
    private val activePlugin: ActivePluginProvider,
    private val commandQueue: CommandQueueProvider,
    private val iobCobCalculatorPlugin: IobCobCalculatorPlugin,
    private val treatmentsPlugin: TreatmentsPlugin,
    private val dateUtil: DateUtil,
//     private val rxBus: RxBusWrapper,
//     private val fabricPrivacy: FabricPrivacy,
//     private val loopPlugin: LoopPlugin,
//     private val xdripCalibrations: XdripCalibrations,
//     private var otp: OneTimePassword,
//     private val config: Config,
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

    var messages = ArrayList<String>()
    var fullCommandReceived = false
    var detailedStatus = false
    var requireIdentifier = true
    val patientName = sp.getString(R.string.key_patient_name, "")
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

        val receivedCommand = intent.getStringExtra("command")
        if (receivedCommand != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Command is " + receivedCommand)
            spokenCommandArray = intent.getStringExtra("command").split(Regex("\\s+")).toTypedArray()
            fullCommandReceived = true
        }

        if (requireIdentifier) {
            if (fullCommandReceived) {
                if (!patientMatch(spokenCommandArray)) {
                    userFeedback("I could not understand the person's name. Try again?", false)
                    return
                }
            } else {
                userFeedback("I did not receive the person's name. Try again?", false)
                return
            }
        }

        val requestType: String? = intent.getStringExtra("requesttype")
        if (requestType == null) {
            userFeedback("I did not receive the request type. Try again?")
            return
        }
        aapsLogger.debug(LTag.VOICECOMMAND, "Received request type: " + requestType + ".")

        when (requestType) {
            "carbrequest" ->
                requestCarbs(intent)
            "carbconfirm" ->
                processCarbs(intent)
            "bolusrequest" ->
                requestBolus(intent)
            "bolusconfirm" ->
                processBolus(intent)
            "profileswitchrequest" ->
                requestProfileSwitch(intent)
            "profileswitchconfirm" ->
                processProfileSwitch(intent)
            "inforequest" ->
                processInfoRequest()
            "calculate" ->
                calculateBolus(intent)
            }
    }


    //////////////////// action request functions section /////////////////////////////

    private fun requestCarbs(intent: Intent) {

        val amount = intent.getStringExtra("amount")
        if ("amount" != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Received initial carb request containing: " + amount)
        } else {
            userFeedback("I did not receive the carb amount. Try again?",false)
            return
        }
        val splitted = amount.split(Regex("\\s+")).toTypedArray()
        val cleaned = splitted[0].replace("g", "", true)  //sometimes Google interprets "25 grams" as "25g". Need to get rid of the g.
        aapsLogger.debug(LTag.VOICECOMMAND, "Carb amount parsed to " + cleaned)
        val gramsRequest = SafeParse.stringToInt(convertToDigit(cleaned))
        val grams = constraintChecker.applyCarbsConstraints(Constraint(gramsRequest)).value()
        if (gramsRequest != grams) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "carb", gramsRequest.toString(), grams.toString()))
            return
        }
        if (grams == 0) {
            userFeedback("Zero grams requested. Aborting.",false)
            return
        }
        var replyText = "To confirm adding " + grams + "grams of carb"
        if (patientName != "") replyText += " for " + patientName
        replyText += ", say Yes."
        userFeedback(replyText, true, "carbconfirm", grams.toString(), patientName)
    }

    private fun processCarbs(intent: Intent) {

        val grams: String? = intent.getStringExtra("amount")
        if (grams != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_carbslog), grams, DateUtil.now()))
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.carbs = grams.toDouble()
            detailedBolusInfo.source = Source.USER
            detailedBolusInfo.date = DateUtil.now()
            if (activePlugin.activePump.pumpDescription.storesCarbInfo) {
                commandQueue.bolus(detailedBolusInfo, object : Callback() {
                    override fun run() {
                        var replyText: String
                        if (result.success) {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                        } else {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsfailed), grams)
                        }
                        if (requireIdentifier) {
                            val recipient: String? = intent.getStringExtra("recipient")
                            if (recipient != null && recipient != "") replyText += " for " + recipient + "."
                        }
                        userFeedback(replyText,false)
                    }
                })
            } else {
                activePlugin.activeTreatments.addToHistoryTreatment(detailedBolusInfo, true)
                var replyText: String = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                if (requireIdentifier) {
                    val recipient: String? = intent.getStringExtra("recipient")
                    if (recipient != null && recipient != "") replyText += " for " + recipient + "."
                }
                userFeedback(replyText,false)
            }
        }
    }

    private fun requestProfileSwitch(intent: Intent) {

        userFeedback("I don't do profile switches yet.",false)
        return

    }

    private fun processProfileSwitch(intent: Intent) {

        //treatmentsPlugin.doProfileSwitch(duration, percentage, timeshift)
        userFeedback("I don't do profile switches yet.",false)
        return
    }

    private fun requestBolus(intent: Intent) {



    }

    private fun processBolus(intent: Intent) {

        if (intent.getStringExtra("units") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing bolus request")
        } else {
            userFeedback("I did not receive the amount. Try again?",false)
            return
        }
        val splitted = intent.getStringExtra("units").split(Regex("\\s+")).toTypedArray()
        val bolusRequest = SafeParse.stringToDouble(convertToDigit(splitted[0]))
        val bolus = constraintChecker.applyBolusConstraints(Constraint(bolusRequest)).value()
        if (bolusRequest != bolus) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "bolus", bolusRequest.toString(), bolus.toString()),false)
            return
        }
        var meal = false
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x].contains("meal", true)) meal = true
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
                                userFeedback(replyText,false)
                            } else {
                                userFeedback(resourceHelper.gs(R.string.voiceassistant_bolusfailed),false)
                            }
                        }
                    })
                }
            })
        } else {
            userFeedback("Zero units requested. Aborting.",false)
        }
    }


    ////////////////////// Information request function section  ///////////////////////////////////////////

    private fun calculateBolus(intent: Intent) {
        userFeedback("I don't do bolus calculations yet.")
        return
    }

    private fun processInfoRequest() {

        var reply = ""

        if (fullCommandReceived) {

            for (x in 0 until spokenCommandArray.size) {
                if (spokenCommandArray[x].contains("detail", true)) detailedStatus = true
            }  //detailedStatus for certain functions such as returnIOB()

            for (x in 0 until spokenCommandArray.size) {
                if (spokenCommandArray[x].contains("glucose", true)) reply += returnGlucose()
                if (spokenCommandArray[x].contains("sugar", true)) reply += returnGlucose()
                if (spokenCommandArray[x].contains("bg", true)) reply += returnGlucose()
                if (spokenCommandArray[x].contains("iob", true)) reply += returnIOB()
                if (spokenCommandArray[x].contains("insulin", true)) reply += returnIOB()
                if (spokenCommandArray[x].contains("cob", true)) reply += returnCOB()
                if (spokenCommandArray[x].contains("carb", true)) reply += returnCOB()
                if (spokenCommandArray[x].contains("trend", true)) reply += returnTrend()
                if (spokenCommandArray[x].contains("basal", true)) reply += returnBasalRate()
                if (spokenCommandArray[x].contains("bolus", true)) reply += returnLastBolus()
                if (spokenCommandArray[x].contains("delta", true)) reply += returnDelta()
                if (spokenCommandArray[x].contains("summary", true))  reply += returnGlucose() + returnDelta() + returnIOB() + returnCOB() + returnStatus()
            }
        } else {
            userFeedback("I did not get your full request. Try again?",false)
            return
        }
        if (reply == "") {
            userFeedback("I could not understand what you were asking for. Try again?",false)
            return
        }
        userFeedback(reply,false)
    }

    private fun returnGlucose(): String {
        val actualBG = iobCobCalculatorPlugin.actualBg()
        val lastBG = iobCobCalculatorPlugin.lastBg()
        var reply = ""
        val units = profileFunction.getUnits()
        if (actualBG != null) {
            reply = "The current sensor glucose reading is " + actualBG.valueToUnitsToString(units) + " " + units + "."
        } else if (lastBG != null) {
            val agoMsec = System.currentTimeMillis() - lastBG.date
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            reply = "The last sensor glucose reading was " + " " + lastBG.valueToUnitsToString(units) + " " + units + ", " + String.format(resourceHelper.gs(R.string.sms_minago), agoMin) + "."
        } else {
            reply = "I could not get the most recent glucose reading."
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
        //treatmentsPlugin.getLastBolusTime(true)
        var output = ""
        val last: Treatment? = treatmentsPlugin.getService().getLastBolus(true)
        if (last != null) {
            val amount: String = last.insulin.toString()
            val date: String = DateUtil.dateString(last.date)
            output =  "The last manual bolus was for " + amount + " units, on " + date + "."
        } else {
            output = "I could not find the last manual bolus."
        }
        return output
    }

    private fun returnCOB(): String {
        val cobInfo = iobCobCalculatorPlugin.getCobInfo(false, "Voice COB")
        return "The carb on board is " + cobInfo.generateCOBString() + "."
    }

    private fun returnBasalRate(): String {
        return "I don't do basal rate requests yet."
    }

    private fun returnStatus(): String {
        return "The pump status is " + activePlugin.activePump.shortStatus(true) + "."
    }

    //////////////////////////////// utility function section //////////////////////////////

    private fun userFeedback(message: String, needsResponse: Boolean = false, type: String = "", amount: String = "", recipient: String = "") {

        //requires a 3rd party software such as Tasker to receive the intent with action "info.nightscout.androidaps.USER_FEEDBACK"
        //and speak the "message" contained in the extras.
        //messages also appear on the "VOICE" fragment in AndroidAPS.

        messages.add(dateUtil.timeString(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + message + "Needs response: " + needsResponse + "</b><br>")
        aapsLogger.debug(LTag.VOICECOMMAND, message + " Needs response: " + needsResponse)

        if (needsResponse) {
            context.sendBroadcast(
                Intent(Intents.USER_FEEDBACK_RESPONSE) // "info.nightscout.androidaps.USER_FEEDBACK_RESPONSE"
                    .putExtra("requesttype", type)
                    .putExtra("amount", amount)
                    .putExtra("recipient", recipient)
                    .putExtra("message", message)
            )
        } else {
            context.sendBroadcast(
                Intent(Intents.USER_FEEDBACK) // "info.nightscout.androidaps.USER_FEEDBACK"
                    .putExtra("message", message)
            )
        }
    }

    private fun patientMatch(wordArray: Array<String>): Boolean {

        var returnCode = false
        aapsLogger.debug(LTag.VOICECOMMAND, patientName)
        for (x in 0 until wordArray.size) {
            //aapsLogger.debug(LTag.VOICECOMMAND, "Command word " + x + " is " + wordArray[x])
            if (wordArray[x].contains(patientName, true)) returnCode = true
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

package info.nightscout.androidaps.plugins.general.voiceAssistant

//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml
//TODO logic to enable certain features based on config (pump control, nsclient, APS)
//TODO write code for setting time for carbs
//TODO fix icon
//TODO Additional commands
//TODO onPreferenceChange listener?

import android.content.Context
import android.content.Intent
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.data.DetailedBolusInfo
import info.nightscout.androidaps.data.Profile
import info.nightscout.androidaps.db.Source
import info.nightscout.androidaps.db.TempTarget
import info.nightscout.androidaps.db.Treatment
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.logging.LTag
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.services.Intents
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.SafeParse
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.android.synthetic.main.configbuilder_single_plugin.view.*
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
    private val rxBus: RxBusWrapper,
    private val fabricPrivacy: FabricPrivacy
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

    private val disposable = CompositeDisposable()
    var messages = ArrayList<String>()
    var fullCommandReceived = false
    var detailedStatus = false
    var requireIdentifier: Any = true
    val patientName = sp.getString(R.string.key_patient_name, "")
    var cleanedCommand = ""
    var bolusReplacements = ""
    var carbReplacements = ""
    var infoRequestReplacements = ""
    lateinit var spokenCommandArray: Array<String>

    override fun onStart() {
        processSettings(null)
        super.onStart()
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(Schedulers.io())
            .subscribe({ event: EventPreferenceChange? -> processSettings(event) }) { fabricPrivacy.logException(it) }
    }

    override fun onStop() {
        disposable.clear()
        super.onStop()
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)
        val _requireIdentifier = preferenceFragment.findPreference(resourceHelper.gs(R.string.key_voiceassistant_requireidentifier)) as SwitchPreference?
            ?: return
        _requireIdentifier.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            requireIdentifier = newValue
            true
        }
    }

    private fun processSettings(ev: EventPreferenceChange?) {
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_requireidentifier)) {
            requireIdentifier = sp.getBoolean(R.string.key_voiceassistant_requireidentifier, true)
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Require patient name set to " + requireIdentifier)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_bolusreplacements)) {
            bolusReplacements = sp.getString(R.string.key_voiceassistant_bolusreplacements,"")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Bolus word replacements set to " + bolusReplacements)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_carbreplacements)) {
            carbReplacements = sp.getString(R.string.key_voiceassistant_carbreplacements,"")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Carb word replacements set to " + carbReplacements)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_inforequests)) {
            infoRequestReplacements = sp.getString(R.string.key_voiceassistant_inforequests,"")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Info request trigger words set to " + infoRequestReplacements)
        }
    }

    fun processCommand(intent: Intent) {

        if (!isEnabled(PluginType.GENERAL)) {
            userFeedback("The voice assistant plugin is disabled. Please enable in the Config Builder.")
            return
        }
        if (!sp.getBoolean(R.string.key_voiceassistant_commandsallowed, false)) {
            userFeedback("Voice commands are not allowed. Please enable them in Preferences.")
            return
        }

        // if the intent is to confirm an already-requested action, then jump straight to execution without further processing
        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Parameters are: " + parameters)
            if (parameters.contains("carbconfirm", true)) { processCarbs(intent); return }
            if (parameters.contains("bolusconfirm", true)) { processBolus(intent); return }
            if (parameters.contains("profileswitchconfirm", true)) { processProfileSwitch(intent); return }
        } else {
            aapsLogger.debug(LTag.VOICECOMMAND, "No parameters received.")
        }

        val receivedCommand: String? = intent.getStringExtra("command")
        if (receivedCommand != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Command received: " + receivedCommand)
            messages.add(dateUtil.timeString(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + "Command received: " + receivedCommand + "</b><br>")
            fullCommandReceived = true
            cleanedCommand = processReplacements(receivedCommand)
            spokenCommandArray = cleanedCommand.split(Regex("\\s+")).toTypedArray()
        } else {
            userFeedback("I did not receive the command. Try again.", false)
            return
        }

        if (requireIdentifier as Boolean) {
            if (!cleanedCommand.contains(patientName, true)) {
                userFeedback("You need to specify the person's name when asking. Try again.", false)
                return
            }
        }
        identifyRequestType(cleanedCommand)
    }

    private fun identifyRequestType(command: String) {

        if (command == "") {
            userFeedback("Something went wrong with processing the command. Try again.")
            return
        }
        if (command.contains("inforequest")) { processInfoRequest() ; return }
        if (command.contains("carb") && command.contains("grams")) { requestCarbs() ; return }
        if (command.contains("bolus") && command.contains("units")) { requestBolus() ; return }
        if (command.contains("profile")) { requestProfileSwitch() ; return }
        if (command.contains("calculate")) { calculateBolus() ; return }
        userFeedback("I did not understand the command. Try again.")
    }


    //////////////////// action request functions section /////////////////////////////

    private fun requestCarbs() {

        var amount = ""
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x] == "grams") amount = spokenCommandArray[x-1]
        }
        val gramsRequest = SafeParse.stringToInt(amount)
        val grams = constraintChecker.applyCarbsConstraints(Constraint(gramsRequest)).value()
        if (gramsRequest != grams) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "carb", gramsRequest.toString(), grams.toString()))
            return
        }
        if (grams == 0) {
            userFeedback("Zero grams requested. Aborting.", false)
            return
        }
        var replyText = "To confirm adding " + grams + " grams of carb"
        if (patientName != "") replyText += " for " + patientName
        replyText += ", say Yes."
        val parameters = "carbconfirm;" + grams.toString() + ";" + patientName
        userFeedback(replyText, true, parameters)
    }

    private fun processCarbs(intent: Intent) {

        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            val splitted = parameters.split(Regex(";")).toTypedArray()
            val grams = splitted[1]
            aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_carbslog), grams))
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
                        if (requireIdentifier as Boolean) {
                            val recipient: String? = intent.getStringExtra("recipient")
                            if (recipient != null && recipient != "") replyText += " for " + recipient + "."
                        }
                        userFeedback(replyText, false)
                    }
                })
            } else {
                activePlugin.activeTreatments.addToHistoryTreatment(detailedBolusInfo, true)
                var replyText: String = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                if (requireIdentifier as Boolean) {
                    val recipient: String? = intent.getStringExtra("recipient")
                    if (recipient != null && recipient != "") replyText += " for " + recipient + "."
                }
                userFeedback(replyText, false)
            }
        }
    }

    private fun requestProfileSwitch() {

        userFeedback("I don't do profile switches yet.", false)
        return

    }

    private fun processProfileSwitch(intent: Intent) {

        //treatmentsPlugin.doProfileSwitch(duration, percentage, timeshift)
        userFeedback("I don't do profile switches yet.", false)
        return
    }

    private fun requestBolus() {

        var amount = ""
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x] == "units") amount = spokenCommandArray[x-1]
        }
        val bolusRequest = SafeParse.stringToDouble(amount)
        val bolus = constraintChecker.applyBolusConstraints(Constraint(bolusRequest)).value()
        if (bolusRequest != bolus) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "bolus", bolusRequest.toString(), bolus.toString()), false)
            return
        }
        if (bolus == 0.0) {
            userFeedback("Zero units requested. Aborting.", false)
            return
        }
        var meal = false
        if (cleanedCommand.contains("meal", true)) meal = true

        var replyText = "To confirm delivery of " + bolus + " units of insulin"
        if (patientName != "") replyText += " for " + patientName
        replyText += ", say Yes."
        val parameters = "bolusconfirm;" + bolus.toString() + ";" + meal.toString()
        userFeedback(replyText, true, parameters)
    }

    private fun processBolus(intent: Intent) {

        var isMeal = false
        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
        val splitted = parameters.split(Regex(";")).toTypedArray()
        val unitsReceived = splitted[1]
        isMeal = splitted[2].toBoolean()
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.insulin = SafeParse.stringToDouble(unitsReceived)
            detailedBolusInfo.source = Source.USER
            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                override fun run() {
                    val resultSuccess = result.success
                    val resultBolusDelivered = result.bolusDelivered
                    commandQueue.readStatus("VOICECOMMAND", object : Callback() {
                        override fun run() {
                            if (resultSuccess) {
                                var replyText = if (isMeal) String.format(resourceHelper.gs(R.string.voiceassistant_mealbolusdelivered), resultBolusDelivered)
                                else String.format(resourceHelper.gs(R.string.voiceassistant_bolusdelivered), resultBolusDelivered)
                                if (isMeal) {
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
                                userFeedback(replyText, false)
                            } else {
                                userFeedback(resourceHelper.gs(R.string.voiceassistant_bolusfailed), false)
                            }
                        }
                    })
                }
            })
        }
    }

    ////////////////////// Information request function section  ///////////////////////////////////////////

    private fun calculateBolus() {
        userFeedback("I don't do bolus calculations yet.")
        return
    }

    private fun processInfoRequest() {

        var reply = ""

        if (fullCommandReceived) {

            for (x in 0 until spokenCommandArray.size) {
                if (spokenCommandArray[x].contains("detail", true)) detailedStatus = true
            }  //detailedStatus for certain functions such as returnIOB()

            //Author's note: This code feels wonkily inefficient cycling through all those "contains" methods over and over again.
            //The goal it achieves is having the information returned to the user in the same order it was requested.
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
            userFeedback("I did not get your full request. Try again.", false)
            return
        }
        if (reply == "") {
            userFeedback("I could not understand what you were asking for. Try again.", false)
            return
        }
        userFeedback(reply, false)
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

    private fun userFeedback(message: String, needsResponse: Boolean = false, parameters: String = "") {

        //requires a 3rd party software such as Tasker to receive the intent with action "info.nightscout.androidaps.USER_FEEDBACK"
        //and speak the "message" contained in the extras.
        //messages also appear on the "VOICE" fragment in AndroidAPS.

        messages.add(dateUtil.timeString(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + message + "</b><br>")
        aapsLogger.debug(LTag.VOICECOMMAND, message)

        if (needsResponse) {
            context.sendBroadcast(
                Intent(Intents.USER_FEEDBACK_RESPONSE) // "info.nightscout.androidaps.USER_FEEDBACK_RESPONSE"
                    .putExtra("parameters", parameters)
                    .putExtra("message", message)
            )
        } else {
            context.sendBroadcast(
                Intent(Intents.USER_FEEDBACK) // "info.nightscout.androidaps.USER_FEEDBACK"
                    .putExtra("message", message)
            )
        }
    }

    private fun processReplacements(string: String): String {
        var output = string

        //step 1: numbers
        output = output.replace("zero", "0", true)
        output = output.replace("one", "1", true)
        output = output.replace("two", "2", true)
        output = output.replace("three", "3", true)
        output = output.replace("four", "4", true)
        output = output.replace("five", "5", true)
        output = output.replace("six", "6", true)
        output = output.replace("seven", "7", true)
        output = output.replace("eight", "8", true)
        output = output.replace("nine", "9", true)
        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 1: " + output)

        //step 2: split numbers and units, such as 25g to 25 g
        output = output.replace("(\\d)([A-Za-z])".toRegex(), "$1 $2")
        output = output.replace("(\\d)(%)".toRegex(), "$1 $2")
        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 2: " + output)

        //step 3: replace units abbreviations with words
        output = output.replace(" g ", " grams ")
        output = output.replace(" % ", " percent ")
        output = output.replace(" u ", " units ")
        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 3: " + output)

        //step 4: user defined replacements
        var wordArray: Array<String>

        //bolus
        if (bolusReplacements != "") {
            wordArray = bolusReplacements.split(Regex(";")).toTypedArray()
            for (x in 0 until wordArray.size) {
                output = output.replace(wordArray[x], "bolus", true)
            }
        }

        //carb
        if (carbReplacements != "") {
            wordArray = carbReplacements.split(Regex(";")).toTypedArray()
            for (x in 0 until wordArray.size) {
                output = output.replace(wordArray[x], "carb", true)
            }
        }

        //inforequest
        if (infoRequestReplacements != "") {
            wordArray = infoRequestReplacements.split(Regex(";")).toTypedArray()
            for (x in 0 until wordArray.size) {
                output = output.replace(wordArray[x], "inforequest", true)
            }
        }

        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 4: " + output)

        return output // cleanedCommand
    }

}

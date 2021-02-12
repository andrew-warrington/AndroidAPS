package info.nightscout.androidaps.plugins.general.voiceAssistant

//TODO move strings to strings.xml
//TODO logic to enable certain features based on config (pump control, nsclient, APS)
//TODO extend commands to work with time statements e.g. "10 minutes ago" where appropriate
//TODO fix icon
//TODO Additional commands
//TODO make the Voice fragment content persistent through application restarts & reboots
//TODO get text validation to ensure only alpha + ; in the prefs screen for word replacements
//TODO get word replacements prefs screen to automatically update after automatic removal of "illegal" words
//TODO word replacements: No duplicates
//TODO create word replacements capability for all keywords
//TODO automation
//TODO implement bolus password?

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
import info.nightscout.androidaps.plugins.general.smsCommunicator.otp.OneTimePassword
import info.nightscout.androidaps.plugins.general.smsCommunicator.otp.OneTimePasswordValidationResult
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
import info.nightscout.androidaps.utils.wizard.BolusWizard
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
    private val fabricPrivacy: FabricPrivacy,
    private val totp: OneTimePassword,
//    private val preferenceFragmentCompat: PreferenceFragmentCompat,
//    private val preferenceScreen: PreferenceScreen
//    private val loopPlugin: LoopPlugin,
//    private val xdripCalibrations: XdripCalibrations,
//    private val config: Config,
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
//    private val preferencesActivity = PreferencesActivity()
    private val pin = sp.getString(R.string.key_smscommunicator_otp_password, "").trim()
    private var fullCommandReceived = false
    private var detailedStatus = false
    private var requireIdentifier: Any = true
    private var fullSentenceResponses: Any = true
    private val patientName = sp.getString(R.string.key_patient_name, "")
    private var cleanedCommand = ""
    private var bolusReplacements = ""
    private var carbReplacements = ""
    private var nameReplacements = ""
    private var keyWordViolations = ""
    private var lastBolusWizard: BolusWizard? = null
    private var keywordArray: Array<String> = arrayOf("carb","bolus","profile","switch","calculate","grams","minute","hour","unit","glucose","iob","insulin","cob","trend","basal","bolus","delta","target","summary","yes","no","automation")
    lateinit var spokenCommandArray: Array<String>
    var messages = ArrayList<String>()

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
            aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: requireIdentifier set to " + newValue)
            true
        }
        val _fullSentenceResponses = preferenceFragment.findPreference(resourceHelper.gs(R.string.key_voiceassistant_fullsentenceresponses)) as SwitchPreference?
            ?: return
        _fullSentenceResponses.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            fullSentenceResponses = newValue
            aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: fullSentenceResponses set to " + newValue)
            true
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
            if (parameters.contains("automationconfirm", true)) { processAutomation(intent); return }
        } else {
            aapsLogger.debug(LTag.VOICECOMMAND, "No parameters received.")
        }

        val receivedCommand: String? = intent.getStringExtra("command")
        if (receivedCommand != null) {

            aapsLogger.debug(LTag.VOICECOMMAND, "Command received: " + receivedCommand)
            messages.add(dateUtil.timeStringWithSeconds(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + "Command received: " + receivedCommand + "</b><br>")
            fullCommandReceived = true

            cleanedCommand = processReplacements(receivedCommand)

            if (cleanedCommand.contains("\bno\b".toRegex(RegexOption.IGNORE_CASE))) return
            //any negative command coming through should stop further processing.
            //TODO via replacements have it be also, "stop", "abort", "cancel", and others

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

        if (command.contains("automation",true)) { requestAutomation() ; return }
        else if (command.contains("bolus",true) && command.contains("[0-9]\\sunits".toRegex(RegexOption.IGNORE_CASE))) { requestBolus() ; return }
        else if (command.contains("bolus",true) && command.contains("[0-9]\\sgrams".toRegex(RegexOption.IGNORE_CASE))) { requestBolusWizard() ; return }
        else if (command.contains("carb",true) && command.contains("[0-9]\\sgrams".toRegex(RegexOption.IGNORE_CASE))) { requestCarbs() ; return }
        else if (command.contains("calculate", true)) { requestBolusWizard() ; return }
        else if (command.contains("profile",true) && command.contains("switch", true)) { requestProfileSwitch() ; return }
        else { processInfoRequest() ; return }
    }

    //////////////////// action request functions section /////////////////////////////

    private fun requestCarbs() {

        var amount = ""
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x] == "grams") amount = spokenCommandArray[x-1]
        }
        if (constraintsOk(amount,"carb")) {
            var replyText = "To confirm adding " + amount + " grams of carb"
            if (patientName != "") replyText += " for " + patientName
            replyText += ", say Yes."
            val counter: Long = DateUtil.now() / 30000L
            val parameters = "carbconfirm;" + totp.generateOneTimePassword(counter) + ";" + amount + ";" + patientName
            userFeedback(replyText, true, parameters)
        }
    }

    private fun processCarbs(intent: Intent) {

        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            val splitted = parameters.split(Regex(";")).toTypedArray()

            if (!(totp.checkOTP(splitted[1] + pin) == OneTimePasswordValidationResult.OK)) {
                userFeedback("Something went wrong with confirming your request. Try again.")
                aapsLogger.debug(LTag.VOICECOMMAND, "OTP check failed for OTP: " + splitted[1])
                return
            }

            val amount = splitted[2]
            if (constraintsOk(amount, "carb")) {

                aapsLogger.debug(LTag.VOICECOMMAND, String.format(resourceHelper.gs(R.string.voiceassistant_carbslog), amount))
                val detailedBolusInfo = DetailedBolusInfo()
                detailedBolusInfo.carbs = amount.toDouble()
                detailedBolusInfo.source = Source.USER
                detailedBolusInfo.date = DateUtil.now()
                if (activePlugin.activePump.pumpDescription.storesCarbInfo) {
                    commandQueue.bolus(detailedBolusInfo, object : Callback() {
                        override fun run() {
                            var replyText: String
                            if (result.success) {
                                replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), amount)
                            } else {
                                replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsfailed), amount)
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
                    var replyText: String = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), amount)
                    if (requireIdentifier as Boolean) {
                        val recipient: String? = intent.getStringExtra("recipient")
                        if (recipient != null && recipient != "") replyText += " for " + recipient + "."
                    }
                    userFeedback(replyText, false)
                }
            }
        }
    }

    private fun requestProfileSwitch() {

        val anInterface = activePlugin.activeProfileInterface
        val store = anInterface.profile
        if (store == null) {
            userFeedback(resourceHelper.gs(R.string.voicecommand_profile_not_configured))
            return
        }
        var percentage = "100"
        var durationMin = "0"
        var durationHour = "0"
        var pindex = ""
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x].contains("minute", true) &&
                spokenCommandArray[x-1].contains("[0-9]".toRegex())) durationMin = spokenCommandArray[x-1]
            if (spokenCommandArray[x].contains("hour", true) &&
                spokenCommandArray[x-1].contains("[0-9]".toRegex())) durationHour = spokenCommandArray[x-1]
            if (spokenCommandArray[x].contains("percent", true) &&
                spokenCommandArray[x-1].contains("[0-9]".toRegex())) percentage = spokenCommandArray[x-1]
        }
        if (percentage == "0") {
            userFeedback("It is not possible to have a 0% profile. Try again.")
            return
        }
        val list = store.getProfileList()
        var profileName = ""
        if (list.isEmpty()) {
            userFeedback(resourceHelper.gs(R.string.voicecommand_profile_not_configured))
            return
        } else {
            for (i in list.indices) {
                if (cleanedCommand.contains(list[i].toString(), true)) {
                    pindex = i.toString()
                    profileName = list[i].toString()
                    aapsLogger.debug(LTag.VOICECOMMAND, "Profile name is " + profileName + " and index is " + i.toString())
                }
            }
        }
        if (pindex == "" || profileName == "") {
            userFeedback("I did not understand which profile you wanted. Try again.")
            return
        }

        val duration = SafeParse.stringToInt(durationMin) + (SafeParse.stringToInt(durationHour) * 60)

        var replyText = "To confirm profile switch to " + profileName + " at " + percentage + " percent,"
        if (duration != 0) replyText += "for " + duration.toString() + " minutes,"
        if (requireIdentifier as Boolean && patientName != "") replyText += " for " + patientName + ", "
        replyText += "say Yes."
        val counter: Long = DateUtil.now() / 30000L
        val parameters = "profileswitchconfirm;" + totp.generateOneTimePassword(counter) + ";" + pindex + ";" + percentage + ";" + duration.toString() + ";" + patientName

        userFeedback(replyText,true,parameters)
    }

    private fun processProfileSwitch(intent: Intent) {
        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            val splitted = parameters.split(Regex(";")).toTypedArray()

            if (!(totp.checkOTP(splitted[1] + pin) == OneTimePasswordValidationResult.OK)) {
                userFeedback("Something went wrong with confirming your request. Try again.")
                aapsLogger.debug(LTag.VOICECOMMAND, "OTP check failed for OTP: " + splitted[1])
                return
            }

            val pindex = SafeParse.stringToInt(splitted[2])
            val percentage = SafeParse.stringToInt(splitted[3])
            val duration = SafeParse.stringToInt(splitted[4])
            aapsLogger.debug(LTag.VOICECOMMAND, "Received profile switch command for profile " + pindex + ", " + percentage + "%, " + duration + " minutes.")

            val anInterface = activePlugin.activeProfileInterface
            val store = anInterface.profile
            if (store == null) {
                userFeedback(resourceHelper.gs(R.string.voicecommand_profile_not_configured))
                return
            }
            val list = store.getProfileList()
            activePlugin.activeTreatments.doProfileSwitch(store, list[pindex] as String, duration, percentage, 0, DateUtil.now())
            //line above: pindex or pindex - 1?
            userFeedback("Profile switch created.")
        }
    }

    private fun requestBolus() {

        var insulinAmount = "0"
        var carbAmount = "0"
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x] == "units") insulinAmount = spokenCommandArray[x-1]
            if (spokenCommandArray[x] == "grams") carbAmount = spokenCommandArray[x-1]
        }

        if (constraintsOk(insulinAmount, "bolus") && constraintsOk(carbAmount, "carb", false)) {

            var meal = false
            if (cleanedCommand.contains("meal", true)) meal = true

            var replyText = "To confirm delivery of " + insulinAmount + " units of insulin"
            if (carbAmount != "0") replyText += " and " + carbAmount + " grams of carb"
            if (patientName != "") replyText += " for " + patientName
            replyText += ", say Yes."
            val counter: Long = DateUtil.now() / 30000L
            val parameters = "bolusconfirm;" + totp.generateOneTimePassword(counter) + ";" + insulinAmount.toString() + ";" + carbAmount.toString()+ ";" + meal.toString()
            userFeedback(replyText, true, parameters)
        }
    }

    private fun processBolus(intent: Intent) {

        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            val splitted = parameters.split(Regex(";")).toTypedArray()

            if (!(totp.checkOTP(splitted[1] + pin) == OneTimePasswordValidationResult.OK)) {
                userFeedback("Something went wrong with confirming your request. Try again.")
                aapsLogger.debug(LTag.VOICECOMMAND, "OTP check failed for OTP: " + splitted[1])
                return
            }

            val insulinAmount = splitted[2]
            val carbAmount = splitted[3]

            if (constraintsOk(insulinAmount, "bolus") && constraintsOk(carbAmount, "carb", false)) {

                val isMeal = splitted[4].toBoolean()
                val detailedBolusInfo = DetailedBolusInfo()
                detailedBolusInfo.insulin = insulinAmount.toDouble()
                detailedBolusInfo.carbs = carbAmount.toDouble()
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
                                            val tt =
                                                if (currentProfile.units == Constants.MMOL) DecimalFormatter.to1Decimal(eatingSoonTT)
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
    }

    private fun requestBolusWizard() {

        var meal = false
        var carbAmount = "0"
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x] == "grams") carbAmount = spokenCommandArray[x-1]
            if (spokenCommandArray[x] == "meal") meal = true
        }

        if (constraintsOk(carbAmount, "carb", false)) {

            val useBG = sp.getBoolean(R.string.key_wearwizard_bg, true)
            val useTT = sp.getBoolean(R.string.key_wearwizard_tt, false)
            val useBolusIOB = sp.getBoolean(R.string.key_wearwizard_bolusiob, true)
            val useBasalIOB = sp.getBoolean(R.string.key_wearwizard_basaliob, true)
            val useCOB = sp.getBoolean(R.string.key_wearwizard_cob, true)
            val useTrend = sp.getBoolean(R.string.key_wearwizard_trend, false)
            val percentage = sp.getInt(R.string.key_boluswizard_percentage, 100).toInt()
            val profile = profileFunction.getProfile()
            val profileName = profileFunction.getProfileName()
            if (profile == null) {
                userFeedback("I could not find your profile. Please create a profile and try again.")
                return
            }
            val bgReading = iobCobCalculatorPlugin.actualBg()
            if (bgReading == null) {
                userFeedback("There was no recent glucose reading to base the calculation on. Please wait for a new reading and try again.")
                return
            }
            val cobInfo = iobCobCalculatorPlugin.getCobInfo(false, "Voice assistant wizard")
            if (cobInfo.displayCob == null) {
                userFeedback("I could not calculate your current carb on board. Please wait for a few minutes and try again.")
                return
            }
            val bolusWizard = BolusWizard(injector).doCalc(profile, profileName, activePlugin.activeTreatments.tempTargetFromHistory,
                SafeParse.stringToInt(carbAmount), cobInfo.displayCob, bgReading.valueToUnits(profileFunction.getUnits()),
                0.0, percentage.toDouble(), useBG, useCOB, useBolusIOB, useBasalIOB, false, useTT, useTrend, false)

            var replyText = ""
            val carbAmountD = SafeParse.stringToDouble(carbAmount)
            if (bolusWizard.calculatedTotalInsulin > 0.0) {
                replyText += "I have calculated " + bolusWizard.calculatedTotalInsulin + "units of insulin are needed for glucose " + bgReading
                replyText += if (carbAmountD > 0.0) " and " + carbAmount + " grams of carb." else "."
            } else if (bolusWizard.carbsEquivalent.toInt() > 0) {
                replyText += "I have calculated that " + bolusWizard.carbsEquivalent.toString() + " grams of carb are required."
            }

            replyText += "Would you like to "
            if (bolusWizard.calculatedTotalInsulin > 0.0) replyText += "deliver the insulin "
            if (bolusWizard.calculatedTotalInsulin > 0.0 && carbAmountD > 0.0) replyText += "and"
            if (carbAmountD > 0.0) replyText += " add the carb?"

            val counter: Long = DateUtil.now() / 30000L
            val parameters = "bolusconfirm;" + totp.generateOneTimePassword(counter) + ";" + bolusWizard.calculatedTotalInsulin.toString() + ";" + carbAmount + ";" + meal
            userFeedback(replyText, true, parameters)
        }
    }

    private fun requestAutomation() {
        return
    }

    private fun processAutomation(intent: Intent) {
        return
    }

    ////////////////////// Information request function section  ///////////////////////////////////////////



    private fun processInfoRequest() {

        var reply = ""

        if (fullCommandReceived) {

            for (x in 0 until spokenCommandArray.size) {
                if (spokenCommandArray[x].contains("detail", true)) detailedStatus = true
            }  //detailedStatus for certain functions such as returnIOB()

            //Author's note: This code feels wonkily inefficient cycling through all those "contains" methods over and over again.
            //The goal it achieves though is having the information returned to the user in the same order it was requested.
            for (x in 0 until spokenCommandArray.size) {
                if (spokenCommandArray[x].contains("glucose", true)) reply += returnGlucose()
                if (spokenCommandArray[x].contains("insulin", true)) reply += returnIOB()
                if (spokenCommandArray[x].contains("iob", true)) reply += returnIOB()
                if (spokenCommandArray[x].contains("carb", true)) reply += returnCOB()
                if (spokenCommandArray[x].contains("cob", true)) reply += returnCOB()
                if (spokenCommandArray[x].contains("trend", true)) reply += returnTrend()
                if (spokenCommandArray[x].contains("basal", true)) reply += returnBasalRate()
                if (spokenCommandArray[x].contains("bolus", true)) reply += returnLastBolus()
                if (spokenCommandArray[x].contains("delta", true)) reply += returnDelta()
                if (spokenCommandArray[x].contains("profile", true)) reply += returnProfileResult()
                if (spokenCommandArray[x].contains("target", true)) reply += returnTarget()
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
        var output = ""
        val units = profileFunction.getUnits()
        if (actualBG != null) {
            output = "The current sensor glucose reading is " + actualBG.valueToUnitsToString(units) + " " + units + "."
        } else if (lastBG != null) {
            val agoMsec = System.currentTimeMillis() - lastBG.date
            val agoMin = (agoMsec / 60.0 / 1000.0).toInt()
            output = "The last sensor glucose reading was " + " " + lastBG.valueToUnitsToString(units) + " " + units + ", " + String.format(resourceHelper.gs(R.string.sms_minago), agoMin) + "."
        } else {
            output = "I could not get the most recent glucose reading."
        }
        return output
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

    private fun returnProfileResult(): String {

        var output = ""
        val anInterface = activePlugin.activeProfileInterface
        val store = anInterface.profile
        if (store == null) {
            return resourceHelper.gs(R.string.voicecommand_profile_not_configured)
        }

        if (cleanedCommand.contains("list", true)) {
            val list = store.getProfileList()
            if (list.isEmpty()) output = resourceHelper.gs(R.string.voicecommand_profile_not_configured)
            else {
                for (i in list.indices) {
                    if (i > 0) output += ",\n"
                    output += (i + 1).toString() + ". "
                    output += list[i]
                }
                output = "The profile list is\n" + output + "."
            }
        } else {

            var profileInfo: String?
                profileInfo = profileFunction.getProfileNameWithDuration()
                //e.g. MyProfile(150%)(1h 13')

            if (profileInfo != null) {

                profileInfo = profileInfo.replace("(", ", ", true).replace(")", "", true)
                //MyProfile, 150%, 1h 13'

                profileInfo = profileInfo.replace("([0-9])(h)".toRegex(RegexOption.IGNORE_CASE), "$1 hours")
                profileInfo = profileInfo.replace("([0-9])(\')".toRegex(RegexOption.IGNORE_CASE), "$1 minutes")
                profileInfo = profileInfo.replace("([0-9])(\\shours\\s)([0-9])".toRegex(RegexOption.IGNORE_CASE), "$1 hours and $3")
                //MyProfile, 150%, 1 hours and 13 minutes

                if (profileInfo.contains("hours") || profileInfo.contains("minutes")) profileInfo += " remaining."
                //MyProfile, 150%, 1 hours and 13 minutes remaining.

                output += "The current profile is " + profileInfo
            }
        }
        return output
    }

    private fun returnTrend(): String {
        return "I don't do trend requests yet."
    }

    private fun returnTarget(): String {
        return "I don't do target requests yet."
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
            val date: String = dateUtil.timeString(last.date)
            output =  "The last manual bolus was for " + amount + " units, at " + date + "."
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

    fun userFeedback(message: String, needsResponse: Boolean = false, parameters: String = "") {

        //requires a 3rd party software such as Tasker to receive the intent and speak the "message" contained in the extras.
        //messages also appear on the "VOICE" fragment in AndroidAPS.

        messages.add(dateUtil.timeStringWithSeconds(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + message + "</b><br>")
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
        var output = " " + string + " "

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
        //aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 1: " + output)

        //step 2: split numbers and units, such as 25g to 25 g
        output = output.replace("(\\d)([A-Za-z])".toRegex(), "$1 $2")
        output = output.replace("(\\d)(%)".toRegex(), "$1 $2")
        output = output.replace("\'", " \'", true)
        //aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 2: " + output)

        //step 3: replace units abbreviations with words
        output = output.replace(" g ", " grams ", true)
        output = output.replace(" % ", " percent ", true)
        output = output.replace(" u ", " units ", true)
        output = output.replace(" ' "," minutes ", true)
        output = output.replace(" m ", " minutes ", true)
        output = output.replace(" h ", " hour ", true)
        output = output.replace("-", " ", true)
        //aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 3: " + output)

        //step 4: user defined replacements
        if (bolusReplacements != "") output = processUserReplacements(output, bolusReplacements,"bolus")
        if (carbReplacements != "") output = processUserReplacements(output, carbReplacements,"carb")
        if (nameReplacements != "") output = processUserReplacements(output, nameReplacements, patientName)

        aapsLogger.debug(LTag.VOICECOMMAND, "Command after word replacements: " + output)

        output = output.trim()

        return output // cleanedCommand
    }

    private fun processUserReplacements(command: String, replacementWords:String, newWord:String): String {
        var output = ""

        val wordArray: Array<String> = replacementWords.trim().split(Regex(";")).toTypedArray()
        for (x in 0 until wordArray.size) output = command.replace(wordArray[x], newWord, true)

        return output
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
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_namereplacements)) {
            nameReplacements = sp.getString(R.string.key_voiceassistant_namereplacements, "")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Name replacements set to " + nameReplacements)
        }

        keyWordViolations = ""
        sp.putString(R.string.key_voiceassistant_bolusreplacements, processKeyWordViolations(bolusReplacements))
        sp.putString(R.string.key_voiceassistant_carbreplacements, processKeyWordViolations(carbReplacements))
        sp.putString(R.string.key_voiceassistant_namereplacements, processKeyWordViolations(nameReplacements))
        //TODO get the preferences screen to refresh automatically

        if (keyWordViolations != "") {
            userFeedback("It is not allowed to have the following words as replacements: " + keyWordViolations + ". These were removed. Exit this screen and reopen it to see the change.")
        }
    }

    private fun processKeyWordViolations(replacements: String): String {

        var output = ";" + replacements + ";"  //ensure we match whole words only, i.e. should find "carb" as it is a keyword, but ignore "carbs" because that is not
        for (x in 0 until keywordArray.size) {
            if (output.contains(";" + keywordArray[x] + ";", true)) {
                keyWordViolations += keywordArray[x] + ", "
                output = output.replace(";" + keywordArray[x] + ";", ";", true)
            }
        }
        output = output.substring(1, output.length - 1) // remove the ";" which were added at the beginning
        aapsLogger.debug(LTag.VOICECOMMAND, "Output is: " + output + " and keywordviolations are: " + keyWordViolations)
        return output
    }

    private fun constraintsOk(requestedAmount: String, type: String, complainOnZero: Boolean = true): Boolean {

        var finalAmount = ""

        if (type == "bolus") { finalAmount = constraintChecker.applyBolusConstraints(Constraint(SafeParse.stringToDouble(requestedAmount))).value().toString() }
        else if (type == "carb") { finalAmount = constraintChecker.applyCarbsConstraints(Constraint(SafeParse.stringToInt(requestedAmount))).value().toString() }
        else return false

        if (requestedAmount != finalAmount) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), type, requestedAmount, finalAmount), false)
            return false
        }
        if (complainOnZero && SafeParse.stringToDouble(finalAmount) == 0.0) {
            userFeedback("Zero " + type + " amount requested. Aborting.", false)
            return false
        }

        return true
    }

}

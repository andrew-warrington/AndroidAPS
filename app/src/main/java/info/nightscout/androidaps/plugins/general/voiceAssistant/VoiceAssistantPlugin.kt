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
//TODO move word replacements to fragment
//TODO create word replacements capability for all keywords
//TODO automation
//TODO implement temp targets
//TODO when voice disabled in config builder icon should disappear
//TODO create a dialog box for recognition instead of Toast
//TODO hotword (Android 11+)
//TODO permissions only requested when needed, i.e. when VoiceAssistant is set to on.
//TODO Check whether bolus wizard works without Wear activated
//TODO add InstallTTSData into setting screen.
//TODO revert safetyplugin
//TODO replace "I confirm" with a random 3-digit number

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
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
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.SafeParse
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.extensions.runOnUiThread
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import info.nightscout.androidaps.utils.wizard.BolusWizard
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
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
    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private var fullCommandReceived = false
    private var detailedStatus = false
    private var requireIdentifier: Any = true
    private var showButton: Any = true
    private var fullSentenceResponses: Any = true
    private val patientName = sp.getString(R.string.key_patient_name, "")
    private var cleanedCommand = ""
    private var bolusReplacements = ""
    private var carbReplacements = ""
    private var nameReplacements = ""
    private var keyWordViolations = ""
    private var calculateReplacements = ""
    private var cancelReplacements = ""
    private var keywordArray: Array<String> = arrayOf("carb", "bolus", "profile", "switch", "calculate", "gram", "minute", "hour", "unit", "glucose", "iob", "insulin", "cob", "trend", "basal", "bolus", "delta", "target", "status", "summary", "cancel", "automation")
    lateinit var tts: TextToSpeech
    lateinit var spokenCommandArray: Array<String>
    var map = HashMap<String, String>()
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
        speechRecognizer.stopListening()
        speechRecognizer.destroy()
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

        val _showButton = preferenceFragment.findPreference(resourceHelper.gs(R.string.key_voiceassistant_showbutton)) as SwitchPreference?
            ?: return
        _showButton.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            showButton = newValue
            aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: showButton set to " + newValue)
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

        if (!(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure) {
            userFeedback("Your device must be secured with a PIN, pattern, or password in order to use the voice assistant. You can set these up in your device settings.")
            return
        }

        if ((context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked) {
            userFeedback("Please unlock your device and try again.")
            return
        }

        val receivedCommand: String? = intent.getStringExtra("query")
        if (receivedCommand != null) {

            aapsLogger.debug(LTag.VOICECOMMAND, "Command received: " + receivedCommand)
            messages.add(dateUtil.timeStringWithSeconds(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + "Command received: " + receivedCommand + "</b><br>")
            fullCommandReceived = true

            cleanedCommand = processReplacements(receivedCommand)

            if (cleanedCommand.contains("cancel", true)) { //any negative command coming through should stop further processing.
                userFeedback("Cancelling.")
                return
            }
            //TODO manage this in consideration of "temp target cancel".


            val parameters = intent.getStringExtra("parameters") ?: ""
            if (parameters != "") {   //then this is a confirmed command.

                // Check next: Did we get a confirmation code?
                val parametersArray = parameters.split(Regex(";")).toTypedArray()
                if (cleanedCommand.contains(parametersArray[1], true)) {

                    aapsLogger.debug(LTag.VOICECOMMAND, "Parameters are: " + parameters)
                    if (parameters.contains("carbconfirm", true)) {
                        processCarbs(intent); return
                    }
                    if (parameters.contains("bolusconfirm", true)) {
                        processBolus(intent); return
                    }
                    if (parameters.contains("profileswitchconfirm", true)) {
                        processProfileSwitch(intent); return
                    }
                    if (parameters.contains("automationconfirm", true)) {
                        processAutomation(intent); return
                    }
                }
            } else {
                aapsLogger.debug(LTag.VOICECOMMAND, "No parameters received, therefore identifying request type.")
            }
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

        if (command.contains("automation", true)) { requestAutomation() ; return }
        else if (command.contains("calculate", true)) { requestBolusWizard() ; return }
        else if (command.contains("bolus", true) && command.contains("[0-9]\\sgram".toRegex(RegexOption.IGNORE_CASE)) && command.contains("[0-9]\\sunit".toRegex(RegexOption.IGNORE_CASE))) { requestBolus() ; return }
        else if (command.contains("bolus", true) && command.contains("[0-9]\\sgram".toRegex(RegexOption.IGNORE_CASE))) { requestBolusWizard() ; return }
        else if (command.contains("bolus", true) && command.contains("[0-9]\\sunit".toRegex(RegexOption.IGNORE_CASE))) { requestBolus() ; return }
        else if (command.contains("insulin", true) && command.contains("[0-9]\\sunit".toRegex(RegexOption.IGNORE_CASE))) { requestBolus() ; return }
        else if (command.contains("carb", true) && (command.contains("[0-9]\\sgram".toRegex(RegexOption.IGNORE_CASE)) || command.contains("[0-9]\\scarb".toRegex(RegexOption.IGNORE_CASE)))) { requestCarbs() ; return }
        //else if (command.contains("carb", true) && command.contains("[0-9]\\scarb".toRegex(RegexOption.IGNORE_CASE))) { requestCarbs() ; return }
        else if (command.contains("basal", true) && command.contains("[0-9]\\sunit".toRegex(RegexOption.IGNORE_CASE))) { requestBasal() ; return }
        else if (command.contains("target", true) &&
            ((command.contains("[0-9]\\smmol".toRegex(RegexOption.IGNORE_CASE)) ||
            command.contains("[0-9]\\smgdl".toRegex(RegexOption.IGNORE_CASE))) ||
            command.contains("eating soon", true) ||
            command.contains("activity", true) ||
            command.contains("hypo", true) ||
            command.contains("cancel", true)))
            { requestTarget() ; return }
        else if (command.contains("profile", true) && command.contains("switch", true)) { requestProfileSwitch() ; return }
        else { processInfoRequest() ; return }
    }

    //////////////////// action request functions section /////////////////////////////

    private fun requestBasal() {

        /*

        if (splitted[1].toUpperCase(Locale.getDefault()) == "CANCEL" || splitted[1].toUpperCase(Locale.getDefault()) == "STOP") {
            val passCode = generatePasscode()
            val reply = String.format(resourceHelper.gs(R.string.smscommunicator_basalstopreplywithcode), passCode)
            receivedSms.processed = true
            messageToConfirm = AuthRequest(injector, receivedSms, reply, passCode, object : SmsAction() {
                override fun run() {
                    aapsLogger.debug("USER ENTRY: SMS BASAL $reply")
                    commandQueue.cancelTempBasal(true, object : Callback() {
                        override fun run() {
                            if (result.success) {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalcanceled)
                                replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                            } else {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalcancelfailed)
                                replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                sendSMS(Sms(receivedSms.phoneNumber, replyText))
                            }
                        }
                    })
                }
            })
        } else if (splitted[1].endsWith("%")) {
            var tempBasalPct = SafeParse.stringToInt(StringUtils.removeEnd(splitted[1], "%"))
            val durationStep = activePlugin.activePump.model().tbrSettings.durationStep
            var duration = 30
            if (splitted.size > 2) duration = SafeParse.stringToInt(splitted[2])
            val profile = profileFunction.getProfile()
            if (profile == null) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.noprofile)))
            else if (tempBasalPct == 0 && splitted[1] != "0%") sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else if (duration <= 0 || duration % durationStep != 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongTbrDuration, durationStep)))
            else {
                tempBasalPct = constraintChecker.applyBasalPercentConstraints(Constraint(tempBasalPct), profile).value()
                val passCode = generatePasscode()
                val reply = String.format(resourceHelper.gs(R.string.smscommunicator_basalpctreplywithcode), tempBasalPct, duration, passCode)
                receivedSms.processed = true
                messageToConfirm = AuthRequest(injector, receivedSms, reply, passCode, object : SmsAction(tempBasalPct, duration) {
                    override fun run() {
                        aapsLogger.debug("USER ENTRY: SMS BASAL $reply")
                        commandQueue.tempBasalPercent(anInteger(), secondInteger(), true, profile, object : Callback() {
                            override fun run() {
                                if (result.success) {
                                    var replyText: String
                                    replyText = if (result.isPercent) String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset_percent), result.percent, result.duration) else String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset), result.absolute, result.duration)
                                    replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                } else {
                                    var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalfailed)
                                    replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                    sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                }
                            }
                        })
                    }
                })
            }
        } else {
            var tempBasal = SafeParse.stringToDouble(splitted[1])
            val durationStep = activePlugin.activePump.model().tbrSettings.durationStep
            var duration = 30
            if (splitted.size > 2) duration = SafeParse.stringToInt(splitted[2])
            val profile = profileFunction.getProfile()
            if (profile == null) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.noprofile)))
            else if (tempBasal == 0.0 && splitted[1] != "0") sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongformat)))
            else if (duration <= 0 || duration % durationStep != 0) sendSMS(Sms(receivedSms.phoneNumber, resourceHelper.gs(R.string.wrongTbrDuration, durationStep)))
            else {
                tempBasal = constraintChecker.applyBasalConstraints(Constraint(tempBasal), profile).value()
                val passCode = generatePasscode()
                val reply = String.format(resourceHelper.gs(R.string.smscommunicator_basalreplywithcode), tempBasal, duration, passCode)
                receivedSms.processed = true
                messageToConfirm = AuthRequest(injector, receivedSms, reply, passCode, object : SmsAction(tempBasal, duration) {
                    override fun run() {
                        aapsLogger.debug("USER ENTRY: SMS BASAL $reply")
                        commandQueue.tempBasalAbsolute(aDouble(), secondInteger(), true, profile, object : Callback() {
                            override fun run() {
                                if (result.success) {
                                    var replyText = if (result.isPercent) String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset_percent), result.percent, result.duration)
                                    else String.format(resourceHelper.gs(R.string.smscommunicator_tempbasalset), result.absolute, result.duration)
                                    replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                    sendSMSToAllNumbers(Sms(receivedSms.phoneNumber, replyText))
                                } else {
                                    var replyText = resourceHelper.gs(R.string.smscommunicator_tempbasalfailed)
                                    replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                    sendSMS(Sms(receivedSms.phoneNumber, replyText))
                                }
                            }
                        })
                    }
                })
            }
        }

*/
    }

    private fun processBasal(intent: Intent) {
        return
    }

    private fun requestTarget() {
        return
    }

    private fun requestCarbs() {

        var amount = ""
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x].contains("gram", true)) amount = spokenCommandArray[x - 1]
        }
        if (constraintsOk(amount, "carb")) {
            val confCode = generateConfirmationCode()
            var replyText = "To confirm adding " + amount + " grams of carb"
            if (requireIdentifier as Boolean && patientName != "") replyText += " for " + patientName
            replyText += ", say " + confCode + "."
            val parameters = "carbconfirm;" + confCode + ";" + amount + ";" + patientName
            userFeedback(replyText, true, parameters)

        }
    }

    fun processCarbs(intent: Intent) {

        val parameters = intent.getStringExtra("parameters")
        if (parameters == null) userFeedback("Something went wrong with the request. Try again.")
        else {
            val splitted = parameters.split(Regex(";")).toTypedArray()

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
                                if (patientName != "") replyText += " for " + patientName + "."
                            }
                            userFeedback(replyText, false)
                        }
                    })
                } else {
                    activePlugin.activeTreatments.addToHistoryTreatment(detailedBolusInfo, true)
                    var replyText: String = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), amount)
                    if (requireIdentifier as Boolean) {
                        if (patientName != "") replyText += " for " + patientName + "."
                    }
                    userFeedback(replyText)
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
                spokenCommandArray[x - 1].contains("[0-9]".toRegex())) durationMin = spokenCommandArray[x - 1]
            if (spokenCommandArray[x].contains("hour", true) &&
                spokenCommandArray[x - 1].contains("[0-9]".toRegex())) durationHour = spokenCommandArray[x - 1]
            if (spokenCommandArray[x].contains("percent", true) &&
                spokenCommandArray[x - 1].contains("[0-9]".toRegex())) percentage = spokenCommandArray[x - 1]
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

        val confCode = generateConfirmationCode()
        var replyText = "To confirm profile switch to " + profileName + " at " + percentage + " percent,"
        if (duration != 0) replyText += "for " + duration.toString() + " minutes,"
        if (requireIdentifier as Boolean && patientName != "") replyText += " for " + patientName + ", "
        replyText += ", say " + confCode + "."
        val parameters = "profileswitchconfirm;" + confCode + ";" + pindex + ";" + percentage + ";" + duration.toString() + ";" + patientName

        userFeedback(replyText, true, parameters)
    }

    private fun processProfileSwitch(intent: Intent) {
        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            val splitted = parameters.split(Regex(";")).toTypedArray()

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
            userFeedback("Profile switch created.")
        }
    }

    private fun requestBolus() {

        var insulinAmount = "0"
        var carbAmount = "0"
        for (x in 0 until spokenCommandArray.size) {
            if (spokenCommandArray[x].contains("unit", true)) insulinAmount = spokenCommandArray[x - 1]
            if (spokenCommandArray[x].contains("gram", true)) carbAmount = spokenCommandArray[x - 1]
        }

        if (constraintsOk(insulinAmount, "bolus") && constraintsOk(carbAmount, "carb", false)) {

            var meal = false
            if (cleanedCommand.contains("meal", true)) meal = true

            val confCode = generateConfirmationCode()
            var replyText = "To confirm delivery of " + insulinAmount + " units of insulin"
            if (carbAmount != "0") replyText += " and " + carbAmount + " grams of carb"
            if (requireIdentifier as Boolean && patientName != "") replyText += " for " + patientName
            replyText += ", say " + confCode + "."
            val parameters = "bolusconfirm;" + confCode + ";" + insulinAmount + ";" + carbAmount + ";" + meal.toString()
            userFeedback(replyText, true, parameters)
        }
    }

    private fun processBolus(intent: Intent) {

        val parameters: String? = intent.getStringExtra("parameters")
        if (parameters != null) {
            val splitted = parameters.split(Regex(";")).toTypedArray()

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
            if (spokenCommandArray[x].contains("gram", true)) carbAmount = spokenCommandArray[x - 1]
            if (spokenCommandArray[x].contains("meal", true)) meal = true
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

            val units = profileFunction.getUnits()

            val cobInfo = iobCobCalculatorPlugin.getCobInfo(false, "Voice assistant wizard")
            if (cobInfo.displayCob == null) {
                userFeedback("I could not calculate your current carb on board. Please wait for a few minutes and try again.")
                return
            }
            val bolusWizard = BolusWizard(injector).doCalc(profile, profileName, activePlugin.activeTreatments.tempTargetFromHistory,
                SafeParse.stringToInt(carbAmount), cobInfo.displayCob, bgReading.valueToUnits(profileFunction.getUnits()),
                0.0, percentage.toDouble(), useBG, useCOB, useBolusIOB, useBasalIOB, false, useTT, useTrend, false)

            val confCode = generateConfirmationCode()
            var replyText = ""
            val carbAmountD = SafeParse.stringToDouble(carbAmount)
            if (bolusWizard.calculatedTotalInsulin > 0.0) {
                replyText += "Bolus wizard calculates " + bolusWizard.calculatedTotalInsulin.toString() + " units of insulin for glucose " + bgReading.valueToUnitsToString(units) + " " + units
                replyText += if (carbAmountD > 0.0) " and " + carbAmount + " grams of carb" else ""
                replyText += if (cobInfo.displayCob > 0) ", with " + cobInfo.displayCob.toString() + " grams of carb on board" else ""
                replyText += "."
            } else if (bolusWizard.carbsEquivalent.toInt() > 0) {
                replyText += "Bolus wizard calculates that " + DecimalFormatter.to0Decimal(bolusWizard.carbsEquivalent).toString()
                if (carbAmountD > 0) replyText += " more"
                replyText += " grams of carb are required."
                userFeedback(replyText)
                return
            }

            var supplementalText = ""
            if (replyText != "") {
                if (bolusWizard.calculatedTotalInsulin > 0.0) supplementalText += "deliver the insulin "
                if (bolusWizard.calculatedTotalInsulin > 0.0 && carbAmountD > 0.0) supplementalText += "and "
                if (carbAmountD > 0.0) supplementalText += "add the carb"
                if (carbAmountD > 0.0 && bolusWizard.carbsEquivalent > 0) supplementalText += " you mentioned"
                if (requireIdentifier as Boolean && patientName != "") supplementalText += ", for " + patientName
            }

            if (supplementalText != "") replyText += " Would you like to " + supplementalText + "?"

            replyText = replyText + " Say " + confCode + "."

            if (replyText == "") {
                userFeedback("The bolus wizard did not return a result.")
                return
            }

            val parameters = "bolusconfirm;" + confCode + ";" + bolusWizard.calculatedTotalInsulin.toString() + ";" + carbAmount + ";" + meal
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
                if (spokenCommandArray[x].contains("status", true)) reply += returnStatus()
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
        val units = profileFunction.getUnits()

        var output = ""
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
        val actualBG = iobCobCalculatorPlugin.actualBg()

        //TODO --- this.
        /*         switch (delta_name) {
            case "DoubleDown":
                delta_name = xdrip.getAppContext().getString(R.string.DoubleDown);
                break;
            case "SingleDown":
                delta_name = xdrip.getAppContext().getString(R.string.SingleDown);
                break;
            case "FortyFiveDown":
                delta_name = xdrip.getAppContext().getString(R.string.FortyFiveDown);
                break;
            case "Flat":
                delta_name = xdrip.getAppContext().getString(R.string.Flat);
                break;
            case "FortyFiveUp":
                delta_name = xdrip.getAppContext().getString(R.string.FortyFiveUp);
                break;
            case "SingleUp":
                delta_name = xdrip.getAppContext().getString(R.string.SingleUp);
                break;
            case "DoubleUp":
                delta_name = xdrip.getAppContext().getString(R.string.DoubleUp);
                break;
            case "NOT COMPUTABLE":
                delta_name = "";
                break;


         */


        if (actualBG != null) {
            return "The trend is " + actualBG.direction
        } else {
            return "I could not calculate the trend. Please wait for a new sensor reading and try again."
        }
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
        aapsLogger.debug(LTag.VOICECOMMAND, "cobInfo is: " + cobInfo.generateCOBString())
        if (cobInfo.generateCOBString().contains("[0-9]".toRegex())) {
            return "The carb on board is " + cobInfo.generateCOBString() + "."
        } else {
            return "I could not calculate the carb on board. Please wait for a new sensor reading and try again."
        }
    }

    private fun returnBasalRate(): String {
        return "I don't do basal rate requests yet."
    }

    private fun returnStatus(): String {
        return "The pump status is " + activePlugin.activePump.shortStatus(true) + "."
    }

    //////////////////////////////// utility function section //////////////////////////////

    fun userFeedback(message: String, needsResponse: Boolean = false, _parameters: String = "") {

        //messages appear on the "VOICE" fragment in AndroidAPS.

        messages.add(dateUtil.timeStringWithSeconds(DateUtil.now()) + " &lt;&lt;&lt; " + "░ " + message + "</b><br>")
        aapsLogger.debug(LTag.VOICECOMMAND, message)
        say(message, needsResponse, _parameters)
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
        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 1: " + output)

        //step 2: split numbers and units, such as 25g to 25 g
        output = output.replace("(\\d)([A-Za-z])".toRegex(), "$1 $2")
        output = output.replace("(\\d)(%)".toRegex(), "$1 $2")
        output = output.replace("\'", " \'", true)
        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 2: " + output)

        //step 3: replace units abbreviations with words
        output = output.replace(" g ", " grams ", true)
        output = output.replace(" % ", " percent ", true)
        output = output.replace(" u ", " units ", true)
        output = output.replace(" ' ", " minutes ", true)
        output = output.replace(" m ", " minutes ", true)
        output = output.replace(" h ", " hours ", true)
        output = output.replace("-", " ", true)
        output = output.replace("/", " ", true)
        aapsLogger.debug(LTag.VOICECOMMAND, "Updated command at step 3: " + output)

        //step 4: user defined replacements
        if (bolusReplacements != "") output = processUserReplacements(output, bolusReplacements, "bolus")
        if (carbReplacements != "") output = processUserReplacements(output, carbReplacements, "carb")
        if (nameReplacements != "") output = processUserReplacements(output, nameReplacements, patientName)
        if (calculateReplacements != "") output = processUserReplacements(output, calculateReplacements, "calculate")
        if (cancelReplacements != "") output = processUserReplacements(output, cancelReplacements, "cancel")

        aapsLogger.debug(LTag.VOICECOMMAND, "Command after word replacements: " + output)

        output = output.trim()

        return output // cleanedCommand
    }

    private fun processUserReplacements(command: String, replacementWords: String, newWord: String): String {
        var output = command

        val wordArray: Array<String> = replacementWords.trim().split(Regex(";")).toTypedArray()
        for (x in 0 until wordArray.size) output = output.replace(wordArray[x], newWord, true)

        return output
    }

    private fun processSettings(ev: EventPreferenceChange?) {
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_requireidentifier)) {
            requireIdentifier = sp.getBoolean(R.string.key_voiceassistant_requireidentifier, true)
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Require patient name set to " + requireIdentifier)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_showbutton)) {
            showButton = sp.getBoolean(R.string.key_voiceassistant_showbutton, true)
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Show button set to " + showButton)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_fullsentenceresponses)) {
            fullSentenceResponses = sp.getBoolean(R.string.key_voiceassistant_fullsentenceresponses, true)
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Full sentence responses set to " + fullSentenceResponses)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_bolusreplacements)) {
            bolusReplacements = sp.getString(R.string.key_voiceassistant_bolusreplacements, "")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Bolus word replacements set to " + bolusReplacements)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_carbreplacements)) {
            carbReplacements = sp.getString(R.string.key_voiceassistant_carbreplacements, "")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Carb word replacements set to " + carbReplacements)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_namereplacements)) {
            nameReplacements = sp.getString(R.string.key_voiceassistant_namereplacements, "")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Name replacements set to " + nameReplacements)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_calculatereplacements)) {
            calculateReplacements = sp.getString(R.string.key_voiceassistant_calculatereplacements, "")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Calculate word replacements set to " + calculateReplacements)
        }
        if (ev == null || ev.isChanged(resourceHelper, R.string.key_voiceassistant_cancelreplacements)) {
            cancelReplacements = sp.getString(R.string.key_voiceassistant_cancelreplacements, "")
            if (ev != null) aapsLogger.debug(LTag.VOICECOMMAND, "Settings change: Cancel word replacements set to " + cancelReplacements)
        }

        keyWordViolations = ""
        sp.putString(R.string.key_voiceassistant_bolusreplacements, processKeyWordViolations(bolusReplacements))
        sp.putString(R.string.key_voiceassistant_carbreplacements, processKeyWordViolations(carbReplacements))
        sp.putString(R.string.key_voiceassistant_namereplacements, processKeyWordViolations(nameReplacements))
        sp.putString(R.string.key_voiceassistant_calculatereplacements, processKeyWordViolations(calculateReplacements))
        sp.putString(R.string.key_voiceassistant_cancelreplacements, processKeyWordViolations(cancelReplacements))
        //TODO get the preferences screen to refresh automatically

        if (keyWordViolations != "") {
            userFeedback("It is not allowed to have the following words as replacements: " + keyWordViolations + ". These were removed. Exit this screen and reopen it to see the change.")
        }
    }

    private fun processKeyWordViolations(replacements: String): String {

        if (replacements == "") return ""

        //ensure we match whole words only, i.e. should find "carb" as it is a keyword, but ignore "carbs" because that is not
        var output = ";" + replacements + ";"  //make sure there are starting and ending ; on the string
        output = output.replace(";;", ";", true)  //make sure there is exactly one starting ; and one ending ; in the string

        if (output.length < 3 ) return ""

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

        if (SafeParse.stringToDouble(requestedAmount) != SafeParse.stringToDouble(finalAmount)) {
            userFeedback(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), type, requestedAmount, finalAmount), false)
            return false
        }
        if (complainOnZero && SafeParse.stringToDouble(finalAmount) == 0.0) {
            userFeedback("Zero " + type + " amount requested. Aborting.", false)
            return false
        }
        return true
    }

    fun listen(message: String, parameters: String? = null) {

        val returnIntent = Intent()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, message)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(bundle: Bundle) {
                aapsLogger.debug(LTag.VOICECOMMAND, "Started listening.")
                runOnUiThread(Runnable { Toast.makeText(context, "Listening ...", Toast.LENGTH_SHORT).show() })
                //show the dialog
                //messageTextView.text = message
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(bytes: ByteArray) {}
            override fun onEndOfSpeech() {
                //close dialog
            }

            override fun onError(i: Int) {
                //close dialog
                aapsLogger.debug(LTag.VOICECOMMAND, "Listening error.")
            }

            override fun onResults(bundle: Bundle) {
                aapsLogger.debug(LTag.VOICECOMMAND, "Received listening results.")
                val data: ArrayList<String>? = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                runOnUiThread { Toast.makeText(context, data?.get(0), Toast.LENGTH_SHORT).show() }
                returnIntent.putExtra("query", data?.get(0))
                if (parameters != null) returnIntent.putExtra("parameters", parameters)
                //close dialog
                processCommand(returnIntent)
            }

            override fun onPartialResults(bundle: Bundle) {
                //val data: ArrayList<String>? = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                //runOnUiThread { Toast.makeText(context, data?.get(0), Toast.LENGTH_SHORT).show() }
                //update dialogbox
            }

            override fun onEvent(i: Int, bundle: Bundle) {}
        })
        speechRecognizer.startListening(intent)

    }

    @Synchronized
    fun say(message: String, needsResponse: Boolean, _parameters: String, delay: Long = 0, retry: Int = 0): Int {

        var result = 0
        Thread(Runnable {
            try {
                initialize(message, needsResponse, _parameters)
                try {
                    Thread.sleep(delay)
                } catch (ee: InterruptedException) {
                }
                try {
                    map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, DateUtil.now().toString());
                    result = tts.speak(message, TextToSpeech.QUEUE_ADD, map)
                } catch (e: NullPointerException) {
                    result = TextToSpeech.ERROR
                    aapsLogger.debug(LTag.VOICECOMMAND, "Got null pointer trying to speak. concurrency issue")
                }

                // speech randomly fails, usually due to the service not being bound so quick after being initialized, so we wait and retry recursively
                if (result != TextToSpeech.SUCCESS && retry < 5) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Failed to speak: retrying in 1s: $retry")
                    say(message, needsResponse, _parameters, delay + 1000, retry + 1)
                    return@Runnable
                }
                // only get here if retries exceeded
                if (result != TextToSpeech.SUCCESS) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Failed to speak after: $retry retries.")
                }
            } finally {
                // nada
            }
        }).start()

        if (result != TextToSpeech.SUCCESS) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Failed to speak after: $retry retries.")
        }
        return result
    }

    @Synchronized private fun initialize(message: String = "", needsResponse: Boolean, __parameters: String) {
        tts = TextToSpeech(context) { status: Int ->
            if (status == TextToSpeech.SUCCESS) {
                val speech_locale = Locale.getDefault()
                aapsLogger.debug(LTag.VOICECOMMAND, "Chosen locale: $speech_locale")
                var set_language_result: Int = try {
                    tts.setLanguage(speech_locale)
                } catch (e: IllegalArgumentException) {
                    // can end up here with Locales like "OS"
                    aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set language error: $e")
                    TextToSpeech.LANG_MISSING_DATA
                } catch (e: Exception) {
                    // can end up here with deep errors from tts system
                    aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set language deep error: $e")
                    TextToSpeech.LANG_MISSING_DATA
                }

                // try various fallbacks
                if (set_language_result == TextToSpeech.LANG_MISSING_DATA || set_language_result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Default system language is not supported")
                    set_language_result = try {
                        tts.setLanguage(Locale.ENGLISH)
                    } catch (e: IllegalArgumentException) {
                        // can end up here with parcel Locales like "OS"
                        aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set default language error: $e")
                        TextToSpeech.LANG_MISSING_DATA
                    } catch (e: Exception) {
                        // can end up here with deep errors from tts system
                        aapsLogger.debug(LTag.VOICECOMMAND, "Got TTS set default language deep error: $e")
                        TextToSpeech.LANG_MISSING_DATA
                    }
                }
                //try any english as last resort
                if (set_language_result == TextToSpeech.LANG_MISSING_DATA || set_language_result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Failed. Retrying up to 4 times.")
                }
            } else {
                aapsLogger.debug(LTag.VOICECOMMAND, "Initialize status code indicates failure, code: $status")
            }

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Speaking: " + message)
                }

                override fun onDone(utteranceId: String) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Finished speaking: " + message)
                    aapsLogger.debug(LTag.VOICECOMMAND, "Needs response: " + needsResponse)
                    aapsLogger.debug(LTag.VOICECOMMAND, "Parameters: " + __parameters)
                    if (needsResponse) {
                        runOnUiThread {if (__parameters != "") listen(message, __parameters)}
                    }
                }

                override fun onError(utteranceId: String) {
                    aapsLogger.debug(LTag.VOICECOMMAND, "Error when speaking: " + message)
                }
            })
        }
    }

    fun installTTSData(context: Context) {
        try {
            val intent = Intent()
            intent.action = TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Could not install Text to Speech data: $e")
        }
    }

    // shutdown existing instance - useful when changing language or parameters
    @Synchronized fun shutdown() {
        try {
            tts.shutdown()
        } catch (e: IllegalArgumentException) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Got exception shutting down service: $e")
        }
    }

    private fun generateConfirmationCode(): String {

        val confirmationCode = Random().nextInt(899) + 100   //generate a pseudo-random number between 100 and 999
        return confirmationCode.toString()
    }
}

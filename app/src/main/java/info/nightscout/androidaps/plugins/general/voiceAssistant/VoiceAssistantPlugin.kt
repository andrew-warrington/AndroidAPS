package info.nightscout.androidaps.plugins.general.voiceAssistant

// Receives intents from an external voice assistant and forwards to the VoiceAssistantPlugin
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

//TODO ensure when two or more phones with AAPS can hear the command, only the correct one processes the request (patient identifier)
//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml
//TODO logic to enable certain features based on config (pump control, nsclient, APS)
//TODO set up substitutions for Tasker using a, b, c
//TODO tasker set up for new command format "add carbohydrates 25 grams now"
//TODO write code for setting time for carbs
//TODO write code for meal bolus
//TODO fix icon
//load preferences at start

import android.content.Context
import android.content.Intent
import dagger.android.HasAndroidInjector
import info.nightscout.androidaps.Config
import info.nightscout.androidaps.Constants
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.VoiceAssistantActivity
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
import info.nightscout.androidaps.plugins.general.smsCommunicator.otp.OneTimePassword
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.DecimalFormatter
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.SafeParse
import info.nightscout.androidaps.utils.XdripCalibrations
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import java.util.ArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceAssistantPlugin @Inject constructor(
        injector: HasAndroidInjector,
        aapsLogger: AAPSLogger,
        resourceHelper: ResourceHelper,
        private val sp: SP,
        private val constraintChecker: ConstraintChecker,
        private val rxBus: RxBusWrapper,
        private val profileFunction: ProfileFunction,
        private val fabricPrivacy: FabricPrivacy,
        private val activePlugin: ActivePluginProvider,
        private val commandQueue: CommandQueueProvider,
        private val loopPlugin: LoopPlugin,
        private val iobCobCalculatorPlugin: IobCobCalculatorPlugin,
        private val xdripCalibrations: XdripCalibrations,
        private var otp: OneTimePassword,
        private val config: Config,
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
    var voiceAssistant= VoiceAssistantActivity()

    override fun onStart() {
//        processSettings(null)
        super.onStart()
//        disposable += rxBus
//           .toObservable(EventPreferenceChange::class.java)
//           .observeOn(Schedulers.io())
//           .subscribe({ event: EventPreferenceChange? -> processSettings(event) }) { fabricPrivacy.logException(it) }
    }

//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        aapsLogger.debug(LTag.VOICECOMMAND, "Google assistant command received")
//        processIntent(intent)
//        TODO add this code back in when the processSettings function is written
//        disposable += rxBus
//            .toObservable(EventPreferenceChange::class.java)
//            .observeOn(Schedulers.io())
//            .subscribe({ event: EventPreferenceChange? -> processSettings(event) }) { fabricPrivacy.logException(it) }
//        finish()
//    }

//    override fun onDestroy() {
//        disposable.clear()
//        super.onStop()
//    }

    fun processVoiceCommand(intent: Intent) {

        val assistantCommandsAllowed = sp.getBoolean(R.string.key_voiceassistant_commandsallowed, false)

        if (!isEnabled(PluginType.GENERAL)) {
            this.voiceAssistant.messageToUser("The voice assistant plugin is disabled. Please enable it.")
            return
        }
        if (!assistantCommandsAllowed) {
            this.voiceAssistant.messageToUser("The voice assistant plugin is not allowed. Please enable it.")
            return
        }
/* TODO need code like below linked to some sort of security and/or identity check
        if (!isAllowedNumber(receivedSms.phoneNumber)) {
            aapsLogger.debug(LTag.SMS, "Ignoring SMS from: " + receivedSms.phoneNumber + ". Sender not allowed")
            receivedSms.ignored = true
            messages.add(receivedSms)
            rxBus.send(EventSmsCommunicatorUpdateGui())
            return
        }
*/
        val requestType: String? = intent.getStringExtra("requesttype")
        if (requestType == null) {
            val requestTypeNotReceived = "Request type not received. Aborting"
            messages.add(requestTypeNotReceived)
            this.voiceAssistant.messageToUser(requestTypeNotReceived)
            return
        } else {
            aapsLogger.debug(LTag.VOICECOMMAND, requestType)
            when (requestType) {
                "carb"         ->
                    processCarbs(intent)
                "bolus"         ->
                    processBolus(intent)
            }
        }
    }

    private fun processCarbs(intent: Intent) {

        if (intent.getStringExtra("amount") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing carb request")
        } else {
            val carbAmountNotReceived = "Carb amount not received. Aborting."
            this.voiceAssistant.messageToUser(carbAmountNotReceived)
            messages.add(carbAmountNotReceived)
            return
        }
        val splitted = intent.getStringExtra("amount").split(Regex("\\s+")).toTypedArray()
        var gramsRequest = SafeParse.stringToInt(splitted[0])
        var grams = constraintChecker.applyCarbsConstraints(Constraint(gramsRequest)).value()
        if (gramsRequest != grams) {
            val constraintResponse = String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "carb", gramsRequest.toString(), grams.toString())
            this.voiceAssistant.messageToUser(constraintResponse)
            messages.add(constraintResponse)
            return
        }
        if (grams == 0) {
            val zeroGramsResponse = "Zero grams requested. Aborting."
            this.voiceAssistant.messageToUser(zeroGramsResponse)
            messages.add(zeroGramsResponse)
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
                        var replyText: String
                        if (result.success) {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                        } else {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsfailed), grams)
                        }
                        voiceAssistant.messageToUser(replyText)
                        messages.add(replyText)
                    }
                })
            } else {
                activePlugin.activeTreatments.addToHistoryTreatment(detailedBolusInfo, true)
                var replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                this.voiceAssistant.messageToUser(replyText)
                messages.add(replyText)
            }
        }
    }

    private fun processBolus(intent: Intent) {

        //TODO security check

        if (intent.getStringExtra("units") != null && intent.getStringExtra("meal") != null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Processing bolus request")
        } else {
            val bolusRequestIncomplete = "Bolus request received was not complete. Aborting"
            this.voiceAssistant.messageToUser(bolusRequestIncomplete)
            messages.add(bolusRequestIncomplete)
            return
        }

        var splitted = intent.getStringExtra("units").split(Regex("\\s+")).toTypedArray()
        val bolusRequest = SafeParse.stringToDouble(splitted[0])
        val bolus = constraintChecker.applyBolusConstraints(Constraint(bolusRequest)).value()
        if (bolusRequest != bolus) {
            val constraintResponse = String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "bolus", bolusRequest.toString(), bolus.toString())
            this.voiceAssistant.messageToUser(constraintResponse)
            messages.add(constraintResponse)
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
                                voiceAssistant.messageToUser(replyText)
                                messages.add(replyText)
                            }
                            else {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_bolusfailed)
//                              replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                voiceAssistant.messageToUser(replyText)
                                messages.add(replyText)
                            }
                        }
                    })
                }
            })
        } else {
            val zeroUnitsResponse = "Zero units requested. Aborting."
            this.voiceAssistant.messageToUser(zeroUnitsResponse)
            messages.add(zeroUnitsResponse)
        }
    }

}

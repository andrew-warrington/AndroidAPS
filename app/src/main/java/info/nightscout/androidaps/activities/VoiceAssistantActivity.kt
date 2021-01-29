package info.nightscout.androidaps.activities

// Receives intents from an external voice assistant and executes.
// As of Jan 2021 recommend voice assistant integration via Tasker and AutoVoice apps on Android, as these work with both Google and Alexa

//TODO ensure when two or more phones with AAPS can hear the command, only the correct one processes the request
//TODO load preferences to ensure Google assistant can be used
//TODO assess whether any permissions required in AndroidManifest
//TODO move strings to strings.xml

import android.content.Intent
import android.os.Bundle
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
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import javax.inject.Inject

class VoiceAssistantActivity : NoSplashAppCompatActivity() {
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintChecker
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var commandQueue: CommandQueueProvider
    @Inject lateinit var sp: SP
    @Inject lateinit var profileFunction: ProfileFunction

    var lastRemoteBolusTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aapsLogger.debug(LTag.VOICECOMMAND, "Google assistant command received")
        processIntent(intent)
//        TODO add this code back in when the processSettings function is written
//        disposable += rxBus
//            .toObservable(EventPreferenceChange::class.java)
//            .observeOn(Schedulers.io())
//            .subscribe({ event: EventPreferenceChange? -> processSettings(event) }) { fabricPrivacy.logException(it) }
        finish()
    }

//    override fun onDestroy() {
//        disposable.clear()
//        super.onStop()
//    }

    fun processIntent(intent: Intent) {

//        val assistantCommandsAllowed = sp.getBoolean(R.string.key_googleassistant_commandsallowed, false)
//TODO need code here to check whether this is set to "on" in preferences and if not to send an intent back to AutoVoice to reply.

        val bundle: Bundle = intent.extras
        val requestType: String? = bundle.getString("requesttype")
        if (requestType == null) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Intent is null. Aborting.")
            messageToUser("An error has occurred. Aborting.")
            return
        } else {
            aapsLogger.debug(LTag.VOICECOMMAND, requestType)
            when (requestType) {
                "carb"         ->
                    if (bundle.getString("amount") == null) {
                        aapsLogger.debug(LTag.VOICECOMMAND, "Amount is null, aborting")
                        messageToUser("An error has occurred. Aborting.")
                        return
                    } else {
                        aapsLogger.debug(LTag.VOICECOMMAND, "Processing carb request")
                        processCarbs(bundle.getString("amount"))
                    }
                "bolus"         ->
                    if (bundle.getString("units") == null || bundle.getBoolean("meal") == null) {
                        aapsLogger.debug(LTag.VOICECOMMAND, "Bolus request received was not complete, aborting")
                        messageToUser("An error has occurred. Aborting.")
                        return
                    } else {
                        aapsLogger.debug(LTag.VOICECOMMAND, "Processing bolus request")
                        processBolus(bundle.getString("units"), bundle.getBoolean("meal"))
                    }
            }
        }
    }

    private fun processCarbs(_amount: String) {
        val time = DateUtil.now()
        val splitted = _amount.split(Regex("\\s+")).toTypedArray()
        var gramsRequest = SafeParse.stringToInt(splitted[0])
        var grams = constraintChecker.applyCarbsConstraints(Constraint(gramsRequest)).value()
        if (gramsRequest != grams) messageToUser(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "carb", gramsRequest, grams))
        aapsLogger.debug(LTag.VOICECOMMAND, grams.toString())
        if (grams == 0) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Zero grams requested. Aborting.")
            messageToUser("Zero grams requested. Aborting.")
            return
        } else {
            val carbslog = String.format(resourceHelper.gs(R.string.voiceassistant_carbslog), grams, dateUtil.timeString(time))
            aapsLogger.debug(LTag.VOICECOMMAND, carbslog)
            val detailedBolusInfo = DetailedBolusInfo()
            detailedBolusInfo.carbs = grams.toDouble()
            detailedBolusInfo.source = Source.USER
            detailedBolusInfo.date = time
            if (activePlugin.activePump.pumpDescription.storesCarbInfo) {
                commandQueue.bolus(detailedBolusInfo, object : Callback() {
                    override fun run() {
                        var replyText: String
                        if (result.success) {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                        } else {
                            replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsfailed), grams)
                        }
                        aapsLogger.debug(LTag.VOICECOMMAND, replyText)
                        messageToUser(replyText)
                    }
                })
            } else {
                activePlugin.activeTreatments.addToHistoryTreatment(detailedBolusInfo, true)
                var replyText = String.format(resourceHelper.gs(R.string.voiceassistant_carbsset), grams)
                messageToUser(replyText)
            }
        }
    }

    private fun processBolus(_units: String, _ismeal: Boolean) {

        //TODO security check

        val splitted = _units.split(Regex("\\s+")).toTypedArray()
        var bolusRequest = SafeParse.stringToDouble(splitted[0])
        var bolus = constraintChecker.applyBolusConstraints(Constraint(bolusRequest)).value()
        if (bolusRequest != bolus) messageToUser(String.format(resourceHelper.gs(R.string.voiceassistant_constraintresult), "bolus", bolusRequest, bolus))
        if (bolus > 0.0) {
            aapsLogger.debug(LTag.VOICECOMMAND, "Received bolus request")
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
                                var replyText = if (_ismeal) String.format(resourceHelper.gs(R.string.smscommunicator_mealbolusdelivered), resultBolusDelivered)
                                else String.format(resourceHelper.gs(R.string.smscommunicator_bolusdelivered), resultBolusDelivered)
                                replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                lastRemoteBolusTime = DateUtil.now()
                                if (_ismeal) {
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
                                    replyText += "\n" + String.format(resourceHelper.gs(R.string.smscommunicator_mealbolusdelivered_tt), tt, eatingSoonTTDuration)
                                    }
                                }
                                messageToUser(replyText)
                            }
                            else {
                                var replyText = resourceHelper.gs(R.string.smscommunicator_bolusfailed)
                                replyText += "\n" + activePlugin.activePump.shortStatus(true)
                                messageToUser(replyText)
                            }
                        }
                    })
                }
            })
        }
        else messageToUser(resourceHelper.gs(R.string.wrongformat))
    }

    private fun messageToUser(message: String) {

        //external voice assistant must implement a receiver to speak these messages back to the user.
        //this is possible via Tasker on Android, for example.

        Intent().also { intent ->
            intent.setAction("info.nightscout.androidaps.CONFIRM_RESULT")
            intent.putExtra("message", message)
            sendBroadcast(intent)
        }
        aapsLogger.debug(LTag.VOICECOMMAND, resourceHelper.gs(R.string.voiceassistant_messagetouser), message)
    }
 }


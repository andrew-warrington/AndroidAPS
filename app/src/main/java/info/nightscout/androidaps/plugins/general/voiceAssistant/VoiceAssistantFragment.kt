package info.nightscout.androidaps.plugins.general.voiceAssistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.R
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.voiceAssistant.events.EventVoiceAssistantUpdateGui
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.HtmlHelper
import info.nightscout.androidaps.utils.extensions.plusAssign
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.voiceassistant_fragment.*
import java.util.*
import javax.inject.Inject
import kotlin.math.max

class VoiceAssistantFragment : DaggerFragment() {

        @Inject lateinit var fabricPrivacy : FabricPrivacy
        @Inject lateinit var aapsLogger: AAPSLogger
        @Inject lateinit var rxBus: RxBusWrapper
        @Inject lateinit var voiceAssistantPlugin: VoiceAssistantPlugin
        @Inject lateinit var dateUtil: DateUtil

    private val disposable = CompositeDisposable()

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                                  savedInstanceState: Bundle?): View? {
            return inflater.inflate(R.layout.voiceassistant_fragment, container, false)
        }

    @Synchronized
        override fun onResume() {
            super.onResume()
            disposable += rxBus
                .toObservable(EventVoiceAssistantUpdateGui::class.java)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ updateGui() }) { fabricPrivacy.logException(it) }
            updateGui()
        }

        @Synchronized
        override fun onPause() {
            super.onPause()
            disposable.clear()
        }

        fun updateGui() {
            Collections.sort(voiceAssistantPlugin.messages)
            val messagesToShow = 40
            val start = max(0, voiceAssistantPlugin.messages.size - messagesToShow)
            var logText = ""
            for (x in start until voiceAssistantPlugin.messages.size) {
                logText += voiceAssistantPlugin.messages[x]
            }
            voiceassistant_log?.text = HtmlHelper.fromHtml(logText)
        }
}
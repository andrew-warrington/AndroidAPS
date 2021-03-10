package info.nightscout.androidaps.plugins.general.voiceAssistant

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.R
import info.nightscout.androidaps.interfaces.ActivePluginProvider
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.general.voiceAssistant.events.EventVoiceAssistantUpdateGui
import info.nightscout.androidaps.utils.*
import info.nightscout.androidaps.utils.extensions.plusAssign
import info.nightscout.androidaps.utils.resources.ResourceHelper
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.voiceassistant_fragment.*
import java.util.*
import javax.inject.Inject
import kotlin.math.max

class VoiceAssistantFragment : DaggerFragment() {

    @Inject lateinit var context1: Context
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var voiceAssistantPlugin: VoiceAssistantPlugin

    private val disposable = CompositeDisposable()
    //private var wordReplacementView: WordListEdit? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.voiceassistant_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // activate messages tab
        processVisibilityOnClick(messages_tab)
        messages.visibility = View.VISIBLE
        // setup listeners
        messages_tab.setOnClickListener {
            processVisibilityOnClick(it)
            messages.visibility = View.VISIBLE
        }
        wordreplacements_tab.setOnClickListener {
            processVisibilityOnClick(it)
            wordreplacements_tab.visibility = View.VISIBLE
        }
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

    private val save = Runnable {
        doEdit()
    }

    fun build() {

        WordListEdit(context1, aapsLogger, view, R.id.wordreplacements, "WR", resourceHelper.gs(R.string.voiceassistant_wr_label), save)

        @Suppress("SetTextI18n")

        wordreplacements_reset.setOnClickListener {
            voiceAssistantPlugin.loadSettings()
            build()
        }

        wordreplacements_save.setOnClickListener {
            if (!voiceAssistantPlugin.isValidEditState()) {
                return@setOnClickListener  //Should not happen as saveButton should not be visible if not valid
            }
            voiceAssistantPlugin.storeSettings(activity)
            build()
        }
        updateGUI()
    }

    fun doEdit() {
        voiceAssistantPlugin.isEdited = true
        updateGUI()
    }

    fun updateGUI() {
        val isValid = voiceAssistantPlugin.isValidEditState()
        val isEdited = voiceAssistantPlugin.isEdited
        if (isValid) {
            this.view?.setBackgroundColor(resourceHelper.gc(R.color.ok_background))

            if (isEdited) {
                //edited profile -> save first
                wordreplacements_save.visibility = View.VISIBLE
            } else {
                wordreplacements_save.visibility = View.GONE
            }
        } else {
            this.view?.setBackgroundColor(resourceHelper.gc(R.color.error_background))
            wordreplacements_save.visibility = View.GONE //don't save an invalid profile
        }

        //Show reset button if data was edited
        if (isEdited) {
            wordreplacements_reset.visibility = View.VISIBLE
        } else {
            wordreplacements_reset.visibility = View.GONE
        }
    }

    private fun processVisibilityOnClick(selected: View) {
        messages_tab.setBackgroundColor(resourceHelper.gc(R.color.defaultbackground))
        wordreplacements_tab.setBackgroundColor(resourceHelper.gc(R.color.defaultbackground))
        selected.setBackgroundColor(resourceHelper.gc(R.color.tabBgColorSelected))
        messages.visibility = View.GONE
        wordreplacements.visibility = View.GONE
    }
}
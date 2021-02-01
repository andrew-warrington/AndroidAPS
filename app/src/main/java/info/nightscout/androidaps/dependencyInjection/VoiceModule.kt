package info.nightscout.androidaps.dependencyInjection

import dagger.Module
import dagger.android.ContributesAndroidInjector
import info.nightscout.androidaps.plugins.general.voiceAssistant.VoiceResponseActivity

@Module
@Suppress("unused")

abstract class VoiceModule {

    @ContributesAndroidInjector abstract fun voiceResponse(): VoiceResponseActivity
}
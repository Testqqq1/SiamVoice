package com.example

import android.app.Application
import com.example.data.SecureApiKeyStore
import com.example.data.SiamVoicePreferences
import com.example.engine.SiamVoiceTtsSynthesizer
import com.example.engine.SynthDispatcher
import com.example.network.GeminiPromptService
import com.example.tts.TextToSpeechManager

class SiamVoiceApplication : Application() {
    lateinit var preferences: SiamVoicePreferences
        private set

    lateinit var apiKeyStore: SecureApiKeyStore
        private set

    lateinit var ttsManager: TextToSpeechManager
        private set

    lateinit var geminiPromptService: GeminiPromptService
        private set

    lateinit var synthesizer: SiamVoiceTtsSynthesizer
        private set

    lateinit var synthDispatcher: SynthDispatcher
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = SiamVoicePreferences(this)
        apiKeyStore = SecureApiKeyStore(this)
        ttsManager = TextToSpeechManager(this)
        geminiPromptService = GeminiPromptService(this)
        synthesizer = SiamVoiceTtsSynthesizer(
            context = this,
            preferences = preferences,
            apiKeyStore = apiKeyStore
        )
        synthDispatcher = SynthDispatcher(
            context = this,
            preferences = preferences,
            ttsManager = ttsManager,
            geminiPromptService = geminiPromptService,
            sharedSynthesizer = synthesizer
        )
    }
}

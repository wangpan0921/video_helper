package com.wangpan.videohelper.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * User-configurable provider settings. API keys are stored on-device only (never bundled or sent
 * anywhere except the configured provider endpoint).
 */
data class AppSettings(
    val asrBaseUrl: String = "https://api.siliconflow.cn/v1",
    val asrApiKey: String = "",
    val asrModel: String = "FunAudioLLM/SenseVoiceSmall",
    val llmBaseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    val llmApiKey: String = "",
    val llmModel: String = "glm-4-flash",
    val language: String = "zh",
    val recordMicByDefault: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ASR_BASE_URL = stringPreferencesKey("asr_base_url")
        val ASR_API_KEY = stringPreferencesKey("asr_api_key")
        val ASR_MODEL = stringPreferencesKey("asr_model")
        val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val LLM_MODEL = stringPreferencesKey("llm_model")
        val LANGUAGE = stringPreferencesKey("language")
        val RECORD_MIC_DEFAULT = booleanPreferencesKey("record_mic_default")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            asrBaseUrl = p[Keys.ASR_BASE_URL] ?: defaults.asrBaseUrl,
            asrApiKey = p[Keys.ASR_API_KEY] ?: defaults.asrApiKey,
            asrModel = p[Keys.ASR_MODEL] ?: defaults.asrModel,
            llmBaseUrl = p[Keys.LLM_BASE_URL] ?: defaults.llmBaseUrl,
            llmApiKey = p[Keys.LLM_API_KEY] ?: defaults.llmApiKey,
            llmModel = p[Keys.LLM_MODEL] ?: defaults.llmModel,
            language = p[Keys.LANGUAGE] ?: defaults.language,
            recordMicByDefault = p[Keys.RECORD_MIC_DEFAULT] ?: defaults.recordMicByDefault
        )
    }

    suspend fun save(settings: AppSettings) {
        context.dataStore.edit { p ->
            p[Keys.ASR_BASE_URL] = settings.asrBaseUrl.trim()
            p[Keys.ASR_API_KEY] = settings.asrApiKey.trim()
            p[Keys.ASR_MODEL] = settings.asrModel.trim()
            p[Keys.LLM_BASE_URL] = settings.llmBaseUrl.trim()
            p[Keys.LLM_API_KEY] = settings.llmApiKey.trim()
            p[Keys.LLM_MODEL] = settings.llmModel.trim()
            p[Keys.LANGUAGE] = settings.language.trim()
            p[Keys.RECORD_MIC_DEFAULT] = settings.recordMicByDefault
        }
    }
}

package com.example.network

import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiApiClient {
    private const val TAG = "GeminiApiClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun recognizeText(base64Image: String): String = withContext(Dispatchers.IO) {
        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            System.getenv("GEMINI_API_KEY") ?: ""
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.e(TAG, "Gemini API Key is blank or placeholder!")
            return@withContext "Ошибка: API-ключ Gemini не настроен. Настройте его в Settings > Secrets."
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

        try {
            // Build requested payload matching Gemini API requirements
            val rootJson = JSONObject()
            val contentsArray = JSONArray()
            val contentObj = JSONObject()
            val partsArray = JSONArray()

            // Prompt part
            val promptPart = JSONObject()
            promptPart.put("text", "Распознай и извлеки весь текст из этого документа. Верни только распознанный текст документа, сохранив исходное форматирование, абзацы, списки и таблицы максимально точно. Не пиши никаких своих комментариев, приветствий или вводных фраз!")
            partsArray.put(promptPart)

            // Image part
            val imagePart = JSONObject()
            val inlineData = JSONObject()
            inlineData.put("mimeType", "image/jpeg")
            inlineData.put("data", base64Image)
            imagePart.put("inlineData", inlineData)
            partsArray.put(imagePart)

            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
            rootJson.put("contents", contentsArray)

            val requestBody = rootJson.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "aistudio-build")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful || responseBody == null) {
                Log.e(TAG, "Request failed code: ${response.code}, body: $responseBody")
                return@withContext "Ошибка распознавания текста: Сервер вернул код ${response.code}"
            }

            // Extract text from GenerateContentResponse
            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                if (content != null) {
                    val parts = content.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val firstPart = parts.getJSONObject(0)
                        val text = firstPart.optString("text")
                        if (!text.isNullOrBlank()) {
                            return@withContext text
                        }
                    }
                }
            }
            return@withContext "Текст не обнаружен на изображении."
        } catch (e: Exception) {
            Log.e(TAG, "Error matching Gemini API response", e)
            return@withContext "Произошла ошибка при обращении к OCR: ${e.message}"
        }
    }
}

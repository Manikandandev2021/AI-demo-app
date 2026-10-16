package com.mani.aidemo

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val text: String, val fromUser: Boolean, val failed: Boolean = false)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val cooldownSeconds: Int = 0   // > 0 while waiting after hitting the free-tier rate limit
)

// Copy the latest stable Flash model name from the Firebase AI Logic "Supported models" page.
private const val MODEL_NAME = "gemini-3.6-flash"

class ChatViewModel : ViewModel() {

    // No API key in the app: Firebase AI Logic + App Check handle access securely.
    // Limited-use App Check tokens enable replay protection (each token is used once).
    private val model = Firebase.ai(
        backend = GenerativeBackend.googleAI(),
        useLimitedUseAppCheckTokens = true
    ).generativeModel(MODEL_NAME)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun send(prompt: String) {
        if (prompt.isBlank() || _uiState.value.isLoading || _uiState.value.cooldownSeconds > 0) return
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage(prompt, true), isLoading = true, error = null)
        }
        viewModelScope.launch {
            try {
                val response = withRetry { model.generateContent(prompt) }
                val reply = response.text ?: "No response received."
                Log.d("ChatVM", "Reply received: ${reply.take(100)}")
                _uiState.update {
                    it.copy(messages = it.messages + ChatMessage(reply, false), isLoading = false)
                }
            } catch (e: Exception) {
                Log.e("ChatVM", "Request failed", e)
                markLastUserMessageFailed()
                if (isRateLimited(e)) {
                    val wait = parseRetrySeconds(e)
                    if (wait <= 120) {
                        // Short per-minute limit: count down, then allow sending again.
                        startCooldown(wait)
                    } else {
                        // Daily limit: no point counting down for hours.
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Daily free limit reached for $MODEL_NAME. Resets in about ${formatWait(wait)}. Try another model."
                            )
                        }
                    }
                    return@launch
                }
                val message = if (isOverloaded(e)) {
                    "The AI service is busy right now. Please try again in a moment."
                } else {
                    e.localizedMessage ?: "Something went wrong"
                }
                _uiState.update { it.copy(isLoading = false, error = message) }
            }
        }
    }

    // Retries temporary server errors (high demand / overloaded) with exponential backoff: 1s, 2s, 4s.
    private suspend fun <T> withRetry(maxAttempts: Int = 3, block: suspend () -> T): T {
        var delayMs = 1000L
        repeat(maxAttempts - 1) {
            try {
                return block()
            } catch (e: Exception) {
                if (!isOverloaded(e)) throw e
                delay(delayMs)
                delayMs *= 2
            }
        }
        return block() // last attempt: let any error reach the caller
    }

    // Shows "Not delivered" under the user's message so failed sends don't look like silent ones.
    private fun markLastUserMessageFailed() {
        _uiState.update { state ->
            val idx = state.messages.indexOfLast { it.fromUser }
            if (idx < 0) state
            else state.copy(messages = state.messages.toMutableList().also {
                it[idx] = it[idx].copy(failed = true)
            })
        }
    }

    // 429 / quota exceeded: retrying immediately would only waste more quota, so wait instead.
    private fun isRateLimited(e: Exception): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return "quota" in msg || "429" in msg || "rate limit" in msg || "resource_exhausted" in msg
    }

    // Reads the wait time from messages like "retry in 7.6s" or "retry in 4h58m36.29s".
    // Returns total seconds, or 60 if the message has no wait time.
    private fun parseRetrySeconds(e: Exception): Int {
        val text = Regex("""retry in ([0-9hms.]+)""").find(e.message.orEmpty())?.groupValues?.get(1)
            ?: return 60
        var total = 0.0
        Regex("""([0-9.]+)([hms])""").findAll(text).forEach { m ->
            val v = m.groupValues[1].toDoubleOrNull() ?: 0.0
            total += when (m.groupValues[2]) { "h" -> v * 3600; "m" -> v * 60; else -> v }
        }
        return if (total > 0) kotlin.math.ceil(total).toInt() + 1 else 60
    }

    private fun formatWait(seconds: Int): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun startCooldown(seconds: Int) {
        viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        cooldownSeconds = remaining,
                        error = "Free usage limit reached. You can send again in $remaining s."
                    )
                }
                delay(1000)
            }
            _uiState.update { it.copy(cooldownSeconds = 0, error = null) }
        }
    }

    private fun isOverloaded(e: Exception): Boolean {
        val msg = e.message.orEmpty().lowercase()
        return "high demand" in msg || "overloaded" in msg || "503" in msg || "unavailable" in msg
    }
}

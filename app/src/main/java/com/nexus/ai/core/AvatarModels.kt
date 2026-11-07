package com.nexus.ai.core

enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING }

enum class Mood { NEUTRAL, HAPPY, SAD, SURPRISED }

object MoodDetector {
    private val happy = listOf("haha", "😂", "😊", "great", "badhiya", "mubarak", "congrat", "shandar", "bahut accha", "बधाई", "बढ़िया", "शानदार", "wow")
    private val sad = listOf("sorry", "maaf", "dukh", "afsos", "sad", "सॉरी", "माफ", "दुख", "अफसोस", "unfortunately")
    private val surprised = listOf("kya baat", "arre", "अरे", "really?", "sach mein", "सच में")

    fun fromReply(text: String): Mood {
        val t = text.lowercase()
        return when {
            sad.any { t.contains(it) } -> Mood.SAD
            surprised.any { t.contains(it) } -> Mood.SURPRISED
            happy.any { t.contains(it) } -> Mood.HAPPY
            else -> Mood.NEUTRAL
        }
    }
}

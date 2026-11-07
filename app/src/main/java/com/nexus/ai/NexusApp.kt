package com.nexus.ai

import android.app.Application
import com.nexus.ai.core.Assistant

class NexusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Assistant.init(this)
    }
}

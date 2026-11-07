package com.nexus.ai.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Only does something when the user asks NEXUS to (read screen, press back/home, tap a labelled button, scroll, type).
 * It never records anything in the background and skips password fields.
 */
class NexusAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: NexusAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        walk(root, sb)
        return sb.toString().take(7000)
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || sb.length > 7000) return
        if (n.isVisibleToUser && !n.isPassword) {
            val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) sb.append(if (n.isClickable) "[button] " else "").append(t).append('\n')
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), sb)
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    /** Taps the first clickable element whose text or description matches one of [labels]. */
    fun clickText(labels: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        for (label in labels) {
            val found = root.findAccessibilityNodeInfosByText(label) ?: continue
            val sorted = found.sortedByDescending { (it.text ?: it.contentDescription)?.toString().equals(label, true) }
            for (node in sorted) {
                var cur: AccessibilityNodeInfo? = node
                var depth = 0
                while (cur != null && depth < 6) {
                    if (cur.isClickable && cur.isEnabled) {
                        if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    }
                    cur = cur.parent
                    depth++
                }
            }
        }
        return false
    }

    /** Retries because the target app needs a moment to open. */
    fun clickWithRetry(labels: List<String>, attempts: Int, delayMs: Long, onDone: (Boolean) -> Unit) {
        var left = attempts
        val task = object : Runnable {
            override fun run() {
                if (clickText(labels)) { onDone(true); return }
                left--
                if (left <= 0) onDone(false) else main.postDelayed(this, delayMs)
            }
        }
        main.postDelayed(task, delayMs)
    }

    fun scroll(down: Boolean): Boolean {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val from = m.heightPixels * (if (down) 0.72f else 0.3f)
        val to = m.heightPixels * (if (down) 0.3f else 0.72f)
        val p = Path().apply { moveTo(x, from); lineTo(x, to) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build()
        return dispatchGesture(g, null, null)
    }

    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}

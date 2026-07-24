package ai.voiceflow.voiceflow_mobile

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts transcribed text directly into the focused editable field of
 * whatever app the user is in. Purely write-only: it never reads or reports
 * screen content anywhere.
 */
class InsertionService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: InsertionService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Insertion is on-demand from OverlayService; no event processing.
    }

    override fun onInterrupt() {}

    /**
     * Appends [text] to the focused editable field. Returns false when no
     * editable field has input focus (caller falls back to clipboard).
     */
    fun insertIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable) return false

        val showingHint =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val existing = if (showingHint) "" else node.text?.toString().orEmpty()
        val sep = when {
            existing.isEmpty() -> ""
            existing.endsWith(" ") || existing.endsWith("\n") -> ""
            else -> " "
        }
        val combined = existing + sep + text

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                combined
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) {
            val sel = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, combined.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, combined.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        }
        return ok
    }
}

package ai.voiceflow.voiceflow_mobile

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts transcribed text directly into the focused editable field of
 * whatever app the user is in. The target field is read locally to preserve
 * its text and selection; that content is never sent to a transcription provider.
 */
class InsertionService : AccessibilityService() {

    data class Target(val identity: AccessibilityNodeInfo,
        val packageName: String, val windowId: Int, val viewId: String?,
        val text: String, val selectionStart: Int, val selectionEnd: Int)

    fun captureTarget(): Target? {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return targetFrom(node)
    }

    private fun targetFrom(node: AccessibilityNodeInfo): Target? {
        if (!node.isEditable || node.isPassword) return null
        val hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        return Target(node, node.packageName?.toString() ?: return null, node.windowId, node.viewIdResourceName,
            if (hint) "" else node.text?.toString().orEmpty(), node.textSelectionStart, node.textSelectionEnd)
    }

    /** Only insert if the original field and its contents/selection are still current. */
    fun insertIntoTarget(target: Target, text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.refresh()) return false
        // AccessibilityNodeInfo.equals compares source-node identity and window ID.
        // Text and resource ID alone cannot distinguish two empty fields in one app.
        val current = targetFrom(node) ?: return false
        if (current != target || !node.isFocused) return false
        val start = target.selectionStart.takeIf { it in 0..target.text.length } ?: target.text.length
        val end = target.selectionEnd.takeIf { it in start..target.text.length } ?: start
        val before = target.text.substring(0, start)
        val after = target.text.substring(end)
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val suffix = if (after.isNotEmpty() && !after.first().isWhitespace() && after.first().isLetterOrDigit()) " " else ""
        val combined = before + prefix + text + suffix + after
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        })
        if (ok) node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            val cursor = before.length + prefix.length + text.length
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        return ok
    }

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

}

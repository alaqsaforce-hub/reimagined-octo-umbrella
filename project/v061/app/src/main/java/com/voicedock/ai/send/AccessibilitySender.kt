package com.voicedock.ai.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.voicedock.ai.agent.AgentSafety
import com.voicedock.ai.insertion.InsertAccessibilityService
import com.voicedock.ai.security.SecretDetector

/**
 * v1.3.3 — the node-tree half of [SendCore]: type into the open chat and press the app's own send button.
 *
 * Everything here is best-effort by nature (chat apps differ, and this is not a supported API), so the rule is:
 * **verify before claiming.** [SendResult.SENT] is only returned when the composer really became empty after the
 * click. Any other outcome keeps the text where the user can still send it by hand and says so.
 */
object AccessibilitySender {

    /** Longest text this route will type; a spoken message is short, and a wall of text is a mistake. */
    private const val MAX_LEN = 1_000

    fun available(): Boolean = InsertAccessibilityService.instance != null

    fun send(ctx: Context, text: String, expectedPackage: String? = null, targetLabel: String = ""): SendResult {
        val svc = InsertAccessibilityService.instance ?: return SendResult.NO_SERVICE
        val body = text.trim()
        if (body.isBlank()) return SendResult.REJECTED
        if (body.length > MAX_LEN) return SendResult.REJECTED
        return svc.sendInOpenChat(body, expectedPackage, ctx)
    }

    /** Exposed for diagnostics: what the phone currently sees (never sent anywhere). */
    fun screenSummary(svc: InsertAccessibilityService): SendScreen {
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
            ?: return SendScreen(pkg = "")
        val pkg = root.packageName?.toString().orEmpty()
        var field = false
        val labels = mutableListOf<String>()
        walk(root) { n ->
            if (!field && n.isEditable && !n.isPassword) {
                val hint = n.hintText?.toString() ?: n.contentDescription?.toString()
                if (SendCore.looksLikeMessageField(hint, n.viewIdResourceName)) field = true
            }
            if (n.isClickable) {
                val l = n.contentDescription?.toString() ?: n.text?.toString()
                if (SendCore.isSendLabel(l)) labels += l.orEmpty()
            }
        }
        return SendScreen(pkg = pkg, hasMessageField = field, sendLabels = labels.distinct())
    }

    internal fun walk(node: AccessibilityNodeInfo?, depth: Int = 0, visit: (AccessibilityNodeInfo) -> Unit) {
        if (node == null || depth > 24) return
        visit(node)
        for (i in 0 until runCatching { node.childCount }.getOrDefault(0)) {
            walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1, visit)
        }
    }

    /** The shared, careful implementation used by the service (also reachable from tests' fakes). */
    fun perform(
        svc: InsertAccessibilityService,
        text: String,
        expectedPackage: String?,
        screen: SendScreen,
        setText: (AccessibilityNodeInfo, String) -> Boolean = { node, value ->
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        },
        click: (AccessibilityNodeInfo) -> Boolean = { it.performAction(AccessibilityNodeInfo.ACTION_CLICK) },
    ): SendResult {
        when (val p = SendCore.plan(screen, expectedPackage)) {
            is SendPlan.Refuse -> return p.result
            SendPlan.Proceed -> Unit
        }
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return SendResult.NO_FIELD
        var field: AccessibilityNodeInfo? = null
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { n ->
            if (field == null && n.isEditable && !n.isPassword) {
                val hint = n.hintText?.toString() ?: n.contentDescription?.toString()
                if (SendCore.looksLikeMessageField(hint, n.viewIdResourceName)) field = n
            }
            if (n.isClickable) {
                val l = n.contentDescription?.toString() ?: n.text?.toString()
                if (SendCore.isSendLabel(l)) buttons += n
            }
        }
        val input = field ?: return SendResult.NO_FIELD
        if (input.isPassword) return SendResult.SECURE_FIELD
        val label = runCatching { input.packageName?.toString().orEmpty() }.getOrDefault("")
        if (AgentSafety.isBlockedPackage(label, emptySet()) || AgentSafety.isFinanceLabel(screen.appLabel)) {
            return SendResult.SECURE_FIELD
        }
        if (SecretDetector.isSecret(text, listOfNotNull(input.hintText?.toString(), input.viewIdResourceName).joinToString(" "))) {
            return SendResult.SECURE_FIELD
        }
        val button = buttons.firstOrNull() ?: return SendResult.NO_SEND_BUTTON

        // Type it: SET_TEXT first (most composers), then paste for the ones that ignore it.
        if (!setText(input, text)) {
            (svc.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText("VoiceDock", text))
            if (!input.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return SendResult.REJECTED
        }
        runCatching { input.refresh() }
        val typed = runCatching { input.text?.toString().orEmpty() }.getOrDefault("")
        if (typed.isBlank()) return SendResult.REJECTED

        if (!click(button)) return SendResult.NO_SEND_BUTTON
        // Verify: a real send clears the composer. Anything else is reported as "did not accept".
        runCatching { input.refresh() }
        val after = runCatching { input.text?.toString().orEmpty() }.getOrDefault("")
        return if (after.isBlank()) SendResult.SENT else SendResult.REJECTED
    }
}

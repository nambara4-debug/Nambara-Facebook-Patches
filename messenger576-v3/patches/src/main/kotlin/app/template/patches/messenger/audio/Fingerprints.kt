package app.template.patches.messenger.audio

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * Messenger 576.0.0.47.92
 *
 * Anchors the existing "Forward" context-menu item. This item is known to be present
 * for voice/audio messages in this exact build. V2 temporarily repurposes Forward
 * for AudioAttachmentData only; forwarding remains unchanged for all other messages.
 */
internal val ForwardMenuItemFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/facebook/messaging/dialog/MenuDialogItem;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/os/Parcelable;",
        "Lcom/facebook/messaging/model/messages/Message;",
        "Ljava/lang/String;",
    ),
    strings = listOf("forward_tap"),
)

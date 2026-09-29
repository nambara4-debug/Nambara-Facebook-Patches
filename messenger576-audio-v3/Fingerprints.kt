package app.template.patches.messenger.audio

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal val SaveVideoMenuItemFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Lcom/facebook/messaging/dialog/MenuDialogItem;",
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/os/Parcelable;",
        "Lcom/facebook/messaging/model/messages/Message;",
        "Ljava/lang/String;",
    ),
    strings = listOf("save_video"),
)

package app.template.patches.messenger.audio

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.template.patches.shared.Constants.MESSENGER_COMPATIBILITY
import app.template.patches.messenger.misc.messengerSignaturePatch

private const val AUDIO_ATTACHMENT =
    "Lcom/facebook/messaging/attachments/AudioAttachmentData;"
private const val MENU_DIALOG_ITEM =
    "Lcom/facebook/messaging/dialog/MenuDialogItem;"
private const val FB_USER_SESSION =
    "Lcom/facebook/auth/usersession/FbUserSession;"
private const val HELPER =
    "Lapp/template/extension/extension/MessengerAudioDownloadHelper;"

private const val AUDIO_DOWNLOADER_PROVIDER_ID = 0x28050

/**
 * V3: repurposes the existing Forward menu item ONLY for audio messages.
 *
 * Why: in Messenger 576 the Save Video plugin is not admitted into the menu for
 * AudioAttachmentData before its own eligibility callback is consulted, so V1 never
 * became visible. Forward is already present for voice messages, making it a reliable
 * insertion point.
 *
 * Behaviour:
 *  - AudioAttachmentData: label becomes "Descarregar áudio" and click downloads audio.
 *  - Any other attachment/message: original Forward behaviour is untouched.
 *
 * Target: com.facebook.orca 576.0.0.47.92 arm64-v8a, versionCode 345212670.
 */
@Suppress("unused")
val messengerDownloadAudioPatch = bytecodePatch(
    name = "Download audio messages V3 (576 arm64)",
    description = "For voice messages, replaces Forward with 'Descarregar áudio' and saves the audio to Downloads.",
) {
    compatibleWith(MESSENGER_COMPATIBILITY)
    dependsOn(messengerSignaturePatch)
    extendWith("extensions/extension.mpe")

    execute {
        val buildMenuMethod = ForwardMenuItemFingerprint.method
        val pluginClassType = ForwardMenuItemFingerprint.classDef.type
        val pluginClass = mutableClassDefBy(pluginClassType)

        val sessionField = pluginClass.fields
            .singleOrNull { it.type == FB_USER_SESSION }
            ?: throw PatchException(
                "Forward plugin: FbUserSession field not found in $pluginClassType"
            )

        val clickMethod = pluginClass.methods.firstOrNull { method ->
            method.returnType == "Z" &&
                method.parameterTypes.size == 10 &&
                method.parameterTypes[0] == "Landroid/content/Context;" &&
                method.parameterTypes[1] == "Landroid/view/View;" &&
                method.parameterTypes[3] == MENU_DIALOG_ITEM &&
                method.parameterTypes[4] == "Lcom/facebook/messaging/model/messages/Message;" &&
                method.parameterTypes.last() == "Z"
        } ?: throw PatchException(
            "Forward plugin click method not found in $pluginClassType"
        )

        // Builder ALN(Context, Parcelable, Message, String) -> MenuDialogItem
        // p2 is the attachment. For voice/audio only, overwrite the generated title.
        // The forward builder contains the stable string "forward_tap". After that
        // point the Lrp builder lives in v2, matching this Messenger 576 build.
        val labelInsertIndex = ForwardMenuItemFingerprint.stringMatches.first().index + 2
        buildMenuMethod.addInstructions(
            labelInsertIndex,
            """
                instance-of v0, p2, $AUDIO_ATTACHMENT
                if-eqz v0, :morphe_audio_label_done
                iput-object p2, v2, LX/Lrp;->A05:Landroid/os/Parcelable;
                const-string v0, "Descarregar áudio"
                iput-object v0, v2, LX/Lrp;->A06:Ljava/lang/CharSequence;
                const/4 v0, 0x0
                iput v0, v2, LX/Lrp;->A04:I
                :morphe_audio_label_done
                nop
            """.trimIndent(),
        )

        // CTh(..., MenuDialogItem, Message, ...) — p4 is MenuDialogItem.
        // Intercept only when its payload A05 is AudioAttachmentData.
        clickMethod.addInstructions(
            0,
            """
                iget-object v0, p4, $MENU_DIALOG_ITEM->A05:Landroid/os/Parcelable;
                instance-of v1, v0, $AUDIO_ATTACHMENT
                if-eqz v1, :morphe_audio_click_original

                check-cast v0, $AUDIO_ATTACHMENT
                iget-object v1, v0, $AUDIO_ATTACHMENT->A01:Landroid/net/Uri;
                if-eqz v1, :morphe_audio_missing_uri

                const v2, $AUDIO_DOWNLOADER_PROVIDER_ID
                invoke-static {v2}, LX/2oy;->A00(I)LX/2oz;
                move-result-object v2
                invoke-static {v2}, LX/2oz;->A0B(LX/2oz;)Ljava/lang/Object;
                move-result-object v2
                check-cast v2, LX/Q51;

                const/4 v3, 0x1
                new-instance v4, LX/Ahr;
                invoke-direct {v4, v3, v1}, LX/Ahr;-><init>(ZLandroid/net/Uri;)V

                iget-object v5, p0, $pluginClassType->${sessionField.name}:$FB_USER_SESSION
                invoke-virtual {v2, v5, v4}, LX/Q51;->A01($FB_USER_SESSION;LX/Ahr;)LX/1FU;
                move-result-object v2

                invoke-static {p1, v2}, $HELPER->saveFuture(Landroid/content/Context;Ljava/lang/Object;)V
                const/4 v0, 0x1
                return v0

                :morphe_audio_missing_uri
                invoke-static {p1}, $HELPER->showMissingAudio(Landroid/content/Context;)V
                const/4 v0, 0x1
                return v0

                :morphe_audio_click_original
                nop
            """.trimIndent(),
        )
    }
}

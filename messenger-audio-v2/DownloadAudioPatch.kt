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

@Suppress("unused")
val messengerDownloadAudioPatch = bytecodePatch(
    name = "Download audio messages",
    description = "Adds a 'Descarregar áudio' action to audio messages and saves them to Downloads.",
) {
    compatibleWith(MESSENGER_COMPATIBILITY)
    dependsOn(messengerSignaturePatch)
    extendWith("extensions/extension.mpe")

    execute {
        val buildMenuMethod = SaveVideoMenuItemFingerprint.method
        val pluginClassType = SaveVideoMenuItemFingerprint.classDef.type
        val pluginClass = mutableClassDefBy(pluginClassType)

        val sessionField = pluginClass.fields
            .singleOrNull { it.type == FB_USER_SESSION }
            ?: throw PatchException(
                "Save-video plugin: FbUserSession field not found in $pluginClassType"
            )

        val eligibilityMethod = pluginClass.methods.firstOrNull { method ->
            method.returnType == "Z" &&
                method.parameterTypes.size == 6 &&
                method.parameterTypes[0] == "Landroid/content/Context;" &&
                method.parameterTypes[1] == "Landroid/os/Parcelable;" &&
                method.parameterTypes[2] == "Lcom/facebook/messaging/model/messages/Message;" &&
                method.parameterTypes[3] == "Lcom/facebook/messaging/model/threads/ThreadSummary;" &&
                method.parameterTypes[4] == "Lcom/facebook/xapp/messaging/capability/vector/Capabilities;" &&
                method.parameterTypes[5] == "Z"
        } ?: throw PatchException(
            "Save-video plugin eligibility method not found in $pluginClassType"
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
            "Save-video plugin click method not found in $pluginClassType"
        )

        eligibilityMethod.addInstructions(
            0,
            """
                iget-object v0, p3, Lcom/facebook/messaging/model/messages/Message;->A1B:Lcom/google/common/collect/ImmutableList;
                if-eqz v0, :morphe_audio_eligibility_original
                invoke-virtual {v0}, Ljava/util/AbstractCollection;->isEmpty()Z
                move-result p1
                if-nez p1, :morphe_audio_eligibility_original
                const/4 p1, 0x0
                invoke-interface {v0, p1}, Ljava/util/List;->get(I)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/facebook/messaging/model/attachment/Attachment;
                iget-object v0, v0, Lcom/facebook/messaging/model/attachment/Attachment;->A05:Lcom/facebook/messaging/model/attachment/AudioData;
                if-eqz v0, :morphe_audio_eligibility_original
                const/4 v0, 0x1
                return v0
                :morphe_audio_eligibility_original
                nop
            """.trimIndent(),
        )

        val labelInsertIndex = SaveVideoMenuItemFingerprint.stringMatches.first().index + 2
        buildMenuMethod.addInstructions(
            labelInsertIndex,
            """
                iget-object v0, p3, Lcom/facebook/messaging/model/messages/Message;->A1B:Lcom/google/common/collect/ImmutableList;
                if-eqz v0, :morphe_audio_label_done
                invoke-virtual {v0}, Ljava/util/AbstractCollection;->isEmpty()Z
                move-result p1
                if-nez p1, :morphe_audio_label_done
                const/4 p1, 0x0
                invoke-interface {v0, p1}, Ljava/util/List;->get(I)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/facebook/messaging/model/attachment/Attachment;
                iget-object v0, v0, Lcom/facebook/messaging/model/attachment/Attachment;->A05:Lcom/facebook/messaging/model/attachment/AudioData;
                if-eqz v0, :morphe_audio_label_done
                const-string v0, "Descarregar áudio"
                iput-object v0, v1, LX/Lrp;->A06:Ljava/lang/CharSequence;
                const/4 v0, 0x0
                iput v0, v1, LX/Lrp;->A04:I
                :morphe_audio_label_done
                nop
            """.trimIndent(),
        )

        clickMethod.addInstructions(
            0,
            """
                iget-object v0, p5, Lcom/facebook/messaging/model/messages/Message;->A1B:Lcom/google/common/collect/ImmutableList;
                if-eqz v0, :morphe_audio_click_original
                invoke-virtual {v0}, Ljava/util/AbstractCollection;->isEmpty()Z
                move-result v1
                if-nez v1, :morphe_audio_click_original
                const/4 v1, 0x0
                invoke-interface {v0, v1}, Ljava/util/List;->get(I)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Lcom/facebook/messaging/model/attachment/Attachment;
                iget-object v0, v0, Lcom/facebook/messaging/model/attachment/Attachment;->A05:Lcom/facebook/messaging/model/attachment/AudioData;
                if-eqz v0, :morphe_audio_click_original

                iget-object v1, v0, Lcom/facebook/messaging/model/attachment/AudioData;->A02:Landroid/net/Uri;
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

package com.example.trashcandetector.client.mixin;

import com.example.trashcandetector.client.TrashCanDetectorClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 ChatScreen，拦截 #pick 输入：
 * - Enter 键：触发自动打开垃圾桶并导出，消息不发送到服务器
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Shadow
    protected TextFieldWidget chatField;

    /**
     * 拦截按键：Enter 触发拾取（不发送到服务器）
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfo ci) {
        String text = this.chatField.getText();
        if (!text.startsWith("#pick")) return;

        // Enter 键：执行拾取，阻止消息发送到服务器
        if (keyCode == GLFW.GLFW_KEY_ENTER) {
            String argument = text.length() > 5 ? text.substring(5).trim() : "";
            try {
                TrashCanDetectorClient.triggerAutoPick(argument);
            } catch (Exception e) {
                TrashCanDetectorClient.LOGGER.error("执行 #pick 失败", e);
            }
            ((ChatScreen) (Object) this).close();
            ci.cancel();
        }
    }
}

package com.example.trashcandetector.client.mixin;

import com.example.trashcandetector.client.TrashCanDetectorClient;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.server.command.CommandSource;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 ChatScreen，拦截 #pick 输入：
 * - Tab 键：弹出物品名称补全建议
 * - Enter 键：触发自动打开垃圾桶并导出，消息不发送到服务器
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Shadow
    protected TextFieldWidget chatField;

    @Shadow
    private ChatInputSuggestor chatInputSuggestor;

    @Unique
    private String lastPickInput = "";

    /**
     * 每帧刷新 #pick 的 Tab 补全建议
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        String text = this.chatField.getText();
        if (!text.startsWith("#pick")) {
            if (!this.lastPickInput.isEmpty()) {
                this.lastPickInput = "";
            }
            return;
        }

        // 文本未变化，跳过
        if (text.equals(this.lastPickInput)) return;
        this.lastPickInput = text;

        // 解析参数并生成建议
        String args = text.length() > 5 ? text.substring(5) : "";
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        LiteralCommandNode<Object> node = dispatcher.register(
            com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("#pick")
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                    .argument("item_name", StringArgumentType.greedyString())
                    .suggests(CommandSource.suggestMatching(
                        TrashCanDetectorClient::getItemIdSuggestions
                    ))
                )
        );

        this.chatInputSuggestor.showSuggestions(
            dispatcher.parse(args, node)
        );
    }

    /**
     * 拦截按键：Tab 弹出建议，Enter 触发拾取（不发送到服务器）
     */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfo ci) {
        String text = this.chatField.getText();
        if (!text.startsWith("#pick")) return;

        // Tab 键：弹出建议，阻止原版焦点切换
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            String args = text.length() > 5 ? text.substring(5).trim() : "";
            CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
            dispatcher.register(
                com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("#pick")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                        .argument("item_name", StringArgumentType.greedyString())
                        .suggests(CommandSource.suggestMatching(
                            TrashCanDetectorClient::getItemIdSuggestions
                        ))
                    )
            );
            this.chatInputSuggestor.showSuggestions(dispatcher.parse(args));
            ci.cancel();
            return;
        }

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

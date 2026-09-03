package com.example.trashcandetector.client.mixin;

import com.example.trashcandetector.client.PickCommandHandler;
import com.example.trashcandetector.client.TrashCanDetectorClient;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.network.ClientCommandSource;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.CommandTreeS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 ClientPlayNetworkHandler：
 * 服务器命令树同步（onCommandTree）后会重建客户端命令分发器，
 * 在此处把 /pick 命令树注册进去，即可让聊天栏对 //pick 提供原生 Tab 补全
 * （ChatInputSuggestor 解析时会跳过开头的第一个 '/'，所以注册字面量是 /pick）。
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Shadow
    private CommandDispatcher<ClientCommandSource> commandDispatcher;

    @Inject(method = "onCommandTree", at = @At("TAIL"))
    private void trashcandetector$registerPickSuggestions(CommandTreeS2CPacket packet, CallbackInfo ci) {
        try {
            PickCommandHandler.registerSuggestions(this.commandDispatcher);
        } catch (Exception e) {
            TrashCanDetectorClient.LOGGER.error("注册 //pick 命令补全失败", e);
        }
    }
}

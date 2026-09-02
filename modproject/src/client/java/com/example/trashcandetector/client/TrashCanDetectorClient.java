package com.example.trashcandetector.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口点：监听服务器聊天消息，检测垃圾桶刷新提示
 */
public class TrashCanDetectorClient implements ClientModInitializer {

    public static final String MOD_ID = "trashcandetector";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("TrashCan Detector 已加载，开始监听垃圾桶刷新消息");

        ClientReceiveMessageEvents.ALLOW_GAME_MESSAGE.register((message, overlay) -> {
            if (overlay) return true;

            String text = message.getString();
            String stripped = stripPunctuation(text);

            if (containsTrashKeywords(stripped)) {
                LOGGER.info("检测到垃圾桶刷新消息: {}", text);
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    client.player.sendMessage(
                        Text.of("垃圾桶刷新啦！快来领取百亿补贴："), false
                    );
                }
            }

            return true;
        });
    }

    /**
     * 移除所有中英文标点符号，防止标点差异导致识别失败
     */
    private static String stripPunctuation(String text) {
        // 中文标点 + 英文标点 + Unicode标点
        return text.replaceAll("[\\p{P}\\p{S}\\s]", "");
    }

    /**
     * 检测是否包含垃圾桶刷新关键词（标点已预先移除）
     */
    private static boolean containsTrashKeywords(String text) {
        return text.contains("物品被意外清理")
            && text.contains("公共垃圾桶")
            && text.contains("领回");
    }
}

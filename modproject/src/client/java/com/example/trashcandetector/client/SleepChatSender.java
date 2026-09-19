package com.example.trashcandetector.client;

import net.minecraft.client.MinecraftClient;

/** Sends chat messages at a configured interval while the local player sleeps. */
public final class SleepChatSender {

    private static int ticksUntilNextMessage;

    private SleepChatSender() {
    }

    public static void tick(MinecraftClient client) {
        if (client.player == null || client.world == null || client.getNetworkHandler() == null) {
            reset();
            return;
        }

        if (!client.player.isSleeping()
            || !TrashCanDetectorConfigs.AUTO_SEND_ZZZ_WHILE_SLEEPING.getBooleanValue()) {
            reset();
            return;
        }

        if (ticksUntilNextMessage > 0) {
            ticksUntilNextMessage--;
            if (ticksUntilNextMessage > 0) {
                return;
            }
        }

        client.getNetworkHandler().sendChatMessage("zzz");
        ticksUntilNextMessage = TrashCanDetectorConfigs.sleepZzzIntervalTicks();
    }

    private static void reset() {
        ticksUntilNextMessage = 0;
    }
}

package com.dayssky.mma.features;

import com.dayssky.mma.MMAClient;
import com.dayssky.mma.util.ChatUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

public class Keybinds {
    private static final KeyMapping keyBindingMeow = new KeyMapping("key.mma.meow", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "category.mma");
    private static final KeyMapping keyBindingPS = new KeyMapping("key.mma.playerstats", InputConstants.Type.MOUSE, InputConstants.UNKNOWN.getValue(), "category.mma");
    private static final KeyMapping togglePlayerHpIndicator = new KeyMapping("key.mma.togglePlayerHpIndicator", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "category.mma");
    private static final KeyMapping removeWaypointKey = new KeyMapping("key.mma.removeWaypoint", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "category.mma");

    private static long meowMsNext = System.currentTimeMillis();

    public static void init() {
        KeyBindingHelper.registerKeyBinding(keyBindingMeow);
        KeyBindingHelper.registerKeyBinding(keyBindingPS);
        KeyBindingHelper.registerKeyBinding(togglePlayerHpIndicator);
        KeyBindingHelper.registerKeyBinding(removeWaypointKey);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        if (keyBindingMeow.consumeClick()) {
            onPressedMeow();
        }
        if (keyBindingPS.consumeClick()) {
            onPressedPS();
        }
        if (togglePlayerHpIndicator.consumeClick()) {
            boolean value = MMAClient.config().hpIndicator.enableGlowingPlayer = !MMAClient.config().hpIndicator.enableGlowingPlayer;
            ChatUtil.send(Component.literal("player HP glowing: " + (value ? "enabled" : "disabled")));
        }

        if (removeWaypointKey.consumeClick()) {
            MMAClient.WAYPOINT.handleRemoveWaypoint();
        }
    }

    private static void onPressedMeow() {
        long current = System.currentTimeMillis();
        if (meowMsNext <= current) {
            meowMsNext = current + 3000L;
            ChatUtil.sendCommand(String.format("chat say %s %s",
                    MMAClient.config().chat.meowingChannel,
                    MMAClient.config().chat.meowingText));
        }
    }

    private static void onPressedPS() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.crosshairPickEntity == null) {
            mc.gameRenderer.pick(0.0F);
        }
        if (mc.crosshairPickEntity instanceof Player player) {
            ChatUtil.sendCommand("ps " + player.getScoreboardName());
        }
    }
}
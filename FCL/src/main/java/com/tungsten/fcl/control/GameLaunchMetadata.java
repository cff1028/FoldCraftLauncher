package com.tungsten.fcl.control;

import android.content.Intent;

public final class GameLaunchMetadata {
    private static final String CONTROLLER = "controller";
    private static final String PLAYER = "TERRACOTTA_PLAYER";
    private final String controller;
    private final String player;

    private GameLaunchMetadata(String controller, String player) {
        this.controller = controller;
        this.player = player;
    }

    public static String resolveController(Intent intent, String fallbackController) {
        String selected = intent == null ? null : intent.getStringExtra(CONTROLLER);
        return selected == null ? fallbackController : selected;
    }

    public static GameLaunchMetadata capture(Intent intent, String fallbackController) {
        return new GameLaunchMetadata(resolveController(intent, fallbackController),
                intent == null ? null : intent.getStringExtra(PLAYER));
    }

    public void applyTo(Intent destination) {
        if (controller != null) destination.putExtra(CONTROLLER, controller);
        if (player != null) destination.putExtra(PLAYER, player);
    }

    public static void copy(Intent source, Intent destination) {
        capture(source, null).applyTo(destination);
    }
}

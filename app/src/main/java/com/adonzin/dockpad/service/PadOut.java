package com.adonzin.dockpad.service;

import com.adonzin.dockpad.core.TargetEncoder;

/** Where a player's input goes: the built-in controls themselves, or a virtual controller. */
interface PadOut extends TargetEncoder.Out {
    String name();
    boolean isAlive();
    String describe();
    void close();
}

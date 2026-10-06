package com.example.synceddoors;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(SyncedDoorsMod.MODID)
public class SyncedDoorsMod {
    public static final String MODID = "synceddoors";

    public SyncedDoorsMod() {
        MinecraftForge.EVENT_BUS.register(new SyncHandler());
    }
}

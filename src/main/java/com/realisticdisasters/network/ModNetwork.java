package com.realisticdisasters.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration. Client handlers are registered separately from client-only code
 * (RegisterClientPayloadHandlersEvent) so nothing here touches client classes.
 */
public final class ModNetwork {
    public static final String VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION).optional();
        registrar.playToClient(DisasterSyncPayload.TYPE, DisasterSyncPayload.CODEC);
        registrar.playToClient(DisasterRemovePayload.TYPE, DisasterRemovePayload.CODEC);
    }

    private ModNetwork() {}
}

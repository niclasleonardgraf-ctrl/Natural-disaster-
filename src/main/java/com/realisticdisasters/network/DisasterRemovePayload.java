package com.realisticdisasters.network;

import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Tells the client a disaster ended (or left sync range). id = -1 clears everything. */
public record DisasterRemovePayload(int id) implements CustomPacketPayload {
    public static final Type<DisasterRemovePayload> TYPE = new Type<>(ModRegistries.id("disaster_remove"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DisasterRemovePayload> CODEC =
            ByteBufCodecs.INT.map(DisasterRemovePayload::new, DisasterRemovePayload::id).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

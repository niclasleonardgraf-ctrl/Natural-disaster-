package com.realisticdisasters.network;

import com.realisticdisasters.control.DisasterControl;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server requests from the Disaster Controller menu. Everything is validated server-side. */
public final class ControlPayloads {

    /** Start {@code disaster} at {@code intensity} using a {@link DisasterControl.Placement}. */
    public record Start(String disaster, int intensity, int placement) implements CustomPacketPayload {
        public static final Type<Start> TYPE = new Type<>(ModRegistries.id("control_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> CODEC = StreamCodec.composite(
                ByteBufCodecs.stringUtf8(32), Start::disaster,
                ByteBufCodecs.VAR_INT, Start::intensity,
                ByteBufCodecs.VAR_INT, Start::placement,
                Start::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Stop disaster {@code id} ({@code -1} = all in the dimension); {@code now} skips the fade-out. */
    public record Stop(int id, boolean now) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = new Type<>(ModRegistries.id("control_stop"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Stop::id,
                ByteBufCodecs.BOOL, Stop::now,
                Stop::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    static void handleStart(Start p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        DisasterType type = null;
        for (DisasterType t : DisasterType.values()) if (t.id().equals(p.disaster())) type = t;
        if (type == null) return;
        var result = DisasterControl.start(player, type, p.intensity(), DisasterControl.Placement.byId(p.placement()), true);
        player.sendOverlayMessage(result.message());
    }

    static void handleStop(Stop p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        player.sendOverlayMessage(DisasterControl.stop(player, p.id(), p.now(), true).message());
    }

    private ControlPayloads() {}
}

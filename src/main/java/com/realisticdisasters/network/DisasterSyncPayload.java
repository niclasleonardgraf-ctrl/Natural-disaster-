package com.realisticdisasters.network;

import com.realisticdisasters.registry.ModRegistries;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Full state of one disaster, sent periodically to nearby players. Small and self-contained so the client
 * can rebuild everything it renders from it.
 *
 * @param data type-specific extra values (e.g. tornado funnel radius, height, heading)
 */
public record DisasterSyncPayload(int id, int kind, int phase, float phaseProgress, float strength, int intensity,
                                  double x, double y, double z, float radius, float[] data)
        implements CustomPacketPayload {

    public static final Type<DisasterSyncPayload> TYPE = new Type<>(ModRegistries.id("disaster_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DisasterSyncPayload> CODEC = StreamCodec.of(
            DisasterSyncPayload::write, DisasterSyncPayload::read);

    private static void write(ByteBuf buf, DisasterSyncPayload p) {
        buf.writeInt(p.id);
        buf.writeByte(p.kind);
        buf.writeByte(p.phase);
        buf.writeFloat(p.phaseProgress);
        buf.writeFloat(p.strength);
        buf.writeByte(p.intensity);
        buf.writeDouble(p.x);
        buf.writeDouble(p.y);
        buf.writeDouble(p.z);
        buf.writeFloat(p.radius);
        buf.writeByte(p.data.length);
        for (float f : p.data) buf.writeFloat(f);
    }

    private static DisasterSyncPayload read(ByteBuf buf) {
        int id = buf.readInt();
        int type = buf.readByte();
        int phase = buf.readByte();
        float progress = buf.readFloat();
        float strength = buf.readFloat();
        int intensity = buf.readByte();
        double x = buf.readDouble(), y = buf.readDouble(), z = buf.readDouble();
        float radius = buf.readFloat();
        int n = Math.min(buf.readUnsignedByte(), 32);
        float[] data = new float[n];
        for (int i = 0; i < n; i++) data[i] = buf.readFloat();
        return new DisasterSyncPayload(id, type, phase, progress, strength, intensity, x, y, z, radius, data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

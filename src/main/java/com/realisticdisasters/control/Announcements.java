package com.realisticdisasters.control;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Big on-screen warnings when a disaster forms, and a quiet notice when it has passed. */
public final class Announcements {

    public static void started(ServerLevel level, Disaster d) {
        if (!DisasterConfig.ANNOUNCEMENTS.getAsBoolean()) return;
        double range = DisasterConfig.SYNC_RANGE.getAsInt() + d.effectRadius();
        ChatFormatting color = DisasterControl.color(d.type());
        Component title = Component.translatable("announce.realisticdisasters.warning", DisasterControl.name(d.type()))
                .withStyle(color, ChatFormatting.BOLD);
        for (ServerPlayer p : level.players()) {
            double dx = d.pos().x - p.getX(), dz = d.pos().z - p.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > range) continue;
            Component where = dist < 12
                    ? Component.translatable("announce.realisticdisasters.here")
                    : Component.translatable("announce.realisticdisasters.distance", (int) Math.round(dist),
                    Component.translatable("direction.realisticdisasters." + direction(dx, dz)));
            Component subtitle = Component.translatable("announce.realisticdisasters.subtitle",
                    Component.translatable("intensity.realisticdisasters." + d.intensity()), where).withStyle(ChatFormatting.WHITE);
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
        }
    }

    public static void ended(ServerLevel level, Disaster d) {
        if (!DisasterConfig.ANNOUNCEMENTS.getAsBoolean()) return;
        double range = DisasterConfig.SYNC_RANGE.getAsInt() + d.effectRadius();
        Component msg = Component.translatable("announce.realisticdisasters.ended", DisasterControl.name(d.type()))
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(d.pos().x, p.getY(), d.pos().z) <= range * range) p.sendOverlayMessage(msg);
        }
    }

    /** 8-point compass direction from the player toward the disaster (north = -z). */
    static String direction(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0) deg += 360;
        String[] names = {"north", "north_east", "east", "south_east", "south", "south_west", "west", "north_west"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    private Announcements() {}
}

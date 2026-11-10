package com.realisticdisasters.control;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.realisticdisasters.config.DisasterConfig;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side logic behind the Disaster Controller: validates requests from the client menu (permission, item in
 * hand, cooldown, limits), picks a spawn position and starts or stops disasters. Shared by the network handlers and
 * the GameTests, so the menu never needs commands.
 */
public final class DisasterControl {
    /** Where a disaster starts, relative to the player using the controller. */
    public enum Placement {
        AHEAD, NEARBY, HERE;

        public static Placement byId(int id) {
            return values()[Math.floorMod(id, values().length)];
        }

        public String key() {
            return "placement.realisticdisasters." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final Map<UUID, Long> LAST_USE = new HashMap<>();

    /** @return feedback for the player (also used as the result in tests) */
    public static Result start(ServerPlayer player, DisasterType type, int intensity, Placement placement, boolean requireItem) {
        Result denied = check(player, requireItem);
        if (denied != null) return denied;
        if (!type.enabled()) return Result.fail(Component.translatable("control.realisticdisasters.disabled", name(type)));
        ServerLevel level = player.level();
        if (DisasterManager.activeCount(level) >= DisasterConfig.MAX_ACTIVE.getAsInt()) {
            return Result.fail(Component.translatable("control.realisticdisasters.too_many", DisasterConfig.MAX_ACTIVE.getAsInt()));
        }
        long now = level.getGameTime();
        long cooldown = DisasterConfig.DEVICE_COOLDOWN.getAsInt() * 20L;
        Long last = LAST_USE.get(player.getUUID());
        if (last != null && now - last < cooldown) {
            return Result.fail(Component.translatable("control.realisticdisasters.cooldown", (cooldown - (now - last) + 19) / 20));
        }

        Vec3 at = position(player, placement);
        int capped = Mth.clamp(intensity, 1, DisasterConfig.MAX_INTENSITY.getAsInt());
        Disaster d = DisasterManager.start(level, type, at, capped);
        LAST_USE.put(player.getUUID(), now);
        return Result.ok(Component.translatable("control.realisticdisasters.started", name(type),
                Component.translatable("intensity.realisticdisasters." + capped)), d);
    }

    /** Stops one disaster ({@code id >= 0}) or all in the player's dimension ({@code id < 0}). */
    public static Result stop(ServerPlayer player, int id, boolean now, boolean requireItem) {
        Result denied = check(player, requireItem);
        if (denied != null) return denied;
        ServerLevel level = player.level();
        if (id < 0) {
            var all = DisasterManager.active(level);
            if (all.isEmpty()) return Result.fail(Component.translatable("control.realisticdisasters.none_active"));
            all.forEach(d -> DisasterManager.stop(level, d.id(), !now));
            return Result.ok(Component.translatable("control.realisticdisasters.stopped_all", all.size()), null);
        }
        return DisasterManager.stop(level, id, !now)
                ? Result.ok(Component.translatable("control.realisticdisasters.stopped"), null)
                : Result.fail(Component.translatable("control.realisticdisasters.not_found"));
    }

    private static Result check(ServerPlayer player, boolean requireItem) {
        if (DisasterConfig.DEVICE_REQUIRES_OP.getAsBoolean()
                && !player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return Result.fail(Component.translatable("control.realisticdisasters.no_permission"));
        }
        if (requireItem && !holdsController(player)) {
            return Result.fail(Component.translatable("control.realisticdisasters.need_item"));
        }
        return null;
    }

    public static boolean holdsController(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).is(ModRegistries.DISASTER_CONTROLLER.get())) return true;
        }
        return false;
    }

    /** Ground-level start position for a placement. */
    public static Vec3 position(ServerPlayer player, Placement placement) {
        ServerLevel level = player.level();
        Vec3 origin = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : flat.normalize();
        Vec3 at = switch (placement) {
            case AHEAD -> origin.add(flat.scale(55));
            case NEARBY -> {
                double a = level.getRandom().nextDouble() * Math.PI * 2;
                double r = 90 + level.getRandom().nextDouble() * 50;
                yield origin.add(Math.cos(a) * r, 0, Math.sin(a) * r);
            }
            case HERE -> origin;
        };
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z));
        return new Vec3(at.x, y, at.z);
    }

    public static Component name(DisasterType type) {
        return Component.translatable("disaster.realisticdisasters." + type.id());
    }

    /** Signature colour per disaster (chat, titles, menu accents). */
    public static ChatFormatting color(DisasterType type) {
        return switch (type) {
            case TORNADO -> ChatFormatting.GRAY;
            case EARTHQUAKE -> ChatFormatting.GOLD;
            case WILDFIRE -> ChatFormatting.RED;
            case VOLCANO -> ChatFormatting.DARK_RED;
            case METEOR_SHOWER -> ChatFormatting.LIGHT_PURPLE;
            case TSUNAMI -> ChatFormatting.BLUE;
            case BLIZZARD -> ChatFormatting.AQUA;
            case SANDSTORM -> ChatFormatting.YELLOW;
        };
    }

    public record Result(boolean success, Component message, Disaster disaster) {
        static Result ok(Component message, Disaster d) {
            return new Result(true, message.copy().withStyle(ChatFormatting.GREEN), d);
        }

        static Result fail(Component message) {
            return new Result(false, message.copy().withStyle(ChatFormatting.RED), null);
        }
    }

    public static void clear() {
        LAST_USE.clear();
    }

    private DisasterControl() {}
}

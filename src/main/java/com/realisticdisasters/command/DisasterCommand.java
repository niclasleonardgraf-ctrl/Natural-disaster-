package com.realisticdisasters.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.disaster.Disaster;
import com.realisticdisasters.disaster.DisasterManager;
import com.realisticdisasters.disaster.DisasterType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * /disaster start &lt;type&gt; [intensity] [pos]
 * /disaster stop [id|all] [now]
 * /disaster list
 */
@EventBusSubscriber(modid = RealisticDisasters.MODID)
public final class DisasterCommand {
    private static final DynamicCommandExceptionType UNKNOWN_TYPE =
            new DynamicCommandExceptionType(o -> Component.translatable("commands.realisticdisasters.unknown_type", o));

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("disaster")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("start")
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(DisasterType.values()).map(DisasterType::id), b))
                                .executes(c -> start(c, 3, null))
                                .then(Commands.argument("intensity", IntegerArgumentType.integer(1, 5))
                                        .executes(c -> start(c, IntegerArgumentType.getInteger(c, "intensity"), null))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(c -> start(c, IntegerArgumentType.getInteger(c, "intensity"),
                                                        Vec3Argument.getVec3(c, "pos")))))))
                .then(Commands.literal("stop")
                        .executes(c -> stopAll(c, true))
                        .then(Commands.literal("all")
                                .executes(c -> stopAll(c, true))
                                .then(Commands.literal("now").executes(c -> stopAll(c, false))))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(c -> stopOne(c, true))
                                .then(Commands.literal("now").executes(c -> stopOne(c, false)))))
                .then(Commands.literal("list").executes(DisasterCommand::list)));
    }

    private static int start(CommandContext<CommandSourceStack> c, int intensity, Vec3 pos) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "type").toLowerCase(Locale.ROOT);
        DisasterType type = Arrays.stream(DisasterType.values()).filter(t -> t.id().equals(name)).findFirst()
                .orElseThrow(() -> UNKNOWN_TYPE.create(name));
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();

        if (pos == null) {
            // Spawn at a cinematic distance in front of the source, so the player sees it form.
            Vec3 origin = src.getPosition();
            Vec3 look = src.getEntity() != null ? src.getEntity().getLookAngle() : new Vec3(1, 0, 0);
            Vec3 flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1.0e-4 ? new Vec3(1, 0, 0) : flat.normalize();
            pos = origin.add(flat.scale(48));
        }
        int x = Mth.floor(pos.x), z = Mth.floor(pos.z);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Disaster d = DisasterManager.start(level, type, new Vec3(pos.x, y, pos.z), intensity);
        src.sendSuccess(() -> Component.translatable("commands.realisticdisasters.started",
                d.describe(), d.id(), d.intensity(), new BlockPos(x, y, z).toShortString()), true);
        return d.id();
    }

    private static int stopAll(CommandContext<CommandSourceStack> c, boolean fade) {
        ServerLevel level = c.getSource().getLevel();
        List<Disaster> all = DisasterManager.active(level);
        all.forEach(d -> DisasterManager.stop(level, d.id(), fade));
        c.getSource().sendSuccess(() -> Component.translatable("commands.realisticdisasters.stopped_all", all.size()), true);
        return all.size();
    }

    private static int stopOne(CommandContext<CommandSourceStack> c, boolean fade) {
        int id = IntegerArgumentType.getInteger(c, "id");
        if (DisasterManager.stop(c.getSource().getLevel(), id, fade)) {
            c.getSource().sendSuccess(() -> Component.translatable("commands.realisticdisasters.stopped", id), true);
            return 1;
        }
        c.getSource().sendFailure(Component.translatable("commands.realisticdisasters.not_found", id));
        return 0;
    }

    private static int list(CommandContext<CommandSourceStack> c) {
        List<Disaster> all = DisasterManager.active(c.getSource().getLevel());
        double cost = DisasterManager.averageTickCostMs(c.getSource().getLevel());
        c.getSource().sendSuccess(() -> Component.translatable("commands.realisticdisasters.cost",
                String.format(java.util.Locale.ROOT, "%.2f", cost)), false);
        if (all.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.translatable("commands.realisticdisasters.none"), false);
            return 0;
        }
        for (Disaster d : all) {
            c.getSource().sendSuccess(() -> Component.translatable("commands.realisticdisasters.entry",
                    d.id(), d.describe(), d.intensity(),
                    Component.translatable("phase.realisticdisasters." + d.phase().name().toLowerCase(Locale.ROOT)),
                    BlockPos.containing(d.pos()).toShortString()), false);
        }
        return all.size();
    }

    private DisasterCommand() {}
}

package com.realisticdisasters.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.realisticdisasters.RealisticDisasters;
import com.realisticdisasters.client.ClientDisaster;
import com.realisticdisasters.client.ClientDisasters;
import com.realisticdisasters.control.DisasterControl;
import com.realisticdisasters.disaster.DisasterType;
import com.realisticdisasters.network.ControlPayloads;
import com.realisticdisasters.registry.ModRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Disaster Controller menu: pick a disaster, its intensity and where it should form, then start it. A second tab
 * lists active disasters nearby with stop buttons. Everything is sent to the server as validated requests.
 */
public class DisasterControllerScreen extends Screen {
    private static final int W = 330, H = 220;
    private static final int HEADER = 26;
    private static final int ROW = 22;
    private static final int LIST_W = 116;

    // palette (ARGB)
    private static final int FRAME_DARK = 0xFF0B0D10, BEVEL_LIGHT = 0xFF59636E, BEVEL_DARK = 0xFF14181C;
    private static final int BODY_TOP = 0xF0262D34, BODY_BOTTOM = 0xF01A1F24;
    private static final int HEADER_TOP = 0xFF333C45, HEADER_BOTTOM = 0xFF262D35;
    private static final int TEXT = 0xFFF2F2F2, TEXT_DIM = 0xFFA9B1B9, TEXT_FAINT = 0xFF7D868F;

    // remembered between openings
    private static DisasterType selected = DisasterType.TORNADO;
    private static int intensity = 3;
    private static DisasterControl.Placement placement = DisasterControl.Placement.AHEAD;
    private static boolean activeTab;

    private int left, top;
    private List<Integer> shownIds = List.of();

    public DisasterControllerScreen() {
        super(Component.translatable("gui.realisticdisasters.controller.title"));
    }

    /** Open on the Active tab next time (used by the dev hook). */
    public static void showActiveTab() {
        activeTab = true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        int rx = left + LIST_W + 16, rw = W - LIST_W - 24;

        // tabs (top right of the header)
        addRenderableWidget(Button.builder(Component.translatable("gui.realisticdisasters.tab.start"), b -> switchTab(false))
                .bounds(left + W - 146, top + 4, 58, 18).build()).active = activeTab;
        int count = ClientDisasters.active().size();
        addRenderableWidget(Button.builder(Component.translatable("gui.realisticdisasters.tab.active", count), b -> switchTab(true))
                .bounds(left + W - 86, top + 4, 80, 18).build()).active = !activeTab;

        if (!activeTab) {
            addRenderableWidget(new IntensitySlider(rx, top + 140, rw, 20));
            addRenderableWidget(Button.builder(placementLabel(), b -> {
                placement = DisasterControl.Placement.values()[(placement.ordinal() + 1) % DisasterControl.Placement.values().length];
                b.setMessage(placementLabel());
                b.setTooltip(placementTooltip());
            }).bounds(rx, top + 164, rw, 20).tooltip(placementTooltip()).build());
            Button start = Button.builder(Component.translatable("gui.realisticdisasters.start", DisasterControl.name(selected))
                    .withStyle(ChatFormatting.BOLD), b -> startSelected()).bounds(rx, top + 190, rw - 26, 22).build();
            addRenderableWidget(start);
            addRenderableWidget(Button.builder(Component.literal("⚙"), b -> openSettings())
                    .bounds(rx + rw - 22, top + 190, 22, 22)
                    .tooltip(Tooltip.create(Component.translatable("gui.realisticdisasters.settings"))).build());
        } else {
            buildActiveTab();
        }
    }

    private void buildActiveTab() {
        List<ClientDisaster> list = sortedActive();
        shownIds = list.stream().map(d -> d.id).toList();
        int y = top + HEADER + 30;
        for (ClientDisaster d : list.subList(0, Math.min(list.size(), 6))) {
            int id = d.id;
            addRenderableWidget(Button.builder(Component.translatable("gui.realisticdisasters.stop"), b -> sendStop(id, false))
                    .bounds(left + W - 118, y + 2, 54, 18)
                    .tooltip(Tooltip.create(Component.translatable("gui.realisticdisasters.stop.tooltip"))).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.realisticdisasters.remove"), b -> sendStop(id, true))
                    .bounds(left + W - 62, y + 2, 54, 18)
                    .tooltip(Tooltip.create(Component.translatable("gui.realisticdisasters.remove.tooltip"))).build());
            y += 25;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.realisticdisasters.stop_all"), b -> sendStop(-1, false))
                .bounds(left + W - 118, top + H - 28, 110, 20).build()).active = !list.isEmpty();
    }

    private void switchTab(boolean active) {
        activeTab = active;
        clearWidgets();
        init();
    }

    @Override
    public void tick() {
        super.tick();
        // keep the active list (and the tab counter) in sync with the world
        List<Integer> ids = sortedActive().stream().map(d -> d.id).toList();
        if (!ids.equals(shownIds)) {
            shownIds = ids;
            clearWidgets();
            init();
        }
    }

    private List<ClientDisaster> sortedActive() {
        Vec3 me = minecraft != null && minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
        List<ClientDisaster> list = new ArrayList<>(ClientDisasters.active());
        list.sort((a, b) -> Double.compare(a.pos().distanceToSqr(me), b.pos().distanceToSqr(me)));
        return list;
    }

    // ------------------------------------------------------------------ actions

    private void startSelected() {
        ClientPacketDistributor.sendToServer(new ControlPayloads.Start(selected.id(), intensity, placement.ordinal()));
        onClose();
    }

    private void sendStop(int id, boolean now) {
        ClientPacketDistributor.sendToServer(new ControlPayloads.Stop(id, now));
    }

    private void openSettings() {
        ModList.get().getModContainerById(RealisticDisasters.MODID).ifPresent(c ->
                Minecraft.getInstance().setScreen(new ConfigurationScreen(c, this)));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!activeTab) {
            DisasterType hit = rowAt(event.x(), event.y());
            if (hit != null) {
                if (hit != selected) {
                    selected = hit;
                    Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.35f));
                    clearWidgets();
                    init();
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private DisasterType rowAt(double mx, double my) {
        int x0 = left + 8, y0 = top + HEADER + 6;
        if (mx < x0 || mx >= x0 + LIST_W) return null;
        int i = (int) Math.floor((my - y0) / ROW);
        if (my < y0 || i < 0 || i >= DisasterType.values().length) return null;
        return DisasterType.values()[i];
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        int accent = 0xFF000000 | DisasterControl.color(activeTab ? DisasterType.TORNADO : selected).getColor();
        // frame + bevel (Minecraft-style), body gradient
        g.fill(left - 2, top - 2, left + W + 2, top + H + 2, FRAME_DARK);
        g.fill(left - 1, top - 1, left + W + 1, top, BEVEL_LIGHT);
        g.fill(left - 1, top - 1, left, top + H + 1, BEVEL_LIGHT);
        g.fill(left, top + H, left + W + 1, top + H + 1, BEVEL_DARK);
        g.fill(left + W, top, left + W + 1, top + H + 1, BEVEL_DARK);
        g.fillGradient(left, top, left + W, top + H, BODY_TOP, BODY_BOTTOM);
        // header
        g.fillGradient(left, top, left + W, top + HEADER, HEADER_TOP, HEADER_BOTTOM);
        g.fill(left, top + HEADER, left + W, top + HEADER + 1, activeTab ? 0xFF8A949E : accent);
        g.item(ModRegistries.DISASTER_CONTROLLER.get().getDefaultInstance(), left + 6, top + 5);
        g.text(font, Component.translatable("gui.realisticdisasters.controller.title").withStyle(ChatFormatting.BOLD), left + 26, top + 5, TEXT);
        g.text(font, Component.translatable("gui.realisticdisasters.controller.subtitle"), left + 26, top + 15, TEXT_FAINT, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        if (activeTab) drawActive(g); else drawStartTab(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, a);
    }

    private void drawStartTab(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // disaster list
        int x0 = left + 8, y0 = top + HEADER + 6;
        g.fill(x0 - 2, y0 - 2, x0 + LIST_W + 2, y0 + ROW * DisasterType.values().length + 2, 0x60000000);
        DisasterType hover = rowAt(mouseX, mouseY);
        for (int i = 0; i < DisasterType.values().length; i++) {
            DisasterType t = DisasterType.values()[i];
            int y = y0 + i * ROW;
            int color = 0xFF000000 | DisasterControl.color(t).getColor();
            if (t == selected) {
                g.fill(x0, y, x0 + LIST_W, y + ROW - 2, (color & 0x00FFFFFF) | 0x50000000);
                g.fill(x0, y, x0 + 2, y + ROW - 2, color);
            } else if (t == hover) {
                g.fill(x0, y, x0 + LIST_W, y + ROW - 2, 0x30FFFFFF);
            }
            icon(g, t, x0 + 5, y + 2, 16);
            g.text(font, DisasterControl.name(t), x0 + 26, y + 6, t == selected ? brighten(color) : TEXT_DIM);
        }

        // details of the selected disaster
        int rx = left + LIST_W + 16, rw = W - LIST_W - 24;
        int color = 0xFF000000 | DisasterControl.color(selected).getColor();
        g.fill(rx, top + HEADER + 6, rx + 36, top + HEADER + 42, 0x60000000);
        g.outline(rx, top + HEADER + 6, 36, 36, color);
        icon(g, selected, rx + 2, top + HEADER + 8, 32);
        g.text(font, DisasterControl.name(selected).copy().withStyle(ChatFormatting.BOLD), rx + 44, top + HEADER + 10, brighten(color));
        g.text(font, Component.translatable("gui.realisticdisasters.intensity_label",
                Component.translatable("intensity.realisticdisasters." + intensity)), rx + 44, top + HEADER + 24, TEXT_DIM, false);
        // text block laid out by its real wrapped height (descriptions differ in length)
        Component desc = Component.translatable("desc.realisticdisasters." + selected.id());
        int y = top + HEADER + 48;
        g.textWithWordWrap(font, desc, rx, y, rw, TEXT_DIM, false);
        y += font.split(desc, rw).size() * font.lineHeight + 4;
        int tier = intensity <= 2 ? 1 : intensity == 3 ? 2 : 3;
        g.textWithWordWrap(font, Component.translatable("gui.realisticdisasters.expect",
                        Component.translatable("expect.realisticdisasters." + selected.id() + "." + tier)),
                rx, y, rw, 0xFFE6C36A, false);
    }

    private void drawActive(GuiGraphicsExtractor g) {
        List<ClientDisaster> list = sortedActive();
        int x = left + 10, y = top + HEADER + 8;
        g.text(font, Component.translatable("gui.realisticdisasters.active.header").withStyle(ChatFormatting.BOLD), x, y, TEXT);
        y += 22;
        if (list.isEmpty()) {
            g.centeredText(font, Component.translatable("gui.realisticdisasters.active.none"), left + W / 2, top + H / 2, TEXT_FAINT);
            g.centeredText(font, Component.translatable("gui.realisticdisasters.active.none2"), left + W / 2, top + H / 2 + 12, TEXT_FAINT);
            return;
        }
        Vec3 me = minecraft.player.position();
        for (ClientDisaster d : list.subList(0, Math.min(list.size(), 6))) {
            int color = 0xFF000000 | DisasterControl.color(d.type).getColor();
            g.fill(x - 2, y - 1, left + W - 122, y + 23, 0x50000000);
            g.fill(x - 2, y - 1, x, y + 23, color);
            icon(g, d.type, x + 3, y + 3, 16);
            g.text(font, DisasterControl.name(d.type).copy().append(" ")
                    .append(Component.translatable("intensity.realisticdisasters." + d.intensity).withStyle(ChatFormatting.GRAY)), x + 24, y + 2, TEXT);
            double dx = d.pos().x - me.x, dz = d.pos().z - me.z;
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            g.text(font, Component.translatable("gui.realisticdisasters.active.row",
                    Component.translatable("phase.realisticdisasters." + d.phase.name().toLowerCase(Locale.ROOT)), dist,
                    Component.translatable("direction.realisticdisasters." + direction(dx, dz))), x + 24, y + 12, TEXT_FAINT, false);
            y += 25;
        }
        if (list.size() > 6) {
            g.text(font, Component.translatable("gui.realisticdisasters.active.more", list.size() - 6), x, top + H - 22, TEXT_FAINT, false);
        }
    }

    /** Signature colours are tuned for chat; lift them toward white so names stay readable on the dark panel. */
    private static int brighten(int argb) {
        int r = (argb >> 16) & 255, gg = (argb >> 8) & 255, b = argb & 255;
        r = r + (255 - r) * 2 / 5;
        gg = gg + (255 - gg) * 2 / 5;
        b = b + (255 - b) * 2 / 5;
        return 0xFF000000 | r << 16 | gg << 8 | b;
    }

    private static String direction(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0) deg += 360;
        String[] names = {"north", "north_east", "east", "south_east", "south", "south_west", "west", "north_west"};
        return names[(int) Math.round(deg / 45.0) % 8];
    }

    private void icon(GuiGraphicsExtractor g, DisasterType t, int x, int y, int size) {
        Identifier tex = ModRegistries.id("textures/gui/icon/" + t.id() + ".png");
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, size, size, 16, 16, 16, 16);
    }

    private static Component placementLabel() {
        return Component.translatable("gui.realisticdisasters.location", Component.translatable(placement.key()));
    }

    private static Tooltip placementTooltip() {
        return Tooltip.create(Component.translatable(placement.key() + ".tooltip"));
    }

    // ------------------------------------------------------------------ intensity slider

    private static final class IntensitySlider extends AbstractSliderButton {
        IntensitySlider(int x, int y, int w, int h) {
            super(x, y, w, h, Component.empty(), (intensity - 1) / 4.0);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            String stars = "★".repeat(intensity) + "☆".repeat(5 - intensity);
            setMessage(Component.translatable("gui.realisticdisasters.intensity", stars,
                    Component.translatable("intensity.realisticdisasters." + intensity)));
        }

        @Override
        protected void applyValue() {
            intensity = Mth.clamp(1 + (int) Math.round(value * 4), 1, 5);
            value = (intensity - 1) / 4.0;   // snap to the 5 levels
        }
    }
}

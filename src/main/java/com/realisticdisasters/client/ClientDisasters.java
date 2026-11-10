package com.realisticdisasters.client;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import com.realisticdisasters.network.DisasterRemovePayload;
import com.realisticdisasters.network.DisasterSyncPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client-side registry of known disasters, fed by the server sync payloads. */
public final class ClientDisasters {
    /** Drop a disaster we haven't heard about for this long (left range, packet loss). */
    private static final int TIMEOUT = 60;
    private static final Map<Integer, ClientDisaster> ACTIVE = new Int2ObjectLinkedOpenHashMap<>();
    /** Removed disasters keep fading out for a moment so effects never pop. */
    private static final Map<Integer, ClientDisaster> FADING = new Int2ObjectLinkedOpenHashMap<>();

    public static void handleSync(DisasterSyncPayload payload, IPayloadContext ctx) {
        ClientDisaster d = ACTIVE.get(payload.id());
        if (d == null) {
            d = new ClientDisaster(payload);
            ACTIVE.put(d.id, d);
            FADING.remove(d.id);
        } else {
            d.apply(payload);
        }
    }

    public static void handleRemove(DisasterRemovePayload payload, IPayloadContext ctx) {
        if (payload.id() < 0) {
            ACTIVE.values().forEach(ClientDisasters::startFade);
            ACTIVE.clear();
            return;
        }
        ClientDisaster d = ACTIVE.remove(payload.id());
        if (d != null) startFade(d);
    }

    private static void startFade(ClientDisaster d) {
        d.fadeOut();
        FADING.put(d.id, d);
    }

    public static void tick() {
        Iterator<ClientDisaster> it = ACTIVE.values().iterator();
        while (it.hasNext()) {
            ClientDisaster d = it.next();
            d.tick();
            if (d.ticksSinceUpdate > TIMEOUT) {
                it.remove();
                startFade(d);
            }
        }
        Iterator<ClientDisaster> fit = FADING.values().iterator();
        while (fit.hasNext()) {
            ClientDisaster d = fit.next();
            d.tick();
            if (d.strength() < 0.005f) {
                fit.remove();
                DisasterVisuals.get(d.type).onRemoved(d);
            }
        }
    }

    public static ClientDisaster get(int id) {
        ClientDisaster d = ACTIVE.get(id);
        return d != null ? d : FADING.get(id);
    }

    /** Active plus fading disasters (everything that should still render). */
    public static Iterable<ClientDisaster> all() {
        if (FADING.isEmpty()) return ACTIVE.values();
        java.util.List<ClientDisaster> list = new java.util.ArrayList<>(ACTIVE.values());
        list.addAll(FADING.values());
        return list;
    }

    public static Collection<ClientDisaster> active() {
        return Collections.unmodifiableCollection(ACTIVE.values());
    }

    public static void clear() {
        ACTIVE.values().forEach(d -> DisasterVisuals.get(d.type).onRemoved(d));
        FADING.values().forEach(d -> DisasterVisuals.get(d.type).onRemoved(d));
        ACTIVE.clear();
        FADING.clear();
    }

    private ClientDisasters() {}
}

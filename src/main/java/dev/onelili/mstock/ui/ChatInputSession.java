package dev.onelili.mstock.ui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatInputSession {
    private final Map<UUID, PendingAction> sessions = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 60_000;

    public void startSession(UUID playerUuid, PendingAction action) {
        sessions.put(playerUuid, action);
    }

    public PendingAction getSession(UUID playerUuid) {
        PendingAction action = sessions.get(playerUuid);
        if (action != null && action.isExpired(TIMEOUT_MS)) {
            sessions.remove(playerUuid, action);
            return null;
        }
        return action;
    }

    public void clearSession(UUID playerUuid) {
        sessions.remove(playerUuid);
    }

    public void clearAll() {
        sessions.clear();
    }

    public boolean hasSession(UUID playerUuid) {
        return getSession(playerUuid) != null;
    }
}

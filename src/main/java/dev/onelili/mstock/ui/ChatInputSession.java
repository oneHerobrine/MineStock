package dev.onelili.mstock.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChatInputSession {
    private final Map<UUID, PendingAction> sessions = new HashMap<>();
    private static final long TIMEOUT_MS = 60_000;

    public void startSession(UUID playerUuid, PendingAction action) {
        sessions.put(playerUuid, action);
    }

    public PendingAction getSession(UUID playerUuid) {
        PendingAction action = sessions.get(playerUuid);
        if (action != null && action.isExpired(TIMEOUT_MS)) {
            sessions.remove(playerUuid);
            return null;
        }
        return action;
    }

    public void clearSession(UUID playerUuid) {
        sessions.remove(playerUuid);
    }

    public boolean hasSession(UUID playerUuid) {
        return getSession(playerUuid) != null;
    }
}

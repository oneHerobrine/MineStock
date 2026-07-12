package dev.onelili.mstock.api;

public record ApiEntry(String iface, String apiKey, boolean tradeable) {
    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}

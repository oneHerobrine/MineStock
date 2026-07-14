package dev.onelili.mstock.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.util.Map;

/** Owns a command registered outside plugin.yml for one plugin enable cycle. */
public final class DynamicCommandRegistration implements AutoCloseable {

    private final CommandMap commandMap;
    private Command command;

    public DynamicCommandRegistration(CommandMap commandMap) {
        this.commandMap = commandMap;
    }

    public synchronized boolean register(String fallbackPrefix, Command candidate) {
        if (command != null) unregister();
        if (commandMap.getCommand(candidate.getName()) != null) return false;

        command = candidate;
        try {
            commandMap.register(fallbackPrefix, candidate);
        } catch (RuntimeException e) {
            if (!candidate.isRegistered()) command = null;
            throw e;
        }
        if (!candidate.isRegistered()) {
            command = null;
            return false;
        }
        return commandMap.getCommand(candidate.getName()) == candidate;
    }

    public synchronized boolean isRegistered() {
        return command != null && command.isRegistered();
    }

    public synchronized void unregister() {
        Command registered = command;
        command = null;
        if (registered == null) return;

        Map<String, Command> knownCommands = commandMap.getKnownCommands();
        knownCommands.entrySet().removeIf(entry -> entry.getValue() == registered);
        registered.unregister(commandMap);
    }

    @Override
    public void close() {
        unregister();
    }
}

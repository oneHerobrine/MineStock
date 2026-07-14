package dev.onelili.mstock.command;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicCommandRegistrationTest {

    @Test
    void removesEveryMappingForItsCommandAndCanRegisterAgain() {
        FakeCommandMap commandMap = new FakeCommandMap();
        DynamicCommandRegistration registration = new DynamicCommandRegistration(commandMap);
        TestCommand first = new TestCommand("st");

        assertTrue(registration.register("minestock", first));
        assertSame(first, commandMap.getCommand("st"));
        assertSame(first, commandMap.getCommand("minestock:st"));

        registration.unregister();
        registration.unregister();
        assertFalse(first.isRegistered());
        assertFalse(commandMap.getKnownCommands().containsValue(first));

        TestCommand second = new TestCommand("st");
        assertTrue(registration.register("minestock", second));
        registration.close();
        assertFalse(commandMap.getKnownCommands().containsValue(second));
    }

    @Test
    void leavesAnotherPluginsConflictingCommandUntouched() {
        FakeCommandMap commandMap = new FakeCommandMap();
        TestCommand other = new TestCommand("st");
        commandMap.register("other", other);

        DynamicCommandRegistration registration = new DynamicCommandRegistration(commandMap);
        assertFalse(registration.register("minestock", new TestCommand("st")));
        registration.close();

        assertSame(other, commandMap.getCommand("st"));
        assertTrue(other.isRegistered());
    }

    @Test
    void cleansUpACommandWhenRegistrationFailsAfterPartialMutation() {
        ThrowingCommandMap commandMap = new ThrowingCommandMap();
        DynamicCommandRegistration registration = new DynamicCommandRegistration(commandMap);
        TestCommand command = new TestCommand("st");

        assertThrows(IllegalStateException.class,
                () -> registration.register("minestock", command));
        registration.close();

        assertFalse(command.isRegistered());
        assertFalse(commandMap.getKnownCommands().containsValue(command));
    }

    private static final class TestCommand extends Command {
        private TestCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return true;
        }
    }

    private static class FakeCommandMap implements CommandMap {
        private final Map<String, Command> knownCommands = new LinkedHashMap<>();

        @Override
        public void registerAll(String fallbackPrefix, List<Command> commands) {
            commands.forEach(command -> register(fallbackPrefix, command));
        }

        @Override
        public boolean register(String label, String fallbackPrefix, Command command) {
            if (knownCommands.containsKey(label)) return false;
            command.register(this);
            knownCommands.put(label, command);
            knownCommands.put(fallbackPrefix + ":" + label, command);
            return true;
        }

        @Override
        public boolean register(String fallbackPrefix, Command command) {
            return register(command.getName(), fallbackPrefix, command);
        }

        @Override
        public boolean dispatch(CommandSender sender, String commandLine) {
            return false;
        }

        @Override
        public Command getCommand(String name) {
            return knownCommands.get(name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String cmdLine) {
            return new ArrayList<>();
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String cmdLine, Location location) {
            return new ArrayList<>();
        }

        @Override
        public void clearCommands() {
            knownCommands.clear();
        }

        @Override
        public Map<String, Command> getKnownCommands() {
            return knownCommands;
        }
    }

    private static final class ThrowingCommandMap extends FakeCommandMap {
        @Override
        public boolean register(String fallbackPrefix, Command command) {
            super.register(fallbackPrefix, command);
            throw new IllegalStateException("simulated partial registration");
        }
    }
}

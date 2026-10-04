package rngtrader.client;

import java.util.*;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;

final class TraderCommand extends CommandBase {
    private final TraderService service;
    TraderCommand(TraderService service) { this.service = service; }
    @Override public String getCommandName() { return "rngtrader"; }
    @Override public String getCommandUsage(ICommandSender sender) { return "/rngtrader help"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
    @Override public void processCommand(ICommandSender sender, String[] args) {
        service.command(args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "help");
    }
    @Override public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args,
            "check", "roster", "start", "status", "pause", "resume", "stop", "release", "help") : null;
    }
}

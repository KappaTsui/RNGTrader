package rngtrader.client;

import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Keeps movement input suspended while the merchant is deliberately released for forty ticks. */
final class RefreshScreen extends GuiScreen {
    private final TraderService service;
    private final CommandInput commands;
    RefreshScreen(TraderService service) { this.service = service; commands = new CommandInput(service); }
    @Override public void initGui() { commands.init(width, height); }
    @Override public void drawScreen(int x, int y, float partial) {
        drawCenteredString(fontRendererObj, service.screenStatus(), width / 2, height / 2, 0xffffff);
        commands.draw();
    }
    @Override protected void keyTyped(char letter, int code) {
        if (!commands.key(letter, code) && code == Keyboard.KEY_ESCAPE) service.command("pause");
    }
    @Override public void updateScreen() { commands.tick(); }
    @Override public boolean doesGuiPauseGame() { return false; }
    @Override public void handleMouseInput() { super.handleMouseInput(); commands.wheel(Mouse.getEventDWheel()); }
}

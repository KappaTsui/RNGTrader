package rngtrader.client;

import net.minecraft.client.gui.GuiMerchant;
import net.minecraft.entity.IMerchant;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

final class TraderScreen extends GuiMerchant {
    private final TraderService service;
    private final CommandInput commands;
    TraderScreen(TraderService service, IMerchant merchant) {
        super(service.mc.thePlayer.inventory, merchant, service.mc.theWorld, "RNGTrader");
        this.service = service; commands = new CommandInput(service);
    }
    @Override public void initGui() { super.initGui(); commands.init(width, height); }
    @Override public void drawScreen(int x, int y, float partial) {
        super.drawScreen(x, y, partial);
        drawCenteredString(fontRendererObj, service.screenStatus(), width / 2, 6, 0xffffff);
        drawCenteredString(fontRendererObj, "Press / for local commands. Escape pauses an active session.", width / 2, 18, 0xbbbbbb);
        commands.draw();
    }
    @Override protected void keyTyped(char letter, int code) {
        if (commands.key(letter, code)) return;
        if (service.holding()) { if (code == Keyboard.KEY_ESCAPE) service.command("pause"); return; }
        super.keyTyped(letter, code);
    }
    @Override protected void mouseClicked(int x, int y, int button) {
        if (!service.holding()) super.mouseClicked(x, y, button);
    }
    @Override protected void mouseMovedOrUp(int x, int y, int button) {
        if (!service.holding()) super.mouseMovedOrUp(x, y, button);
    }
    @Override public void updateScreen() { super.updateScreen(); commands.tick(); }
    @Override public void handleMouseInput() { super.handleMouseInput(); commands.wheel(Mouse.getEventDWheel()); }
    boolean typingCommand() { return commands.focused(); }
}

package rngtrader.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.FontRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.client.ClientCommandHandler;
import org.lwjgl.input.Keyboard;

/** Local command entry without changing the merchant's open container. */
final class CommandInput {
    private final TraderService service;
    private GuiTextField field;
    private int width, height, scroll;
    private long outputUntil;
    CommandInput(TraderService service) { this.service = service; }
    void init(int width, int height) {
        this.width = width; this.height = height;
        String previous = field == null ? "" : field.getText();
        boolean focused = field != null && field.isFocused();
        field = new GuiTextField(Minecraft.getMinecraft().fontRenderer, 4, height - 18, width - 8, 14);
        field.setMaxStringLength(160); field.setEnableBackgroundDrawing(true); field.setText(previous); field.setFocused(focused);
    }
    boolean key(char letter, int code) {
        if (field.isFocused()) {
            if (code == Keyboard.KEY_ESCAPE) { field.setFocused(false); return true; }
            if (code == Keyboard.KEY_RETURN || code == Keyboard.KEY_NUMPADENTER) {
                String command = field.getText().trim(); field.setText(""); field.setFocused(false);
                scroll = 0; outputUntil = System.nanoTime() + 8_000_000_000L;
                if (command.equals("/rngtrader") || command.startsWith("/rngtrader "))
                    ClientCommandHandler.instance.executeCommand(service.mc.thePlayer, command);
                else service.say("This input accepts local /rngtrader commands only.");
            } else field.textboxKeyTyped(letter, code);
            return true;
        }
        if (code == Keyboard.KEY_SLASH || code == Keyboard.KEY_T) {
            field.setFocused(true); field.setText("/rngtrader "); scroll = 0; return true;
        }
        return false;
    }
    void draw() {
        if (!field.isFocused() && System.nanoTime() >= outputUntil) return;
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        List<String> lines = new ArrayList<String>();
        for (String message : service.messages()) lines.addAll(font.listFormattedStringToWidth(message, width - 16));
        int visible = Math.min(16, Math.max(1, (height - 60) / 10));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
        int end = lines.size() - scroll, start = Math.max(0, end - visible);
        Gui.drawRect(2, height - 26 - visible * 10, width - 2, height - 21, 0xdd101010);
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            int color = line.startsWith("FAIL") ? 0xff7777 : line.startsWith("PASS") ? 0x88ee88 : 0xeeeeee;
            font.drawStringWithShadow(line, 6, height - 24 - (end - i) * 10, color);
        }
        if (field.isFocused()) field.drawTextBox();
    }
    void wheel(int delta) { if (field.isFocused()) scroll += delta > 0 ? 3 : delta < 0 ? -3 : 0; }
    void tick() { field.updateCursorCounter(); }
    boolean focused() { return field != null && field.isFocused(); }
}

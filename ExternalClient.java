package com.external;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** EXTERNAL: клик-гуи, бинды командами в чате и визуальные модули. */
public class ExternalClient implements ClientModInitializer {
    public static KeyBinding guiKey;

    @Override
    public void onInitializeClient() {
        Modules.init();
        Config.load();
        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "External GUI", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "External"));
        ClientTickEvents.END_CLIENT_TICK.register(Modules::tick);
        ClientSendMessageEvents.ALLOW_CHAT.register(Commands::handle);
        HudRenderCallback.EVENT.register(Hud::render);
    }
}

/* ---------------- Модули ---------------- */

class Feature {
    final String name;
    int key = -1;
    boolean enabled, wasDown, hasValue;
    double value, min, max, step;

    Feature(String name) { this.name = name; }

    Feature range(double v, double mn, double mx, double st) {
        hasValue = true; value = v; min = mn; max = mx; step = st;
        return this;
    }

    void toggle() { set(!enabled); }

    void set(boolean e) {
        if (e == enabled) return;
        enabled = e;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (e) onEnable(mc); else onDisable(mc);
    }

    void onEnable(MinecraftClient mc) {}
    void onDisable(MinecraftClient mc) {}
    void onTick(MinecraftClient mc) {}
}

class HudModule extends Feature {
    HudModule() { super("HUD"); }
}

class Fullbright extends Feature {
    Fullbright() { super("Fullbright"); }

    @Override void onTick(MinecraftClient mc) {
        if (mc.player != null) {
            mc.player.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.NIGHT_VISION, 1000, 0, false, false, false));
        }
    }

    @Override void onDisable(MinecraftClient mc) {
        if (mc.player != null) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
    }
}

/** Меняет FOV на заданное значение, при выключении возвращает старое. */
class FovBase extends Feature {
    Integer old;

    FovBase(String name) { super(name); }

    @Override void onEnable(MinecraftClient mc) {
        old = mc.options.getFov().getValue();
        apply(mc);
    }

    @Override void onTick(MinecraftClient mc) { apply(mc); }

    @Override void onDisable(MinecraftClient mc) {
        if (old != null) mc.options.getFov().setValue(old);
        old = null;
    }

    void apply(MinecraftClient mc) { mc.options.getFov().setValue((int) value); }
}

class Zoom extends FovBase {
    Zoom() { super("Zoom"); range(30, 30, 80, 5); }
}

class Fov extends FovBase {
    Fov() { super("FOV"); range(100, 70, 110, 5); }
}

final class Modules {
    static final List<Feature> ALL = new ArrayList<>();
    static HudModule HUD;

    static void init() {
        HUD = new HudModule();
        HUD.enabled = true;
        ALL.add(HUD);
        ALL.add(new Fullbright());
        ALL.add(new Zoom());
        ALL.add(new Fov());
    }

    static Feature find(String n) {
        for (Feature f : ALL) if (f.name.equalsIgnoreCase(n)) return f;
        return null;
    }

    static void tick(MinecraftClient mc) {
        if (mc.getWindow() == null) return;
        for (Feature f : ALL) {
            if (f.enabled) f.onTick(mc);
            if (f.key != -1 && mc.currentScreen == null) {
                boolean p = InputUtil.isKeyPressed(mc.getWindow().getHandle(), f.key);
                if (p && !f.wasDown) {
                    f.toggle();
                    Chat.say("\u00a7d" + f.name + " \u00a7f" + (f.enabled ? "ON" : "OFF"));
                }
                f.wasDown = p;
            } else {
                f.wasDown = false;
            }
        }
        while (ExternalClient.guiKey.wasPressed()) mc.setScreen(new ClickGuiScreen());
    }
}

/* ---------------- Чат и команды ---------------- */

final class Chat {
    static void say(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(s), false);
    }
}

final class Commands {
    /** true = отправить сообщение на сервер, false = перехватили. */
    static boolean handle(String msg) {
        if (!msg.startsWith(".")) return true;
        String[] a = msg.substring(1).trim().split("\\s+");
        String cmd = a[0].toLowerCase();
        switch (cmd) {
            case "gui":
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.send(() -> mc.setScreen(new ClickGuiScreen()));
                break;
            case "list":
                for (Feature f : Modules.ALL)
                    Chat.say("\u00a77" + f.name + ": " + (f.enabled ? "\u00a7aON" : "\u00a7cOFF")
                            + " \u00a77[" + keyName(f.key) + "]" + (f.hasValue ? " = " + (int) f.value : ""));
                break;
            case "toggle": case "t": {
                Feature f = need(a, 1);
                if (f == null) break;
                f.toggle();
                Chat.say("\u00a7d" + f.name + " \u00a7f" + (f.enabled ? "ON" : "OFF"));
                break;
            }
            case "bind": {
                Feature f = need(a, 1);
                if (f == null) break;
                if (a.length < 3) { Chat.say("\u00a7cUsage: .bind <module> <key>"); break; }
                int code = parseKey(a[2]);
                if (code == -1) { Chat.say("\u00a7cUnknown key: " + a[2]); break; }
                f.key = code;
                Config.save();
                Chat.say("\u00a7d" + f.name + " \u00a7fbound to \u00a7e" + keyName(code));
                break;
            }
            case "unbind": {
                Feature f = need(a, 1);
                if (f == null) break;
                f.key = -1;
                Config.save();
                Chat.say("\u00a7d" + f.name + " \u00a7funbound");
                break;
            }
            case "set": {
                Feature f = need(a, 1);
                if (f == null) break;
                if (!f.hasValue || a.length < 3) { Chat.say("\u00a7cUsage: .set <module> <number>"); break; }
                try {
                    f.value = Math.max(f.min, Math.min(f.max, Double.parseDouble(a[2])));
                    Config.save();
                    Chat.say("\u00a7d" + f.name + " \u00a7f= " + (int) f.value);
                } catch (NumberFormatException e) { Chat.say("\u00a7cNot a number: " + a[2]); }
                break;
            }
            default:
                Chat.say("\u00a75EXTERNAL \u00a7fcommands:");
                Chat.say("\u00a77.list  .gui  .toggle <module>  .bind <module> <key>");
                Chat.say("\u00a77.unbind <module>  .set <module> <number>");
                Chat.say("\u00a77Example: \u00a7f.bind fullbright r");
        }
        return false;
    }

    private static Feature need(String[] a, int i) {
        if (a.length <= i) { Chat.say("\u00a7cSpecify a module (see .list)"); return null; }
        Feature f = Modules.find(a[i]);
        if (f == null) Chat.say("\u00a7cNo such module: " + a[i]);
        return f;
    }

    static int parseKey(String s) {
        String k = s.toLowerCase();
        switch (k) {
            case "rshift": k = "right.shift"; break;
            case "lshift": k = "left.shift"; break;
            case "ctrl": case "lctrl": k = "left.control"; break;
            case "rctrl": k = "right.control"; break;
            case "alt": case "lalt": k = "left.alt"; break;
            case "ralt": k = "right.alt"; break;
            default:
        }
        try {
            return InputUtil.fromTranslationKey("key.keyboard." + k).getCode();
        } catch (Exception e) {
            return -1;
        }
    }

    static String keyName(int code) {
        if (code == -1) return "-";
        try {
            return InputUtil.fromKeyCode(code, -1).getLocalizedText().getString();
        } catch (Exception e) {
            return "?";
        }
    }
}

/* ---------------- Сохранение ---------------- */

final class Config {
    static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("external.json");
    }

    static void save() {
        try {
            JsonObject root = new JsonObject();
            for (Feature f : Modules.ALL) {
                JsonObject o = new JsonObject();
                o.addProperty("key", f.key);
                if (f.hasValue) o.addProperty("value", f.value);
                root.add(f.name, o);
            }
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception ignored) {}
    }

    static void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            for (Feature f : Modules.ALL) {
                if (!root.has(f.name)) continue;
                JsonObject o = root.getAsJsonObject(f.name);
                if (o.has("key")) f.key = o.get("key").getAsInt();
                if (f.hasValue && o.has("value")) f.value = o.get("value").getAsDouble();
            }
        } catch (Exception ignored) {}
    }
}

/* ---------------- HUD ---------------- */

final class Hud {
    static void render(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || !Modules.HUD.enabled) return;
        ctx.drawTextWithShadow(mc.textRenderer, "EXTERNAL", 4, 4, 0xFF8A5CFF);
        int w = ctx.getScaledWindowWidth(), y = 4;
        for (Feature f : Modules.ALL) {
            if (!f.enabled || f == Modules.HUD) continue;
            ctx.drawTextWithShadow(mc.textRenderer, f.name, w - mc.textRenderer.getWidth(f.name) - 4, y, 0xFFFFFFFF);
            y += 10;
        }
    }
}

/* ---------------- Клик-гуи ---------------- */

class ClickGuiScreen extends Screen {
    private static final int W = 220, ROW = 22, HEAD = 26;
    private Feature listening;

    ClickGuiScreen() { super(Text.literal("External")); }

    @Override public boolean shouldPause() { return false; }

    private int left() { return width / 2 - W / 2; }
    private int top() { return height / 2 - (HEAD + ROW * Modules.ALL.size()) / 2; }

    private Feature rowAt(double mx, double my) {
        if (mx < left() || mx > left() + W || my < top() + HEAD) return null;
        int i = (int) ((my - top() - HEAD) / ROW);
        return i >= 0 && i < Modules.ALL.size() ? Modules.ALL.get(i) : null;
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        int x = left(), y = top(), h = HEAD + ROW * Modules.ALL.size();
        c.fill(x - 2, y - 2, x + W + 2, y + h + 2, 0xFF8A5CFF);
        c.fill(x, y, x + W, y + h, 0xF0111528);
        c.drawTextWithShadow(textRenderer, "EXTERNAL", x + 8, y + 9, 0xFF8A5CFF);
        Feature hover = rowAt(mx, my);
        for (int i = 0; i < Modules.ALL.size(); i++) {
            Feature f = Modules.ALL.get(i);
            int ry = y + HEAD + i * ROW;
            if (f.enabled) c.fill(x, ry, x + W, ry + ROW, 0x558A5CFF);
            if (f == hover) c.fill(x, ry, x + W, ry + ROW, 0x22FFFFFF);
            c.drawTextWithShadow(textRenderer, f.name, x + 8, ry + 7, f.enabled ? 0xFFFFFFFF : 0xFF9AA0B5);
            String r = (f.hasValue ? (int) f.value + "  " : "")
                    + (listening == f ? "[...]" : "[" + Commands.keyName(f.key) + "]");
            c.drawTextWithShadow(textRenderer, r, x + W - 8 - textRenderer.getWidth(r), ry + 7, 0xFF4FC3FF);
        }
        c.drawTextWithShadow(textRenderer, "LMB toggle | RMB bind | wheel value", x, y + h + 8, 0xFF7A8099);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        Feature f = rowAt(mx, my);
        if (f != null) {
            if (button == 0) f.toggle();
            else if (button == 1) listening = (listening == f) ? null : f;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        Feature f = rowAt(mx, my);
        if (f != null && f.hasValue) {
            f.value = Math.max(f.min, Math.min(f.max, f.value + Math.signum(vAmount) * f.step));
            Config.save();
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (listening != null) {
            listening.key = keyCode == GLFW.GLFW_KEY_ESCAPE ? -1 : keyCode;
            listening = null;
            Config.save();
            return true;
        }
        if (ExternalClient.guiKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}

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
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
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
    final String name, category;
    int key = -1;
    boolean enabled, wasDown, hasValue, open, hudOnly;
    double value, min, max, step;
    float anim;

    Feature(String name, String category) { this.name = name; this.category = category; }

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
        Config.save();
    }

    void onEnable(MinecraftClient mc) {}
    void onDisable(MinecraftClient mc) {}
    void onTick(MinecraftClient mc) {}
}

class HudFeature extends Feature {
    HudFeature(String name) { super(name, "HUD"); hudOnly = true; }
}

class Fullbright extends Feature {
    Fullbright() { super("Fullbright", "Visual"); }

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

    FovBase(String name) { super(name, "Visual"); }

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
    static final String[] CATS = {"Visual", "HUD"};
    static final List<Feature> ALL = new ArrayList<>();
    static HudFeature WATERMARK, LIST, COORDS, DIR, SPEED, CLOCK, KEYS, ARMOR;
    static double speed, lastX, lastZ;

    static HudFeature hud(String n, boolean on) {
        HudFeature f = new HudFeature(n);
        f.enabled = on;
        ALL.add(f);
        return f;
    }

    static void init() {
        ALL.add(new Fullbright());
        ALL.add(new Zoom());
        ALL.add(new Fov());
        WATERMARK = hud("Watermark", true);
        LIST = hud("ArrayList", true);
        COORDS = hud("Coordinates", false);
        DIR = hud("Direction", false);
        SPEED = hud("Speed", false);
        CLOCK = hud("Clock", false);
        KEYS = hud("Keystrokes", false);
        ARMOR = hud("Armor", false);
    }

    static Feature find(String n) {
        for (Feature f : ALL) if (f.name.equalsIgnoreCase(n)) return f;
        return null;
    }

    static List<Feature> in(String cat) {
        List<Feature> r = new ArrayList<>();
        for (Feature f : ALL) if (f.category.equals(cat)) r.add(f);
        return r;
    }

    static void tick(MinecraftClient mc) {
        if (mc.getWindow() == null) return;
        if (mc.player != null) {
            speed = Math.hypot(mc.player.getX() - lastX, mc.player.getZ() - lastZ) * 20.0;
            lastX = mc.player.getX();
            lastZ = mc.player.getZ();
        }
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
                if (f.hudOnly) o.addProperty("enabled", f.enabled);
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
                if (f.hudOnly && o.has("enabled")) f.enabled = o.get("enabled").getAsBoolean();
            }
        } catch (Exception ignored) {}
    }
}

/* ---------------- HUD ---------------- */

final class Hud {
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    static final int BG = 0xB0160A2E, ACCENT = 0xFFA678FF, WHITE = 0xFFFFFFFF, GRAY = 0xFF7A8099;

    static void render(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null) return;
        var tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();

        if (Modules.WATERMARK.enabled) watermark(ctx, mc);

        // ArrayList справа сверху
        if (Modules.LIST.enabled) {
            int y = 4;
            for (Feature f : Modules.ALL) {
                if (!f.enabled || f.hudOnly) continue;
                int tw = tr.getWidth(f.name);
                ctx.fill(w - tw - 10, y, w - 2, y + 11, BG);
                ctx.fill(w - 2, y, w, y + 11, ACCENT);
                ctx.drawTextWithShadow(tr, f.name, w - tw - 6, y + 2, WHITE);
                y += 11;
            }
        }

        // Информационный блок слева снизу
        List<String> lines = new ArrayList<>();
        var p = mc.player;
        if (Modules.COORDS.enabled)
            lines.add(String.format(Locale.ROOT, "XYZ  %.1f  %.1f  %.1f", p.getX(), p.getY(), p.getZ()));
        if (Modules.DIR.enabled) {
            String d = p.getHorizontalFacing().asString();
            lines.add("Facing  " + Character.toUpperCase(d.charAt(0)) + d.substring(1));
        }
        if (Modules.SPEED.enabled)
            lines.add(String.format(Locale.ROOT, "Speed  %.1f b/s", Modules.speed));
        if (Modules.CLOCK.enabled)
            lines.add("Time  " + LocalTime.now().format(HM));
        int top = h - 4;
        if (!lines.isEmpty()) {
            int mw = 0;
            for (String s : lines) mw = Math.max(mw, tr.getWidth(s));
            int bh = lines.size() * 11 + 4;
            top = h - 4 - bh;
            ctx.fill(4, top, 4 + mw + 12, top + bh, BG);
            ctx.fill(4, top, 6, top + bh, ACCENT);
            for (int i = 0; i < lines.size(); i++)
                ctx.drawTextWithShadow(tr, lines.get(i), 10, top + 4 + i * 11, WHITE);
        }

        // Keystrokes над информационным блоком
        if (Modules.KEYS.enabled) {
            int ky = top - 6 - 36;
            key(ctx, tr, 23, ky, "W", mc.options.forwardKey.isPressed());
            key(ctx, tr, 4, ky + 19, "A", mc.options.leftKey.isPressed());
            key(ctx, tr, 23, ky + 19, "S", mc.options.backKey.isPressed());
            key(ctx, tr, 42, ky + 19, "D", mc.options.rightKey.isPressed());
        }

        // Броня справа по центру
        if (Modules.ARMOR.enabled) {
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            for (int i = 0; i < slots.length; i++) {
                var st = p.getEquippedStack(slots[i]);
                if (!st.isEmpty()) ctx.drawItem(st, w - 22, h / 2 - 32 + i * 17);
            }
        }
    }

    static void watermark(DrawContext ctx, MinecraftClient mc) {
        var tr = mc.textRenderer;
        var nh = mc.getNetworkHandler();
        var entry = (nh != null && mc.player != null) ? nh.getPlayerListEntry(mc.player.getUuid()) : null;
        int ping = entry != null ? entry.getLatency() : 0;
        String a = mc.getCurrentFps() + " FPS", sep = " | ", b = "EXTERNAL", c = ping + " ms";
        int width = tr.getWidth(a) + tr.getWidth(sep) * 2 + tr.getWidth(b) + tr.getWidth(c) + 14;
        ctx.fill(4, 4, 4 + width, 20, BG);
        ctx.fill(4, 4, 6, 20, ACCENT);
        int x = 11;
        ctx.drawTextWithShadow(tr, a, x, 8, WHITE); x += tr.getWidth(a);
        ctx.drawTextWithShadow(tr, sep, x, 8, GRAY); x += tr.getWidth(sep);
        ctx.drawTextWithShadow(tr, b, x, 8, ACCENT); x += tr.getWidth(b);
        ctx.drawTextWithShadow(tr, sep, x, 8, GRAY); x += tr.getWidth(sep);
        ctx.drawTextWithShadow(tr, c, x, 8, WHITE);
    }

    static void key(DrawContext ctx, net.minecraft.client.font.TextRenderer tr, int x, int y, String s, boolean down) {
        ctx.fill(x, y, x + 17, y + 17, down ? 0xCC8A5CFF : BG);
        ctx.drawTextWithShadow(tr, s, x + (17 - tr.getWidth(s)) / 2, y + 5, WHITE);
    }
}

/* ---------------- Клик-гуи ---------------- */

class ClickGuiScreen extends Screen {
    private static final int PW = 116, HEAD = 24, ROW = 17, SUB = 16;
    private static final int[] PX = new int[2], PY = new int[2];
    private static boolean placed;
    private final long t0 = System.currentTimeMillis();
    private Feature listening, sliding;
    private int slidingPanel, dragPanel = -1;
    private double dragDx, dragDy;

    ClickGuiScreen() { super(Text.literal("External")); }

    @Override protected void init() {
        if (!placed) {
            int total = Modules.CATS.length * PW + (Modules.CATS.length - 1) * 10;
            for (int i = 0; i < Modules.CATS.length; i++) {
                PX[i] = width / 2 - total / 2 + i * (PW + 10);
                PY[i] = Math.max(10, height / 2 - 90);
            }
            placed = true;
        }
    }

    @Override public boolean shouldPause() { return false; }

    private float alpha() { return Math.max(0.05f, Math.min(1f, (System.currentTimeMillis() - t0) / 180f)); }
    private static int argb(float a, int rgb) { return ((int) (Math.max(0f, Math.min(1f, a)) * 255) << 24) | (rgb & 0xFFFFFF); }
    private static int rowH(Feature f) { return ROW + (f.open ? (f.hasValue ? SUB : 0) + SUB : 0); }

    private void setSlider(Feature f, double mx, int panel) {
        double t = Math.max(0, Math.min(1, (mx - (PX[panel] + 8)) / (PW - 16)));
        double v = f.min + Math.round((f.min + t * (f.max - f.min) - f.min) / f.step) * f.step;
        f.value = Math.max(f.min, Math.min(f.max, v));
    }

    private static void rrect(DrawContext c, int x1, int y1, int x2, int y2, int r, int col) {
        c.fill(x1, y1 + r, x2, y2 - r, col);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt((double) r * r - (double) (r - i) * (r - i)));
            c.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, col);
            c.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, col);
        }
    }

    private static int lerp(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static void check(DrawContext c, int x, int y, int col) {
        int[][] pts = {{0, 3}, {1, 4}, {2, 3}, {3, 2}, {4, 1}, {5, 0}};
        for (int[] q : pts) c.fill(x + q[0], y + q[1], x + q[0] + 1, y + q[1] + 2, col);
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        float al = alpha();
        c.fillGradient(0, 0, width, height, argb(al * 0.55f, 0x24104F), argb(al * 0.80f, 0x05020F));
        for (int p = 0; p < Modules.CATS.length; p++) {
            int x = PX[p], y = PY[p];
            List<Feature> fs = Modules.in(Modules.CATS[p]);
            int h = HEAD + 6;
            for (Feature f : fs) h += rowH(f);
            rrect(c, x, y, x + PW, y + h, 7, argb(al * 0.93f, 0x170B30));
            c.drawTextWithShadow(textRenderer, Modules.CATS[p], x + 10, y + 8, argb(al, 0xFFFFFF));
            rrect(c, x + PW - 18, y + 8, x + PW - 10, y + 16, 3, argb(al, 0xA678FF));
            int ry = y + HEAD;
            for (Feature f : fs) {
                f.anim += ((f.enabled ? 1f : 0f) - f.anim) * 0.3f;
                boolean hov = mx >= x && mx < x + PW && my >= ry && my < ry + ROW;
                if (hov) rrect(c, x + 4, ry, x + PW - 4, ry + ROW, 4, argb(al * 0.10f, 0xFFFFFF));
                int col = lerp(0xFFFFFF, 0xB57CFF, f.anim);
                c.drawTextWithShadow(textRenderer, f.name, x + 10, ry + 5, argb(al, col));
                if (f.enabled) check(c, x + PW - 17, ry + 5, argb(al, 0xB57CFF));
                else if (f.hasValue) c.drawTextWithShadow(textRenderer, f.open ? "-" : "+", x + PW - 15, ry + 5, argb(al, 0x6B5C8F));

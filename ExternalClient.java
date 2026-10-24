package com.external;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.ActionResult;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
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
        AttackEntityCallback.EVENT.register((pl, world, hand, entity, hit) -> {
            if (entity instanceof LivingEntity le) Target.set(le);
            return ActionResult.PASS;
        });
        ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
            if (!(screen instanceof ChatScreen)) return;
            ScreenMouseEvents.allowMouseClick(screen).register((scr, mx, my, btn) -> Target.mouseDown(mx, my, btn));
            ScreenMouseEvents.allowMouseRelease(screen).register((scr, mx, my, btn) -> Target.mouseUp());
            ScreenEvents.afterRender(screen).register((scr, ctx, mx, my, delta) -> Target.drag(mx, my));
        });
    }
}

/* ---------------- Модули ---------------- */

class Feature {
    final String name, category;
    int key = -1;
    boolean enabled, wasDown, hasValue, open, hudOnly;
    double value, min, max, step;
    float anim;
    String desc;

    Feature(String name, String category) { this.name = name; this.category = category; }

    Feature d(String s) { desc = s; return this; }

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
    HudFeature(String name, String cat) { super(name, cat); hudOnly = true; }
}

class Fullbright extends Feature {
    Fullbright() { super("Fullbright", "Visuals"); }

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

    FovBase(String name) { super(name, "Visuals"); }

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

class NoBob extends Feature {
    Boolean old;
    NoBob() { super("NoBob", "Performance"); }
    @Override void onEnable(MinecraftClient mc) {
        old = mc.options.getBobView().getValue();
        mc.options.getBobView().setValue(false);
    }
    @Override void onDisable(MinecraftClient mc) {
        if (old != null) mc.options.getBobView().setValue(old);
        old = null;
    }
}

class NoClouds extends Feature {
    CloudRenderMode old;
    NoClouds() { super("NoClouds", "Performance"); }
    @Override void onEnable(MinecraftClient mc) {
        old = mc.options.getCloudRenderMode().getValue();
        mc.options.getCloudRenderMode().setValue(CloudRenderMode.OFF);
    }
    @Override void onDisable(MinecraftClient mc) {
        if (old != null) mc.options.getCloudRenderMode().setValue(old);
        old = null;
    }
}

class MinParticles extends Feature {
    Object old;
    MinParticles() { super("Particles", "Performance"); }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override void onEnable(MinecraftClient mc) {
        SimpleOption opt = mc.options.getParticles();
        old = opt.getValue();
        if (old instanceof Enum<?> e) {
            Object[] vals = e.getDeclaringClass().getEnumConstants();
            if (vals != null && vals.length > 0) opt.setValue(vals[vals.length - 1]);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override void onDisable(MinecraftClient mc) {
        if (old != null) ((SimpleOption) mc.options.getParticles()).setValue(old);
        old = null;
    }
}

class RenderDist extends Feature {
    Integer old;
    RenderDist() { super("RenderDist", "Performance"); range(8, 2, 32, 1); }
    @Override void onEnable(MinecraftClient mc) { old = mc.options.getViewDistance().getValue(); apply(mc); }
    @Override void onTick(MinecraftClient mc) { apply(mc); }
    void apply(MinecraftClient mc) {
        int v = (int) value;
        if (mc.options.getViewDistance().getValue() != v) mc.options.getViewDistance().setValue(v);
    }
    @Override void onDisable(MinecraftClient mc) {
        if (old != null) mc.options.getViewDistance().setValue(old);
        old = null;
    }
}

class Zoom extends FovBase {
    Zoom() { super("Zoom"); range(30, 30, 80, 5); }
}

class Fov extends FovBase {
    Fov() { super("FOV"); range(100, 70, 110, 5); }
}

class FpsCap extends Feature {
    Integer old;
    FpsCap() { super("FpsCap", "Performance"); range(144, 30, 260, 10); }
    @Override void onEnable(MinecraftClient mc) { old = mc.options.getMaxFps().getValue(); apply(mc); }
    @Override void onTick(MinecraftClient mc) { apply(mc); }
    void apply(MinecraftClient mc) {
        int v = (int) value;
        if (mc.options.getMaxFps().getValue() != v) mc.options.getMaxFps().setValue(v);
    }
    @Override void onDisable(MinecraftClient mc) {
        if (old != null) mc.options.getMaxFps().setValue(old);
        old = null;
    }
}

class EntityDist extends Feature {
    Double old;
    EntityDist() { super("EntityDist", "Performance"); range(1.0, 0.5, 5.0, 0.5); }
    @Override void onEnable(MinecraftClient mc) { old = mc.options.getEntityDistanceScaling().getValue(); apply(mc); }
    @Override void onTick(MinecraftClient mc) { apply(mc); }
    void apply(MinecraftClient mc) {
        double v = value;
        if (Math.abs(mc.options.getEntityDistanceScaling().getValue() - v) > 0.01) mc.options.getEntityDistanceScaling().setValue(v);
    }
    @Override void onDisable(MinecraftClient mc) {
        if (old != null) mc.options.getEntityDistanceScaling().setValue(old);
        old = null;
    }
}

class TargetFX extends Feature {
    int t;
    TargetFX() { super("TargetFX", "Extra"); range(1, 1, 3, 1); }

    @Override void onTick(MinecraftClient mc) {
        var e = Target.cur;
        if (e == null || mc.world == null) return;
        t++;
        double x = e.getX(), y = e.getY(), z = e.getZ(), hg = e.getHeight();
        int mode = (int) value;
        if (mode == 1) {                       // духи: две изогнутые светящиеся ленты кружат вокруг цели
            for (int k = 0; k < 2; k++) {
                double dir = k == 0 ? 1 : -1;     // вторая лента летит с другой стороны, наклонно
                for (int j = 0; j < 26; j++) {
                    if (j > 8 && j % 2 == 1) continue;
                    if (j > 16 && j % 4 != 0) continue;
                    double a = t * 0.16 * dir + k * Math.PI - j * 0.07 * dir;
                    double yy = y + hg * 0.5 + Math.sin(a) * hg * 0.28 + Math.sin(t * 0.05 + k) * hg * 0.12;
                    double px = x + Math.cos(a) * 0.9, pz = z + Math.sin(a) * 0.9;
                    if (j < 12) mc.world.addParticle(ParticleTypes.END_ROD, px, yy, pz, 0, 0, 0);
                    else mc.world.addParticle(ParticleTypes.WITCH, px, yy, pz, 0, 0, 0);
                }
            }
        } else if (mode == 2) {                // молнии на теле
            for (int k = 0; k < 4; k++) {
                mc.world.addParticle(ParticleTypes.ELECTRIC_SPARK,
                        x + (Math.random() - 0.5) * 0.7, y + Math.random() * hg, z + (Math.random() - 0.5) * 0.7,
                        (Math.random() - 0.5) * 0.3, (Math.random() - 0.5) * 0.3, (Math.random() - 0.5) * 0.3);
            }
        } else {                               // кольцо
            for (int k = 0; k < 12; k++) {
                double a = t * 0.1 + k * Math.PI * 2 / 12;
                mc.world.addParticle(ParticleTypes.END_ROD, x + Math.cos(a) * 0.9, y + 0.15, z + Math.sin(a) * 0.9, 0, 0, 0);
            }
        }
    }
}

class DeathCoords extends Feature {
    boolean dead;
    DeathCoords() { super("DeathCoords", "Extra"); }
    @Override void onTick(MinecraftClient mc) {
        if (mc.player == null) return;
        if (!mc.player.isAlive()) {
            if (!dead) {
                dead = true;
                Chat.say("\u00a7dDeath at \u00a7f" + (int) Math.floor(mc.player.getX()) + " "
                        + (int) Math.floor(mc.player.getY()) + " " + (int) Math.floor(mc.player.getZ()));
            }
        } else dead = false;
    }
}

final class UiFont {
    static final Style S = Style.EMPTY.withFont(Identifier.of("external", "ui"));

    static Text t(String s) { return Text.literal(s).setStyle(S); }
    static int w(TextRenderer tr, String s) { return tr.getWidth(t(s)); }
    static void draw(DrawContext c, TextRenderer tr, String s, int x, int y, int color) {
        c.drawText(tr, t(s), x, y, color, false);
    }
    static void drawBold(DrawContext c, TextRenderer tr, String s, int x, int y, int color) {
        c.drawText(tr, Text.literal(s).setStyle(S.withBold(true)), x, y, color, false);
    }
    static void drawCentered(DrawContext c, TextRenderer tr, String s, int cx, int y, int color) {
        draw(c, tr, s, cx - w(tr, s) / 2, y, color);
    }
}

final class Theme {
    static final int[] COLORS = {0xA678FF, 0x4FC3FF, 0xFF6EB4, 0x5CFFA0, 0xFFA05C};
    static int idx = 0;
    static int rgb() { return COLORS[idx]; }
    static int argb() { return 0xFF000000 | rgb(); }
    static int light() {
        int c = rgb();
        int r = ((c >> 16) & 255), g = ((c >> 8) & 255), b = (c & 255);
        return ((r + (255 - r) / 4) << 16) | ((g + (255 - g) / 4) << 8) | (b + (255 - b) / 4);
    }
}

final class Modules {
    static final String[] CATS = {"Visuals", "Performance", "Panels", "Widgets", "Extra"};
    static final List<Feature> ALL = new ArrayList<>();
    static HudFeature WATERMARK, TIME, INFO, STATS, EFFECTS, BINDS, ARMOR;
    static HudFeature KEYS, CPS, DIR, PLAYERS, HELD, ITEMS, NETHER, LIGHT, DAY, MEMORY, LIST, TARGETHUD;
    static Feature FULLBRIGHT;
    static double speed, lastX, lastZ;

    static HudFeature hud(String n, String cat, boolean on, String desc) {
        HudFeature f = new HudFeature(n, cat);
        f.enabled = on;
        f.desc = desc;
        ALL.add(f);
        return f;
    }

    static void init() {
        FULLBRIGHT = new Fullbright().d("Полная яркость без факела");
        ALL.add(FULLBRIGHT);
        ALL.add(new Zoom().d("Приближение камеры"));
        ALL.add(new Fov().d("Свой угол обзора"));
        ALL.add(new NoBob().d("Убирает покачивание камеры при ходьбе"));
        ALL.add(new NoClouds().d("Отключает облака для FPS"));
        ALL.add(new MinParticles().d("Минимум частиц для FPS"));
        ALL.add(new RenderDist().d("Дальность прорисовки в чанках"));
        ALL.add(new FpsCap().d("Лимит FPS (260 = без лимита)"));
        ALL.add(new EntityDist().d("Дальность прорисовки мобов"));
        WATERMARK = hud("Watermark", "Panels", true, "Название клиента вверху по центру");
        TIME = hud("Time", "Panels", true, "Часы рядом с названием");
        INFO = hud("Info", "Panels", true, "Координаты и режим игры");
        STATS = hud("Stats", "Panels", true, "FPS, скорость и пинг");
        EFFECTS = hud("Effects", "Panels", true, "Эффекты с таймером слева");
        BINDS = hud("Binds", "Panels", false, "Список твоих биндов справа");
        ARMOR = hud("Armor", "Panels", true, "Броня и прочность возле хотбара");
        KEYS = hud("Keystrokes", "Widgets", false, "Показ нажатых клавиш WASD");
        CPS = hud("CPS", "Widgets", false, "Клики в секунду");
        DIR = hud("Direction", "Widgets", false, "Куда ты смотришь");
        PLAYERS = hud("Players", "Widgets", false, "Игроков на сервере, крупно");
        HELD = hud("HeldItem", "Widgets", false, "Предмет в руке и прочность");
        ITEMS = hud("Items", "Widgets", false, "Тотемы, яблоки, жемчуг, кристаллы");
        NETHER = hud("Nether", "Widgets", false, "Координаты для портала в Незер");
        LIGHT = hud("Light", "Widgets", false, "Уровень света под ногами");
        DAY = hud("Day", "Widgets", false, "Номер игрового дня");
        MEMORY = hud("Memory", "Widgets", false, "Использование оперативной памяти");
        LIST = hud("ArrayList", "Panels", true, "Список включённых модулей справа сверху");
        TARGETHUD = hud("TargetHUD", "Extra", true, "Ник, HP и дистанция цели. Двигай мышкой при открытом чате");
        ALL.add(new TargetFX().d("Эффект на цели: 1 духи, 2 молнии, 3 кольцо (выключи Particles)"));
        ALL.add(new DeathCoords().d("Пишет в чат координаты смерти"));
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
        Target.tick(mc);
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
            root.addProperty("_theme", Theme.idx);
            root.addProperty("_tx", Target.px);
            root.addProperty("_ty", Target.py);
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
            if (root.has("_tx")) Target.px = root.get("_tx").getAsInt();
            if (root.has("_ty")) Target.py = root.get("_ty").getAsInt();
            if (root.has("_theme")) Theme.idx = Math.max(0, Math.min(Theme.COLORS.length - 1, root.get("_theme").getAsInt()));
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

/* ---------------- HUD (стекло, как в iOS) ---------------- */

final class Target {
    static LivingEntity cur;
    static long seen;
    static float shown;
    static int px = -1, py = -1, offX, offY;
    static boolean dragging;
    static final int W = 134, H = 48;

    static void set(LivingEntity e) { cur = e; seen = System.currentTimeMillis(); }

    static void tick(MinecraftClient mc) {
        if (mc.player == null) { cur = null; return; }
        var t = mc.targetedEntity;
        if (t instanceof LivingEntity le && le != mc.player) set(le);
        if (cur != null && (!cur.isAlive() || cur.isRemoved() || System.currentTimeMillis() - seen > 3000)) cur = null;
    }

    static boolean mouseDown(double mx, double my, int btn) {
        if (btn == 0 && Modules.TARGETHUD.enabled && px >= 0 && mx >= px && mx <= px + W && my >= py && my <= py + H) {
            dragging = true;
            offX = (int) mx - px;
            offY = (int) my - py;
            return false;
        }
        return true;
    }

    static boolean mouseUp() {
        if (dragging) { dragging = false; Config.save(); }
        return true;
    }

    static void drag(double mx, double my) {
        if (!dragging) return;
        px = (int) mx - offX;
        py = (int) my - offY;
    }

    static void render(DrawContext ctx, MinecraftClient mc, int w, int h) {
        if (!Modules.TARGETHUD.enabled) return;
        boolean preview = mc.currentScreen instanceof ChatScreen;
        if (cur == null && !preview) return;
        if (px < 0) { px = w / 2 + 24; py = h / 2 + 24; }
        px = Math.max(0, Math.min(w - W, px));
        py = Math.max(0, Math.min(h - H, py));
        var tr = mc.textRenderer;
        String name; float hp, max, abs; double dist;
        if (cur != null) {
            name = cur.getName().getString();
            hp = cur.getHealth(); max = Math.max(1f, cur.getMaxHealth()); abs = cur.getAbsorptionAmount();
            dist = mc.player.distanceTo(cur);
        } else { name = "Target"; hp = 20; max = 20; abs = 0; dist = 3.5; }
        shown += (hp - shown) * 0.15f;
        if (Math.abs(hp - shown) < 0.05f) shown = hp;

        Hud.glass(ctx, px, py, W, H);
        if (dragging) ClickGuiScreen.rrect(ctx, px - 1, py - 1, px + W + 1, py + H + 1, 8, (Theme.argb() & 0x00FFFFFF) | 0x30000000);
        ClickGuiScreen.rrect(ctx, px + 6, py + 6, px + 30, py + 30, 6, Theme.argb());
        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        UiFont.drawBold(ctx, tr, letter, px + 18 - UiFont.w(tr, letter) / 2, py + 14, 0xFFFFFFFF);
        String nm = name;
        while (UiFont.w(tr, nm) > W - 42 && nm.length() > 2) nm = nm.substring(0, nm.length() - 1);
        UiFont.drawBold(ctx, tr, nm, px + 36, py + 7, 0xFFFFFFFF);
        UiFont.draw(ctx, tr, String.format(java.util.Locale.ROOT, "%.1f m", dist), px + 36, py + 19, Hud.DIM);
        String hpTxt = String.format(java.util.Locale.ROOT, "%.1f", hp) + (abs > 0 ? " +" + (int) abs : "");
        UiFont.draw(ctx, tr, hpTxt, px + W - 6 - UiFont.w(tr, hpTxt), py + 19, 0xFFFFFFFF);

        float ratio = Math.max(0f, Math.min(1f, shown / max));
        int bx = px + 6, by = py + H - 12, bw = W - 12;
        ClickGuiScreen.rrect(ctx, bx, by, bx + bw, by + 6, 3, 0x50000000);
        int fw = Math.max(6, (int) (bw * ratio));
        int col = 0xFF000000 | ((int) ((1 - ratio) * 235 + 20) << 16) | ((int) (ratio * 190 + 50) << 8) | 0x50;
        if (hp > 0 || preview) ClickGuiScreen.rrect(ctx, bx, by, bx + fw, by + 6, 3, col);
        if (preview && cur == null) UiFont.draw(ctx, tr, "drag me", px + W + 4, py + 4, Hud.DIM);
    }
}

final class Hud {
    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    static final int WHITE = 0xFFFFFFFF, DIM = 0xFFB4B4C8;
    static final String DOT = " \u00b7 ";
    static final ArrayDeque<Long> CLICKS = new ArrayDeque<>();
    static boolean lastDown;

    static int fl(double d) { return (int) Math.floor(d); }

    static void glass(DrawContext c, int x, int y, int w, int h) {
        int r = Math.min(7, h / 2);
        ClickGuiScreen.rrect(c, x - 1, y + 1, x + w + 1, y + h + 2, r + 1, 0x26000000);   // мягкая тень
        ClickGuiScreen.rrect(c, x, y, x + w, y + h, r, 0x3C0C0C18);                        // прозрачная основа
        ClickGuiScreen.rrect(c, x, y, x + w, y + h, r, 0x18FFFFFF);                        // молочная плёнка
        c.fillGradient(x + r, y + 1, x + w - r, y + Math.max(2, h / 2), 0x38FFFFFF, 0x00FFFFFF); // блик сверху
        c.fill(x + r, y, x + w - r, y + 1, 0x70FFFFFF);                                    // верхняя кромка
        c.fill(x + r, y + h - 1, x + w - r, y + h, 0x30FFFFFF);                            // нижняя кромка
        c.fill(x, y + r, x + 1, y + h - r, 0x30FFFFFF);                                    // левая кромка
        c.fill(x + w - 1, y + r, x + w, y + h - r, 0x24FFFFFF);                            // правая кромка
    }

    /** Стеклянная плашка с цветной точкой и текстом; части через « · » */
    static int pill(DrawContext c, net.minecraft.client.font.TextRenderer tr, int x, int y, String text) {
        String[] parts = text.split(" \u00b7 ");
        int w = 20 + (parts.length - 1) * UiFont.w(tr, DOT);
        for (String s : parts) w += UiFont.w(tr, s);
        glass(c, x, y, w, 16);
        ClickGuiScreen.rrect(c, x + 5, y + 5, x + 11, y + 11, 3, Theme.argb());
        int cx = x + 15;
        for (int i = 0; i < parts.length; i++) {
            UiFont.draw(c, tr, parts[i], cx, y + 4, WHITE);
            cx += UiFont.w(tr, parts[i]);
            if (i < parts.length - 1) { UiFont.draw(c, tr, DOT, cx, y + 4, DIM); cx += UiFont.w(tr, DOT); }
        }
        return w;
    }

    static int cps(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !lastDown) CLICKS.add(now);
        lastDown = down;
        while (!CLICKS.isEmpty() && now - CLICKS.peekFirst() > 1000) CLICKS.pollFirst();
        return CLICKS.size();
    }

    static void logo(DrawContext c, int x, int y) {
        ClickGuiScreen.rrect(c, x, y, x + 12, y + 12, 3, Theme.argb());
        c.fill(x + 3, y + 3, x + 4, y + 9, WHITE);
        c.fill(x + 3, y + 3, x + 9, y + 4, WHITE);
        c.fill(x + 3, y + 5, x + 7, y + 6, WHITE);
        c.fill(x + 3, y + 8, x + 9, y + 9, WHITE);
    }

    static void key(DrawContext ctx, net.minecraft.client.font.TextRenderer tr, int x, int y, String s, boolean down) {
        glass(ctx, x, y, 17, 17);
        if (down) ClickGuiScreen.rrect(ctx, x, y, x + 17, y + 17, 6, (Theme.argb() & 0x00FFFFFF) | 0x99000000);
        UiFont.draw(ctx, tr, s, x + (17 - UiFont.w(tr, s)) / 2, y + 5, WHITE);
    }

    static void render(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.player == null) return;
        var tr = mc.textRenderer;
        var p = mc.player;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        var nh = mc.getNetworkHandler();
        var entry = nh != null ? nh.getPlayerListEntry(p.getUuid()) : null;
        int ping = entry != null ? entry.getLatency() : 0;

        // --- слева сверху: стопка плашек
        int y = 6;
        if (Modules.INFO.enabled) {
            pill(ctx, tr, 6, y, fl(p.getX()) + " " + fl(p.getY()) + " " + fl(p.getZ()) + DOT + (mc.isInSingleplayer() ? "Single" : "Online"));
            y += 19;
        }
        if (Modules.STATS.enabled) {
            pill(ctx, tr, 6, y, mc.getCurrentFps() + " FPS" + DOT + String.format(Locale.ROOT, "%.2f BPS", Modules.speed) + DOT + ping + " ms");
            y += 19;
        }
        if (Modules.DIR.enabled) {
            String d = p.getHorizontalFacing().asString();
            pill(ctx, tr, 6, y, "Facing " + Character.toUpperCase(d.charAt(0)) + d.substring(1));
            y += 19;
        }
        if (Modules.NETHER.enabled) {
            pill(ctx, tr, 6, y, "Nether " + fl(p.getX() / 8) + " " + fl(p.getY()) + " " + fl(p.getZ() / 8));
            y += 19;
        }
        if (Modules.LIGHT.enabled && mc.world != null) {
            pill(ctx, tr, 6, y, "Light " + mc.world.getLightLevel(p.getBlockPos()));
            y += 19;
        }
        if (Modules.DAY.enabled && mc.world != null) {
            pill(ctx, tr, 6, y, "Day " + (mc.world.getTimeOfDay() / 24000L));
            y += 19;
        }
        if (Modules.MEMORY.enabled) {
            Runtime rt = Runtime.getRuntime();
            long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
            pill(ctx, tr, 6, y, "RAM " + used + "/" + (rt.maxMemory() >> 20) + " MB");
        }

        // --- сверху по центру: название и время
        int cx = w / 2;
        String time = LocalTime.now().format(HM);
        int ww = UiFont.w(tr, "EXTERNAL") + 30, tw = UiFont.w(tr, time) + 14;
        int total = (Modules.WATERMARK.enabled ? ww : 0) + (Modules.TIME.enabled ? tw : 0)
                + (Modules.WATERMARK.enabled && Modules.TIME.enabled ? 4 : 0);
        int x0 = cx - total / 2;
        if (Modules.WATERMARK.enabled) {
            glass(ctx, x0, 6, ww, 16);
            logo(ctx, x0 + 4, 8);
            UiFont.draw(ctx, tr, "EXTERNAL", x0 + 21, 10, WHITE);
            x0 += ww + 4;
        }
        if (Modules.TIME.enabled) {
            glass(ctx, x0, 6, tw, 16);
            UiFont.draw(ctx, tr, time, x0 + 7, 10, WHITE);
        }
        if (Modules.PLAYERS.enabled && nh != null) {
            ctx.getMatrices().push();
            ctx.getMatrices().translate(cx, 28, 0);
            ctx.getMatrices().scale(2f, 2f, 1f);
            UiFont.drawCentered(ctx, tr, nh.getPlayerList().size() + " online", 0, 0, WHITE);
            ctx.getMatrices().pop();
        }

        // --- справа сверху: список модулей
        if (Modules.LIST.enabled) {
            int ry = 6;
            for (Feature f : Modules.ALL) {
                if (!f.enabled || f.hudOnly) continue;
                int fw = UiFont.w(tr, f.name) + 14;
                glass(ctx, w - 6 - fw, ry, fw, 14);
                UiFont.draw(ctx, tr, f.name, w - 6 - fw + 7, ry + 3, WHITE);
                ry += 17;
            }
        }

        // --- слева по центру: эффекты
        if (Modules.EFFECTS.enabled) {
            List<String[]> rows = new ArrayList<>();
            for (var e : p.getStatusEffects()) {
                if (e.getEffectType() == StatusEffects.NIGHT_VISION && Modules.FULLBRIGHT.enabled) continue;
                int sec = e.getDuration() / 20;
                String t = e.isInfinite() ? "inf" : (sec / 60) + ":" + String.format(Locale.ROOT, "%02d", sec % 60);
                rows.add(new String[]{e.getEffectType().value().getName().getString() + " " + (e.getAmplifier() + 1), t});
            }
            if (!rows.isEmpty()) {
                int pw = UiFont.w(tr, "Effects") + 20;
                for (String[] r : rows) pw = Math.max(pw, UiFont.w(tr, r[0]) + UiFont.w(tr, r[1]) + 28);
                int ph = 20 + rows.size() * 12;
                int py = h / 2 - ph / 2;
                glass(ctx, 6, py, pw, ph);
                UiFont.draw(ctx, tr, "Effects", 12, py + 5, DIM);
                for (int i = 0; i < rows.size(); i++) {
                    UiFont.draw(ctx, tr, rows.get(i)[0], 12, py + 18 + i * 12, WHITE);
                    UiFont.draw(ctx, tr, rows.get(i)[1], 6 + pw - 6 - UiFont.w(tr, rows.get(i)[1]), py + 18 + i * 12, DIM);
                }
            }
        }

        // --- справа по центру: бинды
        if (Modules.BINDS.enabled) {
            List<String[]> rows = new ArrayList<>();
            for (Feature f : Modules.ALL) if (f.key != -1) rows.add(new String[]{f.name, Commands.keyName(f.key)});
            if (!rows.isEmpty()) {
                int pw = UiFont.w(tr, "Keys") + 20;
                for (String[] r : rows) pw = Math.max(pw, UiFont.w(tr, r[0]) + UiFont.w(tr, r[1]) + 28);
                int ph = 20 + rows.size() * 12;
                int px = w - 6 - pw, py = h / 2 - ph / 2;
                glass(ctx, px, py, pw, ph);
                UiFont.draw(ctx, tr, "Keys", px + 6, py + 5, DIM);
                for (int i = 0; i < rows.size(); i++) {
                    UiFont.draw(ctx, tr, rows.get(i)[0], px + 6, py + 18 + i * 12, WHITE);
                    UiFont.draw(ctx, tr, rows.get(i)[1], px + pw - 6 - UiFont.w(tr, rows.get(i)[1]), py + 18 + i * 12, Theme.argb());
                }
            }
        }

        // --- снизу слева: клавиши и CPS
        if (Modules.KEYS.enabled) {
            int ky = h - 62;
            key(ctx, tr, 25, ky, "W", mc.options.forwardKey.isPressed());
            key(ctx, tr, 6, ky + 19, "A", mc.options.leftKey.isPressed());
            key(ctx, tr, 25, ky + 19, "S", mc.options.backKey.isPressed());
            key(ctx, tr, 44, ky + 19, "D", mc.options.rightKey.isPressed());
        }
        if (Modules.CPS.enabled) pill(ctx, tr, 6, h - 22, "CPS " + cps(mc));

        // --- по центру над хотбаром: предмет в руке
        if (Modules.HELD.enabled) {
            var st = p.getMainHandStack();
            if (!st.isEmpty()) {
                String n = st.getName().getString();
                if (st.getCount() > 1) n += " x" + st.getCount();
                int max = st.getMaxDamage();
                if (max > 0) n += DOT + ((max - st.getDamage()) * 100 / max) + "%";
                int pw = 20 + UiFont.w(tr, n.replace(DOT, "")) + UiFont.w(tr, DOT);
                pill(ctx, tr, cx - pw / 2, h - 52, n);
            }
        }

        // --- справа от хотбара: броня
        if (Modules.ARMOR.enabled) {
            EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
            List<ItemStack> worn = new ArrayList<>();
            for (EquipmentSlot sl : slots) { var st = p.getEquippedStack(sl); if (!st.isEmpty()) worn.add(st); }
            if (!worn.isEmpty()) {
                int bw = worn.size() * 24 + 6, bx = cx + 98, by = h - 34;
                glass(ctx, bx, by, bw, 31);
                for (int i = 0; i < worn.size(); i++) {
                    var st = worn.get(i);
                    ctx.drawItem(st, bx + 6 + i * 24, by + 3);
                    int max = st.getMaxDamage();
                    if (max > 0) {
                        String pct = ((max - st.getDamage()) * 100 / max) + "%";
                        UiFont.draw(ctx, tr, pct, bx + 6 + i * 24 + 8 - UiFont.w(tr, pct) / 2, by + 21, DIM);
                    }
                }
            }
        }

        // --- снизу справа: предметы
        if (Modules.ITEMS.enabled) {
            net.minecraft.item.Item[] its = {Items.TOTEM_OF_UNDYING, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.END_CRYSTAL};
            int bw = 6;
            int[] cnt = new int[its.length];
            for (int i = 0; i < its.length; i++) { cnt[i] = p.getInventory().count(its[i]); bw += 20 + UiFont.w(tr, String.valueOf(cnt[i])) + 6; }
            int bx = w - 6 - bw, by = h - 24;
            glass(ctx, bx, by, bw, 20);
            int ix = bx + 6;
            for (int i = 0; i < its.length; i++) {
                ctx.drawItem(new ItemStack(its[i]), ix, by + 2);
                UiFont.draw(ctx, tr, String.valueOf(cnt[i]), ix + 18, by + 6, WHITE);
                ix += 20 + UiFont.w(tr, String.valueOf(cnt[i])) + 6;
            }
        }

        Target.render(ctx, mc, w, h);
    }
}

/* ---------------- Клик-гуи ---------------- */

class ClickGuiScreen extends Screen {
    private static final int HEAD = 26, ROW = 17, SUB = 16, GAP = 8;
    private final long t0 = System.currentTimeMillis();
    private Feature listening, sliding;
    private int slidingPanel;

    ClickGuiScreen() { super(Text.literal("External")); }

    @Override public boolean shouldPause() { return false; }

    private int count() { return Modules.CATS.length + 1; }
    private int pw() { return Math.max(84, Math.min(122, (width - 24 - (count() - 1) * GAP) / count())); }
    private int px(int i) { return width / 2 - (count() * pw() + (count() - 1) * GAP) / 2 + i * (pw() + GAP); }
    private int top() { return Math.max(14, height / 2 - 105); }
    private float alpha() { return Math.max(0.05f, Math.min(1f, (System.currentTimeMillis() - t0) / 200f)); }
    static int argb(float a, int rgb) { return ((int) (Math.max(0f, Math.min(1f, a)) * 255) << 24) | (rgb & 0xFFFFFF); }
    private static int rowH(Feature f) { return ROW + (f.open ? (f.hasValue ? SUB : 0) + SUB : 0); }

    private int panelH(int p) {
        int h = HEAD + 6;
        for (Feature f : Modules.in(Modules.CATS[p])) h += rowH(f);
        return h;
    }

    static void rrect(DrawContext c, int x1, int y1, int x2, int y2, int r, int col) {
        c.fill(x1, y1 + r, x2, y2 - r, col);
        for (int i = 0; i < r; i++) {
            int inset = r - (int) Math.round(Math.sqrt((double) r * r - (double) (r - i) * (r - i)));
            c.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, col);
            c.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, col);
        }
    }

    private static void glassPanel(DrawContext c, int x, int y, int w, int h, float al) {
        int r = 9;
        rrect(c, x - 1, y + 2, x + w + 1, y + h + 3, r + 1, argb(al * 0.18f, 0x000000));
        rrect(c, x, y, x + w, y + h, r, argb(al * 0.50f, 0x120A26));
        rrect(c, x, y, x + w, y + h, r, argb(al * 0.10f, 0xFFFFFF));
        c.fillGradient(x + r, y + 1, x + w - r, y + 30, argb(al * 0.22f, 0xFFFFFF), argb(0f, 0xFFFFFF));
        c.fill(x + r, y, x + w - r, y + 1, argb(al * 0.45f, 0xFFFFFF));
        c.fill(x + r, y + h - 1, x + w - r, y + h, argb(al * 0.20f, 0xFFFFFF));
        c.fill(x, y + r, x + 1, y + h - r, argb(al * 0.22f, 0xFFFFFF));
        c.fill(x + w - 1, y + r, x + w, y + h - r, argb(al * 0.16f, 0xFFFFFF));
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

    private void setSlider(Feature f, double mx, int panel) {
        double t = Math.max(0, Math.min(1, (mx - (px(panel) + 10)) / (pw() - 20)));
        double v = f.min + Math.round((t * (f.max - f.min)) / f.step) * f.step;
        f.value = Math.max(f.min, Math.min(f.max, v));
    }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        float al = alpha();
        c.fillGradient(0, 0, width, height, argb(al * 0.30f, 0x2A1259), argb(al * 0.60f, 0x05020F));
        int pw = pw(), top = top(), maxH = 66;
        Feature hoverF = null;
        for (int p = 0; p < Modules.CATS.length; p++) {
            int x = px(p), h = panelH(p);
            maxH = Math.max(maxH, h);
            glassPanel(c, x, top, pw, h, al);
            UiFont.drawBold(c, textRenderer, Modules.CATS[p], x + 10, top + 8, argb(al, 0xFFFFFF));
            rrect(c, x + pw - 19, top + 9, x + pw - 10, top + 18, 3, argb(al * 0.9f, Theme.rgb()));
            int ry = top + HEAD;
            for (Feature f : Modules.in(Modules.CATS[p])) {
                f.anim += ((f.enabled ? 1f : 0f) - f.anim) * 0.3f;
                boolean hov = mx >= x && mx < x + pw && my >= ry && my < ry + ROW;
                if (hov) { rrect(c, x + 4, ry, x + pw - 4, ry + ROW, 4, argb(al * 0.10f, 0xFFFFFF)); hoverF = f; }
                UiFont.draw(c, textRenderer, f.name, x + 10, ry + 5, argb(al, lerp(0xFFFFFF, Theme.light(), f.anim)));
                if (f.enabled) check(c, x + pw - 17, ry + 5, argb(al, Theme.light()));
                else if (f.hasValue) UiFont.draw(c, textRenderer, f.open ? "-" : "+", x + pw - 15, ry + 5, argb(al, 0x6B5C8F));
                int y2 = ry + ROW;
                if (f.open) {
                    rrect(c, x + 4, y2, x + pw - 4, ry + rowH(f), 4, argb(al * 0.6f, 0x0A0418));
                    if (f.hasValue) {
                        double t = (f.value - f.min) / (f.max - f.min);
                        int tx1 = x + 10, tx2 = x + pw - 10, kx = tx1 + (int) ((tx2 - tx1) * t);
                        UiFont.draw(c, textRenderer, f.step < 1 ? String.format(java.util.Locale.ROOT, "%.1f", f.value) : String.valueOf((int) f.value), x + 10, y2 + 1, argb(al, 0xFFFFFF));
                        c.fill(tx1, y2 + 11, tx2, y2 + 13, argb(al, 0x2E2150));
                        c.fill(tx1, y2 + 11, kx, y2 + 13, argb(al, Theme.rgb()));
                        rrect(c, kx - 2, y2 + 9, kx + 2, y2 + 15, 2, argb(al, 0xFFFFFF));
                        y2 += SUB;
                    }
                    String b = listening == f ? "Bind: ..." : "Bind: " + Commands.keyName(f.key);
                    UiFont.draw(c, textRenderer, b, x + 10, y2 + 4, argb(al, Theme.rgb()));
                }
                ry += rowH(f);
            }
        }
        // панель тем
        int tp = Modules.CATS.length, tx = px(tp);
        glassPanel(c, tx, top, pw, 66, al);
        UiFont.drawBold(c, textRenderer, "Themes", tx + 10, top + 8, argb(al, 0xFFFFFF));
        UiFont.draw(c, textRenderer, "Accent", tx + 10, top + 28, argb(al, 0x9A8CC0));
        int step = (pw - 20) / Theme.COLORS.length, size = Math.min(13, step - 3);
        for (int i = 0; i < Theme.COLORS.length; i++) {
            int cx = tx + 10 + i * step;
            if (i == Theme.idx) rrect(c, cx - 2, top + 41, cx + size + 2, top + 43 + size + 2, 6, argb(al * 0.6f, 0xFFFFFF));
            rrect(c, cx, top + 43, cx + size, top + 43 + size, 5, argb(al, Theme.COLORS[i]));
        }
        if (hoverF != null && hoverF.desc != null)
            UiFont.drawCentered(c, textRenderer, hoverF.desc, width / 2, top + maxH + 12, argb(al, 0xD8CCFF));
        UiFont.drawCentered(c, textRenderer, "ЛКМ вкл/выкл  |  ПКМ настройки и бинд", width / 2, height - 14, argb(al, 0x8A78B8));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int pw = pw(), top = top();
        // темы
        int tp = Modules.CATS.length, tx = px(tp);
        if (btn == 0 && mx >= tx + 10 && mx < tx + pw && my >= top + 41 && my <= top + 62) {
            int step = (pw - 20) / Theme.COLORS.length, size = Math.min(13, step - 3);
            int i = (int) ((mx - (tx + 10)) / step);
            if (i >= 0 && i < Theme.COLORS.length && (mx - (tx + 10)) % step <= size + 2) {
                Theme.idx = i;
                Config.save();
                return true;
            }
        }
        for (int p = 0; p < Modules.CATS.length; p++) {
            int x = px(p);
            if (mx < x || mx > x + pw) continue;
            int ry = top + HEAD;
            for (Feature f : Modules.in(Modules.CATS[p])) {
                if (my >= ry && my < ry + ROW) {
                    if (btn == 0) f.toggle();
                    else if (btn == 1) f.open = !f.open;
                    return true;
                }
                int y2 = ry + ROW;
                if (f.open) {
                    if (f.hasValue) {
                        if (my >= y2 && my < y2 + SUB) {
                            if (btn == 0) { sliding = f; slidingPanel = p; setSlider(f, mx, p); }
                            return true;
                        }
                        y2 += SUB;
                    }
                    if (my >= y2 && my < y2 + SUB) {
                        if (btn == 0) listening = f;
                        return true;
                    }
                }
                ry += rowH(f);
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (sliding != null) { setSlider(sliding, mx, slidingPanel); return true; }
        return super.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (sliding != null) Config.save();
        sliding = null;
        return super.mouseReleased(mx, my, btn);
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

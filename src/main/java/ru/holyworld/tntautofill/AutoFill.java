package ru.holyworld.tntautofill;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Без бинда: сам следит за миром. Как только игрок вручную ставит Тнт-Пушку (обычным ПКМ),
 * мод замечает новый раздатчик рядом с игроком и сразу же:
 * 1) кладёт в него Динамит B;
 * 2) ставит рядом редстоун блок (пушка стреляет один раз).
 * Кирку не трогает - это только автозарядка.
 */
public final class AutoFill {

    /** Пока false, автозарядка полностью выключена - мод даже не смотрит на мир. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    private enum Step {
        IDLE,
        PRE_REDSTONE, OPEN_FOR_FILL, WAIT_GUI_FILL, FILL, FILL_PUT, FILL_BACK,
        PLACE_REDSTONE
    }

    private record Spot(BlockPos target, BlockPos support, Direction face, Vec3d hit) {
    }

    private static final int EQUIP_NOT_FOUND = 0;
    private static final int EQUIP_READY = 1;
    private static final int EQUIP_SELECTED = 2;
    private static final int EQUIP_SWAPPED = 3;

    private static final double REACH = 4.5;

    private static final Set<Block> INTERACTIVE = Set.of(
            Blocks.CRAFTING_TABLE, Blocks.SMITHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.LOOM, Blocks.NOTE_BLOCK, Blocks.DISPENSER, Blocks.DROPPER
    );

    // ------------------------------------------------------------------ слежение за миром

    private int holdTicks;
    private final Set<Long> knownDispensers = new HashSet<>();

    // ------------------------------------------------------------------ состояние цикла

    private Step step = Step.IDLE;
    private int wait;
    private int stepTicks;
    private int openRetries;
    private int equipTries;

    private int fillSrc;
    private int fillDst;
    private int fillLeft;

    private BlockPos cannonPos;
    private BlockPos redstonePos;
    private List<Spot> spots;

    // ------------------------------------------------------------------ тик

    public void tick(MinecraftClient mc) {
        if (!enabled) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null) {
            return;
        }

        watch(mc, p, w);

        if (step == Step.IDLE) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (!isGuiStep(step) && mc.currentScreen != null) {
            return;
        }
        int guard = 0;
        while (step != Step.IDLE && wait == 0 && guard++ < 10) {
            if (!isGuiStep(step) && mc.currentScreen != null) {
                return;
            }
            Step before = step;
            stepTicks++;
            try {
                run(mc, p, w);
            } catch (Throwable t) {
                HolyWorldTntAutofill.LOGGER.error("Ошибка в автозарядке", t);
                fail(mc, p, "Внутренняя ошибка: " + t);
                return;
            }
            if (step == before) {
                break;
            }
        }
    }

    /** Ищет новый раздатчик рядом с игроком, если он только что держал пушку в руке. */
    private void watch(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        Config cfg = HolyWorldTntAutofill.CONFIG;

        if (isCannon(p.getMainHandStack())) {
            holdTicks = cfg.holdWindowTicks;
        } else if (holdTicks > 0) {
            holdTicks--;
        }

        BlockPos center = p.getBlockPos();
        int r = cfg.scanRadius;
        BlockPos.Mutable m = new BlockPos.Mutable();

        // чистим позиции, где раздатчика больше нет (снова могут сработать в будущем)
        knownDispensers.removeIf(packed -> {
            BlockPos pos = BlockPos.fromLong(packed);
            if (pos.isWithinDistance(center, r + 1)) {
                return !w.getBlockState(pos).isOf(Blocks.DISPENSER);
            }
            return false;
        });

        boolean canTrigger = step == Step.IDLE && holdTicks > 0;
        BlockPos found = null;

        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    m.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (!w.getBlockState(m).isOf(Blocks.DISPENSER)) {
                        continue;
                    }
                    long packed = m.asLong();
                    if (knownDispensers.add(packed)) {
                        if (canTrigger && found == null
                                && p.getEyePos().distanceTo(Vec3d.ofCenter(m)) <= REACH + 1.0) {
                            found = m.toImmutable();
                        }
                    }
                }
            }
        }

        if (found != null) {
            startFor(mc, p, found);
        }
    }

    private void startFor(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos) {
        if (!has(p, AutoFill::isTnt)) {
            say(p, "Пушка поставлена, но Динамита B нет в инвентаре.", Formatting.RED, false);
            return;
        }
        if (!has(p, s -> s.isOf(Items.REDSTONE_BLOCK))) {
            say(p, "Пушка поставлена, но редстоун блока нет в инвентаре.", Formatting.RED, false);
            return;
        }
        reset(mc);
        cannonPos = pos;
        goTo(Step.PRE_REDSTONE, 0);
    }

    private void reset(MinecraftClient mc) {
        step = Step.IDLE;
        wait = 0;
        stepTicks = 0;
        openRetries = 0;
        equipTries = 0;
        fillSrc = -1;
        fillDst = -1;
        fillLeft = 0;
        cannonPos = null;
        redstonePos = null;
        spots = null;
    }

    private void goTo(Step next, int delay) {
        step = next;
        wait = Math.max(0, delay);
        stepTicks = 0;
        equipTries = 0;
    }

    private void fail(MinecraftClient mc, ClientPlayerEntity p, String msg) {
        say(p, msg, Formatting.RED, false);
        closeGuiIfOpen(p);
        reset(mc);
    }

    private static boolean isGuiStep(Step s) {
        return s == Step.WAIT_GUI_FILL || s == Step.FILL || s == Step.FILL_PUT
                || s == Step.FILL_BACK;
    }

    // ------------------------------------------------------------------ шаги

    private void run(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w) {
        Config cfg = HolyWorldTntAutofill.CONFIG;
        ClientPlayerInteractionManager im = mc.interactionManager;

        switch (step) {

            // редстоун блок берём в руку и ищем место для него заранее,
            // пока ещё даже не открыли пушку - экономим время перед самой постановкой
            case PRE_REDSTONE -> {
                if (!ensureHeld(mc, p, s -> s.isOf(Items.REDSTONE_BLOCK), "редстоун блок")) {
                    return;
                }
                if (spots == null) {
                    spots = findRedstoneSpots(w, p);
                }
                if (spots.isEmpty()) {
                    fail(mc, p, "Рядом с пушкой нет места для редстоун блока.");
                    return;
                }
                goTo(Step.OPEN_FOR_FILL, 0);
            }

            case OPEN_FOR_FILL -> {
                if (!w.getBlockState(cannonPos).isOf(Blocks.DISPENSER)) {
                    reset(mc);
                    return;
                }
                if (p.isSneaking() || p.getEyePos().distanceTo(Vec3d.ofCenter(cannonPos)) > REACH) {
                    if (stepTicks > 60) {
                        fail(mc, p, "Не получилось открыть пушку (Shift / далеко).");
                    }
                    return;
                }
                useCannon(mc, p);
                goTo(Step.WAIT_GUI_FILL, 0);
            }

            case WAIT_GUI_FILL -> {
                if (guiOpen(p)) {
                    goTo(Step.FILL, cfg.fillDelayTicks);
                } else if (stepTicks > 30) {
                    if (++openRetries > 2) {
                        fail(mc, p, "Окно пушки не открылось.");
                    } else {
                        goTo(Step.OPEN_FOR_FILL, 1);
                    }
                }
            }

            case FILL -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                int cs = containerSize(h);
                int src = -1;
                for (int i = cs; i < h.slots.size(); i++) {
                    if (isTnt(h.slots.get(i).getStack())) {
                        src = i;
                        break;
                    }
                }
                if (src < 0) {
                    if (stepTicks <= 6) {
                        return;
                    }
                    fail(mc, p, "Динамит B не найден в инвентаре. " + describeTnt(h));
                    return;
                }
                int dst = -1;
                if (cs == 9 && h.slots.get(4).getStack().isEmpty()) {
                    dst = 4; // центральный слот раздатчика - как в оригинале
                }
                if (dst < 0) {
                    for (int i = 0; i < cs; i++) {
                        if (h.slots.get(i).getStack().isEmpty()) {
                            dst = i;
                            break;
                        }
                    }
                }
                if (dst < 0) {
                    fail(mc, p, "В пушке нет свободного слота.");
                    return;
                }
                fillSrc = src;
                if (h.slots.get(src).getStack().getCount() <= cfg.tntPerCannon) {
                    im.clickSlot(h.syncId, src, 0, SlotActionType.QUICK_MOVE, p);
                    p.closeHandledScreen();
                    goTo(Step.PLACE_REDSTONE, 0);
                    return;
                }
                fillDst = dst;
                fillLeft = cfg.tntPerCannon;
                im.clickSlot(h.syncId, src, 0, SlotActionType.PICKUP, p);
                goTo(Step.FILL_PUT, cfg.clickDelayTicks);
            }

            case FILL_PUT -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                do {
                    im.clickSlot(h.syncId, fillDst, 1, SlotActionType.PICKUP, p);
                    fillLeft--;
                } while (fillLeft > 0 && cfg.clickDelayTicks == 0);
                if (fillLeft > 0) {
                    wait = cfg.clickDelayTicks;
                } else {
                    goTo(Step.FILL_BACK, cfg.clickDelayTicks);
                }
            }

            case FILL_BACK -> {
                if (!guiOpen(p)) {
                    fail(mc, p, "Окно пушки закрылось раньше времени.");
                    return;
                }
                ScreenHandler h = p.currentScreenHandler;
                im.clickSlot(h.syncId, fillSrc, 0, SlotActionType.PICKUP, p);
                p.closeHandledScreen();
                goTo(Step.PLACE_REDSTONE, 0);
            }

            case PLACE_REDSTONE -> {
                // редстоун блок и место уже подготовлены в PRE_REDSTONE - если вдруг
                // выбило из руки (например, GUI пушки), берём заново
                if (!ensureHeld(mc, p, s -> s.isOf(Items.REDSTONE_BLOCK), "редстоун блок")) {
                    return;
                }
                if (spots == null) {
                    spots = findRedstoneSpots(w, p);
                }
                if (spots.isEmpty()) {
                    fail(mc, p, "Рядом с пушкой нет места для редстоун блока.");
                    return;
                }
                Spot s = spots.remove(0);
                im.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(s.hit(), s.face(), s.support(), false));
                p.swingHand(Hand.MAIN_HAND);
                redstonePos = s.target();
                say(p, "\u041e\u0440\u0435\u0448\u043d\u0438\u043a \u0443\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d, \u043a\u043b\u0438\u0435\u043d\u0442 \u0441\u043a\u043e\u0440\u043e 200.", Formatting.LIGHT_PURPLE, false);
                reset(mc);
            }

            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ вспомогательное

    private void useCannon(MinecraftClient mc, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(cannonPos);
        Direction face = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        Vec3d hit = c.add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, cannonPos, false));
    }

    private List<Spot> findRedstoneSpots(ClientWorld w, ClientPlayerEntity p) {
        List<Spot> out = new ArrayList<>();
        BlockState cs = w.getBlockState(cannonPos);
        Direction front = cs.contains(Properties.FACING) ? cs.get(Properties.FACING) : null;
        Vec3d eye = p.getEyePos();

        Direction[] targets = {
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN
        };
        Direction[] supports = {
                Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP
        };

        for (Direction d : targets) {
            if (d == front) {
                continue;
            }
            BlockPos n = cannonPos.offset(d);
            if (!w.getBlockState(n).isReplaceable()) {
                continue;
            }
            if (p.getBoundingBox().intersects(new Box(n))) {
                continue;
            }
            for (Direction f : supports) {
                BlockPos b = n.offset(f);
                if (b.equals(cannonPos)) {
                    continue;
                }
                BlockState bs = w.getBlockState(b);
                if (bs.isAir() || bs.hasBlockEntity() || INTERACTIVE.contains(bs.getBlock())
                        || !bs.isFullCube(w, b)) {
                    continue;
                }
                Direction face = f.getOpposite();
                Vec3d hit = Vec3d.ofCenter(b).add(
                        face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
                if (eye.distanceTo(hit) > REACH) {
                    continue;
                }
                out.add(new Spot(n, b, face, hit));
                break;
            }
        }
        return out;
    }

    private boolean ensureHeld(MinecraftClient mc, ClientPlayerEntity p, Predicate<ItemStack> pred, String what) {
        int r = equip(mc, p, pred);
        if (r == EQUIP_READY) {
            return true;
        }
        if (r == EQUIP_NOT_FOUND) {
            fail(mc, p, "Не могу взять в руку: " + what + ".");
            return false;
        }
        if (!pred.test(p.getMainHandStack())) {
            if (++equipTries > 5) {
                fail(mc, p, "Не могу взять в руку: " + what + ".");
            } else {
                wait = 1;
            }
            return false;
        }
        return true;
    }

    private int equip(MinecraftClient mc, ClientPlayerEntity p, Predicate<ItemStack> pred) {
        if (pred.test(p.getMainHandStack())) {
            return EQUIP_READY;
        }
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (pred.test(inv.getStack(i))) {
                inv.selectedSlot = i;
                return EQUIP_SELECTED;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (pred.test(inv.getStack(i))) {
                mc.interactionManager.clickSlot(
                        p.playerScreenHandler.syncId, i, inv.selectedSlot, SlotActionType.SWAP, p);
                return EQUIP_SWAPPED;
            }
        }
        return EQUIP_NOT_FOUND;
    }

    private static boolean has(ClientPlayerEntity p, Predicate<ItemStack> pred) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            if (pred.test(inv.getStack(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean guiOpen(ClientPlayerEntity p) {
        return p.currentScreenHandler != null && p.currentScreenHandler != p.playerScreenHandler;
    }

    private static void closeGuiIfOpen(ClientPlayerEntity p) {
        if (guiOpen(p)) {
            p.closeHandledScreen();
        }
    }

    private static int containerSize(ScreenHandler h) {
        return Math.max(0, h.slots.size() - 36);
    }

    private static boolean containerHasTnt(ScreenHandler h) {
        int cs = containerSize(h);
        for (int i = 0; i < cs; i++) {
            if (h.slots.get(i).getStack().isOf(Items.TNT)) {
                return true;
            }
        }
        return false;
    }

    private static String describeTnt(ScreenHandler h) {
        StringBuilder sb = new StringBuilder("TNT в инвентаре: ");
        int n = 0;
        for (int i = containerSize(h); i < h.slots.size(); i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (st.isOf(Items.TNT)) {
                sb.append("[").append(st.getName().getString()).append(" x").append(st.getCount()).append("] ");
                n++;
            }
        }
        if (n == 0) {
            sb.append("нет");
        }
        return sb.toString();
    }

    private static boolean isCannon(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.DISPENSER)) {
            return false;
        }
        String need = norm(HolyWorldTntAutofill.CONFIG.cannonName);
        return need.isEmpty() || norm(s.getName().getString()).contains(need);
    }

    private static boolean isTnt(ItemStack s) {
        if (s.isEmpty() || !s.isOf(Items.TNT)) {
            return false;
        }
        String need = norm(HolyWorldTntAutofill.CONFIG.tntName);
        return need.isEmpty() || norm(s.getName().getString()).contains(need);
    }

    private static final String CYR = "\u0430\u0432\u0435\u043a\u043c\u043d\u043e\u0440\u0441\u0442\u0445\u0443\u0451";
    private static final String LAT = "abekmhopctxye";

    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            int idx = CYR.indexOf(c);
            sb.append(idx >= 0 ? LAT.charAt(idx) : c);
        }
        return sb.toString();
    }

    private static void say(ClientPlayerEntity p, String msg, Formatting color, boolean overlay) {
        p.sendMessage(Text.literal(HolyWorldTntAutofill.CONFIG.chatPrefix + " " + msg).formatted(color), overlay);
    }
}

package com.mcmodworkspace.moreballs.client;

import com.mcmodworkspace.moreballs.BallPouchHelper;
import com.mcmodworkspace.moreballs.MoreBalls;
import com.mcmodworkspace.moreballs.network.OpenPouchPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 收纳袋的按键。
 *
 * <h2>两个键</h2>
 * <ul>
 *   <li><b>G</b> —— 打开<b>饰品栏里</b>的收纳袋。<b>只认饰品栏</b>：袋子拿在手里
 *       （主手或副手）时按 G 不生效 —— 手持想开界面，用主手右键</li>
 *   <li><b>R</b> —— <b>长按 0.2 秒</b>展开径向菜单（挑弹药）。身上有收纳袋才弹，
 *       袋子在背包 / 快捷栏 / <b>副手</b> / 饰品栏都算</li>
 * </ul>
 *
 * <p>G 键按下后只发一个空包给服务端，由服务端去找袋子并打开界面 —— 容器界面必须服务端开。</p>
 *
 * <h2>长按是怎么判的</h2>
 * <p>按下那一刻记下时刻，之后每刻看还按着没有、以及按了多久；满 0.5 秒就开轮盘，
 * 并且标记「这次已经开过了」，免得一直到松手前反复重开。松开时清掉状态。</p>
 *
 * <h2>26.x 的两个 API 变化</h2>
 * <ul>
 *   <li>按键类型常量改名（26.2 是 {@code KEYSYM}、26.3 是 {@code KEYBOARD}）——
 *       用不带该参数的三参构造就能两边通用</li>
 *   <li>按键分组是 {@code KeyMapping.Category} 对象，不再是一个字符串键</li>
 * </ul>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID, value = Dist.CLIENT)
public final class BallPouchKeys {

    private BallPouchKeys() {
    }

    /** 按键设置里的分组（「旋转工艺」） */
    private static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(MoreBalls.MOD_ID, "main"));

    /**
     * <b>不与其他任何按键冲突的上下文</b>（作者 2026-10-24 要求）。
     *
     * <p>NeoForge 的 {@code KeyMapping} 默认用 {@code KeyConflictContext.UNIVERSAL} ——
     * 那个上下文的 {@code conflicts()} 对谁都说「是」，于是同一按键在按键设置里会被判冲突，
     * 而且原版在冲突时会只让其中一个生效。</p>
     *
     * <p>这里返回 false：游戏认为它<b>不和任何东西冲突</b>，所以同一按键下的其它功能
     * 照常触发，我们自己也照常触发 —— 也就是「兼容」而不是「独占」。</p>
     */
    public static final net.neoforged.neoforge.client.settings.IKeyConflictContext NO_CONFLICT =
            new net.neoforged.neoforge.client.settings.IKeyConflictContext() {
                @Override
                public boolean isActive() {
                    return true;
                }

                @Override
                public boolean conflicts(net.neoforged.neoforge.client.settings.IKeyConflictContext other) {
                    return false;
                }
            };
    /** G 键 —— 打开饰品栏里的收纳袋 */
    public static final KeyMapping OPEN_POUCH = new KeyMapping(
            "key.rotation_craft.open_pouch",
            com.mojang.blaze3d.platform.InputConstants.KEY_G,
            CATEGORY);

    /** R 键 —— 长按展开径向菜单 */
    public static final KeyMapping RADIAL_MENU = new KeyMapping(
            "key.rotation_craft.radial_menu",
            NO_CONFLICT,   // 不独占：相同按键下的其它功能照常生效（作者 2026-10-24）
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            com.mojang.blaze3d.platform.InputConstants.KEY_R,
            CATEGORY);
    /** 长按判定门槛（毫秒）—— 作者 2026-10-16 从 0.2 秒改为 **3 刻** */
    private static final long HOLD_MILLIS = 150L;   // 3 刻 × 50ms = 150ms

    /** 按下 R 的时刻，-1 表示没按 */
    private static long radialPressedAt = -1L;

    /** 这一轮长按是否已经开过菜单了 */
    private static boolean radialOpened = false;

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_POUCH);
        event.register(RADIAL_MENU);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        // G 键：只在世界内、没开其它界面时响应
        if (minecraft.player != null) {
            while (OPEN_POUCH.consumeClick()) {
                ClientPacketDistributor.sendToServer(OpenPouchPayload.INSTANCE);
            }
        }

        tickRadialMenu(minecraft);
    }

    /** R 键的长按判定 */
    private static void tickRadialMenu(Minecraft minecraft) {
        if (minecraft.player == null) {
            radialPressedAt = -1L;
            radialOpened = false;
            return;
        }

        // 轮盘已经开着的时候，这里什么都不做。
        //
        // 关键坑：**界面打开时 KeyMapping 的状态不一定还在更新** —— 实测 GUI 一开
        // {@code isDown()} 就变成 false，如果拿它当「松手」的判据，轮盘刚弹出来就会被
        // 自己关掉（表现是「闪一下就没了」）。所以松手检测全部交给
        // {@link RadialMenuScreen} 自己用 GLFW 原始键状态做，那边不受界面影响。
        if (minecraft.gui.screen() instanceof RadialMenuScreen) {
            return;
        }

        boolean down = RADIAL_MENU.isDown();

        if (down) {
            if (radialPressedAt < 0L) {
                radialPressedAt = System.currentTimeMillis();   // 刚按下
                radialOpened = false;
            }
            long held = System.currentTimeMillis() - radialPressedAt;
            if (!radialOpened
                    && held >= HOLD_MILLIS
                    && minecraft.gui.screen() == null) {     // 已经在别的界面里就不弹
                // 留一条诊断：万一轮盘还是没出来，日志里能看到按下判定走了多远、身上有几个袋子
                MoreBalls.LOGGER.debug("[pouch] 长按 R 达标，弹轮盘（身上袋子数 {}）",
                        BallPouchHelper.allPouchStacks(minecraft.player).size());
                minecraft.setScreenAndShow(new RadialMenuScreen());
                radialOpened = true;
            }
        } else {
            radialPressedAt = -1L;
            radialOpened = false;
        }
    }
}

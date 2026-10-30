package com.mcmodworkspace.moreballs;

import com.mcmodworkspace.moreballs.entity.FlailHeadEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * <b>流星锤「扔出中」状态的同步与禁止切换物品栏</b>（作者 2026-10-19 规格）。
 *
 * <h2>要满足的两条规格</h2>
 * <ul>
 *   <li>「扔出后，掷出者手中的流星锤贴图将变为<b>只有线、没有球</b>的形态」</li>
 *   <li>「此时<b>无法切换至其它物品栏</b>」</li>
 * </ul>
 *
 * <h2>为什么两条放在同一个类</h2>
 * <p>它们必须由**同一个状态**驱动（{@code ModComponents.FLAIL_CASTING}）。
 * 分开写的话，一旦某个 tick 只更新了其中一边，就会出现「图标是纯线但能切栏」
 * 或「切不了栏但图标还带着球」的错位 —— 那种错位玩家一眼就看出来，却极难复现。</p>
 *
 * <h2>为什么不复用 {@code LocalPlayerMixin}</h2>
 * <p>那个 mixin 的职责是 inject {@code itemUseSpeedMultiplier()F}（只影响装填减速），
 * 往里塞「锁背包格」是两件事，而且它是 client 专属、拿不到服务端权威状态。</p>
 */
@EventBusSubscriber(modid = MoreBalls.MOD_ID)
public final class FlailCastHandler {


    private FlailCastHandler() {
    }

    /**
     * <b>每次登录都把状态归零</b>（作者 2026-10-19 要求）。
     *
     * <p>存档里可能留着「扔出中」标记或孤儿锤头实体 —— 那样玩家一进游戏，
     * 手里每把流星锤都只显示一根绳、新做的也立刻变纯线，且找不回来。
     * 登录时无条件清一次：范围内的锤头实体全部撤回并移除，物品标记一起清掉。</p>
     */
    @SubscribeEvent
    public static void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // ① 把周围还活着的锤头全部收回来（物品走 returnFlailToOwner，不会丢）
        var heads = player.level().getEntitiesOfClass(FlailHeadEntity.class,
                player.getBoundingBox().inflate(FlailItem.MAX_LINE_LENGTH + 8.0D),
                h -> h.getOwner() == player && !h.isRemoved());
        for (FlailHeadEntity h : heads) {
            h.retireToOwner();
        }
        // ② 物品上的三个状态标记一并清掉
        FlailItem.clearCastingFlags(player);
        MoreBalls.LOGGER.info("[flail] 登录状态归零：收回 {} 个锤头、清除扔出中标记", heads.size());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // ===== ① 先做与"手上拿什么"无关的清理（作者 2026-10-29 报「回收后锤头消失」）=====
        //
        //  原来的顺序是「先判 isFlail(held)，不是流星锤就 return」—— 于是主手一旦不是锤
        //  （换手、被别的物品替换、把锤挪到背包），`FLAIL_CASTING` 就再也没人同步，
        //  那把锤永久停在 `flail_string_only` 形态（纯线、没有球）。
        //
        //  现在把清理提到最前面：**只要场上没有锤头，就把整包流星锤的标记清干净**，
        //  完全不依赖主手是什么。
        if (FlailItem.findHead(player.level(), player) == null) {
            // 只在真有残留标记时才清 + 同步，避免每 tick 无条件广播容器
            if (FlailItem.hasCastingFlag(player)) {
                FlailItem.clearCastingOnly(player);
                player.containerMenu.broadcastChanges();
                player.getInventory().setChanged();
            }
        }

        ItemStack held = player.getMainHandItem();

        // ===== 手里不是流星锤 =====
        //
        // ⚠️⚠️ 这里原来直接 `return`，于是有个死角（作者 2026-10-29 报「回收后锤头消失、
        //   无法再次掷出」，和他早期报的「其他流星锤拿到手里就消失」是同一个根因）：
        //
        //   玩家扔出流星锤后**把另一把锤挪到手上 / 或把扔出的那把挪走** ⇒ 主手不再是
        //   FlailItem ⇒ 这里提前返回 ⇒ `FLAIL_CASTING` 标记**永远不再被同步**。
        //   于是那把锤永久停在「只有线、没有球」的形态，也就是玩家眼里的「锤头消失了」；
        //   而且它还留着扔出中标记，右键蓄力被拦，表现为「无法再次掷出」。
        //
        //   修法：主手不是锤时，只要场上已经没有锤头，就把**整个背包**里所有流星锤的
        //   三个状态标记清一遍。`clearCastingFlags` 只是 36 次 getItem + 标记比较，
        //   而且只在「没有锤头」时走到，开销可以忽略。
        if (!FlailItem.isFlail(held)) {
            FlailHeadEntity orphan = FlailItem.findHead(player.level(), player);
            if (orphan == null) {
                FlailItem.clearCastingOnly(player);
            }
            return;
        }

        FlailHeadEntity head = FlailItem.findHead(player.level(), player);
        boolean casting = head != null;
        boolean was = FlailItem.isCasting(held);
        if (was != casting) {
            FlailItem.setCasting(held, casting);
            // 诊断日志（作者 2026-10-29 报「回收后锤头消失」反复出现）：
            // 形态切换的每一处都留痕，跑一次就能看出是「该清没清」还是「清错了对象」。
            MoreBalls.LOGGER.info("[flail] 形态切换：casting {} → {}  head={}  slot={}（场上锤头数 {}）",
                    was, casting,
                    head == null ? "null" : ("#" + head.getId() + " stage=" + head.stage()),
                    player.getInventory().getSelectedSlot(),
                    player.level().getEntitiesOfClass(FlailHeadEntity.class,
                            player.getBoundingBox().inflate(FlailItem.MAX_LINE_LENGTH + 8.0D),
                            h -> h.getOwner() == player && !h.isRemoved()).size());
        }

        if (!casting) {
            return;
        }

        // ===== 已扔出：禁止切换物品栏 =====
        //
        // 判据是「手里这把流星锤处于扔出中」，所以玩家把流星锤挪到别的格后
        // 主手就不再是 FlailItem ⇒ 上面那个 `isFlail(held)` 会提前返回、自然解锁。
        // 这正是作者要的：锁的是**这把锤在手上的这段时间**，不是永久锁人。
        int origin = head.originSlot();
        Inventory inventory = player.getInventory();
        // ⚠️ 兜底：originSlot 是普通字段（不走同步），非正常创建路径下会是 -1 ——
        //    那种情况原来整段都被跳过 ⇒ 完全不锁物品栏（作者 2026-10-30：「未实现」）。
        //    这里退回「背包快捷栏里那把流星锤所在的格」。
        if (origin < 0 || origin >= Inventory.getSelectionSize()) {
            origin = -1;
            for (int s = 0; s < Inventory.getSelectionSize(); s++) {
                if (FlailItem.isFlail(inventory.getItem(s))) {
                    origin = s;
                    break;
                }
            }
        }
        if (origin >= 0 && inventory.getSelectedSlot() != origin) {
            inventory.setSelectedSlot(origin);
            // 诊断（作者 2026-10-30：「无法切换物品栏的功能未实现」——先确认这段到底有没有跑）
            MoreBalls.LOGGER.info("[flail] 锁定物品栏：{} → {}（锤头 stage={}）",
                    inventory.getSelectedSlot(), origin, head.stage());
        }

        // ⚠️⚠️ 原来这里会**把副手的流星锤直接清空**（作者 2026-10-24：
        //   「还会导致其他的流星锤拿到手里就消失，无法使用」）。
        //
        //   那是把玩家的物品凭空销毁 —— 绝对不能这么干。换手本身也不会破坏状态：
        //   玩家一换手，主手就不再是 FlailItem，上面那个 `isFlail(held)` 会提前返回，
        //   `FLAIL_CASTING` 标记自然被清掉，锤头该回收回收。不需要额外干预。
    }
}

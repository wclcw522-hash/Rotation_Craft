package com.mcmodworkspace.rotationcraft;

import java.util.List;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.resources.ResourceKey;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 创造模式分类注册。
 *
 * <p>「球」这个分类是<b>标签驱动</b>的：内容不是写死的物品清单，而是每次打开时
 * 实时遍历物品注册表、筛出带 {@code #rotation_craft:balls} 标签的物品。好处是任何
 * 物品 —— 原版的、本模组的、第三方模组的 —— 只要被加进该标签就会自动出现在这里，
 * 不需要改一行 Java 代码。</p>
 *
 * <p>显示名走语言文件：{@code itemGroup.rotation_craft}
 * （zh_cn =「球」，en_us =「Balls」）。</p>
 */
public final class ModCreativeTabs {

    private ModCreativeTabs() {
    }

    /** 创造模式分类注册器；在入口类里挂到 mod 事件总线 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RotationCraft.MOD_ID);

    /**
     * 「战斗用品」分类（作者 2026-10-19）—— 目前只有流星锤一档，
     * 页内标题走 {@code itemGroup.rotation_craft.battle.flail}。
     *
     * <p>展示方式与组合球一致：**为每种球各摆一个实例**。
     * 底层是同一个物品（`rotation_craft:flail`），靠 {@code FLAIL_BALL} 组件区分 ——
     * 这样 19 种「独立展示」不需要 19 个物品注册。</p>
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BATTLE =
            TABS.register("battle", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rotation_craft.battle"))
                    .icon(() -> new ItemStack(ModItems.FLAIL.get()))
                    .displayItems((parameters, output) -> {
                        // ① 每种球各一个流星锤 —— 遍历球的注册表，满耐久的才算
                        parameters.holders()
                                .lookupOrThrow(Registries.ITEM)
                                .listElements()
                                .filter(holder -> holder.is(ModTags.Items.BALLS))
                                .forEach(holder -> {
                                    ItemStack flail = FlailItem.of(new ItemStack(holder.value()));
                                    if (!flail.isEmpty()) {
                                        output.accept(flail);
                                    }
                                });
                        // ② 组合球用**通用占位**（作者：组合流星锤采用通用占位展示）
                        output.accept(ModItems.FLAIL.get());
                    })
                    .build());
    /** 「球」分类 —— 只收带 {@code #rotation_craft:balls} 标签的东西 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BALLS =
            TABS.register("balls", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rotation_craft"))
                    .icon(() -> new ItemStack(Items.SNOWBALL))
                    .displayItems((parameters, output) -> parameters.holders()
                            .lookupOrThrow(Registries.ITEM)
                            .listElements()
                            .filter(holder -> holder.is(ModTags.Items.BALLS))
                            .forEach(holder -> output.accept(holder.value())))
                    .build());

    // ===== 分类页布局（作者 2026-10-07 定稿）=====
    //
    //  · 「球」一页装下**全部**球 —— 实心 / 空心 / 雪球三类合起来展示，
    //    不再拆成三个子分类页（它们本来就都在 #rotation_craft:balls 里）。
    //  · 「半成品」一页装下**两种切球**（半球 + 四分之一球）—— 它们是部件、
    //    不是球，所以从「球」那边独立出来，另起一个 fragments 大类。
    //  · 「旋转工艺」页是本模组全部物品的兜底清单。

    /**
     * 「半成品」—— 半球与四分之一球合起来展示。
     *
     * <p>这两件都是「模板 + 组件」：裸物品只是模板，既没有来源、也拼不出贴图。
     * 所以这里摆出**带组件的示例**，用来看词条、对照配方。</p>
     */
    // ⚠️ 「半成品」这个独立分类已移除（作者 2026-10-09）——
    //    里面摆的是 8 种材质的半球与四分之一球，属于刷屏内容，且碎片
    //    本身只是「模板 + 组件」，没有单独开分类的必要。

    /**
     * 「旋转工艺」—— 本模组的<b>全部</b>物品。
     *
     * <p>上面那个「球」分类是标签驱动的，只会列出球；而模组还加了别的东西
     * （收纳袋、碎石这类材料），它们在「球」页里看不到。这个分类专门兜底：
     * <b>凡是本模组注册的物品一律列在这里</b>，不需要手工维护清单。</p>
     *
     * <p>物品<b>可以同时出现在多个分类里</b>，所以球会既在「球」页、也在这里，
     * 不算重复。</p>
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ALL =
            TABS.register("all", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.rotation_craft.all"))
                    .icon(() -> new ItemStack(ModItems.BALL_POUCH.get()))
                    .displayItems((parameters, output) -> {
                        // 先摆球 —— 按标签来，和「球」页保持一致的顺序
                        parameters.holders()
                                .lookupOrThrow(Registries.ITEM)
                                .listElements()
                                .filter(holder -> holder.is(ModTags.Items.BALLS))
                                .forEach(holder -> output.accept(holder.value()));

                        // 再把本模组其余物品补上（收纳袋、碎石……）。
                        // 直接遍历自己的注册器，以后加新物品不用回来改这里
                        ModItems.ITEMS.getEntries().forEach(entry -> {
                            Item item = entry.value();
                            if (item.builtInRegistryHolder().is(ModTags.Items.BALLS)) {
                                return;
                            }

                            output.accept(item);
                        });

                        // ⚠️ **半球 / 四分之一球的示例实例已移除**（作者 2026-10-09 指定）：
                        //    之前这里摆了 8 种材质的半球 + 8 种四分之一球，太刷屏。
                        //    碎片本身是「模板 + 组件」，玩家拿部件合出实物即可，
                        //    要看长什么样去 JEI 的配方页。
                        //
                        // ===== 本模组添加的附魔书（作者 2026-10-09 指定）=====
                        // 原版附魔书靠 stored_enchantments 组件承载附魔，
                        // 这里逐个包成 ItemStack，让玩家在本分类里直接拿到它们。
                        var enchLookup = parameters.holders().lookupOrThrow(
                                net.minecraft.core.registries.Registries.ENCHANTMENT);
                        for (ResourceKey<Enchantment> ench : ModEnchantments.ALL) {
                            ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                            // 附魔书靠 stored_enchantments 组件承载附魔 ——
                            // ItemStack.enchant 需要 Holder<Enchantment>，从 lookup 里取
                            enchLookup.get(ench).ifPresent(holder -> {
                                book.enchant(holder, 1);
                                output.accept(book);
                            });
                        }
                    })
                    .build());
}

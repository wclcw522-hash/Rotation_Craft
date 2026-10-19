package com.mcmodworkspace.moreballs;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * <b>流星锤配方</b>：一个满耐久的球 + 一根线，<b>相邻对角</b>摆放（作者 2026-10-19 规格）。
 *
 * <h2>为什么必须自定义，不能写 json 的 crafting_shaped</h2>
 * <p>原版 {@code crafting_shaped} 的 {@code result} 只能写<b>物品 id</b>，带不了数据组件。
 * 而流星锤「是哪种球」全靠 {@code ModComponents.FLAIL_BALL} 组件 ——
 * 写死 id 只会得到一把空的流星锤（图标退回 fallback、数值全默认）。</p>
 *
 * <p>顺带这个自定义类型还解决了另一件事：<b>满耐久校验</b>。
 * {@code crafting_shaped} 的 result 本来就管不了材料耐久，而作者要求
 * 「任意<b>满耐久</b>的实心/空心球」才能合成。</p>
 *
 * <h2>为什么做成无状态单例</h2>
 * <p>形状（对角两格）与材料种类（球 + 线）都是固定的，没有任何需要持久化的字段 ——
 * 所以照原版 {@code RepairItemRecipe} 的写法用 {@code MapCodec.unit(INSTANCE)}，
 * 数据文件里只要一行 {@code {"type": "rotation_craft:flail"}}。</p>
 *
 * <h2>「对角」怎么判</h2>
 * <pre>
 *   球 .         . 线
 *   . 线   或    球 .
 * </pre>
 * <p>只认这两种摆法 —— 同行或同列相邻都不算（作者说的是「相邻对角」）。</p>
 */
public final class FlailRecipe extends CustomRecipe {

    public static final FlailRecipe INSTANCE = new FlailRecipe();

    public static final MapCodec<FlailRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);

    public static final StreamCodec<RegistryFriendlyByteBuf, FlailRecipe> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    public static final RecipeSerializer<FlailRecipe> SERIALIZER =
            new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

    private FlailRecipe() {
        // ⚠️ CustomRecipe 没有构造器（默认无参）—— 对照 BallComboRecipe.Vertical，
        //    那里根本没有任何 super(...) 调用。分类走 category() 的默认实现（MISC）。
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return findDiagonal(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        Diagonal pair = findDiagonal(input);
        if (pair == null) {
            return ItemStack.EMPTY;
        }
        return FlailItem.of(pair.ball());
    }

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return SERIALIZER;
    }

    /** 找到的那一对：球 + 线的位置 */
    private record Diagonal(ItemStack ball, int ballSlot, int stringSlot) {
    }

    /**
     * 在 2×2 里找「球与线对角相邻」的摆法。
     *
     * <p>网格编号（{@code CraftingInput} 的宽高都是 2）：
     * <pre>
     *   0 1
     *   2 3
     * </pre>
     * 对角对只有两组：{@code (0,3)} 与 {@code (1,2)}。</p>
     */
    private static Diagonal findDiagonal(CraftingInput input) {
        if (input.width() != 2 || input.height() != 2) {
            return null;
        }
        // 必须是恰好两格有东西，其余为空
        int filled = 0;
        for (int i = 0; i < 4; i++) {
            if (!input.getItem(i).isEmpty()) {
                filled++;
            }
        }
        if (filled != 2) {
            return null;
        }

        for (int[] pair : new int[][] { { 0, 3 }, { 1, 2 } }) {
            ItemStack a = input.getItem(pair[0]);
            ItemStack b = input.getItem(pair[1]);
            // 两种朝向都要认：球可能在对角线任一端
            ItemStack ball = ballOrNull(a);
            ItemStack string = stringOrNull(b);
            if (ball != null && string != null) {
                return new Diagonal(ball, pair[0], pair[1]);
            }
            ball = ballOrNull(b);
            string = stringOrNull(a);
            if (ball != null && string != null) {
                return new Diagonal(ball, pair[1], pair[0]);
            }
        }
        return null;
    }

    /**
     * 这一格是不是「合格的球」——
     * 是球、且<b>满耐久</b>（{@link BallClusterTier#of} 落在 PRISTINE）。
     *
     * <p>⚠️ 满耐久这一条对组合球同样生效 —— 组合球继承 {@code BallItem} 的整套耐久系统，
     * 作者 2026-10-19 明确「组合球不是也有正常的耐久系统吗」。</p>
     */
    private static ItemStack ballOrNull(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() != 1) {
            return null;
        }
        if (!BallAmmo.isBall(stack)) {
            return null;
        }
        if (BallClusterTier.of(stack).isClustered()) {
            return null;   // 不是满耐久 ⇒ 不允许合成
        }
        return stack;
    }

    /** 这一格是不是线（原版 `minecraft:string`），且只有一根 */
    private static ItemStack stringOrNull(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.STRING) || stack.getCount() != 1) {
            return null;
        }
        return stack;
    }

}

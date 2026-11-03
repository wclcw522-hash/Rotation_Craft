package com.mcmodworkspace.moreballs.mixin;

import net.minecraft.world.item.crafting.display.SlotDisplay;
import com.mcmodworkspace.moreballs.ModItems;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * <b>切石机：组合球只保留「四分之一」那一条配方 + 修首次点击</b>（作者 2026-11-03）。
 *
 * <h2>为什么必须在这里过滤，而不是在 {@code matches} 里</h2>
 * <p>{@link BallCuttingRecipe#matches} 里早就写了「组合球不能对半切 → 返回 false」，
 * 但那条判断<b>对切石机的配方列表完全无效</b>：NeoForge 的
 * {@code stonecutterRecipes().selectByInput(item)} 是<b>按 ingredient 建的索引</b>，
 * <b>从头到尾不调用 {@code matches}</b>。于是组合球放进切石机后会看到
 * 「半球」和「四分之一球」两个配方。</p>
 *
 * <p>后果是玩家最常点的第一个（半球）会走进
 * {@code AbstractContainerMenuMixin#moreballs$cutBalls} 里那条
 * 「组合球 + 半球 → 吞掉这次点击」的分支 —— 点下去什么都不发生，
 * 表现就是<b>「切组合球一点都不能用了」</b>。</p>
 *
 * <p>所以只能在列表这一层把那条配方真正摘掉，玩家就只会看到「四分之一球」。</p>
 *
 * <h2>顺手修的第二个问题：第一个配方要点两次</h2>
 * <p>{@code selectedRecipeIndex} 是 {@code DataSlot.standalone()}，<b>初值是 0</b>。
 * 而原版 {@code clickMenuButton} 的第一句就是
 * {@code if (selectedRecipeIndex.get() == buttonId) return false;} ——
 * 于是列表里<b>第 0 条配方第一次点永远没反应</b>（实测：点第一次无产物，点第二次才出）。</p>
 *
 * <p>修法：在 HEAD 判断「索引相同但产物槽是空的」，这说明它<b>从没真正算过产物</b>，
 * 把索引重置成 -1 让原版继续走正常流程即可。产物槽非空时不动，保持原版「点不动」的语义。</p>
 */
@Mixin(StonecutterMenu.class)
public abstract class StonecutterMenuMixin {

    @Shadow
    private SelectableRecipe.SingleInputSet<StonecutterRecipe> recipesForInput;

    @Shadow
    private DataSlot selectedRecipeIndex;

    /**
     * 组合球 → 把「产物是半球」的那条配方从可见列表里摘掉。
     *
     * <p>只对组合球生效（普通球两种切法都合法）。摘完如果当前选中索引越界就重置成 -1。</p>
     */
    @Inject(method = "slotsChanged", at = @At("TAIL"), require = 0)
    private void moreballs$dropComboHalfRecipe(Container container, CallbackInfo ci) {
        StonecutterMenu self = (StonecutterMenu) (Object) this;
        ItemStack in = self.getSlot(StonecutterMenu.INPUT_SLOT).getItem();


        if (!in.is(ModItems.COMBO_BALL.get())) {
            return;
        }
        if (this.recipesForInput.size() <= 1) {
            return;
        }


        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> kept =
                this.recipesForInput.entries().stream()
                        .filter(entry -> {
                            // ⚠️ 只能用 optionDisplay 判断 —— 客户端的 SingleInputSet 是走
                            //    noRecipeCodec 同步过来的，recipe 引用被剥掉了（Optional.empty），
                            //    按 recipe 判会让客户端一条都过滤不掉，界面上仍然是两个配方。
                            //
                            // ⚠️⚠️ 而且 `ItemStackTemplate.item()` 返回的是 **Reference<Item>**，
                            //    不是 Item —— 拿它直接跟 `ModItems.BALL_HALF.get()` 比永远不相等，
                            //    过滤会静默失效（实测过：日志里 item=Reference{...ball_half}）。
                            //    所以这里先 create() 出 ItemStack，再用 is() 比。
                            var display = entry.recipe().optionDisplay();
                            if (display instanceof SlotDisplay.ItemStackSlotDisplay slot) {
                                ItemStack preview = slot.stack().create();
                                return !preview.is(ModItems.BALL_HALF.get());
                            }
                            return true;
                        })
                        .toList();

        if (kept.size() == this.recipesForInput.size()) {
            return;   // 没有可摘的
        }

        this.recipesForInput = new SelectableRecipe.SingleInputSet<>(kept);
        if (this.selectedRecipeIndex.get() >= this.recipesForInput.size()) {
            this.selectedRecipeIndex.set(-1);
        }
    }

    /**
     * 修「第 0 条配方第一次点没反应」。
     *
     * <p>索引相同 + 产物槽为空 ⇒ 从未真正算过产物，把索引重置成 -1，原版就会照常
     * {@code isValidRecipeIndex → set → setupResultSlot} 走一遍。</p>
     */
    @Inject(method = "clickMenuButton", at = @At("HEAD"), require = 0)
    private void moreballs$allowFirstClick(Player player, int buttonId,
                                           CallbackInfoReturnable<Boolean> cir) {
        StonecutterMenu self = (StonecutterMenu) (Object) this;

        if (buttonId < 0 || buttonId >= this.recipesForInput.size()) {
            return;
        }
        if (self.getSelectedRecipeIndex() != buttonId) {
            return;
        }
        if (!self.getSlot(StonecutterMenu.RESULT_SLOT).getItem().isEmpty()) {
            return;   // 产物已在，原版「点不动」的语义保持不变
        }
        this.selectedRecipeIndex.set(-1);
    }
}

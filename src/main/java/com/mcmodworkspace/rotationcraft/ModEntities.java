package com.mcmodworkspace.rotationcraft;

import com.mcmodworkspace.rotationcraft.entity.BallEnderpearl;
import com.mcmodworkspace.rotationcraft.entity.BallProjectile;
import com.mcmodworkspace.rotationcraft.entity.DripstoneShard;
import com.mcmodworkspace.rotationcraft.entity.SummonedBee;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的实体注册。
 *
 * <p>判定盒尺寸的来源要分两层看，别把两个数混成一个：
 * 球的贴图是 16×16，圆体占 x2..13 / y2..13 共 <b>12 像素</b>，
 * 按「1 像素 = 1/16 格」换算 —— 12 ÷ 16 = <b>0.75 格</b>，这是<b>外观</b>的直径。
 * 而实体判定盒实际设的是 {@code .sized(0.25F, 0.25F)}，即 0.25 × 0.25
 * （作者 2026-10-08 指定，照抄原版雪球的判定盒）。
 * 所以：0.75 是贴图换算出的视觉尺寸，0.25 才是代码里生效的判定尺寸。
 * 追踪范围（{@code clientTrackingRange 4} / {@code updateInterval 10}）也照抄原版雪球，
 * 让它在网络同步上与其它投掷物表现一致。</p>
 */
public final class ModEntities {

    private ModEntities() {
    }

    /** 实体类型注册器；在入口类里挂到 mod 事件总线 */
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, RotationCraft.MOD_ID);

    /**
     * {@code rotation_craft:ball_ender_pearl} —— 作为弩弹药射出的末影珍珠。
     *
     * <p>与原版珍珠的区别只有一个：命中实体时造成 {@link BallEnderpearl#AMMO_DAMAGE} 点伤害，
     * 传送功能照旧。</p>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<BallEnderpearl>> BALL_ENDER_PEARL =
            ENTITIES.register("ball_ender_pearl", () -> EntityType.Builder
                    .<BallEnderpearl>of(BallEnderpearl::new, MobCategory.MISC)
                .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(RotationCraft.MOD_ID, "ball_ender_pearl"))));

    /** {@code rotation_craft:ball} —— 所有球类弹药的通用投射物 */
    /**
     * 流星锤的锤头（作者 2026-10-19）。
     *
     * <p>判定盒 <b>0.5 见方</b>（与球一致）—— 作者 2026-10-30 把锤头碰撞箱从 0.2 改成 0.5
     * 以统一手感：「碰撞箱变了但是模型没变」那次就是这么定的。</p>
     *
     * <p>⚠️ <b>与球的关系（作者 2026-11-01 明确过一次）</b>：</p>
     * <ul>
     *   <li>它是<b>独立的实体种类</b>（{@code rotation_craft:flail_head} ≠ {@code rotation_craft:ball}）；</li>
     *   <li>但类上<b>继承了 {@code BallProjectile}</b> 以复用物理与词条 —— 作者明确
     *       「代码可以重叠、球和锤头可以互相交互」，所以这不是问题；</li>
     *   <li>代价是**所有按 {@code BallProjectile} 类型批量操作的地方都要显式排除锤头** ——
     *       目前有四处：{@code BallPhysicsCore.enforceBallCap}（球上限清理）、
     *       {@code BallMobAI.tryCatchBall} / {@code tryPickUpBall}、{@code BallPouchCurios.autoCollect}。
     *       再加新的「批量处理球」逻辑时，记得一并考虑。</li>
     * </ul>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<com.mcmodworkspace.rotationcraft.entity.FlailHeadEntity>> FLAIL_HEAD =
            ENTITIES.register("flail_head", () -> EntityType.Builder
                    .<com.mcmodworkspace.rotationcraft.entity.FlailHeadEntity>of(
                            com.mcmodworkspace.rotationcraft.entity.FlailHeadEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(6)
                    .updateInterval(5)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(RotationCraft.MOD_ID, "flail_head"))));
    public static final DeferredHolder<EntityType<?>, EntityType<BallProjectile>> BALL =
            ENTITIES.register("ball", () -> EntityType.Builder
                    .<BallProjectile>of(BallProjectile::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(RotationCraft.MOD_ID, "ball"))));

    /**
     * {@code rotation_craft:dripstone_shard} —— 滴水石球的【尖刺】射出去的<b>滴水石锥</b>。
     *
     * <p>作者 2026-10-15 指定：击中 / 碰撞 / 破碎时一次射出 8 枚「最小的滴水石锥」，
     * 基础伤害 3。它要<b>真的是滴水石锥的样子</b>，所以实体携带
     * {@code minecraft:pointed_dripstone} 的物品栈、客户端直接用原版投掷物渲染器画它
     * （见 {@code RotationCraftClient}），不需要自己写任何渲染代码。</p>
     *
     * <p>判定盒照抄原版雪球那套（0.25 × 0.25 / 追踪 4 / 同步间隔 10）——
     * 与球的实体保持一致，网络表现也一致。</p>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<DripstoneShard>> DRIPSTONE_SHARD =
            ENTITIES.register("dripstone_shard", () -> EntityType.Builder
                    .<DripstoneShard>of(DripstoneShard::new, MobCategory.MISC)
                    .sized(0.25F, 0.25F)
                    .clientTrackingRange(4)
                    .updateInterval(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(RotationCraft.MOD_ID, "dripstone_shard"))));

    /**
     * {@code rotation_craft:summoned_bee} —— 【招蜂引蝶 x】召唤出来的<b>战斗宠物蜜蜂</b>
     * （作者 2026-10-16 指定做成独立生物）。
     *
     * <p>它继承原版 {@code Bee}（飞行、模型、贴图全部复用），只改三件事：
     * 可以无限攻击、不仇恨召唤者、8 秒后直接消失。
     * 详见 {@link com.mcmodworkspace.rotationcraft.entity.SummonedBee}。</p>
     *
     * <p>判定盒与追踪参数照抄原版蜜蜂（0.7 × 0.6 / 追踪 8 / 同步间隔 3）——
     * 让它在网络上的表现与真蜜蜂一致。名字走语言键
     * {@code entity.rotation_craft.summoned_bee}（「被召唤的蜜蜂」），不需要 {@code setCustomName}。</p>
     */
    public static final DeferredHolder<EntityType<?>, EntityType<SummonedBee>> SUMMONED_BEE =
            ENTITIES.register("summoned_bee", () -> EntityType.Builder
                    .<SummonedBee>of(SummonedBee::new, MobCategory.CREATURE)
                    .sized(0.7F, 0.6F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE,
                            Identifier.fromNamespaceAndPath(RotationCraft.MOD_ID, "summoned_bee"))));

    /**
     * 生物的<b>属性注册</b>。
     *
     * <p>⚠️ 这是本模组第一次注册「真正的生物」（前面几个都是投射物
     * {@code MobCategory.MISC}，不需要属性）—— 所以这个事件订阅也是新加的。
     * 不加的话实体会创建失败并抛「未注册属性」。</p>
     */
    @net.neoforged.fml.common.EventBusSubscriber(modid = RotationCraft.MOD_ID)
    public static final class Attributes {

        private Attributes() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        public static void onAttributeCreation(
                net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) {
            event.put(SUMMONED_BEE.get(), SummonedBee.createAttributes().build());
        }
    }
}

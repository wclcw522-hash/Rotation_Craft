package com.mcmodworkspace.moreballs.client;

import java.util.HashMap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import java.util.Map;
import java.util.Set;

import com.mcmodworkspace.moreballs.entity.BallProjectile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;

/**
 * <b>球在世界里的 3D 模型</b>（作者 2026-10-29：把 Blockbench 建的球模型做进 mod）。
 *
 * <h2>为什么用 standalone 模型</h2>
 * <p>26.x 已经没有 {@code BakedModel} 了，渲染管线重构成「按需烘焙的独立模型」。
 * NeoForge 为此开了 {@link ModelEvent.RegisterStandalone} —— 可以把任意一个模型 json
 * 当成独立资源烘焙出来，用 {@link ModelManager#getStandaloneModel} 取到
 * {@link BlockStateModelPart}，再交给 {@code OrderedSubmitNodeCollector#submitBlockModel} 画。</p>
 *
 * <p>这条路子是「不改物品模型、只在实体渲染器里换几何」，所以：</p>
 * <ul>
 *   <li><b>物品栏 / 手持 / 掉落物仍然是原来的 2D 图标</b>（{@code item/generated}），不受影响</li>
 *   <li>只有扔出去、躺在地上的球实体用 3D 模型</li>
 * </ul>
 *
 * <h2>17 份模型</h2>
 * <p>几何完全一样（Blockbench 里那 44 个立方体），只有贴图不同 —— 所以生成 17 个
 * {@code models/item/ball3d_<key>.json}，一个个注册。加新球只要加一份 json + 这里加一个 key。</p>
 */
@EventBusSubscriber(modid = com.mcmodworkspace.moreballs.MoreBalls.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class Ball3DModels {

    /**
     * <b>模型几何的半高（格）</b> —— 滚动旋转的轴心。
     *
     * <p>模型画在 <b>0~8 单位</b>（0.5 格直径）的盒子里，几何中心在 `y = 4 单位 = 0.25 格`。
     * ⚠️ 这个值**不等于碰撞箱中心**（碰撞箱仍是 0.25 格高，中心 0.125）——
     * 旋转必须绕模型自己的中心，否则球会绕它下方的点甩。</p>
     */
    public static final float MODEL_HALF_HEIGHT = 0.25F;

    /** 模型几何的半宽（格）= 滚动半径，用于把位移换算成滚动角度 `Δθ = Δs / r` */
    public static final float MODEL_HALF_WIDTH = 0.25F;

    /** 全部有 3D 模型的球（顺序无所谓，但要和 models/item/ball3d_<key>.json 对得上） */
    public static final String[] KEYS = {
            "wooden", "cobblestone", "iron", "gold", "hollow_iron", "amethyst",
            "copper", "hollow_copper", "redstone", "diamond", "lapis", "tidal",
            "emerald", "dripstone", "preserve", "talisman", "echo"
    };

    /** 这三个用的是单独生成的「填满版」贴图（在 textures/item/ball3d/ 下），不是原版球图标 */
    private static final Set<String> SPECIAL_TEX = Set.of("tidal", "preserve", "talisman");

    /**
     * <b>象限模型</b> —— 组合球的 3D 组合渲染用（作者 2026-11-01）。
     *
     * <h2>★ 它是「完整整球几何 + 只有 1/4 不透明的分片贴图」</h2>
     * <p>直接复用资源里早就有的 {@code combo_piece_<source>_<q>.json} ——
     * 那种 json 只有两个字段：</p>
     * <pre>
     * { "parent": "rotation_craft:item/ball3d_&lt;key&gt;",       // ← 完整整球几何
     *   "textures": { "0": "...&lt;key&gt;_quarter_&lt;q&gt;" } }      // ← 只有该象限不透明
     * </pre>
     * <p>所以<b>四层几何完全相同、必然重合</b>，接缝与偏移在数学上不可能出现，
     * 每层只是各自把「自己那 1/4」画出来。这是 1.2.0.48 就定下的正解。</p>
     *
     * <h2>⚠️ 不要再自己切几何</h2>
     * <p>1.2.0.94 我按 x=0 / y=4 把球模型切成四份残缺子模型（68 份
     * {@code ball3d_*_q*}），结果：切面会露出「较长的切球侧面」、而且作为物品模型
     * 还要额外补偿 {@code ItemTransform} 的居中，手持与掉落物位置双双偏移。
     * <b>那条路是错的</b>，那 68 份文件已删除。</p>
     */
    private static final Map<String, StandaloneModelKey<BlockStateModelPart>> QUADRANT_MODELS = new HashMap<>();

    /** 组合球能出现的来源个数（{@code combo_piece_<source>_<q>} 覆盖 0..SIZE-1） */
    public static final int QUADRANT_SOURCE_COUNT = 17;

    private static final Map<String, StandaloneModelKey<BlockStateModelPart>> MODELS = new HashMap<>();
    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    static {
        for (String k : KEYS) {
            MODELS.put(k, new StandaloneModelKey<>(() -> "rotation_craft:ball3d_" + k));
            // ⚠️ 必须走 block 图集（textures/block/ball3d/）——
            // standalone 模型用 simpleModelWrapper 烘焙时绑的是方块图集，
            // 引用 item 图集里的贴图会被直接拒收（日志：Rejecting block model ... outside of supported atlas）。
            TEXTURES.put(k, Identifier.fromNamespaceAndPath("rotation_craft", "block/ball3d/" + k));
        }
        // 象限模型：直接复用资源里已有的 combo_piece_<source>_<q>（完整整球几何 + 1/4 分片贴图）
        for (int src = 0; src < QUADRANT_SOURCE_COUNT; src++) {
            for (int q = 1; q <= 4; q++) {
                String qk = src + "_" + q;
                int srcF = src; int qF = q;
                QUADRANT_MODELS.put(qk, new StandaloneModelKey<>(
                        () -> "rotation_craft:item/combo_piece_3d_" + srcF + "_" + qF));
            }
        }
    }

    private Ball3DModels() {
    }

    /**
     * 注册 17 个独立模型。
     *
     * <p>⚠️ 为什么不是「物品模型 + display_context 分流」那条路：
     * 那条路要走 {@code ItemTransform.apply}，它里面有一层硬编码的 {@code translate(-0.5)}
     * 居中（为占满 0~16 空间的模型准备），而我们的几何只占其中一小块 ——
     * 补偿来补偿去始终有偏差，作者实测「静止时就看得出和碰撞箱中心不重合」。
     * standalone 模型没有这一层：模型坐标写什么，世界坐标就是什么。</p>
     */
    @SubscribeEvent
    public static void onRegisterStandalone(ModelEvent.RegisterStandalone event) {
        for (String k : KEYS) {
            event.register(MODELS.get(k), SimpleUnbakedStandaloneModel.simpleModelWrapper(
                    Identifier.fromNamespaceAndPath("rotation_craft", "item/ball3d_" + k)));
        }
        // 象限模型（组合球用）：combo_piece_<source>_<1..4>
        for (int src = 0; src < QUADRANT_SOURCE_COUNT; src++) {
            for (int q = 1; q <= 4; q++) {
                String qk = src + "_" + q;
                event.register(QUADRANT_MODELS.get(qk), SimpleUnbakedStandaloneModel.simpleModelWrapper(
                        Identifier.fromNamespaceAndPath("rotation_craft", "item/combo_piece_3d_" + qk)));
            }
        }
    }

    /**
     * 取某个象限的烘焙模型（组合球的 3D 组合渲染用）。
     *
     * @param source 来源球在 {@code BallFragments.sources()} 里的下标（0..18）
     * @param q      象限 1..4（1 左上 / 2 右上 / 3 左下 / 4 右下）
     * @return 模型；不存在或还没烘焙好时返回 {@code null}
     */
    public static BlockStateModelPart quadrantPart(int source, int q) {
        if (q < 1 || q > 4 || source < 0 || source >= QUADRANT_SOURCE_COUNT) {
            return null;
        }
        StandaloneModelKey<BlockStateModelPart> k = QUADRANT_MODELS.get(source + "_" + q);
        if (k == null) {
            return null;
        }
        return Minecraft.getInstance().getModelManager().getStandaloneModel(k);
    }

    /** 象限模型该用的 RenderType —— 与整球同一个图集 */
    public static net.minecraft.client.renderer.rendertype.RenderType quadrantRenderType() {
        return net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet();
    }


    /**
     * 从球实体身上推导该用哪份 3D 模型。
     *
     * @return 模型 key；没有对应 3D 模型时返回 {@code null}（调用方应退回原版 2D 渲染）
     */
    public static String keyOf(BallProjectile entity) {
        return keyOf(entity.getItem());
    }

    /** 同上，直接吃物品栈。{@code iron_ball} → {@code iron}；组合球等没有 3D 模型的返回 null */
    public static String keyOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null || !"rotation_craft".equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (!path.endsWith("_ball")) {
            return null;
        }
        String k = path.substring(0, path.length() - "_ball".length());
        return MODELS.containsKey(k) ? k : null;
    }

    /** 取烘焙好的模型。模型没就绪（比如资源重载中途）时返回 null。 */
    public static BlockStateModelPart part(String key) {
        StandaloneModelKey<BlockStateModelPart> k = MODELS.get(key);
        if (k == null) {
            return null;
        }
        return Minecraft.getInstance().getModelManager().getStandaloneModel(k);
    }

    /**
     * 该球 3D 模型用的 RenderType（带 alpha 裁剪，空心球要看得出镂空）。
     *
     * <p>⚠️ 用 {@code itemCutout} 而不是 {@code entityCutout}（作者 2026-10-29：「黑漆漆的看不出来是啥球」）。
     * 我们的几何是「方块模型那一套 quads」（{@link BlockStateModelPart}），
     * 而 {@code entityCutout} 是按**实体模型的法线**做漫反射的 —— 两者不是同一套光照，
     * 套上去整体偏暗，贴图颜色出不来。{@code itemCutout} 才是给物品/方块模型用的那条管线。</p>
     */
    public static net.minecraft.client.renderer.rendertype.RenderType renderType(String key) {
        // ⚠️ 方块模型必须用方块那套 RenderType（读 block atlas），不能用 entityCutout / itemCutout。
        //    之前两版发黑就是选错了：那两条管线要求的是「实体模型 / 物品模型」的顶点格式与光照，
        //    套到 BlockStateModelPart 的 quads 上颜色直接被压成黑。
        return net.minecraft.client.renderer.Sheets.cutoutBlockItemSheet();
    }
}

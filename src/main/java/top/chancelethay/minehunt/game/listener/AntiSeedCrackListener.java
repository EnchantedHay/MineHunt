package top.chancelethay.minehunt.game.listener;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.security.SecureRandom;
import java.util.*;

/**
 * 防种子破解监听器。
 *
 * <p>原版中结构（要塞、村庄等）的生成位置由世界种子加上一个固定的“盐值”（salt）决定，
 * 玩家一旦通过若干结构坐标反推出种子，就能预知全图结构与地形，使追猎失去意义。
 *
 * <p>本监听器在 {@code minehunt_} 开头的世界初始化时（{@link WorldInitEvent}），
 * 通过 NMS 反射遍历区块生成器持有的 {@link StructureSet} 列表，为
 * {@link RandomSpreadStructurePlacement} 类结构替换为使用随机盐值的副本，从而打断种子推算。
 *
 * <p>注意：要塞（{@link ConcentricRingsStructurePlacement}）会被刻意跳过，以免破坏末影龙流程所需的可达性。
 * 该实现强依赖 NMS 内部字段，Minecraft 版本升级时可能需要适配。
 */
public class AntiSeedCrackListener implements Listener {

    private final SecureRandom secureRandom = new SecureRandom();
    /** 反射遍历对象图的最大深度，避免无谓的深层递归。 */
    private static final int MAX_SEARCH_DEPTH = 3;

    // 缓存类的字段列表，避免每次调用都遍历继承链
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** 世界初始化时入口：仅处理本插件的游戏世界，定位区块生成器并随机化其结构盐值。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldInit(WorldInitEvent e) {
        World world = e.getWorld();
        if (!world.getName().startsWith("minehunt_")) return;

        try {
            ServerLevel nmsWorld = ((CraftWorld) world).getHandle();

            Object chunkMap = findChunkMap(nmsWorld.getChunkSource());

            if (chunkMap != null) {
                Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                recursiveInject(chunkMap, 0, visited);
                Bukkit.getLogger().info("[MineHunt] " + world.getName() + " structure salt randomized.");
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[MineHunt] InjectionError" + t.getMessage());
        }
    }

    /** 在对象图中有界深度优先地查找 {@code List<StructureSet>} 字段并就地替换；通过 {@code visited} 防止环引用。 */
    private void recursiveInject(Object target, int depth, Set<Object> visited) {
        if (target == null || depth > MAX_SEARCH_DEPTH) return;

        Class<?> clazz = target.getClass();
        String name = clazz.getName();
        if ((name.startsWith("java.") || name.startsWith("javax.")) && !List.class.isAssignableFrom(clazz)) return;
        if (target instanceof Enum) return;
        if (!visited.add(target)) return;

        for (Field field : getAllFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            try { field.setAccessible(true); } catch (InaccessibleObjectException | SecurityException ex) { continue; }

            Object value;
            try { value = field.get(target); } catch (Exception ex) { continue; }
            if (value == null) continue;

            // List<StructureSet>
            if (value instanceof List<?> list) {
                if (!list.isEmpty() && isStructureSetList(list)) {
                    replaceStructureList(field, target, list);
                }
            }
            else if (name.startsWith("net.minecraft")) {
                recursiveInject(value, depth + 1, visited);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void replaceStructureList(Field field, Object target, List<?> originalList) {
        try {
            List<Object> hackedList = new ArrayList<>();
            for (Object item : originalList) {
                StructureSet original = null;
                if (item instanceof Holder<?> h && h.value() instanceof StructureSet s) original = s;
                else if (item instanceof StructureSet s) original = s;

                if (original != null) {
                    StructureSet hacked = createHackedStructureSet(original);

                    if (item instanceof Holder<?>) hackedList.add(Holder.direct(hacked));
                    else hackedList.add(hacked);
                }
            }
            if (!hackedList.isEmpty()) field.set(target, hackedList);
        } catch (Exception ignored) {}
    }

    /** 复制一个 {@link StructureSet}，将其放置策略换成随机盐值版本；要塞（同心环）保持原样。 */
    private StructureSet createHackedStructureSet(StructureSet original) {
        try {
            if (original.placement() instanceof ConcentricRingsStructurePlacement) {
                return original;
            }
            return new StructureSet(original.structures(), createHackedPlacement(original.placement()));
        } catch (Exception e) { return original; }
    }

    private StructurePlacement createHackedPlacement(StructurePlacement original) {
        int randomSalt = secureRandom.nextInt(Integer.MAX_VALUE);
        try {
            Class<?> base = StructurePlacement.class;
            net.minecraft.core.Vec3i off = ReflectUtils.getPrivateField(original, base, "locateOffset");
            StructurePlacement.FrequencyReductionMethod freq = ReflectUtils.getPrivateField(original, base, "frequencyReductionMethod");
            float f = ReflectUtils.getPrivateField(original, base, "frequency");
            Optional<?> ex = ReflectUtils.getPrivateField(original, base, "exclusionZone");

            if (original instanceof RandomSpreadStructurePlacement s) {
                return new RandomSpreadStructurePlacement(off, freq, f,
                        randomSalt,
                        (Optional)ex, s.spacing(), s.separation(), s.spreadType());
            }
        } catch (Exception ignored) {}
        return original;
    }

    /** 反射定位 {@code ServerChunkCache} 中的 ChunkMap 字段（结构集合的持有者）。 */
    private Object findChunkMap(ServerChunkCache cache) {
        for (Field f : getAllFields(cache.getClass())) {
            if (f.getType().getName().contains("ChunkMap")) {
                try { f.setAccessible(true); return f.get(cache); } catch (Exception ignored) {}
            }
        }
        return null;
    }
    private List<Field> getAllFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> fields = new ArrayList<>();
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                Collections.addAll(fields, cur.getDeclaredFields());
                cur = cur.getSuperclass();
            }
            return fields;
        });
    }
    private boolean isStructureSetList(List<?> list) {
        Object first = list.get(0);
        if (first instanceof Holder<?> h && h.value() instanceof StructureSet) return true;
        return first instanceof StructureSet;
    }
    /** 反射读取私有字段的小工具。 */
    private static class ReflectUtils {
        @SuppressWarnings("unchecked")
        public static <T> T getPrivateField(Object instance, Class<?> declaredClass, String fieldName) throws Exception {
            try { Field f = declaredClass.getDeclaredField(fieldName); f.setAccessible(true); return (T) f.get(instance); }
            catch (NoSuchFieldException e) { throw e; }
        }
    }
}
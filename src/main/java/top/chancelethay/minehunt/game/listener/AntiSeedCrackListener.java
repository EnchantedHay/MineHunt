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

public class AntiSeedCrackListener implements Listener {

    private final SecureRandom secureRandom = new SecureRandom();
    private static final int MAX_SEARCH_DEPTH = 3;

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

    private Object findChunkMap(ServerChunkCache cache) {
        for (Field f : getAllFields(cache.getClass())) {
            if (f.getType().getName().contains("ChunkMap")) {
                try { f.setAccessible(true); return f.get(cache); } catch (Exception ignored) {}
            }
        }
        return null;
    }
    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) { Collections.addAll(fields, clazz.getDeclaredFields()); clazz = clazz.getSuperclass(); }
        return fields;
    }
    private boolean isStructureSetList(List<?> list) {
        Object first = list.get(0);
        if (first instanceof Holder<?> h && h.value() instanceof StructureSet) return true;
        return first instanceof StructureSet;
    }
    private static class ReflectUtils {
        @SuppressWarnings("unchecked")
        public static <T> T getPrivateField(Object instance, Class<?> declaredClass, String fieldName) throws Exception {
            try { Field f = declaredClass.getDeclaredField(fieldName); f.setAccessible(true); return (T) f.get(instance); }
            catch (NoSuchFieldException e) { throw e; }
        }
    }
}
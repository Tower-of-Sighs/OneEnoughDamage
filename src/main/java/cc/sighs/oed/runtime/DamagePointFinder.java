package cc.sighs.oed.runtime;

import cc.sighs.oed.asm.DamagePointData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import cpw.mods.modlauncher.api.INameMappingService;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

public final class DamagePointFinder {
    private static final Set<String> LIVING_HURT_METHOD_NAMES = livingHurtMethodNames();

    private final StackWalker stackWalker = StackWalker.getInstance();
    private final Map<String, List<DamagePointData.DamagePoint>> damagePointsByCaller;
    private final Map<String, List<DamagePointData.DamagePoint>> damagePointsByOwnerAndDescriptor;
    private final Map<String, List<Integer>> observedCallSites = new ConcurrentHashMap<>();

    public DamagePointFinder(List<DamagePointData.DamagePoint> points) {
        this.damagePointsByCaller = buildDamagePointIndex(points);
        this.damagePointsByOwnerAndDescriptor = buildOwnerDescriptorIndex(points);
    }

    public DamagePointData.DamagePoint find() {
        for (Caller caller : findDamageCallers()) {
            List<DamagePointData.DamagePoint> points = damagePointsByCaller.get(caller.key());
            if (points == null) {
                points = uniqueOwnerDescriptorMatch(caller);
            }
            if (points == null) {
                continue;
            }

            List<DamagePointData.DamagePoint> matches = new ArrayList<>(points);
            if (matches.size() == 1) {
                return matches.get(0);
            }

            DamagePointData.DamagePoint point = findByObservedCallSite(caller, matches);
            if (point != null) {
                return point;
            }
        }
        return null;
    }

    private List<DamagePointData.DamagePoint> uniqueOwnerDescriptorMatch(Caller caller) {
        List<DamagePointData.DamagePoint> matches = damagePointsByOwnerAndDescriptor.get(caller.ownerDescriptorKey());
        if (matches == null || matches.isEmpty()) {
            return null;
        }

        Set<String> methods = new LinkedHashSet<>();
        for (DamagePointData.DamagePoint match : matches) {
            methods.add(match.method());
            if (methods.size() > 1) {
                return null;
            }
        }
        return matches;
    }

    private DamagePointData.DamagePoint findByObservedCallSite(Caller caller, List<DamagePointData.DamagePoint> matches) {
        String key = caller.key();
        List<Integer> callSites = observedCallSites.computeIfAbsent(key, ignored -> new ArrayList<>());
        int callSiteIndex;
        synchronized (callSites) {
            if (!callSites.contains(caller.byteCodeIndex())) {
                callSites.add(caller.byteCodeIndex());
                callSites.sort(Integer::compareTo);
            }
            if (caller.byteCodeIndex() < 0) {
                return null;
            }
            callSiteIndex = callSites.indexOf(caller.byteCodeIndex());
        }

        matches.sort(Comparator.comparingInt(DamagePointData.DamagePoint::ordinal));
        return callSiteIndex >= 0 && callSiteIndex < matches.size() ? matches.get(callSiteIndex) : null;
    }

    private List<Caller> findDamageCallers() {
        return stackWalker.walk(frames -> {
            boolean[] seenLivingHurt = {false};
            return frames
                    .filter(frame -> {
                        if (seenLivingHurt[0]) {
                            return true;
                        }
                        if ("net.minecraft.world.entity.LivingEntity".equals(frame.getClassName())
                                && LIVING_HURT_METHOD_NAMES.contains(frame.getMethodName())) {
                            seenLivingHurt[0] = true;
                        }
                        return false;
                    })
                    .map(frame -> new Caller(frame.getClassName(), frame.getMethodName(), frame.getDescriptor(), frame.getByteCodeIndex()))
                    .toList();
        });
    }

    private static Map<String, List<DamagePointData.DamagePoint>> buildDamagePointIndex(List<DamagePointData.DamagePoint> points) {
        Map<String, List<DamagePointData.DamagePoint>> index = new HashMap<>();
        for (DamagePointData.DamagePoint point : points) {
            index.computeIfAbsent(callerKey(point.owner(), point.method(), point.descriptor()), ignored -> new ArrayList<>()).add(point);
        }
        return Map.copyOf(index);
    }

    private static Map<String, List<DamagePointData.DamagePoint>> buildOwnerDescriptorIndex(List<DamagePointData.DamagePoint> points) {
        Map<String, List<DamagePointData.DamagePoint>> index = new HashMap<>();
        for (DamagePointData.DamagePoint point : points) {
            index.computeIfAbsent(ownerDescriptorKey(point.owner(), point.descriptor()), ignored -> new ArrayList<>()).add(point);
        }
        return Map.copyOf(index);
    }

    private static String callerKey(String owner, String method, String descriptor) {
        return owner + "#" + method + descriptor;
    }

    private static String ownerDescriptorKey(String owner, String descriptor) {
        return owner + "#" + descriptor;
    }

    private static Set<String> livingHurtMethodNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add("hurt");
        names.add("m_6469_");
        names.add(remapMethodName("m_6469_"));
        return Set.copyOf(names);
    }

    private static String remapMethodName(String name) {
        try {
            return ObfuscationReflectionHelper.remapName(INameMappingService.Domain.METHOD, name);
        } catch (RuntimeException | LinkageError ignored) {
            return name;
        }
    }

    private record Caller(String owner, String method, String descriptor, int byteCodeIndex) {
        private String key() {
            return callerKey(owner, method, descriptor);
        }

        private String ownerDescriptorKey() {
            return DamagePointFinder.ownerDescriptorKey(owner, descriptor);
        }
    }
}

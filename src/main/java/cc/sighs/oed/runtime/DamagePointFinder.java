package cc.sighs.oed.runtime;

import cc.sighs.oed.asm.DamagePointData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DamagePointFinder {
    private final StackWalker stackWalker = StackWalker.getInstance();
    private final Map<String, List<DamagePointData.DamagePoint>> damagePointsByCaller;
    private final Map<String, List<Integer>> observedCallSites = new ConcurrentHashMap<>();

    public DamagePointFinder(List<DamagePointData.DamagePoint> points) {
        this.damagePointsByCaller = buildDamagePointIndex(points);
    }

    public DamagePointData.DamagePoint find(String damageSource, float amount) {
        for (Caller caller : findDamageCallers()) {
            List<DamagePointData.DamagePoint> points = damagePointsByCaller.get(caller.key());
            if (points == null) {
                continue;
            }

            List<DamagePointData.DamagePoint> matches = new ArrayList<>();
            for (DamagePointData.DamagePoint point : points) {
                if (!damageSourceMatches(point.damageSource(), damageSource)) {
                    continue;
                }
                matches.add(point);
            }
            if (matches.isEmpty()) {
                continue;
            }
            if (matches.size() == 1) {
                return matches.get(0);
            }

            return findByObservedCallSite(caller, matches);
        }
        return null;
    }

    private static boolean damageSourceMatches(String scannedSource, String runtimeSource) {
        return scannedSource.equals(runtimeSource) || camelToSnake(scannedSource).equals(runtimeSource);
    }

    private static String camelToSnake(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
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
            if (caller.byteCodeIndex() < 0 || callSites.size() < matches.size()) {
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
                        if ("net.minecraft.world.entity.LivingEntity".equals(frame.getClassName()) && "hurt".equals(frame.getMethodName())) {
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

    private static String callerKey(String owner, String method, String descriptor) {
        return owner + "#" + method + descriptor;
    }

    private record Caller(String owner, String method, String descriptor, int byteCodeIndex) {
        private String key() {
            return callerKey(owner, method, descriptor);
        }
    }
}

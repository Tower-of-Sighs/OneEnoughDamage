package cc.sighs.oed.asm;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModFileInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;

/**
 * Static bytecode analyzer that finds which living-entity-like classes create projectiles,
 * effect entities and other spawned damage-dealing entities.
 *
 * <p>The output is a map from created class name to the set of classes that directly
 * instantiate it with {@code new}. This is used by the dictionary generator to attribute
 * projectile/effect damage points back to the entity that fires or summons them.</p>
 *
 * <p>The analyzer uses ASM class hierarchy information instead of {@link Class#forName(String)}
 * so that it does not trigger loading of client-only classes on a dedicated server.</p>
 */
public final class EntityCreatorAnalyzer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ENTITY_INTERNAL = "net/minecraft/world/entity/Entity";
    private static final String LIVING_ENTITY_INTERNAL = "net/minecraft/world/entity/LivingEntity";
    private static final String PROJECTILE_INTERNAL = "net/minecraft/world/entity/projectile/Projectile";
    private static final String ENTITY_TYPE_INTERNAL = "net/minecraft/world/entity/EntityType";
    private static final String BEHAVIOR_INTERNAL = "net/minecraft/world/entity/ai/behavior/Behavior";
    private static final String ITEM_INTERNAL = "net/minecraft/world/item/Item";
    private static final String BLOCK_INTERNAL = "net/minecraft/world/level/block/Block";
    private static final String MOB_EFFECT_INTERNAL = "net/minecraft/world/effect/MobEffect";
    private static final Pattern BEHAVIOR_TARGET_PATTERN = Pattern.compile(
            "L" + Pattern.quote(BEHAVIOR_INTERNAL) + "<(?:L)?([^;>]+)"
    );
    private static final Pattern ENTITY_TYPE_PATTERN = Pattern.compile(
            "L" + Pattern.quote(ENTITY_TYPE_INTERNAL) + "<(?:L)?([^;>]+)"
    );

    private final Map<String, ClassInfo> hierarchy = new LinkedHashMap<>();
    private final List<Creation> creations = new ArrayList<>();
    private final Map<String, String> behaviorTargets = new LinkedHashMap<>();
    private Map<String, Set<String>> creatorIndex;

    /**
     * Runs the analysis over the current classpath.
     */
    public Map<String, Set<String>> analyze() {
        if (creatorIndex != null) {
            return creatorIndex;
        }
        SourceSummary summary = new SourceSummary();
        Set<Path> entries = classpathEntries(summary);
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                summary.scannedDirectories++;
                scanDirectory(entry);
            } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                summary.scannedJars++;
                scanJar(entry);
            }
        }

        creatorIndex = new LinkedHashMap<>();
        for (Creation creation : creations) {
            if (creation.methodReturn() && !isProjectileCreationTarget(creation.created())) {
                continue;
            }
            if (!isSpawnableEntity(creation.created)) {
                continue;
            }
            creatorIndex.computeIfAbsent(creation.created, ignored -> new LinkedHashSet<>()).add(creation.creator);
        }
        buildBehaviorTargets();
        LOGGER.info("OED creator analysis sources: {} unique entries (classpath {}, LoadingModList {}, ModList {}, legacy {}), scanned {} directories and {} jars",
                entries.size(), summary.classpath, summary.loadingModList, summary.modList, summary.legacyClasspathFile,
                summary.scannedDirectories, summary.scannedJars);
        LOGGER.info("OED creator analysis: {} classes scanned, {} creations found, {} created classes are spawnable entities, {} behavior targets found",
                hierarchy.size(), creations.size(), creatorIndex.size(), behaviorTargets.size());
        return creatorIndex;
    }

    /**
     * Returns the living entities that (directly or transitively) create the given class.
     * Recursively walks through intermediate spawnable entities such as projectiles.
     */
    public Set<String> creators(String created) {
        analyze();
        Set<String> creators = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(created);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            Set<String> direct = creatorIndex.get(current);
            if (direct == null) {
                continue;
            }
            for (String creator : direct) {
                if (isSpawnableEntity(creator)) {
                    queue.add(creator);
                } else if (isLivingEntity(creator)) {
                    creators.add(creator);
                }
            }
        }
        return creators;
    }

    /**
     * Returns the classes that directly instantiate the given class, without recursion.
     * This exposes item/block/effect creators that would otherwise be skipped by
     * {@link #creators(String)} because they are neither spawnable entities nor living entities.
     */
    public Set<String> directCreators(String created) {
        analyze();
        return creatorIndex.getOrDefault(created, Set.of());
    }

    private void scanDirectory(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        try (InputStream input = Files.newInputStream(path)) {
                            scanClass(input);
                        } catch (IOException | RuntimeException e) {
                            LOGGER.warn("OED creator analysis: failed to scan {}: {}", path, e.toString());
                        }
                    });
        } catch (IOException | UncheckedIOException ignored) {
        }
    }

    private void scanJar(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    scanClass(input);
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("OED creator analysis: failed to scan {} from {}: {}", entry.getName(), jarPath, e.toString());
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("OED creator analysis: failed to open jar {}: {}", jarPath, e.toString());
        }
    }

    private void scanClass(InputStream input) throws IOException {
        byte[] bytes = input.readAllBytes();
        if (bytes.length == 0) {
            return;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE);

        String className = classNode.name.replace('/', '.');
        hierarchy.put(className, new ClassInfo(classNode.superName, List.copyOf(classNode.interfaces), classNode.signature));

        ClassReader codeReader = new ClassReader(bytes);
        ClassNode codeNode = new ClassNode();
        codeReader.accept(codeNode, ClassReader.SKIP_FRAMES);
        analyzeClassCode(className, codeNode);
        analyzeClassFields(className, codeNode);
    }

    private void analyzeClassFields(String className, ClassNode classNode) {
        String creator = outerClass(className);
        for (org.objectweb.asm.tree.FieldNode field : classNode.fields) {
            String target = parseEntityTypeTarget(field.signature);
            if (target == null) {
                target = parseEntityTypeTarget(field.desc);
            }
            if (target != null) {
                creations.add(new Creation(creator, target.replace('/', '.'), false));
            }
        }
    }

    private static String parseEntityTypeTarget(String signature) {
        if (signature == null) {
            return null;
        }
        Matcher matcher = ENTITY_TYPE_PATTERN.matcher(signature);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private void analyzeClassCode(String className, ClassNode classNode) {
        String creator = outerClass(className);
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() == Opcodes.NEW && instruction instanceof TypeInsnNode typeInsn) {
                    String createdBinary = typeInsn.desc.replace('/', '.');
                    creations.add(new Creation(creator, createdBinary, false));
                } else if (instruction instanceof MethodInsnNode methodInsn) {
                    String returnType = methodReturnType(methodInsn.desc);
                    if (returnType != null) {
                        creations.add(new Creation(creator, returnType, true));
                    }
                }
            }
        }
    }

    private static String methodReturnType(String descriptor) {
        int end = descriptor.lastIndexOf(')');
        if (end < 0 || end + 1 >= descriptor.length() || descriptor.charAt(end + 1) != 'L') {
            return null;
        }
        int classEnd = descriptor.indexOf(';', end + 2);
        if (classEnd < 0) {
            return null;
        }
        return descriptor.substring(end + 2, classEnd).replace('/', '.');
    }

    public boolean isSpawnableEntity(String className) {
        if (className == null || className.startsWith("java/") || className.startsWith("javax/")) {
            return false;
        }
        String binary = className.replace('/', '.');
        // Any Entity subclass that is not a LivingEntity can be a minion/projectile/effect.
        if (!extendsClass(binary, ENTITY_INTERNAL)) {
            return false;
        }
        return !extendsClass(binary, LIVING_ENTITY_INTERNAL);
    }

    private boolean isProjectileCreationTarget(String className) {
        String internal = className.replace('.', '/');
        return !ENTITY_INTERNAL.equals(internal)
                && !PROJECTILE_INTERNAL.equals(internal)
                && extendsClass(internal, PROJECTILE_INTERNAL);
    }

    /**
     * Returns whether the given class extends {@link net.minecraft.world.entity.LivingEntity}
     * according to the scanned hierarchy.
     */
    public boolean isLivingEntity(String className) {
        return extendsClass(className.replace('.', '/'), LIVING_ENTITY_INTERNAL);
    }

    public Set<String> livingDescendants(String className) {
        analyze();
        Set<String> descendants = new LinkedHashSet<>();
        String binary = className.replace('/', '.');
        for (String candidate : hierarchy.keySet()) {
            if (candidate.equals(binary)) {
                continue;
            }
            if (isLivingEntity(candidate) && extendsClass(candidate, binary.replace('.', '/'))) {
                descendants.add(candidate);
            }
        }
        return descendants;
    }

    /**
     * Returns a human-readable category for the given owner class.
     */
    public String ownerType(String className) {
        String internal = className.replace('.', '/');
        if (extendsClass(internal, LIVING_ENTITY_INTERNAL)) {
            return "living";
        }
        if (extendsClass(internal, PROJECTILE_INTERNAL)) {
            return "projectile";
        }
        if (extendsClass(internal, ENTITY_INTERNAL)) {
            return "entity";
        }
        if (extendsClass(internal, ITEM_INTERNAL)) {
            return "item";
        }
        if (extendsClass(internal, BLOCK_INTERNAL)) {
            return "block";
        }
        if (extendsClass(internal, MOB_EFFECT_INTERNAL)) {
            return "effect";
        }
        if (extendsClass(internal, BEHAVIOR_INTERNAL)) {
            return "behavior";
        }
        return "other";
    }

    /**
     * If the given class is an AI {@code Behavior<T>} (or inherits from one), returns the
     * internal name of the target entity type {@code T}. This lets damage points owned by
     * behavior classes be attributed back to the living entity they belong to.
     */
    public String behaviorTarget(String className) {
        return behaviorTargets.get(className);
    }

    private void buildBehaviorTargets() {
        for (Map.Entry<String, ClassInfo> entry : hierarchy.entrySet()) {
            String target = findBehaviorTargetInHierarchy(entry.getKey());
            if (target != null) {
                behaviorTargets.put(entry.getKey(), target);
            }
        }
    }

    private String findBehaviorTargetInHierarchy(String className) {
        Set<String> visited = new LinkedHashSet<>();
        String current = className.replace('.', '/');
        while (current != null && !visited.contains(current)) {
            visited.add(current);
            ClassInfo info = hierarchy.get(current.replace('/', '.'));
            if (info == null) {
                return null;
            }
            String target = parseBehaviorTarget(info.signature);
            if (target != null) {
                return target;
            }
            current = info.superName;
        }
        return null;
    }

    private static String parseBehaviorTarget(String signature) {
        if (signature == null) {
            return null;
        }
        Matcher matcher = BEHAVIOR_TARGET_PATTERN.matcher(signature);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace('/', '.');
    }

    private boolean extendsClass(String className, String targetInternal) {
        Set<String> visited = new LinkedHashSet<>();
        String current = className.replace('.', '/');
        while (current != null && !visited.contains(current)) {
            visited.add(current);
            if (targetInternal.equals(current)) {
                return true;
            }
            ClassInfo info = hierarchy.get(current.replace('/', '.'));
            if (info == null) {
                return false;
            }
            if (targetInternal.equals(info.superName)) {
                return true;
            }
            for (String iface : info.interfaces) {
                if (targetInternal.equals(iface)) {
                    return true;
                }
                if (extendsClass(iface.replace('/', '.'), targetInternal)) {
                    return true;
                }
            }
            current = info.superName;
        }
        return false;
    }

    private static String outerClass(String className) {
        int innerMarker = className.indexOf('$');
        return innerMarker >= 0 ? className.substring(0, innerMarker) : className;
    }

    private static Set<Path> classpathEntries(SourceSummary summary) {
        Set<Path> entries = new LinkedHashSet<>();
        addClasspathProperty(entries, "java.class.path", summary);
        addLoadingModListEntries(entries, summary);
        addModFileEntries(entries, summary);

        String legacyClasspathFile = System.getProperty("legacyClassPath.file");
        if (legacyClasspathFile != null && !legacyClasspathFile.isBlank()) {
            Path path = Paths.get(legacyClasspathFile);
            if (Files.isRegularFile(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        if (addClasspathEntry(entries, line)) {
                            summary.legacyClasspathFile++;
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }

        return entries;
    }

    private static void addLoadingModListEntries(Set<Path> entries, SourceSummary summary) {
        LoadingModList loadingModList;
        try {
            loadingModList = LoadingModList.get();
        } catch (RuntimeException ignored) {
            return;
        }
        if (loadingModList == null) {
            return;
        }
        for (ModFileInfo modFileInfo : loadingModList.getModFiles()) {
            try {
                if (addClasspathEntry(entries, modFileInfo.getFile().getFilePath().toString())) {
                    summary.loadingModList++;
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void addModFileEntries(Set<Path> entries, SourceSummary summary) {
        ModList modList;
        try {
            modList = ModList.get();
        } catch (RuntimeException ignored) {
            return;
        }
        if (modList == null) {
            return;
        }
        for (IModFileInfo modFileInfo : modList.getModFiles()) {
            try {
                if (addClasspathEntry(entries, modFileInfo.getFile().getFilePath().toString())) {
                    summary.modList++;
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void addClasspathProperty(Set<Path> entries, String property, SourceSummary summary) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return;
        }
        for (String entry : value.split(File.pathSeparator)) {
            if (addClasspathEntry(entries, entry)) {
                summary.classpath++;
            }
        }
    }

    private static boolean addClasspathEntry(Set<Path> entries, String entry) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        Path path = Paths.get(entry);
        if (Files.exists(path)) {
            return entries.add(path.toAbsolutePath().normalize());
        }
        return false;
    }

    private record ClassInfo(String superName, List<String> interfaces, String signature) {
    }

    private record Creation(String creator, String created, boolean methodReturn) {
    }

    private static final class SourceSummary {
        private int classpath;
        private int loadingModList;
        private int modList;
        private int legacyClasspathFile;
        private int scannedDirectories;
        private int scannedJars;
    }
}

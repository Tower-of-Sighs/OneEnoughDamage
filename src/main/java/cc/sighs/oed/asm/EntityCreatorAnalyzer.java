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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
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

    private final Map<String, ClassInfo> hierarchy = new LinkedHashMap<>();
    private final List<Creation> creations = new ArrayList<>();

    /**
     * Runs the analysis over the current classpath.
     */
    public Map<String, Set<String>> analyze() {
        for (Path entry : classpathEntries()) {
            if (Files.isDirectory(entry)) {
                scanDirectory(entry);
            } else if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                scanJar(entry);
            }
        }

        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Creation creation : creations) {
            if (!isSpawnableEntity(creation.created)) {
                continue;
            }
            result.computeIfAbsent(creation.created, ignored -> new LinkedHashSet<>()).add(creation.creator);
        }
        LOGGER.info("OED creator analysis: {} classes scanned, {} creations found, {} created classes are spawnable entities",
                hierarchy.size(), creations.size(), result.size());
        return result;
    }

    private void scanDirectory(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        try (InputStream input = Files.newInputStream(path)) {
                            scanClass(input);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        } catch (UncheckedIOException ignored) {
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
                }
            }
        } catch (IOException ignored) {
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
        hierarchy.put(className, new ClassInfo(classNode.superName, List.copyOf(classNode.interfaces)));

        ClassReader codeReader = new ClassReader(bytes);
        ClassNode codeNode = new ClassNode();
        codeReader.accept(codeNode, ClassReader.SKIP_FRAMES);
        analyzeClassCode(className, codeNode);
    }

    private void analyzeClassCode(String className, ClassNode classNode) {
        String creator = outerClass(className);
        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() != Opcodes.NEW || !(instruction instanceof TypeInsnNode typeInsn)) {
                    continue;
                }
                String createdBinary = typeInsn.desc.replace('/', '.');
                creations.add(new Creation(creator, createdBinary));
            }
        }
    }

    private boolean isSpawnableEntity(String className) {
        if (className == null || className.startsWith("java/") || className.startsWith("javax/")) {
            return false;
        }
        String binary = className.replace('/', '.');
        if (binary.startsWith("net.minecraft.world.entity.projectile.") || binary.contains(".entity.projectile.")) {
            return extendsClass(binary, PROJECTILE_INTERNAL);
        }
        if (binary.startsWith("net.minecraft.world.entity.effect.") || binary.contains(".entity.effect.")) {
            return extendsClass(binary, ENTITY_INTERNAL);
        }
        return extendsClass(binary, PROJECTILE_INTERNAL);
    }

    /**
     * Returns whether the given class extends {@link net.minecraft.world.entity.LivingEntity}
     * according to the scanned hierarchy.
     */
    public boolean isLivingEntity(String className) {
        return extendsClass(className.replace('.', '/'), LIVING_ENTITY_INTERNAL);
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

    private static Set<Path> classpathEntries() {
        Set<Path> entries = new LinkedHashSet<>();
        addClasspathProperty(entries, "java.class.path");

        String legacyClasspathFile = System.getProperty("legacyClassPath.file");
        if (legacyClasspathFile != null && !legacyClasspathFile.isBlank()) {
            Path path = Paths.get(legacyClasspathFile);
            if (Files.isRegularFile(path)) {
                try {
                    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                        addClasspathEntry(entries, line);
                    }
                } catch (IOException ignored) {
                }
            }
        }

        return entries;
    }

    private static void addClasspathProperty(Set<Path> entries, String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return;
        }
        for (String entry : value.split(File.pathSeparator)) {
            addClasspathEntry(entries, entry);
        }
    }

    private static void addClasspathEntry(Set<Path> entries, String entry) {
        if (entry == null || entry.isBlank()) {
            return;
        }
        Path path = Paths.get(entry);
        if (Files.exists(path)) {
            entries.add(path);
        }
    }

    private record ClassInfo(String superName, List<String> interfaces) {
    }

    private record Creation(String creator, String created) {
    }
}

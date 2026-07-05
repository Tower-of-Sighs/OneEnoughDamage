package cc.sighs.oed.scan;

import cc.sighs.oed.asm.DamagePointConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;

public final class DamagePointScanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HURT_TARGET_OWNER_PREFIX = "net/minecraft/world/entity/";
    private static final String DAMAGE_SOURCES_OWNER = "net/minecraft/world/damagesource/DamageSources";
    private static final String ENTITY_HURT_DESC = "(Lnet/minecraft/world/damagesource/DamageSource;F)Z";
    private static final Path CACHE_FILE = Paths.get("config", "OED", "damage_points-cache.json");
    private static boolean scannedThisRun;

    private final List<DamagePointScanResult> scanResults = new ArrayList<>();
    private int scannedClasses;
    private int scannedDirectories;
    private int scannedJars;
    private long scanElapsedMillis;

    private DamagePointScanner() {
    }

    public static void scanAndWriteCacheIfNeeded() {
        if (scannedThisRun) {
            return;
        }
        if (DamagePointConfig.readCache() && Files.exists(CACHE_FILE)) {
            return;
        }
        scannedThisRun = true;
        new DamagePointScanner().scanAndWriteCache();
    }

    private void scanAndWriteCache() {
        long started = System.nanoTime();
        scanResults.clear();
        scannedClasses = 0;
        scannedDirectories = 0;
        scannedJars = 0;
        for (Path path : classpathEntries()) {
            if (Files.isDirectory(path)) {
                scannedDirectories++;
                scanDirectory(path);
            } else if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                scannedJars++;
                scanJar(path);
            }
        }
        scanElapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        LOGGER.info("OED scanner: scanned {} classes in {} directories and {} jars, found {} hurt calls in {} ms",
                scannedClasses, scannedDirectories, scannedJars, scanResults.size(), scanElapsedMillis);
        writeCache();
    }

    public static Path cacheFile() {
        return CACHE_FILE;
    }

    public static List<DamagePointScanResult> readCache() {
        if (!Files.isRegularFile(CACHE_FILE)) {
            return List.of();
        }
        try (InputStream input = Files.newInputStream(CACHE_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<DamagePointScanResult> points = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("points")) {
                JsonObject object = element.getAsJsonObject();
                points.add(new DamagePointScanResult(
                        getString(object, "owner"),
                        getString(object, "method"),
                        getString(object, "descriptor"),
                        getInt(object, "ordinal"),
                        getFloat(object, "default"),
                        getString(object, "damageSource"),
                        getBoolean(object, "transformed"),
                        getBoolean(object, "constant")
                ));
            }
            return points;
        } catch (IOException | RuntimeException e) {
            LOGGER.error("OED scanner: failed to read cache", e);
            return List.of();
        }
    }

    private void scanDirectory(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .forEach(path -> {
                        try (InputStream input = Files.newInputStream(path)) {
                            scanClass(input);
                        } catch (IOException | RuntimeException ignored) {
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
                } catch (IOException | RuntimeException ignored) {
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
        reader.accept(classNode, ClassReader.SKIP_FRAMES);
        scannedClasses++;
        scanOnly(classNode.name.replace('/', '.'), classNode);
    }

    private void scanOnly(String targetClassName, ClassNode targetClass) {
        for (MethodNode method : targetClass.methods) {
            int hurtOrdinal = 0;

            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                if (!isEntityHurtCall(instruction)) {
                    continue;
                }

                hurtOrdinal++;
                Float hardcodedDamage = resolvePreviousFloat(method, instruction);
                boolean constant = hardcodedDamage != null;
                float defaultDamage = constant ? hardcodedDamage : 1.0F;

                String methodName = method.name;
                String damageSource = findDamageSourceFactory(instruction);
                scanResults.add(new DamagePointScanResult(
                        targetClassName,
                        methodName,
                        method.desc,
                        hurtOrdinal,
                        defaultDamage,
                        damageSource,
                        false,
                        constant
                ));
            }
        }
    }

    private static String findDamageSourceFactory(AbstractInsnNode hurtCall) {
        for (AbstractInsnNode current = hurtCall.getPrevious(); current != null; current = current.getPrevious()) {
            if (current instanceof MethodInsnNode methodInsn && DAMAGE_SOURCES_OWNER.equals(methodInsn.owner)) {
                return methodInsn.name;
            }
        }
        return "unknown";
    }

    private static boolean isEntityHurtCall(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode methodInsn)) {
            return false;
        }

        return methodInsn.getOpcode() == Opcodes.INVOKEVIRTUAL
                && methodInsn.owner.startsWith(HURT_TARGET_OWNER_PREFIX)
                && "hurt".equals(methodInsn.name)
                && ENTITY_HURT_DESC.equals(methodInsn.desc);
    }

    private static Float resolvePreviousFloat(MethodNode method, AbstractInsnNode instruction) {
        return resolvePreviousFloat(method, instruction, 0);
    }

    private static Float resolvePreviousFloat(MethodNode method, AbstractInsnNode instruction, int depth) {
        AbstractInsnNode previous = previousMeaningful(instruction.getPrevious());
        if (previous == null || depth > 8) {
            return null;
        }

        return resolveFloatValue(method, previous, depth + 1);
    }

    private static Float resolveFloatValue(MethodNode method, AbstractInsnNode instruction, int depth) {
        if (instruction == null || depth > 8) {
            return null;
        }

        Float constant = readFloatConstant(instruction);
        if (constant != null) {
            return constant;
        }

        return switch (instruction.getOpcode()) {
            case Opcodes.FLOAD -> instruction instanceof VarInsnNode varInsn ? resolveStoredFloat(method, instruction, varInsn.var, depth + 1) : null;
            case Opcodes.I2F -> {
                Integer value = resolvePreviousInt(method, instruction, depth + 1);
                yield value == null ? null : (float) value;
            }
            case Opcodes.FNEG -> {
                Float value = resolvePreviousFloat(method, instruction, depth + 1);
                yield value == null ? null : -value;
            }
            case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV -> resolveBinaryFloat(method, instruction, depth + 1);
            default -> null;
        };
    }

    private static Float readFloatConstant(AbstractInsnNode instruction) {
        return switch (instruction.getOpcode()) {
            case Opcodes.FCONST_0 -> 0.0F;
            case Opcodes.FCONST_1 -> 1.0F;
            case Opcodes.FCONST_2 -> 2.0F;
            case Opcodes.LDC -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Float value ? value : null;
            default -> null;
        };
    }

    private static Integer resolvePreviousInt(MethodNode method, AbstractInsnNode instruction, int depth) {
        AbstractInsnNode previous = previousMeaningful(instruction.getPrevious());
        if (previous == null || depth > 8) {
            return null;
        }

        Integer constant = readIntConstant(previous);
        if (constant != null) {
            return constant;
        }

        return switch (previous.getOpcode()) {
            case Opcodes.ILOAD -> previous instanceof VarInsnNode varInsn ? resolveStoredInt(method, previous, varInsn.var, depth + 1) : null;
            default -> null;
        };
    }

    private static Integer readIntConstant(AbstractInsnNode instruction) {
        return switch (instruction.getOpcode()) {
            case Opcodes.ICONST_M1 -> -1;
            case Opcodes.ICONST_0 -> 0;
            case Opcodes.ICONST_1 -> 1;
            case Opcodes.ICONST_2 -> 2;
            case Opcodes.ICONST_3 -> 3;
            case Opcodes.ICONST_4 -> 4;
            case Opcodes.ICONST_5 -> 5;
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> instruction instanceof IntInsnNode intInsn ? intInsn.operand : null;
            case Opcodes.LDC -> instruction instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value ? value : null;
            default -> null;
        };
    }

    private static Float resolveStoredFloat(MethodNode method, AbstractInsnNode before, int local, int depth) {
        AbstractInsnNode store = findPreviousLocalWrite(method, before, local, Opcodes.FSTORE);
        if (store == null) {
            return null;
        }

        return resolvePreviousFloat(method, store, depth + 1);
    }

    private static Integer resolveStoredInt(MethodNode method, AbstractInsnNode before, int local, int depth) {
        AbstractInsnNode store = findPreviousLocalWrite(method, before, local, Opcodes.ISTORE);
        if (store == null) {
            return null;
        }

        return resolvePreviousInt(method, store, depth + 1);
    }

    private static AbstractInsnNode findPreviousLocalWrite(MethodNode method, AbstractInsnNode before, int local, int storeOpcode) {
        int scanned = 0;
        for (AbstractInsnNode current = before.getPrevious(); current != null && scanned < 80; current = current.getPrevious()) {
            current = previousMeaningful(current);
            if (current == null) {
                return null;
            }
            if (isBranchBoundary(current)) {
                return null;
            }
            if (current instanceof IincInsnNode iincInsn && iincInsn.var == local) {
                return null;
            }
            if (current instanceof VarInsnNode varInsn && varInsn.var == local) {
                return varInsn.getOpcode() == storeOpcode ? varInsn : null;
            }
            if (current == method.instructions.getFirst()) {
                break;
            }
            scanned++;
        }
        return null;
    }

    private static Float resolveBinaryFloat(MethodNode method, AbstractInsnNode operation, int depth) {
        AbstractInsnNode rightInsn = previousMeaningful(operation.getPrevious());
        if (rightInsn == null) {
            return null;
        }

        Float right = resolveFloatValue(method, rightInsn, depth + 1);
        if (right == null) {
            return null;
        }

        AbstractInsnNode leftInsn = previousMeaningful(rightInsn.getPrevious());
        if (leftInsn == null) {
            return null;
        }

        Float left = resolveFloatValue(method, leftInsn, depth + 1);
        if (left == null) {
            return null;
        }

        return switch (operation.getOpcode()) {
            case Opcodes.FADD -> left + right;
            case Opcodes.FSUB -> left - right;
            case Opcodes.FMUL -> left * right;
            case Opcodes.FDIV -> right == 0.0F ? null : left / right;
            default -> null;
        };
    }

    private static boolean isBranchBoundary(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        return instruction instanceof LabelNode
                || opcode == Opcodes.GOTO
                || opcode == Opcodes.JSR
                || opcode == Opcodes.RET
                || opcode == Opcodes.TABLESWITCH
                || opcode == Opcodes.LOOKUPSWITCH
                || (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IF_ACMPNE)
                || opcode == Opcodes.IFNULL
                || opcode == Opcodes.IFNONNULL;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction;
        while (current instanceof LabelNode || current instanceof LineNumberNode || current instanceof FrameNode) {
            current = current.getPrevious();
        }
        return current;
    }

    private void writeCache() {
        try {
            Files.createDirectories(CACHE_FILE.getParent());
            Files.writeString(CACHE_FILE, toJson(), StandardCharsets.UTF_8);
            LOGGER.info("OED scanner: wrote cache with {} points to {}", scanResults.size(), CACHE_FILE);
        } catch (IOException e) {
            LOGGER.error("OED scanner: failed to write cache", e);
        }
    }

    private String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"stats\": {\n");
        json.append("    \"scannedClasses\": ").append(scannedClasses).append(",\n");
        json.append("    \"scannedDirectories\": ").append(scannedDirectories).append(",\n");
        json.append("    \"scannedJars\": ").append(scannedJars).append(",\n");
        json.append("    \"elapsedMillis\": ").append(scanElapsedMillis).append("\n");
        json.append("  },\n");
        json.append("  \"points\": [\n");
        for (int i = 0; i < scanResults.size(); i++) {
            DamagePointScanResult result = scanResults.get(i);
            json.append("    {\n");
            json.append("      \"owner\": \"").append(escape(result.owner())).append("\",\n");
            json.append("      \"method\": \"").append(escape(result.method())).append("\",\n");
            json.append("      \"descriptor\": \"").append(escape(result.descriptor())).append("\",\n");
            json.append("      \"ordinal\": ").append(result.ordinal()).append(",\n");
            json.append("      \"default\": ").append(result.defaultDamage()).append(",\n");
            json.append("      \"damageSource\": \"").append(escape(result.damageSource())).append("\",\n");
            json.append("      \"constant\": ").append(result.constant()).append(",\n");
            json.append("      \"transformed\": ").append(result.transformed()).append(",\n");
            String attributePath = attributePath(result.owner(), result.method(), result.ordinal(), result.constant());
            String description = result.owner() + "#" + result.method() + "#" + result.ordinal();
            json.append("      \"attribute\": \"oneenoughdamage:").append(escape(attributePath)).append("\",\n");
            json.append("      \"description\": \"").append(escape(description)).append("\"\n");
            json.append("    }");
            if (i + 1 < scanResults.size()) {
                json.append(",");
            }
            json.append("\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    public static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static String attributePath(String owner, String method, int ordinal, boolean constant) {
        String[] parts = owner.split("\\.");
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                path.append('/');
            }
            path.append(camelToSnake(parts[i]));
        }
        path.append('/').append(camelToSnake(method)).append('/').append(ordinal);
        path.append('/').append(constant ? 'r' : 'm');
        return normalizeAttributePath(path.toString());
    }

    public static String normalizeAttributePath(String value) {
        String result = value.toLowerCase(Locale.ROOT);
        result = result.replaceAll("[^a-z0-9/._-]", "_");
        result = result.replaceAll("_+", "_");
        result = result.replaceAll("(/|^)_+|_(/|$)", "$1$2");
        return result;
    }

    public static String camelToSnake(String value) {
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

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static int getInt(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : 0;
    }

    private static float getFloat(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsFloat() : 0.0F;
    }

    private static boolean getBoolean(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }
}

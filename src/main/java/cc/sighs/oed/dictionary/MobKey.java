package cc.sighs.oed.dictionary;

public record MobKey(String enName, String zhName, String fallback, String type, String entityId) implements Comparable<MobKey> {
    public MobKey(String enName, String zhName, String fallback) {
        this(enName, zhName, fallback, null, null);
    }

    public MobKey(String enName, String zhName, String fallback, String type) {
        this(enName, zhName, fallback, type, null);
    }

    @Override
    public int compareTo(MobKey other) {
        return enName.compareToIgnoreCase(other.enName);
    }
}

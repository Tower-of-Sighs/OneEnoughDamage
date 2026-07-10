package cc.sighs.oed.scan;

public record DamagePointScanResult(
        String owner,
        String method,
        String descriptor,
        int ordinal,
        float defaultDamage,
        String damageType,
        boolean transformed,
        boolean constant,
        String attribute
) {
    public DamagePointScanResult(String owner, String method, String descriptor, int ordinal, float defaultDamage, boolean transformed, boolean constant) {
        this(owner, method, descriptor, ordinal, defaultDamage, "unknown", transformed, constant, null);
    }

    public DamagePointScanResult(String owner, String method, String descriptor, int ordinal, float defaultDamage, String damageType, boolean transformed, boolean constant) {
        this(owner, method, descriptor, ordinal, defaultDamage, damageType, transformed, constant, null);
    }

    public DamagePointScanResult {
        if (damageType == null || damageType.isBlank()) {
            damageType = "unknown";
        }
    }

    public String attribute() {
        return attribute == null || attribute.isBlank()
                ? "oneenoughdamage:" + DamagePointScanner.attributePath(owner, method, ordinal, constant)
                : attribute;
    }

    public String description() {
        return owner + "#" + method + "#" + ordinal;
    }

    public DamagePointScanResult withAttribute(String attribute) {
        return new DamagePointScanResult(owner, method, descriptor, ordinal, defaultDamage, damageType, transformed, constant, attribute);
    }
}

package cc.sighs.oed.scan;

public record DamagePointScanResult(
        String owner,
        String method,
        String descriptor,
        int ordinal,
        float defaultDamage,
        boolean transformed,
        boolean constant,
        String attribute
) {
    public DamagePointScanResult(String owner, String method, String descriptor, int ordinal, float defaultDamage, boolean transformed, boolean constant) {
        this(owner, method, descriptor, ordinal, defaultDamage, transformed, constant, null);
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
        return new DamagePointScanResult(owner, method, descriptor, ordinal, defaultDamage, transformed, constant, attribute);
    }
}

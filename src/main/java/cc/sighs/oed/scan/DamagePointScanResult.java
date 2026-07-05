package cc.sighs.oed.scan;

public record DamagePointScanResult(
        String owner,
        String method,
        String descriptor,
        int ordinal,
        float defaultDamage,
        String damageSource,
        boolean transformed,
        boolean constant
) {
    public String attribute() {
        return "oneenoughdamage:" + DamagePointScanner.attributePath(owner, method, ordinal, constant);
    }

    public String description() {
        return owner + "#" + method + "#" + ordinal;
    }
}

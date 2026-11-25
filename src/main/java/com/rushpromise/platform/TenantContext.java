package com.rushpromise.platform;

import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class TenantContext {
    public static final String LEGACY = "public";
    private static final Pattern SHOP_SCHEMA = Pattern.compile("shop_[a-f0-9]{32}");
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static String schema() {
        Binding bound = CURRENT.get();
        return bound == null ? LEGACY : bound.schema();
    }

    public static boolean legacy() { return LEGACY.equals(schema()); }

    public static boolean nativeAuthorized() {
        Binding bound = CURRENT.get();
        return bound != null && bound.nativeAuthorized();
    }

    public static Scope enter(String schema) { return bind(schema, false); }

    static Scope enterNative(String schema) {
        if (LEGACY.equals(schema)) throw new IllegalArgumentException("Tenant native scope cannot be legacy");
        return bind(schema, true);
    }

    private static Scope bind(String schema, boolean nativeAuthorized) {
        if (!LEGACY.equals(schema) && (schema == null || !SHOP_SCHEMA.matcher(schema).matches())) {
            throw new IllegalArgumentException("Unregistered schema format");
        }
        Binding previous = CURRENT.get();
        CURRENT.set(new Binding(schema, nativeAuthorized));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static <T> T withSchema(String schema, Supplier<T> work) {
        try (Scope ignored = enter(schema)) {
            return work.get();
        }
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private record Binding(String schema, boolean nativeAuthorized) {}
}

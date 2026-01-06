package org.apache.logging.log4j.util;

/**
 * Stub Supplier interface for TeaVM compatibility
 * This is a functional interface similar to java.util.function.Supplier
 */
@FunctionalInterface
public interface Supplier<T> {
    T get();
}

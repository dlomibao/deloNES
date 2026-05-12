package org.apache.logging.log4j;

import org.apache.logging.log4j.util.Supplier;

/**
 * Stub Logger for TeaVM compatibility
 * Redirects logging to browser console via GDX
 */
public interface Logger {
    void info(String message);
    void info(String message, Object p0);
    void info(String message, Object p0, Object p1);
    void info(String message, Object p0, Object p1, Object p2);
    void info(String message, Object p0, Object p1, Object p2, Object p3);
    void info(String message, Object... params);
    
    void warn(String message);
    void warn(String message, Object p0);
    void warn(String message, Object p0, Object p1);
    void warn(String message, Object... params);
    
    void error(String message);
    void error(String message, Object p0);
    void error(String message, Object p0, Object p1);
    void error(String message, Object p0, Object p1, Object p2);
    void error(String message, Object... params);
    void error(String message, Throwable throwable);
    void error(Supplier<?> messageSupplier);
    
    void debug(String message);
    void debug(String message, Object p0);
    void debug(String message, Object... params);
    
    void trace(Object message);
    void trace(String message);
    void trace(String message, Object... params);
    void trace(String message, Supplier<?>... paramSuppliers);
    void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7);
}

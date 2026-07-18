package org.apache.logging.log4j;

import com.badlogic.gdx.Gdx;
import org.apache.logging.log4j.util.Supplier;

/**
 * Stub LogManager for TeaVM compatibility
 * Creates loggers that use GDX Application logging
 */
public class LogManager {
    public static Logger getLogger(Class<?> clazz) {
        return new GdxLogger(clazz.getSimpleName());
    }
    
    public static Logger getLogger(String name) {
        return new GdxLogger(name);
    }
    
    private static class GdxLogger implements Logger {
        private final String name;
        
        public GdxLogger(String name) {
            this.name = name;
        }
        
        @Override
        public void info(String message) {
            if (Gdx.app != null) {
                Gdx.app.log(name, message);
            }
        }
        
        @Override
        public void info(String message, Object p0) {
            info(format(message, p0));
        }
        
        @Override
        public void info(String message, Object p0, Object p1) {
            info(format(message, p0, p1));
        }
        
        @Override
        public void info(String message, Object p0, Object p1, Object p2) {
            info(format(message, p0, p1, p2));
        }
        
        @Override
        public void info(String message, Object p0, Object p1, Object p2, Object p3) {
            info(format(message, p0, p1, p2, p3));
        }
        
        @Override
        public void info(String message, Object... params) {
            info(format(message, params));
        }
        
        @Override
        public void warn(String message) {
            if (Gdx.app != null) {
                Gdx.app.log(name, "WARN: " + message);
            }
        }
        
        @Override
        public void warn(String message, Object p0) {
            warn(format(message, p0));
        }
        
        @Override
        public void warn(String message, Object p0, Object p1) {
            warn(format(message, p0, p1));
        }
        
        @Override
        public void warn(String message, Object p0, Object p1, Object p2) {
            warn(format(message, p0, p1, p2));
        }

        @Override
        public void warn(String message, Object... params) {
            warn(format(message, params));
        }
        
        @Override
        public void error(String message) {
            if (Gdx.app != null) {
                Gdx.app.error(name, message);
            }
        }
        
        @Override
        public void error(String message, Object p0) {
            error(format(message, p0));
        }
        
        @Override
        public void error(String message, Object p0, Object p1) {
            error(format(message, p0, p1));
        }
        
        @Override
        public void error(String message, Object p0, Object p1, Object p2) {
            error(format(message, p0, p1, p2));
        }
        
        @Override
        public void error(String message, Object... params) {
            error(format(message, params));
        }
        
        @Override
        public void error(Supplier<?> messageSupplier) {
            if (Gdx.app != null && messageSupplier != null) {
                Gdx.app.error(name, String.valueOf(messageSupplier.get()));
            }
        }
        
        @Override
        public void error(String message, Throwable throwable) {
            if (Gdx.app != null) {
                Gdx.app.error(name, message, throwable);
            }
        }
        
        // Trace/debug log methods are deliberate no-ops on the web build.
        // CPU6502.clock() and PPU.clock() call log.trace() per instruction,
        // and the format(String, Object...) path below uses
        // String.replaceFirst("\\{\\}", ...) which compiles a regex on every
        // call — pre-optimization Phase 1 profile showed it dominated the
        // emulator hot loop (~50% of CPU time). Trace/debug are dev-only
        // signals; skipping them entirely is the correct shape for a web
        // build that has no debug log destination anyway.
        @Override
        public void debug(String message) { /* no-op */ }

        @Override
        public void debug(String message, Object p0) { /* no-op */ }

        @Override
        public void debug(String message, Object... params) { /* no-op */ }

        @Override
        public void trace(Object message) { /* no-op */ }

        @Override
        public void trace(String message) { /* no-op */ }

        @Override
        public void trace(String message, Object... params) { /* no-op */ }

        @Override
        public void trace(String message, Supplier<?>... paramSuppliers) { /* no-op */ }

        @Override
        public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) { /* no-op */ }
        
        private String format(String message, Object... params) {
            if (params == null || params.length == 0 || message == null) {
                return message;
            }
            // Plain {} placeholder replacement — no regex. The original
            // implementation used String.replaceFirst("\\{\\}", ...) which
            // compiles a regex pattern per call; that single line cost
            // ~50% of CPU time on the web build's emulator hot loop.
            StringBuilder sb = new StringBuilder(message.length() + 32);
            int paramIdx = 0;
            int from = 0;
            while (from < message.length()) {
                int next = message.indexOf("{}", from);
                if (next < 0 || paramIdx >= params.length) {
                    sb.append(message, from, message.length());
                    break;
                }
                sb.append(message, from, next);
                sb.append(String.valueOf(params[paramIdx++]));
                from = next + 2;
            }
            return sb.toString();
        }
    }
}

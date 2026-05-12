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
        
        @Override
        public void debug(String message) {
            if (Gdx.app != null) {
                Gdx.app.debug(name, message);
            }
        }
        
        @Override
        public void debug(String message, Object p0) {
            debug(format(message, p0));
        }
        
        @Override
        public void debug(String message, Object... params) {
            debug(format(message, params));
        }
        
        @Override
        public void trace(Object message) {
            if (Gdx.app != null) {
                Gdx.app.debug(name, "TRACE: " + String.valueOf(message));
            }
        }
        
        @Override
        public void trace(String message) {
            if (Gdx.app != null) {
                Gdx.app.debug(name, "TRACE: " + message);
            }
        }
        
        @Override
        public void trace(String message, Object... params) {
            trace(format(message, params));
        }
        
        @Override
        public void trace(String message, Supplier<?>... paramSuppliers) {
            if (Gdx.app != null && paramSuppliers != null) {
                Object[] params = new Object[paramSuppliers.length];
                for (int i = 0; i < paramSuppliers.length; i++) {
                    params[i] = paramSuppliers[i].get();
                }
                trace(format(message, params));
            } else {
                trace(message);
            }
        }
        
        @Override
        public void trace(String message, Object p0, Object p1, Object p2, Object p3, Object p4, Object p5, Object p6, Object p7) {
            trace(format(message, p0, p1, p2, p3, p4, p5, p6, p7));
        }
        
        private String format(String message, Object... params) {
            if (params == null || params.length == 0) {
                return message;
            }
            // Simple placeholder replacement
            String result = message;
            for (int i = 0; i < params.length; i++) {
                result = result.replaceFirst("\\{\\}", String.valueOf(params[i]));
            }
            return result;
        }
    }
}

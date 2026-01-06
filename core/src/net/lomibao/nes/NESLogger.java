package net.lomibao.nes;

/**
 * Minimal logging utility compatible with GWT
 * Logs to System.out (visible in browser console when running on GWT)
 */
public class NESLogger {
    private String name;
    
    public NESLogger(Class<?> clazz) {
        this.name = clazz.getSimpleName();
    }
    
    public void trace(String msg) {
        // Suppress trace in production
    }
    
    public void trace(String msg, Object... args) {
        // Suppress trace in production
    }
    
    public void debug(String msg, Object... args) {
        logOutput("DEBUG", msg, args);
    }
    
    public void info(String msg, Object... args) {
        logOutput("INFO", msg, args);
    }
    
    public void warn(String msg, Object... args) {
        logOutput("WARN", msg, args);
    }
    
    public void error(String msg, Object... args) {
        logOutput("ERROR", msg, args);
    }
    
    public void error(String msg, Throwable t) {
        logOutput("ERROR", msg, t);
    }
    
    private void logOutput(String level, String msg, Object... args) {
        try {
            String formatted = String.format(msg.replaceAll("\\{\\}", "%s"), args);
            System.out.println("[" + name + "] " + level + ": " + formatted);
        } catch (Exception e) {
            System.out.println("[" + name + "] " + level + ": " + msg);
        }
    }
    
    private void logOutput(String level, String msg, Throwable t) {
        System.out.println("[" + name + "] " + level + ": " + msg);
        if (t != null) {
            t.printStackTrace();
        }
    }
    
    public static NESLogger getLogger(Class<?> clazz) {
        return new NESLogger(clazz);
    }
}

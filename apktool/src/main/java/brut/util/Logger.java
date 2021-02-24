package brut.util;

import java.util.logging.Level;

public interface Logger {

    void log(Level level, String message, Throwable ex);

    void info(String message);

    void warning(String message);

    void error(String message);
}

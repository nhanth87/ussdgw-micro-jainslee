package et.restlink.ussdgw.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Ss7PersistDirs {
    public static final String DEFAULT_RELATIVE = "configs/ss7-persist";
    public static final String[] PROPERTY_KEYS = {
            "sctp.persist.dir", "m3ua.persist.dir",
            "sccpmanagement.persist.dir", "sccprouter.persist.dir", "sccpresource.persist.dir",
            "tcapmanagement.persist.dir", "mapmanagement.persist.dir", "capmanagement.persist.dir",
    };

    private Ss7PersistDirs() {}

    public static Path ensureConfigured(String configured) {
        Path dir = Path.of(configured == null || configured.isBlank() ? DEFAULT_RELATIVE : configured);
        if (!dir.isAbsolute()) {
            dir = Path.of("").toAbsolutePath().resolve(dir).normalize();
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String abs = dir.toString();
        for (String key : PROPERTY_KEYS) {
            System.setProperty(key, abs);
        }
        return dir;
    }
}

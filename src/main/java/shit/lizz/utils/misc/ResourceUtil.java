package shit.lizz.utils.misc;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ResourceUtil {
    private static final Logger logger;
    private static final String RESOURCE_READ_ERROR_MSG;

    public static Optional<byte[]> getResource(String string) {
        try (InputStream inputStream = ResourceUtil.getResourceStream(string)) {
            return Optional.of(inputStream.readAllBytes());
        } catch (IOException iOException) {
            logger.log(Level.SEVERE, RESOURCE_READ_ERROR_MSG + string, iOException);
            return Optional.empty();
        }
    }

    public static InputStream getResourceStream(String string) throws FileNotFoundException {
        return new FileInputStream(string);
    }

    public static InputStream openResourceStream(String string) throws IOException {
        Path path = Paths.get(string, new String[0]);
        return Files.newInputStream(path, new OpenOption[0]);
    }

    static {
        RESOURCE_READ_ERROR_MSG = "Failed to read bytes from resource: ";
        logger = Logger.getLogger(ResourceUtil.class.getName());
    }
}
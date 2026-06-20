package shit.nilore.utils.misc;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class IOUtil {

    public static void writeBytes(File file, byte[] bytes) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(bytes);
        }
    }

    public static File getChildFile(File parent, String name) {
        return new File(parent, name);
    }

    public static String readString(InputStream inputStream) {
        StringBuilder stringBuilder = new StringBuilder();
        try {
            String line;
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append('\n');
            }
        } catch (Exception exception) {
            exception.printStackTrace();
        }
        return stringBuilder.toString();
    }

    public static byte[] readBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[fis.available()];
            int read = 0;
            while (read < bytes.length) {
                int count = fis.read(bytes, read, bytes.length - read);
                if (count < 0) break;
                read += count;
            }
            return bytes;
        }
    }

    public static void closeReader(FileReader fileReader) {
        try {
            fileReader.close();
        } catch (IOException e) {
            System.out.println("Failed to close reader");
        }
    }

    public static FileReader openReader(File file) {
        try {
            return new FileReader(file);
        } catch (IOException e) {
            return null;
        }
    }

    public static File ensureFile(File file) {
        try {
            if (!file.exists()) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                file.createNewFile();
            }
        } catch (IOException e) {
            // ignore
        }
        return file;
    }

    public static void writeJson(File file, JsonObject jsonObject) {
        try (FileWriter fileWriter = new FileWriter(file)) {
            fileWriter.write(new GsonBuilder().setPrettyPrinting().create().toJson(jsonObject));
        } catch (IOException e) {
            file.delete();
        }
    }

    public static byte[] readAllBytes(InputStream stream) throws IOException {
        return stream.readAllBytes();
    }

    public static byte[] readResource(String resource) throws IOException {
        InputStream inputStream = ClassLoader.getSystemResourceAsStream(resource);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + resource);
        }
        return readAllBytes(inputStream);
    }

    public static InputStream toInputStream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}

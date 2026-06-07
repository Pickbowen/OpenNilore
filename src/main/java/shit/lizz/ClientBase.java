package shit.lizz;

import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ClientBase {
    public static Minecraft mc;
    public static Logger logger = LogManager.getLogger("Client");
    public static float yaw;
    public static boolean isLoading;
    public static final ConcurrentLinkedQueue<Runnable> delayPackets = new ConcurrentLinkedQueue<>();
}

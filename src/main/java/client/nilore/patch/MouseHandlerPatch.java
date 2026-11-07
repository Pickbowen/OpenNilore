package client.nilore.patch;

import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.MouseHandler;
import client.nilore.NiloreClient;
import client.nilore.event.impl.KeyEvent;
import client.nilore.modules.KeyBind;

@Patch(MouseHandler.class)
public class MouseHandlerPatch {
    @Inject(method = "onPress", desc = "(JIII)V")
    public static void onPress(MouseHandler handler, long window, int button, int action, int modifiers, CallbackInfo callbackInfo) {
        if (handler == null || !NiloreClient.isReady()) return;
        if (button < 0 || button > 7) return;
        KeyEvent event = new KeyEvent(KeyBind.mouseCode(button), action != 0);
        NiloreClient.getInstance().getEventBus().call(event);
    }
}

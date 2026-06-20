package shit.nilore.event.impl;

import lombok.Getter;
import lombok.Setter;
import lombok.Generated;
import net.minecraft.network.chat.Component;
import shit.nilore.event.EventMarker;
import shit.nilore.event.impl.ChatReceiveEvent.MessageType;

public class ChatReceiveEvent
implements EventMarker {
    public enum MessageType {
        NAME, CHAT, SYSTEM
    }
    @Getter
    private ChatReceiveEvent.MessageType msgType;
    @Getter @Setter
    private Component component;

    public ChatReceiveEvent(Component component) {
        this.msgType = ChatReceiveEvent.MessageType.NAME;
        this.component = component;
    }

    @Generated
    public void setMessageType(ChatReceiveEvent.MessageType chatReceiveEvent$MessageType) {
        this.msgType = chatReceiveEvent$MessageType;
    }

    @Generated
    public ChatReceiveEvent(ChatReceiveEvent.MessageType chatReceiveEvent$MessageType, Component component) {
        this.msgType = chatReceiveEvent$MessageType;
        this.component = component;
    }
}
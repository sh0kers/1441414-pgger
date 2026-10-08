package ru.holyworld.tntautofill;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

public class ChatCapture {

    private static volatile String last = null;

    public static void init() {
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            last = msg;
            return true;
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            last = "/" + cmd;
            return true;
        });
    }

    public static String poll() {
        String v = last;
        last = null;
        return v;
    }
}

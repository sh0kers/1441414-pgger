package ru.holyworld.tntautofill;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

import java.util.concurrent.ConcurrentLinkedQueue;

public class ChatCapture {

    private static final ConcurrentLinkedQueue<String> outgoing = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();

    public static void init() {
        ClientSendMessageEvents.ALLOW_CHAT.register(msg -> {
            outgoing.add(msg);
            return true;
        });
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> {
            outgoing.add("/" + cmd);
            return true;
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            incoming.add(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            incoming.add(message.getString());
        });
    }

    public static String pollOutgoing() {
        return outgoing.poll();
    }

    public static String pollIncoming() {
        return incoming.poll();
    }

    public static void drainIncoming() {
        incoming.clear();
    }
}

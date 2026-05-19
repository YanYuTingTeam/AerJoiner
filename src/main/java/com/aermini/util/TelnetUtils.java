package com.aermini.util;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class TelnetUtils {
    public static boolean isServerReachable(String host, int port, int timeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            return socket.isConnected();
        } catch (IOException e) {
            return false;
        }
    }
}

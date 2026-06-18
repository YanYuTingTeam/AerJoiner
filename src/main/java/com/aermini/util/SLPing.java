package com.aermini.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;

public class SLPing {

    public static Response ping(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());

            byte[] hostBytes = host.getBytes("UTF-8");
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, 47);
            writeVarInt(handshake, hostBytes.length);
            handshake.write(hostBytes);
            handshake.write(port >> 8);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);

            sendPacket(out, handshake.toByteArray());
            sendPacket(out, new byte[]{0x00});
            out.flush();

            readVarInt(in);
            readVarInt(in);
            int jsonLen = readVarInt(in);
            byte[] jsonBytes = new byte[jsonLen];
            in.readFully(jsonBytes);

            String jsonStr = new String(jsonBytes, "UTF-8");
            JsonObject root = new JsonParser().parse(jsonStr).getAsJsonObject();

            JsonObject players = root.getAsJsonObject("players");
            int online = players.get("online").getAsInt();
            int max = players.get("max").getAsInt();

            JsonObject ver = root.getAsJsonObject("version");
            String versionName = ver.get("name").getAsString();
            int protocol = ver.get("protocol").getAsInt();

            String motdClean = cleanMotd(root.get("description")).replaceAll("§[0-9a-zA-Z]", "");
            String favicon = root.has("favicon") ? root.get("favicon").getAsString() : "";

            return new Response(online, max, versionName, protocol, motdClean, favicon, jsonStr);
        } catch (Exception e) {
            return null;
        }
    }

    private static void sendPacket(DataOutputStream out, byte[] data) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, data.length);
        buf.write(data);
        out.write(buf.toByteArray());
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, position = 0;
        byte b;
        while (true) {
            b = in.readByte();
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) break;
            position += 7;
            if (position >= 32) throw new RuntimeException("VarInt too big");
        }
        return value;
    }

    private static String cleanMotd(JsonElement element) {
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            StringBuilder sb = new StringBuilder(obj.has("text") ? obj.get("text").getAsString() : "");
            if (obj.has("extra")) for (JsonElement e : obj.getAsJsonArray("extra")) sb.append(cleanMotd(e));
            return sb.toString();
        }
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : element.getAsJsonArray()) sb.append(cleanMotd(e));
            return sb.toString();
        }
        return "";
    }

    public static class Response {
        public final int online;
        public final int max;
        public final String version;
        public final int protocol;
        public final String motdClean;
        public final String favicon;
        public final String raw;

        Response(int online, int max, String version, int protocol, String motdClean, String favicon, String raw) {
            this.online = online;
            this.max = max;
            this.version = version;
            this.protocol = protocol;
            this.motdClean = motdClean;
            this.favicon = favicon;
            this.raw = raw;
        }
    }
}
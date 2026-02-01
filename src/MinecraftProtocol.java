import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;
import java.util.HashMap;
import java.util.Map;

public class MinecraftProtocol {
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 5000;
    
    private static final int[] PROTOCOL_VERSIONS = {
        774, 773, 772, 770, 769, 768, 767, 766, 765, 764, 763, 762, 761, 760, 759,
        758, 757, 756, 755, 754, 753, 751, 736, 735, 578, 575, 498, 477, 404, 393,
        340, 335, 316, 315, 210, 110, 109, 108, 107, 47, 5
    };
    
    // Mapping version names to protocol numbers
    private static final Map<String, Integer> VERSION_MAP = new HashMap<>();
    static {
        VERSION_MAP.put("1.21.11", 774);
        VERSION_MAP.put("1.21.10", 773);
        VERSION_MAP.put("1.21.9", 773);
        VERSION_MAP.put("1.21.8", 772);
        VERSION_MAP.put("1.21.6", 770);
        VERSION_MAP.put("1.21.5", 769);
        VERSION_MAP.put("1.21.4", 768);
        VERSION_MAP.put("1.21.3", 768);
        VERSION_MAP.put("1.21.2", 768);
        VERSION_MAP.put("1.21.1", 767);
        VERSION_MAP.put("1.21", 767);
        VERSION_MAP.put("1.20.6", 766);
        VERSION_MAP.put("1.20.5", 766);
        VERSION_MAP.put("1.20.4", 765);
        VERSION_MAP.put("1.20.3", 765);
        VERSION_MAP.put("1.20.2", 764);
        VERSION_MAP.put("1.20.1", 763);
        VERSION_MAP.put("1.20", 763);
        VERSION_MAP.put("1.19.4", 762);
        VERSION_MAP.put("1.19.3", 761);
        VERSION_MAP.put("1.19.2", 760);
        VERSION_MAP.put("1.19.1", 760);
        VERSION_MAP.put("1.19", 759);
        VERSION_MAP.put("1.18.2", 758);
        VERSION_MAP.put("1.18.1", 757);
        VERSION_MAP.put("1.18", 757);
        VERSION_MAP.put("1.17.1", 756);
        VERSION_MAP.put("1.17", 755);
        VERSION_MAP.put("1.16.5", 754);
        VERSION_MAP.put("1.16.4", 754);
        VERSION_MAP.put("1.16.3", 753);
        VERSION_MAP.put("1.16.2", 751);
        VERSION_MAP.put("1.16.1", 736);
        VERSION_MAP.put("1.16", 735);
        VERSION_MAP.put("1.15.2", 578);
        VERSION_MAP.put("1.15", 575);
        VERSION_MAP.put("1.14.4", 498);
        VERSION_MAP.put("1.14", 477);
        VERSION_MAP.put("1.13.2", 404);
        VERSION_MAP.put("1.13", 393);
        VERSION_MAP.put("1.12.2", 340);
        VERSION_MAP.put("1.12", 335);
        VERSION_MAP.put("1.11.2", 316);
        VERSION_MAP.put("1.11.1", 316);
        VERSION_MAP.put("1.11", 315);
        VERSION_MAP.put("1.10", 210);
        VERSION_MAP.put("1.9.4", 110);
        VERSION_MAP.put("1.9.3", 110);
        VERSION_MAP.put("1.9.2", 109);
        VERSION_MAP.put("1.9.1", 108);
        VERSION_MAP.put("1.9", 107);
        VERSION_MAP.put("1.8", 47);
        VERSION_MAP.put("1.7.10", 5);
    }
    
    public static ServerInfo queryServer(String ip, int port) {
        try {
            return performHandshake(ip, port);
        } catch (Exception e) {
            return new ServerInfo(ip, port);
        }
    }
    
    private static ServerInfo performHandshake(String ip, int port) throws IOException {
        long startTime = System.currentTimeMillis();
        
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(READ_TIMEOUT);
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT);
            
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                
                sendHandshake(out, ip, port, PROTOCOL_VERSIONS[0]);
                
                out.writeByte(1);
                out.writeByte(0);
                
                readVarInt(in);
                int packetId = readVarInt(in);
                
                if (packetId != 0) {
                    throw new IOException("Invalid packet ID: " + packetId);
                }
                
                int jsonLength = readVarInt(in);
                if (jsonLength <= 0 || jsonLength > 32767) {
                    throw new IOException("Invalid JSON length: " + jsonLength);
                }
                
                byte[] jsonBytes = new byte[jsonLength];
                in.readFully(jsonBytes);
                String json = new String(jsonBytes, StandardCharsets.UTF_8);
                
                long now = System.currentTimeMillis();
                out.writeByte(9);
                out.writeByte(1);
                out.writeLong(now);
                
                readVarInt(in);
                readVarInt(in);
                in.readLong();
                
                long ping = System.currentTimeMillis() - startTime;
                
                return parseServerInfo(ip, port, json, ping);
            }
        }
    }
    
    private static void sendHandshake(DataOutputStream out, String host, int port, int protocolVersion) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream handshake = new DataOutputStream(buffer);
        
        handshake.writeByte(0);
        writeVarInt(handshake, protocolVersion);
        writeString(handshake, host);
        handshake.writeShort(port);
        writeVarInt(handshake, 1);
        
        byte[] packet = buffer.toByteArray();
        writeVarInt(out, packet.length);
        out.write(packet);
    }
    
    private static ServerInfo parseServerInfo(String ip, int port, String json, long ping) {
        try {
            JSONObject obj = new JSONObject(json);
            
            String version = obj.optJSONObject("version") != null
                ? obj.getJSONObject("version").optString("name", "Unknown")
                : "Unknown";
            
            int protocolVersion = obj.optJSONObject("version") != null
                ? obj.getJSONObject("version").optInt("protocol", -1)
                : -1;
            
            JSONObject players = obj.optJSONObject("players");
            int online = players != null ? players.optInt("online", 0) : 0;
            int max = players != null ? players.optInt("max", 0) : 0;
            
            String motd = "";
            if (obj.has("description")) {
                Object desc = obj.get("description");
                if (desc instanceof String) {
                    motd = (String) desc;
                } else if (desc instanceof JSONObject) {
                    motd = extractTextFromJson((JSONObject) desc);
                }
            }
            
            boolean hasWhitelist = checkWhitelistSmart(ip, port, version, protocolVersion);
            
            return new ServerInfo(ip, port, true, version, online, max, motd, hasWhitelist, ping);
            
        } catch (JSONException e) {
            return new ServerInfo(ip, port, true, "Parse Error", 0, 0, "", false, ping);
        }
    }
    
    private static String extractTextFromJson(JSONObject json) {
        StringBuilder text = new StringBuilder();
        
        if (json.has("text")) {
            text.append(json.optString("text", ""));
        }
        
        if (json.has("extra")) {
            try {
                JSONArray extra = json.getJSONArray("extra");
                for (int i = 0; i < extra.length(); i++) {
                    Object item = extra.get(i);
                    if (item instanceof String) {
                        text.append(item);
                    } else if (item instanceof JSONObject) {
                        text.append(extractTextFromJson((JSONObject) item));
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        
        return text.toString();
    }
    
    private static boolean checkWhitelistSmart(String ip, int port, String version, int reportedProtocol) {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("[WhiteList Check] Starting for: " + ip + ":" + port);
        System.out.println("[WhiteList Check] Server version: " + version);
        System.out.println("[WhiteList Check] Reported protocol: " + reportedProtocol);
        System.out.println("=".repeat(70));
        
        // Try to get protocol from reported version
        Integer detectedProtocol = getProtocolFromVersion(version);
        
        if (detectedProtocol != null) {
            System.out.println("[WhiteList Check] Detected protocol from version: " + detectedProtocol);
            WhitelistCheckResult result = checkByFakeLogin(ip, port, "WhiteListTest", detectedProtocol);
            if (result.status == CheckStatus.SUCCESS) {
                System.out.println("[WhiteList Check] ✓ SUCCESS with detected protocol!");
                System.out.println("[WhiteList Check] ✓ RESULT: " + (result.hasWhitelist ? "HAS WHITELIST" : "NO WHITELIST"));
                return result.hasWhitelist;
            }
        }
        
        // If we have reported protocol from server, try it
        if (reportedProtocol > 0) {
            System.out.println("[WhiteList Check] Trying reported protocol: " + reportedProtocol);
            WhitelistCheckResult result = checkByFakeLogin(ip, port, "WhiteListTest", reportedProtocol);
            if (result.status == CheckStatus.SUCCESS) {
                System.out.println("[WhiteList Check] ✓ SUCCESS with reported protocol!");
                System.out.println("[WhiteList Check] ✓ RESULT: " + (result.hasWhitelist ? "HAS WHITELIST" : "NO WHITELIST"));
                return result.hasWhitelist;
            }
        }
        
        // Fallback: try priority protocols
        int[] priorityProtocols = {767, 765, 763, 762, 761, 760, 758, 754, 47};
        System.out.println("[WhiteList Check] Trying priority protocols...");
        
        for (int protocol : priorityProtocols) {
            if (protocol == reportedProtocol || (detectedProtocol != null && protocol == detectedProtocol)) {
                continue; // Already tried
            }
            
            WhitelistCheckResult result = checkByFakeLogin(ip, port, "WhiteListTest", protocol);
            
            if (result.status == CheckStatus.SUCCESS) {
                System.out.println("[WhiteList Check] ✓ RESULT: " + (result.hasWhitelist ? "HAS WHITELIST" : "NO WHITELIST"));
                return result.hasWhitelist;
            }
        }
        
        // Last resort: try all remaining protocols
        System.out.println("[WhiteList Check] Trying all remaining protocols...");
        for (int protocol : PROTOCOL_VERSIONS) {
            boolean alreadyTried = false;
            for (int p : priorityProtocols) {
                if (p == protocol) {
                    alreadyTried = true;
                    break;
                }
            }
            if (alreadyTried || protocol == reportedProtocol || 
                (detectedProtocol != null && protocol == detectedProtocol)) {
                continue;
            }
            
            WhitelistCheckResult result = checkByFakeLogin(ip, port, "WhiteListTest", protocol);
            if (result.status == CheckStatus.SUCCESS) {
                System.out.println("[WhiteList Check] ✓ RESULT: " + (result.hasWhitelist ? "HAS WHITELIST" : "NO WHITELIST"));
                return result.hasWhitelist;
            }
        }
        
        System.out.println("[WhiteList Check] All protocols failed - assuming NO WHITELIST");
        return false;
    }
    
    private static Integer getProtocolFromVersion(String version) {
        if (version == null || version.isEmpty()) return null;
        
        // Clean version string (remove "Minecraft ", "Paper ", "Spigot ", etc.)
        String cleanVersion = version
            .replaceAll("(?i)(minecraft|paper|spigot|purpur|fabric|forge|bungeecord|waterfall|velocity)\\s*", "")
            .trim();
        
        // Try exact match
        if (VERSION_MAP.containsKey(cleanVersion)) {
            return VERSION_MAP.get(cleanVersion);
        }
        
        // Try to extract version number (e.g., "1.20.1" from "Paper 1.20.1")
        String[] parts = cleanVersion.split("\\s+");
        for (String part : parts) {
            if (VERSION_MAP.containsKey(part)) {
                return VERSION_MAP.get(part);
            }
        }
        
        // Try partial match for versions like "1.20.x"
        for (Map.Entry<String, Integer> entry : VERSION_MAP.entrySet()) {
            if (cleanVersion.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    private enum CheckStatus {
        SUCCESS,
        PROTOCOL_MISMATCH,
        ERROR
    }
    
    private static class WhitelistCheckResult {
        CheckStatus status;
        boolean hasWhitelist;
        
        WhitelistCheckResult(CheckStatus status, boolean hasWhitelist) {
            this.status = status;
            this.hasWhitelist = hasWhitelist;
        }
    }
    
    private static WhitelistCheckResult checkByFakeLogin(String ip, int port, String username, int protocolVersion) {
        System.out.println("[WhiteList Check] Attempting login with protocol " + protocolVersion);
        
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(3000);
            socket.connect(new InetSocketAddress(ip, port), 3000);
            
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                
                // Handshake
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                DataOutputStream handshake = new DataOutputStream(buffer);
                handshake.writeByte(0);
                writeVarInt(handshake, protocolVersion);
                writeString(handshake, ip);
                handshake.writeShort(port);
                writeVarInt(handshake, 2); // Login state
                
                byte[] packet = buffer.toByteArray();
                writeVarInt(out, packet.length);
                out.write(packet);
                out.flush();
                
                // Login Start packet format varies by protocol version:
                // < 759 (before 1.19): Name only
                // 759-760 (1.19-1.19.2): Name + Player UUID
                // >= 761 (1.19.3+): Name + Player UUID
                buffer = new ByteArrayOutputStream();
                DataOutputStream login = new DataOutputStream(buffer);
                login.writeByte(0); // Packet ID = 0x00 (Login Start)
                
                writeString(login, username);
                
                // Add UUID for 1.19+ (protocol 759+)
                if (protocolVersion >= 759) {
                    // Player UUID (most significant bits + least significant bits)
                    // Using 0 for both parts (null UUID)
                    login.writeLong(0L);  // Most significant bits
                    login.writeLong(0L);  // Least significant bits
                }
                
                packet = buffer.toByteArray();
                writeVarInt(out, packet.length);
                out.write(packet);
                out.flush();
                
                // Read response
                socket.setSoTimeout(2000);
                
                try {
                    int length = readVarInt(in);
                    if (length <= 0 || length > 32767) {
                        System.out.println("[WhiteList Check] Invalid packet length: " + length);
                        return new WhitelistCheckResult(CheckStatus.ERROR, false);
                    }
                    
                    int packetId = readVarInt(in);
                    System.out.println("[WhiteList Check] Packet ID: 0x" + String.format("%02X", packetId) + 
                                     " | Protocol: " + protocolVersion);
                    
                    if (packetId == 0x00) { // Disconnect
                        int msgLength = readVarInt(in);
                        if (msgLength > 0 && msgLength < 32768) {
                            byte[] msgBytes = new byte[msgLength];
                            in.readFully(msgBytes);
                            String message = new String(msgBytes, StandardCharsets.UTF_8);
                            
                            System.out.println("[WhiteList Check] Message: " + message);
                            
                            // Check for version mismatch
                            if (isVersionMismatch(message)) {
                                System.out.println("[WhiteList Check] → Version mismatch");
                                return new WhitelistCheckResult(CheckStatus.PROTOCOL_MISMATCH, false);
                            }
                            
                            // Check for whitelist
                            boolean hasWhitelist = analyzeDisconnectMessage(message);
                            return new WhitelistCheckResult(CheckStatus.SUCCESS, hasWhitelist);
                        }
                        
                    } else if (packetId == 0x01) { // Encryption Request
                        System.out.println("[WhiteList Check] → Encryption = NO whitelist");
                        return new WhitelistCheckResult(CheckStatus.SUCCESS, false);
                        
                    } else if (packetId == 0x02) { // Login Success
                        System.out.println("[WhiteList Check] → Login success = NO whitelist");
                        return new WhitelistCheckResult(CheckStatus.SUCCESS, false);
                        
                    } else if (packetId == 0x03) { // Set Compression
                        System.out.println("[WhiteList Check] → Compression = NO whitelist");
                        return new WhitelistCheckResult(CheckStatus.SUCCESS, false);
                    }
                    
                } catch (IOException e) {
                    System.out.println("[WhiteList Check] Read error: " + e.getMessage());
                    return new WhitelistCheckResult(CheckStatus.ERROR, false);
                }
                
            }
            
        } catch (Exception e) {
            System.out.println("[WhiteList Check] Connection error: " + e.getMessage());
            return new WhitelistCheckResult(CheckStatus.ERROR, false);
        }
        
        return new WhitelistCheckResult(CheckStatus.ERROR, false);
    }
    
    private static boolean isVersionMismatch(String message) {
        String lower = message.toLowerCase();
        String[] versionKeywords = {
            "outdated client", "outdated server", "version mismatch",
            "несовпадение версий", "устаревший клиент", "устаревший сервер",
            "требуется", "required", "incompatible", "несовместим"
        };
        
        for (String keyword : versionKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        
        // Check JSON translate keys
        if (message.contains("multiplayer.disconnect.outdated") ||
            message.contains("multiplayer.disconnect.incompatible")) {
            return true;
        }
        
        return false;
    }

    private static boolean analyzeDisconnectMessage(String message) {
        String lower = message.toLowerCase();

        String[] whitelistRoots = {

                "whitelist", "white-list", "white list", "whitelis",
                "not allow", "not allowe", "not permit", "not permitte",
                "deny", "deni", "denied",
                "restrict", "restriction",
                "forbid", "forbidden",
                "unauthor", "unauthoriz",
                "authoriz", "authorisat",
                "access den", "access forbid",
                "join den", "join forbid",
                "not member", "only member",
                "private serv", "private server",
                "you cannot", "you can't",
                "not invited", "invite only",

                "бел",
                "вайт",
                "спис",
                "доступ",
                "запрещ",
                "разреш",
                "не разреш",
                "нет доступ",
                "доступ закр",
                "авториз",
                "не авториз",
                "огранич",
                "приват",
                "закрыт",
                "вход запрещ",
                "не пуск",
                "только для",
                "нет прав",
                "отказано"
        };

        for (String root : whitelistRoots) {
            if (lower.contains(root)) {
                System.out.println("[WhiteList Check] ✓ ROOT MATCH: '" + root + "'");
                return true;
            }
        }

        if (message.contains("{") && message.contains("}")) {
            try {
                JSONObject json = new JSONObject(message);

                if (json.has("translate")) {
                    String translate = json.getString("translate").toLowerCase();
                    for (String root : whitelistRoots) {
                        if (translate.contains(root)) {
                            System.out.println("[WhiteList Check] ✓ JSON TRANSLATE ROOT: '" + root + "'");
                            return true;
                        }
                    }
                }

                String fullText = extractTextFromJson(json).toLowerCase();
                for (String root : whitelistRoots) {
                    if (fullText.contains(root)) {
                        System.out.println("[WhiteList Check] ✓ JSON TEXT ROOT: '" + root + "'");
                        return true;
                    }
                }

            } catch (Exception ignored) {
            }
        }

        return false;
    }

    
    private static void writeString(DataOutputStream out, String string) throws IOException {
        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
    
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }
    
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        byte currentByte;
        
        do {
            currentByte = in.readByte();
            value |= (currentByte & 0x7F) << position;
            position += 7;
            
            if (position >= 32) {
                throw new IOException("VarInt too big");
            }
        } while ((currentByte & 0x80) != 0);
        
        return value;
    }
}
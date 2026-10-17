package com.example.aegisac.log.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一日志实体：包含 5 个基础必须字段与按需可选字段
 * 序列化/反序列化时严格遵循不输出 null 原则
 */
public class LogEntry {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    // ==================== 统一必须基础字段 ====================
    private final int v;                  // 格式版本号 (初始为 1)
    private final LogType type;           // 事件类型
    private final long time;              // 毫秒时间戳
    private final String server;          // 服务器节点标识
    private final UUID traceId;           // 链路关联 ID
    private String hash;                  // 链式哈希 (落盘时填入)

    // ==================== 玩家相关 (可选) ====================
    private String player;
    private UUID uuid;
    private String ip;
    private String brand;
    private Integer ping;

    // ==================== 违规相关 (可选) ====================
    private String check;
    private Integer vl;
    private LogSeverity severity;
    private String reason;
    private Map<String, Object> metrics;

    // ==================== 回退相关 (可选) ====================
    private String mode;                  // HARD / SOFT / LOG_ONLY
    private CoordPoint from;
    private CoordPoint to;
    private Boolean fallbackUsed;

    // ==================== 管理员相关 (可选) ====================
    private String actor;
    private UUID actorUuid;
    private String action;
    private String target;
    private String args;
    private String result;                // SUCCESS / FAILED / DENIED

    // ==================== 会话相关 (可选) ====================
    private String sessionAction;         // JOIN / QUIT / WORLD_CHANGE
    private Long duration;                // 在线时长 (ms)
    private String extra;                 // 附加上下文

    // ==================== 完整性检查点相关 (可选) ====================
    private String latestHash;
    private Long lineCount;

    private LogEntry(Builder builder) {
        this.v = builder.v;
        this.type = builder.type;
        this.time = builder.time;
        this.server = builder.server;
        this.traceId = builder.traceId != null ? builder.traceId : UUID.randomUUID();
        this.hash = builder.hash;

        this.player = builder.player;
        this.uuid = builder.uuid;
        this.ip = builder.ip;
        this.brand = builder.brand;
        this.ping = builder.ping;

        this.check = builder.check;
        this.vl = builder.vl;
        this.severity = builder.severity;
        this.reason = builder.reason;
        this.metrics = builder.metrics != null ? Collections.unmodifiableMap(builder.metrics) : null;

        this.mode = builder.mode;
        this.from = builder.from;
        this.to = builder.to;
        this.fallbackUsed = builder.fallbackUsed;

        this.actor = builder.actor;
        this.actorUuid = builder.actorUuid;
        this.action = builder.action;
        this.target = builder.target;
        this.args = builder.args;
        this.result = builder.result;

        this.sessionAction = builder.sessionAction;
        this.duration = builder.duration;
        this.extra = builder.extra;

        this.latestHash = builder.latestHash;
        this.lineCount = builder.lineCount;
    }

    /**
     * 构建用于计算哈希的规范化载荷字符串 (除 hash 字段以外的规范化 JSON)
     */
    public String buildSignablePayload() {
        JsonObject obj = serializeToJsonObject();
        obj.remove("hash");
        return GSON.toJson(obj);
    }

    public String toJson() {
        return GSON.toJson(serializeToJsonObject());
    }

    public JsonObject serializeToJsonObject() {
        JsonObject root = new JsonObject();
        root.addProperty("v", v);
        root.addProperty("type", type.name());
        root.addProperty("time", time);
        root.addProperty("server", server);
        root.addProperty("traceId", traceId.toString());
        if (hash != null) root.addProperty("hash", hash);

        if (player != null) root.addProperty("player", player);
        if (uuid != null) root.addProperty("uuid", uuid.toString());
        if (ip != null) root.addProperty("ip", ip);
        if (brand != null) root.addProperty("brand", brand);
        if (ping != null) root.addProperty("ping", ping);

        if (check != null) root.addProperty("check", check);
        if (vl != null) root.addProperty("vl", vl);
        if (severity != null) root.addProperty("severity", severity.name());
        if (reason != null) root.addProperty("reason", reason);
        if (metrics != null && !metrics.isEmpty()) {
            root.add("metrics", GSON.toJsonTree(metrics));
        }

        if (mode != null) root.addProperty("mode", mode);
        if (from != null) root.add("from", from.toJson());
        if (to != null) root.add("to", to.toJson());
        if (fallbackUsed != null) root.addProperty("fallbackUsed", fallbackUsed);

        if (actor != null) root.addProperty("actor", actor);
        if (actorUuid != null) root.addProperty("actorUuid", actorUuid.toString());
        if (action != null) root.addProperty("action", action);
        if (target != null) root.addProperty("target", target);
        if (args != null) root.addProperty("args", args);
        if (result != null) root.addProperty("result", result);

        if (sessionAction != null) root.addProperty("sessionAction", sessionAction);
        if (duration != null) root.addProperty("duration", duration);
        if (extra != null) root.addProperty("extra", extra);

        if (latestHash != null) root.addProperty("latestHash", latestHash);
        if (lineCount != null) root.addProperty("lineCount", lineCount);

        return root;
    }

    public static LogEntry fromJson(String jsonString) {
        JsonObject obj = JsonParser.parseString(jsonString).getAsJsonObject();
        Builder b = new Builder(
                LogType.valueOf(obj.get("type").getAsString()),
                obj.get("server").getAsString()
        );

        if (obj.has("v")) b.version(obj.get("v").getAsInt());
        if (obj.has("time")) b.time(obj.get("time").getAsLong());
        if (obj.has("traceId")) b.traceId(UUID.fromString(obj.get("traceId").getAsString()));
        if (obj.has("hash")) b.hash(obj.get("hash").getAsString());

        if (obj.has("player")) b.player(obj.get("player").getAsString());
        if (obj.has("uuid")) b.uuid(UUID.fromString(obj.get("uuid").getAsString()));
        if (obj.has("ip")) b.ip(obj.get("ip").getAsString());
        if (obj.has("brand")) b.brand(obj.get("brand").getAsString());
        if (obj.has("ping")) b.ping(obj.get("ping").getAsInt());

        if (obj.has("check")) b.check(obj.get("check").getAsString());
        if (obj.has("vl")) b.vl(obj.get("vl").getAsInt());
        if (obj.has("severity")) b.severity(LogSeverity.valueOf(obj.get("severity").getAsString()));
        if (obj.has("reason")) b.reason(obj.get("reason").getAsString());
        if (obj.has("metrics")) {
            JsonObject m = obj.getAsJsonObject("metrics");
            for (Map.Entry<String, JsonElement> e : m.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    if (e.getValue().getAsJsonPrimitive().isNumber()) {
                        b.metric(e.getKey(), e.getValue().getAsDouble());
                    } else if (e.getValue().getAsJsonPrimitive().isBoolean()) {
                        b.metric(e.getKey(), e.getValue().getAsBoolean());
                    } else {
                        b.metric(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        }

        if (obj.has("mode")) b.mode(obj.get("mode").getAsString());
        if (obj.has("from")) b.from(CoordPoint.fromJson(obj.getAsJsonObject("from")));
        if (obj.has("to")) b.to(CoordPoint.fromJson(obj.getAsJsonObject("to")));
        if (obj.has("fallbackUsed")) b.fallbackUsed(obj.get("fallbackUsed").getAsBoolean());

        if (obj.has("actor")) b.actor(obj.get("actor").getAsString());
        if (obj.has("actorUuid")) b.actorUuid(UUID.fromString(obj.get("actorUuid").getAsString()));
        if (obj.has("action")) b.action(obj.get("action").getAsString());
        if (obj.has("target")) b.target(obj.get("target").getAsString());
        if (obj.has("args")) b.args(obj.get("args").getAsString());
        if (obj.has("result")) b.result(obj.get("result").getAsString());

        if (obj.has("sessionAction")) b.sessionAction(obj.get("sessionAction").getAsString());
        if (obj.has("duration")) b.duration(obj.get("duration").getAsLong());
        if (obj.has("extra")) b.extra(obj.get("extra").getAsString());

        if (obj.has("latestHash")) b.latestHash(obj.get("latestHash").getAsString());
        if (obj.has("lineCount")) b.lineCount(obj.get("lineCount").getAsLong());

        return b.build();
    }

    // Getters & Setters
    public int getV() { return v; }
    public LogType getType() { return type; }
    public long getTime() { return time; }
    public String getServer() { return server; }
    public UUID getTraceId() { return traceId; }
    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public String getPlayer() { return player; }
    public UUID getUuid() { return uuid; }
    public String getIp() { return ip; }
    public String getBrand() { return brand; }
    public Integer getPing() { return ping; }

    public String getCheck() { return check; }
    public Integer getVl() { return vl; }
    public LogSeverity getSeverity() { return severity; }
    public String getReason() { return reason; }
    public Map<String, Object> getMetrics() { return metrics; }

    public String getMode() { return mode; }
    public CoordPoint getFrom() { return from; }
    public CoordPoint getTo() { return to; }
    public Boolean isFallbackUsed() { return fallbackUsed; }

    public String getActor() { return actor; }
    public UUID getActorUuid() { return actorUuid; }
    public String getAction() { return action; }
    public String getTarget() { return target; }
    public String getArgs() { return args; }
    public String getResult() { return result; }

    public String getSessionAction() { return sessionAction; }
    public Long getDuration() { return duration; }
    public String getExtra() { return extra; }

    public String getLatestHash() { return latestHash; }
    public Long getLineCount() { return lineCount; }

    public static class Builder {
        private int v = 1;
        private final LogType type;
        private long time = System.currentTimeMillis();
        private final String server;
        private UUID traceId = UUID.randomUUID();
        private String hash;

        private String player;
        private UUID uuid;
        private String ip;
        private String brand;
        private Integer ping;

        private String check;
        private Integer vl;
        private LogSeverity severity;
        private String reason;
        private Map<String, Object> metrics;

        private String mode;
        private CoordPoint from;
        private CoordPoint to;
        private Boolean fallbackUsed;

        private String actor;
        private UUID actorUuid;
        private String action;
        private String target;
        private String args;
        private String result;

        private String sessionAction;
        private Long duration;
        private String extra;

        private String latestHash;
        private Long lineCount;

        public Builder(LogType type, String server) {
            this.type = type;
            this.server = server;
        }

        public Builder version(int v) { this.v = v; return this; }
        public Builder time(long time) { this.time = time; return this; }
        public Builder traceId(UUID traceId) { this.traceId = traceId; return this; }
        public Builder hash(String hash) { this.hash = hash; return this; }

        public Builder player(String player) { this.player = player; return this; }
        public Builder uuid(UUID uuid) { this.uuid = uuid; return this; }
        public Builder ip(String ip) { this.ip = ip; return this; }
        public Builder brand(String brand) { this.brand = brand; return this; }
        public Builder ping(Integer ping) { this.ping = ping; return this; }

        public Builder check(String check) { this.check = check; return this; }
        public Builder vl(Integer vl) { this.vl = vl; return this; }
        public Builder severity(LogSeverity severity) { this.severity = severity; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder metric(String key, Object val) {
            if (this.metrics == null) this.metrics = new LinkedHashMap<>();
            this.metrics.put(key, val);
            return this;
        }

        public Builder mode(String mode) { this.mode = mode; return this; }
        public Builder from(CoordPoint from) { this.from = from; return this; }
        public Builder to(CoordPoint to) { this.to = to; return this; }
        public Builder fallbackUsed(Boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; return this; }

        public Builder actor(String actor) { this.actor = actor; return this; }
        public Builder actorUuid(UUID actorUuid) { this.actorUuid = actorUuid; return this; }
        public Builder action(String action) { this.action = action; return this; }
        public Builder target(String target) { this.target = target; return this; }
        public Builder args(String args) { this.args = args; return this; }
        public Builder result(String result) { this.result = result; return this; }

        public Builder sessionAction(String sessionAction) { this.sessionAction = sessionAction; return this; }
        public Builder duration(Long duration) { this.duration = duration; return this; }
        public Builder extra(String extra) { this.extra = extra; return this; }

        public Builder latestHash(String latestHash) { this.latestHash = latestHash; return this; }
        public Builder lineCount(Long lineCount) { this.lineCount = lineCount; return this; }

        public LogEntry build() {
            return new LogEntry(this);
        }
    }
}

package com.example.gateway.service;

import com.example.gateway.web.dto.CreateContainerRequest;
import com.example.gateway.web.dto.MountSpec;
import com.example.gateway.web.dto.PortSpec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建请求摘要：用于创建幂等（{@code Idempotency-Key}）的「同键必须同参」校验。
 *
 * <p>对<b>应用默认后的 effective 值</b>做规范化序列化再取 SHA-256 前 32 位 hex。同一键重放若参数
 * 漂移（镜像/命令/端口/资源等不同），摘要不同，调用方应拒绝而不是返回旧容器。
 *
 * <p>规范化规则：
 * <ul>
 *   <li>顺序无关字段（env / ports / mounts / meta）先排序再序列化；cmd 保序（命令顺序有语义）。</li>
 *   <li>每段以「长度前缀 + 分隔符」编码，避免分隔符歧义。</li>
 *   <li>{@code name} 用<b>原始入参</b>（空/空白归一为固定哨兵）——绝不能纳入服务端生成的
 *       {@code gw-<client>-<uuid>} 名字，否则同参重放会被误判为漂移。</li>
 *   <li>标量用 effective 值传入（默认已应用），省略字段与显式给默认值等价。</li>
 * </ul>
 */
public final class CreateRequestDigest {

    /** 空 name 的固定哨兵（区别于任何合法名字）。 */
    private static final String NULL_NAME = "\u0000<null-name>";

    private CreateRequestDigest() {
    }

    /**
     * @param req          原始请求（读取 image/name/cmd/env/ports/mounts/meta）
     * @param memoryBytes  应用默认后的内存上限
     * @param cpus         应用默认后的 CPU 核数
     * @param pidsLimit    应用默认后的 pids 上限
     * @param ttlSeconds   应用默认后的 TTL 秒数
     */
    public static String compute(CreateContainerRequest req, long memoryBytes, double cpus,
                                 long pidsLimit, long ttlSeconds) {
        StringBuilder sb = new StringBuilder(256);
        append(sb, "image", req.image());
        append(sb, "name", (req.name() == null || req.name().isBlank()) ? NULL_NAME : req.name().trim());
        append(sb, "memoryBytes", Long.toString(memoryBytes));
        append(sb, "cpus", Double.toString(cpus));
        append(sb, "pidsLimit", Long.toString(pidsLimit));
        append(sb, "ttlSeconds", Long.toString(ttlSeconds));

        // cmd 保序
        List<String> cmd = req.cmd() == null ? List.of() : req.cmd();
        append(sb, "cmd.count", Integer.toString(cmd.size()));
        for (int i = 0; i < cmd.size(); i++) {
            append(sb, "cmd[" + i + "]", cmd.get(i));
        }

        // env 按键排序
        List<String> envPairs = new ArrayList<>();
        if (req.env() != null) {
            for (Map.Entry<String, String> e : req.env().entrySet()) {
                envPairs.add(nz(e.getKey()) + "=" + nz(e.getValue()));
            }
        }
        envPairs.sort(String::compareTo);
        appendList(sb, "env", envPairs);

        // ports 按 protocol:containerPort 排序
        List<String> ports = new ArrayList<>();
        for (PortSpec p : req.portsOrEmpty()) {
            ports.add(p.protocolOrDefault() + ":" + p.containerPort());
        }
        ports.sort(String::compareTo);
        appendList(sb, "ports", ports);

        // mounts 排序（无 mounts 时与空列表等价）
        List<String> mounts = new ArrayList<>();
        if (req.mounts() != null) {
            for (MountSpec m : req.mounts()) {
                mounts.add(nz(m.hostPath()) + ":" + nz(m.containerPath()) + ":" + m.readOnly());
            }
        }
        mounts.sort(String::compareTo);
        appendList(sb, "mounts", mounts);

        // meta 按键排序
        List<String> metas = new ArrayList<>();
        for (Map.Entry<String, String> e : req.metaOrEmpty().entrySet()) {
            metas.add(nz(e.getKey()) + "=" + nz(e.getValue()));
        }
        metas.sort(String::compareTo);
        appendList(sb, "meta", metas);

        return sha256Hex32(sb.toString());
    }

    private static void appendList(StringBuilder sb, String field, List<String> values) {
        append(sb, field + ".count", Integer.toString(values.size()));
        for (int i = 0; i < values.size(); i++) {
            append(sb, field + "[" + i + "]", values.get(i));
        }
    }

    /** 长度前缀 + 分段，避免分隔符歧义；null 用 -1 长度与空串区分。 */
    private static void append(StringBuilder sb, String key, String value) {
        String v = value;
        sb.append(key).append('=');
        if (v == null) {
            sb.append(-1);
        } else {
            sb.append(v.length());
        }
        sb.append(':').append(v == null ? "" : v).append(';');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String sha256Hex32(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}

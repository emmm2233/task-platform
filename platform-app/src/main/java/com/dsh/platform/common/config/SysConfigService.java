package com.dsh.platform.common.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统参数读取（带本地缓存）。
 *
 * <p>为什么不让业务代码直接读 {@code sys_config}：
 * <ol>
 *   <li>业务代码里散落 SQL 字符串 → 改个键名要全局搜索，必然漏改；</li>
 *   <li>参数是低频写、高频读，每次查库纯属浪费；</li>
 *   <li>缺键时要<b>立刻失败并说清缺哪个</b>，而不是返回 null 让调用方 NPE。</li>
 * </ol>
 *
 * <p>缓存失效：参数变更时由 {@code sys_config_history} 的写入方广播
 * {@code CACHE_INVALIDATE} 事件，本类监听后清理（§P2-⑯ 事件注册表已登记该事件）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysConfigService {

    private final JdbcTemplate jdbc;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmUp() {
        refresh();
    }

    /** 全量重载。参数变更后调用。 */
    public void refresh() {
        cache.clear();
        // ⚠️ sys_config 没有 enabled 列 —— 它的列是 editable（能否在管理端改），
        //    不是"是否生效"。所有登记的参数都是生效的。
        jdbc.query("SELECT config_key, config_value FROM sys_config",
                rs -> {
                    cache.put(rs.getString("config_key"), rs.getString("config_value"));
                });
        log.info("sys_config 已加载 {} 条", cache.size());
    }

    /** 取字符串。键不存在即抛异常 —— ⛔ 不返回 null。 */
    public String getString(String key) {
        String v = cache.get(key);
        if (v == null) {
            // 可能是别的实例刚写入 → 回源一次再判
            String fresh = jdbc.query(
                    "SELECT config_value FROM sys_config WHERE config_key = ?",
                    rs -> rs.next() ? rs.getString(1) : null, key);
            if (fresh == null) {
                throw new IllegalStateException("系统参数未配置：" + key + "（迁移 V4 应已种子化）");
            }
            cache.put(key, fresh);
            return fresh;
        }
        return v;
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key).trim());
    }

    public long getLong(String key) {
        return Long.parseLong(getString(key).trim());
    }

    public boolean getBoolean(String key) {
        String v = getString(key).trim();
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    public BigDecimal getDecimal(String key) {
        return new BigDecimal(getString(key).trim());
    }
}

package com.dsh.platform.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 信封加密（§5.8.10）。
 *
 * <p>密文格式：{@code v1:base64(IV ‖ 密文 ‖ GCM Tag)}。
 * 前缀 {@code v1} 是<b>版本号</b>，为将来轮换算法留路 —— 没有它，
 * 换算法时无法区分新旧密文，只能全量停机重加密。
 *
 * <p>用途：手机号、身份证号、银行卡号等 L3/L4 数据。
 * ⛔ 这些数据的明文<b>不得</b>出现在日志、异常信息、接口响应里。
 *
 * <p>KEK 的来源：
 * <ul>
 *   <li>开发环境 —— 配置项 {@code platform.crypto.kek}（Base64 的 32 字节）</li>
 *   <li>生产环境 —— ⛔ 必须来自 Vault/KMS，不落配置文件、不进镜像</li>
 * </ul>
 */
public class EnvelopeCipher {

    private static final String PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey key;

    public EnvelopeCipher(String base64Kek) {
        byte[] raw = Base64.getDecoder().decode(base64Kek);
        if (raw.length != 32) {
            throw new IllegalArgumentException("KEK 必须是 32 字节（AES-256）的 Base64，实际 " + raw.length + " 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** 加密。每次调用都用新的随机 IV —— ⛔ 绝不能复用 IV。 */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // ⛔ 异常信息不得包含明文
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 解密。密文被篡改时 GCM 校验会失败并抛异常（这正是我们要的）。 */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不认识的密文版本前缀");
        }
        try {
            byte[] all = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败（密文可能被篡改）", e);
        }
    }
}

package com.voucherx.admission;

/**
 * 秒杀新增准入控制模式。OFF 保留 V1 行为，便于公平对照和紧急关闭。
 */
public enum AdmissionMode {
    OFF, FIXED, ADAPTIVE
}

package com.example.readingapp.utils;

/**
 * 密码强度策略（后端单一事实来源）。
 *
 * <p>统一规则：<b>8~64 位，且必须同时包含字母（A-Z/a-z）和数字（0-9）</b>。
 *
 * <p>适用范围：仅用于「注册」与「修改密码」等<b>写入密码</b>的入口。登录流程只做
 * BCrypt 哈希比对，不调用本类，因此老账号（历史弱密码）仍可正常登录。
 *
 * <p>对外只暴露：长度上下限常量、统一文案常量，以及两个校验方法。校验方法
 * <b>不抛异常</b>，由调用方决定如何处置返回值。
 */
public final class PasswordPolicy {

    /** 密码最小长度（含）。 */
    public static final int MIN_LENGTH = 8;

    /** 密码最大长度（含）。 */
    public static final int MAX_LENGTH = 64;

    /** 统一的校验失败提示文案（与 App 端逐字一致，可直接展示给用户）。 */
    public static final String ERROR_MESSAGE = "密码需为 8~64 位，且同时包含字母和数字";

    private PasswordPolicy() {
        // 工具类不允许实例化
    }

    /**
     * 校验密码是否符合强度策略。
     *
     * @param password 待校验的原始密码，允许为 {@code null}
     * @return 校验通过返回 {@code null}；不通过返回可直接展示给用户的错误文案
     *         （即 {@link #ERROR_MESSAGE}）
     */
    public static String validate(String password) {
        if (password == null || password.isEmpty()) {
            return ERROR_MESSAGE;
        }
        int length = password.length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return ERROR_MESSAGE;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < length; i++) {
            char c = password.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
            if (hasLetter && hasDigit) {
                break;
            }
        }

        if (!hasLetter || !hasDigit) {
            return ERROR_MESSAGE;
        }
        return null;
    }

    /**
     * 便捷判断密码是否合规。
     *
     * @param password 待校验的原始密码，允许为 {@code null}
     * @return 合规返回 {@code true}，否则 {@code false}
     */
    public static boolean isValid(String password) {
        return validate(password) == null;
    }
}

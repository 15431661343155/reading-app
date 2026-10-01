package com.example.readingapp.util;

import java.io.File;
import java.io.IOException;

/**
 * 路径穿越防护：文件名来自 URL（可能含 ../、..%2F 等变体）时，
 * 拼接前必须经 {@link #safeResolve} 校验「解析后的真实路径仍落在基目录内」。
 *
 * <p>注意不能只做字符串 contains("../") 检查——URL 编码（%2E%2E%2F）、
 * 双重编码、反斜杠等变体都能绕过；canonical path 比较是唯一可靠做法。
 */
public final class PathSafety {

    private PathSafety() {}

    /**
     * 把用户可控的 fileName 解析到 baseDir 内，返回 canonical File。
     * 越界（解析后不在 baseDir 内）抛 IllegalArgumentException。
     */
    public static File safeResolve(String baseDir, String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        try {
            File base = new File(baseDir).getCanonicalFile();
            File target = new File(base, fileName).getCanonicalFile();
            // 必须是 base 的严格子路径：等于 base 本身（fileName 为 "." 等）也不放行——
            // 否则管理端删除接口会删掉整个基目录
            if (!target.getPath().startsWith(base.getPath() + File.separator)) {
                throw new IllegalArgumentException("非法文件路径");
            }
            return target;
        } catch (IOException e) {
            throw new IllegalArgumentException("文件路径解析失败");
        }
    }
}

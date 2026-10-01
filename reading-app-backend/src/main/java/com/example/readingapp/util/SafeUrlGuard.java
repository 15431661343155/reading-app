package com.example.readingapp.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;

/**
 * SSRF 防护：服务端对外发起 HTTP 请求前校验目标 URL。
 *
 * <p>背景：书城的书源功能（/api/admin/online-source/**、/api/admin/book-source/import-url）
 * 匿名开放（App 端依赖），服务端会按外部 URL 发起请求。若不校验，攻击者可让服务器：
 * <ul>
 *   <li>访问内网服务（127.0.0.1、10.x、192.168.x、172.16-31.x、169.254.x 元数据等）</li>
 *   <li>使用非 http/https 协议（file:、gopher: 等）</li>
 * </ul>
 *
 * <p>用法：在真正 openConnection / exchange 之前调用 {@link #check(String)}，
 * 校验不通过抛 {@link IllegalArgumentException}（message 可直接展示给用户）。
 *
 * <p>已知局限（接受残余风险，注释留档）：
 * <ul>
 *   <li><b>DNS Rebinding</b>：此处先解析校验、后续请求会再次解析，两次结果可能不同。
 *       完整修复需「校验后直接连接解析出的 IP」，对 RestTemplate/URLConnection 改造过大，
 *       本项目书源 URL 均由管理员导入、匿名用户只能触发搜索/浏览，风险可接受。</li>
 *   <li><b>302 跳转</b>：HttpURLConnection 会自动跟随重定向，公网 URL 可能 302 到内网。
 *       同上，接受残余风险。</li>
 * </ul>
 */
public final class SafeUrlGuard {

    private SafeUrlGuard() {}

    /** 校验 URL，不通过时抛 IllegalArgumentException */
    public static void check(String urlStr) {
        if (urlStr == null || urlStr.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        URL url;
        try {
            url = new URL(urlStr.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 格式错误");
        }

        // 1) 仅允许 http/https（杜绝 file:/jar:/ftp:/gopher: 等）
        String protocol = url.getProtocol();
        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("仅允许 http/https 协议");
        }

        // 2) 主机名必须存在
        String host = url.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }

        // 3) 解析全部 IP，逐个拒绝内网/保留地址（一个都不放行）
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IllegalArgumentException("主机名无法解析: " + host);
        }
        if (addrs == null || addrs.length == 0) {
            throw new IllegalArgumentException("主机名无法解析: " + host);
        }
        for (InetAddress addr : addrs) {
            if (isForbidden(addr)) {
                throw new IllegalArgumentException("禁止访问内网/保留地址");
            }
        }
    }

    private static boolean isForbidden(InetAddress addr) {
        // JDK 内建判断：回环(127.x/::1)、任意本地(0.0.0.0/::)、链路本地(169.254/fe80)、
        // 站点本地(10/172.16-31/192.168 与 IPv6 fc00::/7)、组播
        if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
            return true;
        }
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            int o1 = b[0] & 0xFF, o2 = b[1] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT（含云元数据类网段常见落点）
            if (o1 == 100 && o2 >= 64 && o2 <= 127) return true;
            // 0.0.0.0/8（isAnyLocalAddress 只命中 0.0.0.0 单个地址）
            if (o1 == 0) return true;
            // 192.0.0.0/24 与 198.18.0.0/15（保留/基准测试）
            if (o1 == 192 && o2 == 0 && (b[2] & 0xFF) == 0) return true;
            if (o1 == 198 && (o2 == 18 || o2 == 19)) return true;
        }
        return false;
    }
}

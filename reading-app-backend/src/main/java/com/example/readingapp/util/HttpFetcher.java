package com.example.readingapp.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;

@Slf4j
public class HttpFetcher {

    private int timeout = 15000;
    private String defaultCharset = "UTF-8";
    private boolean followRedirects = true;
    private String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 全局宽松 SSL 初始化（静态执行一次，对所有通过 JDK HttpsURLConnection 发起的请求生效）：
     *  - 信任所有服务器证书（包括自签、过期、域名/IP 不匹配）
     *  - 跳过主机名校验（Legado 书源大量使用裸 IP 如 https://38.34.172.127，证书 SAN 里没 IP 会直接被 Java 拒绝）
     *  这样 Wikisource / 七猫 / 夜伴书屋 / 其他任意 Legado 自定义书源都不会因握手失败而空结果。
     */
    static {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            log.info("HttpFetcher: 已启用全局宽松 SSL（兼容 Legado 书源常见的裸 IP/自签证书/过期证书/SAN 不匹配）");
        } catch (Exception e) {
            log.warn("HttpFetcher: 设置宽松 SSL 失败，部分国内书源可能遭遇证书/SAN 错误: {}", e.getMessage());
        }
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public void setDefaultCharset(String charset) {
        this.defaultCharset = charset;
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public static class RequestConfig {
        public String url;
        public String method = "GET";
        public String body;
        public Map<String, String> headers;
        public String charset;
        public Integer timeoutMs;
        public Boolean followRedirects;

        public RequestConfig(String url) {
            this.url = url;
        }

        public static RequestConfig get(String url) {
            return new RequestConfig(url);
        }
    }

    public String fetch(RequestConfig config) {
        try {
            // SSRF 防护：拒绝内网/保留地址与非 http(s) 协议（匿名书源接口可被触发，见 SafeUrlGuard）
            SafeUrlGuard.check(config.url);

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(config.timeoutMs != null ? config.timeoutMs : timeout);
            factory.setReadTimeout(config.timeoutMs != null ? config.timeoutMs : timeout);

            RestTemplate rt = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();

            headers.set("User-Agent", userAgent);
            headers.set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7");
            headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

            if (config.headers != null) {
                for (Map.Entry<String, String> e : config.headers.entrySet()) {
                    if ("User-Agent".equalsIgnoreCase(e.getKey()) || "user-agent".equalsIgnoreCase(e.getKey())) {
                        headers.set("User-Agent", e.getValue());
                    } else {
                        headers.set(e.getKey(), e.getValue());
                    }
                }
            }

            HttpMethod httpMethod = HttpMethod.GET;
            if ("POST".equalsIgnoreCase(config.method)) httpMethod = HttpMethod.POST;
            else if ("PUT".equalsIgnoreCase(config.method)) httpMethod = HttpMethod.PUT;
            else if ("DELETE".equalsIgnoreCase(config.method)) httpMethod = HttpMethod.DELETE;

            String body = config.body;
            if (body != null && !body.isEmpty() && httpMethod == HttpMethod.POST) {
                if (headers.getContentType() == null) {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                }
            }

            // —— 适配 application/x-www-form-urlencoded：把 raw k=v&k2=v2 字符串拆成 MultiValueMap
            // FormHttpMessageConverter 只接受 MultiValueMap，传 String + form-urlencoded contentType
            // 会被 StringHttpMessageConverter (不匹配) / FormHttpMessageConverter (不接受String类型)
            // 组合拒绝 → 整个 ajax 失败，导致七猫游客 token 拿不到 → 44010102
            MediaType ct = headers.getContentType();
            boolean isForm = ct != null && "application/x-www-form-urlencoded".equalsIgnoreCase(ct.getType() + "/" + ct.getSubtype());
            if (isForm && body != null && !body.isEmpty()) {
                MultiValueMap<String, String> formMap = new LinkedMultiValueMap<>();
                try {
                    for (String pair : body.split("&")) {
                        if (pair.isEmpty()) continue;
                        int eq = pair.indexOf('=');
                        if (eq < 0) {
                            formMap.add(java.net.URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                        } else {
                            String k = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                            String v = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                            formMap.add(k, v);
                        }
                    }
                    HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formMap, headers);
                    ResponseEntity<byte[]> response = rt.exchange(toURI(config.url), httpMethod, entity, byte[].class);
                    byte[] bodyBytes = response.getBody();
                    if (bodyBytes == null) return null;
                    bodyBytes = decompressGzipIfNeeded(bodyBytes);
                    String charset = config.charset != null ? config.charset : defaultCharset;
                    if (response.getHeaders().getContentType() != null) {
                        Charset cs = response.getHeaders().getContentType().getCharset();
                        if (cs != null) charset = cs.name();
                    }
                    return new String(bodyBytes, Charset.forName(charset));
                } catch (Exception splitFailed) {
                    // fallback to String entity below (e.g. weird encoding)
                    log.warn("解析 form-urlencoded body 失败，回退 String: {}", splitFailed.getMessage());
                }
            }

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<byte[]> response = rt.exchange(toURI(config.url), httpMethod, entity, byte[].class);

            byte[] bodyBytes = response.getBody();
            if (bodyBytes == null) return null;
            bodyBytes = decompressGzipIfNeeded(bodyBytes);

            String charset = config.charset != null ? config.charset : defaultCharset;
            if (response.getHeaders().getContentType() != null) {
                Charset cs = response.getHeaders().getContentType().getCharset();
                if (cs != null) charset = cs.name();
            }

            return new String(bodyBytes, Charset.forName(charset));
        } catch (Exception e) {
            // 打印完整堆栈，七猫 form 提交/SSL 证书/连接超时等问题都能快速定位
            log.warn("HTTP 请求失败: {} - {}", config.url, e.toString(), e);
            return null;
        }
    }

    public String fetch(String url) {
        return fetch(RequestConfig.get(url));
    }

    public String fetch(String url, Map<String, String> headers) {
        RequestConfig cfg = RequestConfig.get(url);
        cfg.headers = headers;
        return fetch(cfg);
    }

    /**
     * 检测并解压 gzip 压缩的响应体。
     *
     * <p>部分 API（如猫眼看书）返回 gzip 压缩的响应，但可能未正确设置 Content-Encoding 头，
     * 导致 RestTemplate/HttpURLConnection 不会自动解压。此方法通过检测 gzip 魔数（0x1F 0x8B）
     * 来判断是否需要手动解压。
     */
    private byte[] decompressGzipIfNeeded(byte[] data) {
        if (data == null || data.length < 2) return data;
        // gzip 魔数：0x1F 0x8B
        if ((data[0] & 0xFF) != 0x1F || (data[1] & 0xFF) != 0x8B) return data;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            GZIPInputStream gis = new GZIPInputStream(bis);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = gis.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
            gis.close();
            bis.close();
            bos.close();
            byte[] decompressed = bos.toByteArray();
            log.debug("HTTP 响应 gzip 解压: {} bytes → {} bytes", data.length, decompressed.length);
            return decompressed;
        } catch (Exception e) {
            log.warn("gzip 解压失败，返回原始数据: {}", e.getMessage());
            return data;
        }
    }

    public static String encodeUrl(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * 将 String URL 转为 URI，避免 RestTemplate 对已编码 URL 二次编码。
     *
     * <p>RestTemplate.exchange(String url, ...) 默认使用 UriComponentsBuilder 重新编码 URL，
     * 导致已编码的 %E6 被二次编码为 %25E6，服务器收到与签名不匹配的关键词 → 44010102 参数错误。
     * 改用 URI 对象传入，RestTemplate 不再重新编码。
     *
     * <p>若 URL 不合法（含未编码的非法字符），回退为 String URL（由 RestTemplate 处理）。
     */
    private URI toURI(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            log.debug("URL 转为 URI 失败，回退为 String: {} - {}", url, e.getMessage());
            return null;
        }
    }
}

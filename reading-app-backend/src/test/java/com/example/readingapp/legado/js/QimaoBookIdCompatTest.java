package com.example.readingapp.legado.js;

import com.example.readingapp.legado.analyze.AnalyzeUrl;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证七猫 web rank API 的 book_id 是否兼容 app API（detail/toc）。
 * web rank: https://www.qimao.com/api/rank/book-list  (无需签名)
 * app detail: https://api-bc.wtzw.com/api/v1/reader/detail?id=<id>  (需签名)
 */
class QimaoBookIdCompatTest {

    @Test
    void testWebBookIdWithAppDetailApi() throws Exception {
        String jsLib = new String(Files.readAllBytes(Paths.get("d:/android/reading-app/qimao_jslib.js")), "UTF-8");
        String baseUrl = "https://api-bc.wtzw.com";
        LegadoJsEngine engine = new LegadoJsEngine(jsLib, baseUrl);

        // 用 app 签名构造 book_id=195958 (来自 web rank) 的详情 URL
        String jsStr = "qmBookDetailUrl.call(this, '195958')";
        Map<String, Object> bindings = new LinkedHashMap<>();
        Object urlResult = engine.eval(jsStr, null, bindings);
        System.out.println("=== app detail URL for book_id=195958 ===");
        System.out.println(urlResult);

        if (urlResult != null) {
            // 通过 AnalyzeUrl 执行请求
            AnalyzeUrl au = new AnalyzeUrl(urlResult.toString(), null, null, baseUrl, new LinkedHashMap<>(), false);
            au.setJsEngine(engine);
            System.out.println("url = " + au.getUrl());
            String resp = au.execute();
            System.out.println("=== app detail response ===");
            System.out.println(resp == null ? "<NULL>" : (resp.length() < 800 ? resp : resp.substring(0, 800) + "..."));
        }
    }
}

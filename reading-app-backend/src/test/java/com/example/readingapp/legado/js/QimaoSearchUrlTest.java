package com.example.readingapp.legado.js;

import com.example.readingapp.legado.analyze.AnalyzeUrl;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 复现七猫 searchUrl 解析：通过完整 AnalyzeUrl 流程
 * （analyzeJs → evalJS → jsEngine.eval → analyzeUrl）验证最终 URL。
 */
class QimaoSearchUrlTest {

    @Test
    void testQimaoSearchUrlViaAnalyzeUrl() throws Exception {
        // 1. 读取 jsLib（从 DB 导出的文件）
        String jsLibPath = "d:/android/reading-app/qimao_jslib.js";
        String jsLib = new String(Files.readAllBytes(Paths.get(jsLibPath)), "UTF-8");
        System.out.println("jsLib length = " + jsLib.length());

        // 2. 创建引擎
        String baseUrl = "https://api-bc.wtzw.com";
        LegadoJsEngine engine = new LegadoJsEngine(jsLib, baseUrl);

        // 3. 通过 AnalyzeUrl 走完整流程（与 LegadoBookSourceService.searchBooks 一致）
        String searchUrl = "@js:qmSearchUrl.call(this,key,page)";
        Map<String, String> headers = new LinkedHashMap<>();
        AnalyzeUrl analyzeUrl = new AnalyzeUrl(searchUrl, "斗破苍穹", 1, baseUrl, headers, false);
        analyzeUrl.setJsEngine(engine);

        System.out.println("===== AnalyzeUrl 结果 =====");
        System.out.println("getUrl      = " + analyzeUrl.getUrl());
        System.out.println("getUrlNoQuery = " + analyzeUrl.getUrlNoQuery());
        System.out.println("getEncodedQuery = " + analyzeUrl.getEncodedQuery());
        System.out.println("getMethod   = " + analyzeUrl.getMethod());
        System.out.println("headerMap   = " + analyzeUrl.headerMap);
        System.out.println("==========================");

        // 4. 直接 eval 对比
        System.out.println("--- 直接 eval 对比 ---");
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("key", "斗破苍穹");
        bindings.put("page", 1);
        Object result = engine.eval("qmSearchUrl.call(this,key,page)", null, bindings);
        System.out.println("直接 eval 结果 = " + result);
    }
}

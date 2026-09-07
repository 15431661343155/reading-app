package com.example.readingapp.legado.analyze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnalyzeRuleSmokeTest {

    @Test
    @DisplayName("CSS getString")
    void testCssGetString() {
        AnalyzeRule rule = new AnalyzeRule();
        rule.setContent("<html><body><div class='title'>Hello World</div><a href='/page1'>Link1</a><a href='/page2'>Link2</a></body></html>", "https://example.com/");
        String title = rule.getString("@CSS:.title@text");
        assertEquals("Hello World", title, "CSS getString should return 'Hello World'");
    }

    @Test
    @DisplayName("CSS getStringList")
    void testCssGetStringList() {
        AnalyzeRule rule = new AnalyzeRule();
        rule.setContent("<html><body><div class='title'>Hello World</div><a href='/page1'>Link1</a><a href='/page2'>Link2</a></body></html>", "https://example.com/");
        List<String> links = rule.getStringList("@CSS:a@href", null, false);
        assertNotNull(links);
        assertEquals(2, links.size());
    }

    @Test
    @DisplayName("CSS getElements")
    void testCssGetElements() {
        AnalyzeRule rule = new AnalyzeRule();
        rule.setContent("<html><body><div class='title'>Hello World</div><a href='/page1'>Link1</a><a href='/page2'>Link2</a></body></html>", "https://example.com/");
        List<Object> elements = rule.getElements("@CSS:a");
        assertNotNull(elements);
        assertEquals(2, elements.size());
    }

    @Test
    @DisplayName("URL resolution")
    void testUrlResolution() {
        AnalyzeRule rule = new AnalyzeRule();
        rule.setContent("<html><body><a href='/page1'>Link1</a></body></html>", "https://example.com/");
        String absUrl = rule.getString("@CSS:a@href", null, true);
        assertEquals("https://example.com/page1", absUrl);
    }

    @Test
    @DisplayName("JSON getString")
    void testJsonGetString() {
        AnalyzeRule jsonRule = new AnalyzeRule();
        jsonRule.setContent("{\"name\":\"Test Book\",\"author\":\"John\"}", "https://example.com/");
        String name = jsonRule.getString("$.name");
        assertEquals("Test Book", name);
    }

    @Test
    @DisplayName("Variable put/get")
    void testPutGet() {
        AnalyzeRule varRule = new AnalyzeRule();
        varRule.put("myKey", "myValue");
        assertEquals("myValue", varRule.get("myKey"));
    }

    @Test
    @DisplayName("splitSourceRule JS split")
    void testSplitSourceRule() {
        AnalyzeRule rule = new AnalyzeRule();
        rule.setContent("<html><body><div>test</div></body></html>", "https://example.com/");
        List<AnalyzeRule.SourceRule> splitRules = rule.splitSourceRule("@CSS:div@text@js:result");
        assertEquals(2, splitRules.size(), "Should split into CSS and JS segments");
        assertEquals(AnalyzeRule.Mode.Default, splitRules.get(0).mode);
        assertEquals(AnalyzeRule.Mode.Js, splitRules.get(1).mode);
    }

    @Test
    @DisplayName("evalJS returns null (stub)")
    void testEvalJsStub() {
        AnalyzeRule rule = new AnalyzeRule();
        assertNull(rule.evalJS("1+1", null));
    }

    @Test
    @DisplayName("## regex replace")
    void testRegexReplace() {
        AnalyzeRule regexRule = new AnalyzeRule();
        regexRule.setContent("<html><body><div>Phone: 123-456-7890</div></body></html>", "https://example.com/");
        String replaced = regexRule.getString("@CSS:div@text##[0-9-]+##");
        assertEquals("Phone: ", replaced);
    }
}
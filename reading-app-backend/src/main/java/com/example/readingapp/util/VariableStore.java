package com.example.readingapp.util;

import java.util.HashMap;
import java.util.Map;

public class VariableStore {

    private final Map<String, String> globalVars = new HashMap<>();
    private final Map<String, String> tmpVars = new HashMap<>();
    private final Map<String, String> baseVars = new HashMap<>();

    public VariableStore() {
    }

    public void put(String key, String value) {
        if (key != null) {
            globalVars.put(key, value != null ? value : "");
        }
    }

    public String get(String key) {
        if (key == null) return "";
        String v = globalVars.get(key);
        return v != null ? v : "";
    }

    public void putTmp(String key, String value) {
        if (key != null) {
            tmpVars.put(key, value != null ? value : "");
        }
    }

    public String getTmp(String key) {
        if (key == null) return "";
        String v = tmpVars.get(key);
        return v != null ? v : "";
    }

    public void putBase(String key, String value) {
        if (key != null) {
            baseVars.put(key, value != null ? value : "");
        }
    }

    public String getBase(String key) {
        if (key == null) return "";
        String v = baseVars.get(key);
        return v != null ? v : "";
    }

    public Map<String, String> getAll() {
        Map<String, String> all = new HashMap<>();
        all.putAll(baseVars);
        all.putAll(globalVars);
        all.putAll(tmpVars);
        return all;
    }

    public String replaceVars(String template) {
        if (template == null) return null;
        if (!template.contains("{{")) return template;
        String result = template;
        for (Map.Entry<String, String> e : tmpVars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        for (Map.Entry<String, String> e : globalVars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        for (Map.Entry<String, String> e : baseVars.entrySet()) {
            result = result.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return result;
    }

    public void clearTmp() {
        tmpVars.clear();
    }

    public void clearAll() {
        globalVars.clear();
        tmpVars.clear();
    }
}

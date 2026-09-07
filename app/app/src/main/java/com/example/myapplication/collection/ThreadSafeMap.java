package com.example.myapplication.collection;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 线程安全的Map包装器
 * 使用ConcurrentHashMap实现线程安全
 */
@SuppressWarnings({"unused", "SuspiciousMethodCalls"})
public class ThreadSafeMap<K, V> {

    private final Map<K, V> map;

    /**
     * 创建一个空的线程安全映射
     */
    public ThreadSafeMap() {
        this(new HashMap<>());
    }

    /**
     * 创建一个包含指定映射的线程安全映射
     */
    public ThreadSafeMap(Map<? extends K, ? extends V> map) {
        this.map = new ConcurrentHashMap<>(map);
    }

    /**
     * 存入键值对
     */
    public V put(K key, V value) {
        return map.put(key, value);
    }

    /**
     * 批量存入键值对
     */
    public void putAll(Map<? extends K, ? extends V> map) {
        this.map.putAll(map);
    }

    /**
     * 根据键获取值
     */
    public V get(Object key) {
        return map.get(key);
    }

    /**
     * 根据键移除元素
     */
    public V remove(Object key) {
        return map.remove(key);
    }

    /**
     * 检查是否包含指定键
     */
    public boolean containsKey(Object key) {
        return map.containsKey(key);
    }

    /**
     * 检查是否包含指定值
     */
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    /**
     * 清空映射
     */
    public void clear() {
        map.clear();
    }

    /**
     * 获取映射大小
     */
    public int size() {
        return map.size();
    }

    /**
     * 检查映射是否为空
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * 获取不可修改的映射视图
     */
    public Map<K, V> unmodifiableMap() {
        return Map.copyOf(map);
    }

    /**
     * 获取内部映射的只读引用（仅用于遍历）
     */
    public Map<K, V> getReadOnlyMap() {
        return Map.copyOf(map);
    }

    /**
     * 批量处理操作（由调用者同步）
     */
    public interface BulkOperation<K, V> {
        void execute(Map<K, V> map);
    }

    /**
     * 执行批量操作
     */
    public void executeBulkOperation(BulkOperation<K, V> operation) {
        operation.execute(map);
    }
}
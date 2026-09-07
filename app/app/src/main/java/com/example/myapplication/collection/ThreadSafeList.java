package com.example.myapplication.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 线程安全的List包装器
 * 使用CopyOnWriteArrayList实现线程安全
 */
@SuppressWarnings({"unused", "SuspiciousMethodCalls"})
public class ThreadSafeList<E> {

    private final List<E> list;

    /**
     * 创建一个空的线程安全列表
     */
    public ThreadSafeList() {
        this(new ArrayList<>());
    }

    /**
     * 创建一个包含指定集合元素的线程安全列表
     */
    public ThreadSafeList(Collection<? extends E> collection) {
        this.list = new CopyOnWriteArrayList<>(collection);
    }

    /**
     * 添加元素
     */
    public boolean add(E e) {
        return list.add(e);
    }

    /**
     * 批量添加元素
     */
    public boolean addAll(Collection<? extends E> collection) {
        return list.addAll(collection);
    }

    /**
     * 移除元素
     */
    public boolean remove(Object o) {
        return list.remove(o);
    }

    /**
     * 清空列表
     */
    public void clear() {
        list.clear();
    }

    /**
     * 获取指定索引的元素
     */
    public E get(int index) {
        return list.get(index);
    }

    /**
     * 设置指定索引的元素
     */
    public E set(int index, E element) {
        return list.set(index, element);
    }

    /**
     * 获取列表大小
     */
    public int size() {
        return list.size();
    }

    /**
     * 检查是否包含指定元素
     */
    public boolean contains(Object o) {
        return list.contains(o);
    }

    /**
     * 检查列表是否为空
     */
    public boolean isEmpty() {
        return list.isEmpty();
    }

    /**
     * 转换为不可修改的列表
     */
    public List<E> unmodifiableList() {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 获取内部列表的只读引用（仅用于遍历）
     */
    public List<E> getReadOnlyList() {
        return Collections.unmodifiableList(list);
    }

    /**
     * 批量处理操作（由调用者同步）
     */
    public interface BulkOperation<E> {
        void execute(List<E> list);
    }

    /**
     * 执行批量操作
     */
    public void executeBulkOperation(BulkOperation<E> operation) {
        operation.execute(list);
    }
}
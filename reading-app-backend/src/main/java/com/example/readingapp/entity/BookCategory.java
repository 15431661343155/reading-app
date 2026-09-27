package com.example.readingapp.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 书籍分类（主分类 / 子分类两级的唯一来源表）。
 *
 * <p>自引用结构：{@code parentId == null} 表示主分类（男生 / 女生…），
 * 否则表示挂在某个主分类下的子分类。两级结构刻意不做成任意层级：
 * 书城页的「子页面」与后台的级联选择框都只按两级渲染。
 *
 * <p>注意：MySQL 唯一索引对 NULL 不去重，所以 {@code uk_book_category} 不能真正拦住
 * 「两个同名主分类」，查重必须在 Java 侧做（见 AdminCategoryController）。
 */
@Data
@Entity
@Table(name = "book_category")
public class BookCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** 主分类 id；null = 本行就是主分类 */
    @Column(name = "parent_id")
    private Long parentId;

    /** 同级排序（升序）；种子数据按 10 递增，留出插入空间 */
    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

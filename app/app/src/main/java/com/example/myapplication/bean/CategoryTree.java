package com.example.myapplication.bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 书籍分类树（后端 GET /api/books/category-tree）。
 *
 * <p>书城「本站藏书」用它渲染顶部子页面：{@code 新书}（前端固定加的虚拟页，不在本结构里）
 * + {@code mains} 里的每个主分类；每个主分类的 {@code subs} 用作该页内的二级筛选。
 */
public class CategoryTree {

    private List<MainCategory> mains;
    /** 书库里出现过、但还没登记进分类表的子分类名（本端暂不使用，留作后续扩展） */
    private List<String> librarySubs;

    public List<MainCategory> getMains() {
        return mains == null ? new ArrayList<>() : mains;
    }

    public void setMains(List<MainCategory> mains) {
        this.mains = mains;
    }

    public List<String> getLibrarySubs() {
        return librarySubs == null ? new ArrayList<>() : librarySubs;
    }

    public void setLibrarySubs(List<String> librarySubs) {
        this.librarySubs = librarySubs;
    }

    public static class MainCategory {
        private Long id;
        private String name;
        private List<String> subs;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getName() { return name == null ? "" : name; }
        public void setName(String name) { this.name = name; }

        public List<String> getSubs() { return subs == null ? new ArrayList<>() : subs; }
        public void setSubs(List<String> subs) { this.subs = subs; }
    }
}

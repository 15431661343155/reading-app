package com.example.readingapp.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
public class AdminController {

    @GetMapping
    public String index() {
        return "admin/index";
    }

    @GetMapping("/books")
    public String books() {
        return "admin/books";
    }

    @GetMapping("/chapters")
    public String chapters() {
        return "admin/chapters";
    }

    @GetMapping("/users")
    public String users() {
        return "admin/users";
    }

    @GetMapping("/import")
    public String importPage() {
        return "admin/import";
    }

    @GetMapping("/users/reading")
    public String userReading() {
        return "admin/user-reading";
    }

    @GetMapping("/feedback")
    public String feedback() {
        return "admin/feedback";
    }

    @GetMapping("/resource")
    public String resource() {
        return "admin/resource";
    }

    @GetMapping("/online-source")
    public String onlineSource() {
        return "admin/online-source";
    }

    @GetMapping("/book-source")
    public String bookSource() {
        return "admin/book-source";
    }
}

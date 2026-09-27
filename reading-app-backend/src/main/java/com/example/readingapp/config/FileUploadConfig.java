package com.example.readingapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FileUploadConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /covers/** 映射到本地 uploads/covers 文件夹
        registry.addResourceHandler("/covers/**")
                .addResourceLocations("file:uploads/covers/");
        // 将 EPUB 解析出来的图片资源映射到本地 uploads/books 文件夹
        registry.addResourceHandler("/books/**")
                .addResourceLocations("file:uploads/books/");
        // 用户头像（App「个人信息」/ Web「个人中心」上传）映射到本地 uploads/avatars 文件夹
        registry.addResourceHandler("/avatars/**")
                .addResourceLocations("file:uploads/avatars/");
    }
}

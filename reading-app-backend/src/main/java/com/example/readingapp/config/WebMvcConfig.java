package com.example.readingapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${file.upload.font-path:uploads/fonts/}")
    private String fontPath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 字体文件静态资源映射
        registry.addResourceHandler("/fonts/**")
                .addResourceLocations("file:" + fontPath);
    }
}

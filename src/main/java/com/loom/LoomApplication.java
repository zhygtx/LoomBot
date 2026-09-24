package com.loom;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling//开启定时任务
@EnableAsync//开启异步任务
@ConfigurationPropertiesScan//开启属性扫描
public class LoomApplication {

    public static void main(String[] args) {

        SpringApplication.run(LoomApplication.class, args);

    }
}

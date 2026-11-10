package com.giaokhoa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GiaoKhoaApplication {

    public static void main(String[] args) {
        SpringApplication.run(GiaoKhoaApplication.class, args);
    }
}

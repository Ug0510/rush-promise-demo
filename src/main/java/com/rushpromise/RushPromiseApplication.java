package com.rushpromise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RushPromiseApplication {
    public static void main(String[] args) {
        SpringApplication.run(RushPromiseApplication.class, args);
    }
}

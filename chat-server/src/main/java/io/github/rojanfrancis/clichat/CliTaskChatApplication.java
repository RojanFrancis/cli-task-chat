package io.github.rojanfrancis.clichat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CliTaskChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(CliTaskChatApplication.class, args);
    }
}

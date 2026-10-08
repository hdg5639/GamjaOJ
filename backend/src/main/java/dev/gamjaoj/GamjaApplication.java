package dev.gamjaoj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class GamjaApplication {
  public static void main(String[] args) {
    SpringApplication.run(GamjaApplication.class, args);
  }
}

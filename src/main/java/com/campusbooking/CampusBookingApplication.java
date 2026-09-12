package com.campusbooking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CampusBookingApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusBookingApplication.class, args);
    }
}

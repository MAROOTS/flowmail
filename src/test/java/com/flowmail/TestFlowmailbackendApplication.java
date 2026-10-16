package com.flowmail;

import org.springframework.boot.SpringApplication;

public class TestFlowmailbackendApplication {

    public static void main(String[] args) {
        SpringApplication.from(FlowmailbackendApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}

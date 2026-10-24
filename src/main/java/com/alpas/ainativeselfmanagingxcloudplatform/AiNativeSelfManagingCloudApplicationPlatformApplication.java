package com.alpas.ainativeselfmanagingxcloudplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiNativeSelfManagingCloudApplicationPlatformApplication {

    public static void main(String[] args) {
        if (com.alpas.ainativeselfmanagingxcloudplatform.workbench.WorkerAgent.enabled()) {
            com.alpas.ainativeselfmanagingxcloudplatform.workbench.WorkerAgent.run();
            return;
        }
        SpringApplication.run(AiNativeSelfManagingCloudApplicationPlatformApplication.class, args);
    }
}

package com.alpas.ainativeselfmanagingxcloudplatform.workbench;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit bounds for one coordinator and its execution pool. Invalid settings fail startup. */
@Component
public record ExecutionCapacity(
        @Value("${platform.capacity.repair-workers:2}") int repairWorkers,
        @Value("${platform.capacity.repair-queue:16}") int repairQueue,
        @Value("${platform.capacity.remote-workers:4}") int remoteWorkers,
        @Value("${platform.capacity.remote-jobs:16}") int remoteJobs,
        @Value("${platform.capacity.job-history:200}") int jobHistory) {
    public ExecutionCapacity {
        bounded("repair workers", repairWorkers, 1, 16);
        bounded("repair queue", repairQueue, 1, 128);
        bounded("remote workers", remoteWorkers, 1, 32);
        bounded("remote jobs", remoteJobs, Math.max(repairWorkers, remoteWorkers), 128);
        bounded("job history", jobHistory, remoteJobs + repairWorkers, 1000);
    }

    private static void bounded(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be " + minimum + "–" + maximum);
    }

    public static ExecutionCapacity defaults() {
        return new ExecutionCapacity(2, 16, 4, 16, 200);
    }
}

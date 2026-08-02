package io.terrakube.api.plugin.scheduler.job.tcl.executor.persistent;

import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/executor-pool")
public class ExecutorPoolController {

    private final PersistentExecutorQueueService persistentExecutorQueueService;

    @GetMapping(produces = "application/json", value = "/status")
    public PersistentExecutorQueueService.ExecutorPoolStatus status() {
        return persistentExecutorQueueService.getPoolStatus();
    }
}

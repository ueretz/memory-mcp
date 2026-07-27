package ru.iuribabalin.memorymcp.service;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String projectScope, String taskKey) {
        super("No task '" + taskKey + "' in project '" + projectScope + "' - call task_start first");
    }
}

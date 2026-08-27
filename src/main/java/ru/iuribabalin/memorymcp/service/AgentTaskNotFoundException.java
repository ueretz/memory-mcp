package ru.iuribabalin.memorymcp.service;

public class AgentTaskNotFoundException extends RuntimeException {

    public AgentTaskNotFoundException(String projectScope, String taskKey, Long agentTaskId) {
        super("No agent task " + agentTaskId + " under task '" + taskKey + "' in project '" + projectScope + "'");
    }
}

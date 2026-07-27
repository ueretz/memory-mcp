package ru.iuribabalin.memorymcp.service;

public class MemoryNotFoundException extends RuntimeException {

    public MemoryNotFoundException(String name) {
        super("No memory entry named '" + name + "'");
    }
}

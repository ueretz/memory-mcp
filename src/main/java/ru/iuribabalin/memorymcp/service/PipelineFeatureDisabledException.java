package ru.iuribabalin.memorymcp.service;

public class PipelineFeatureDisabledException extends RuntimeException {
    public PipelineFeatureDisabledException() {
        super("Экспериментальная функция «Пайплайны» выключена. Включите её в Настройках дашборда.");
    }
}

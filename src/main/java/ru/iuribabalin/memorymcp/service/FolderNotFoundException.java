package ru.iuribabalin.memorymcp.service;

public class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(String name) {
        super("No folder named '" + name + "' - call folder_create first");
    }
}

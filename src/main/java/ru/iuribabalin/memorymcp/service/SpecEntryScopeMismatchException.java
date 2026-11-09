package ru.iuribabalin.memorymcp.service;

/** A spec entry was linked to a task it doesn't belong to - almost always a mistyped entry name. */
public class SpecEntryScopeMismatchException extends RuntimeException {

    public SpecEntryScopeMismatchException(String entryName, String projectScope, String taskKey) {
        super("Entry '" + entryName + "' is not scoped to " + projectScope + "/" + taskKey
                + " - save the spec with that projectScope and taskKey before linking it");
    }
}

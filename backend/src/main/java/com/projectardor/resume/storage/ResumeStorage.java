package com.projectardor.resume.storage;

public interface ResumeStorage {
    String save(byte[] content, String extension);
    byte[] read(String storageKey);
    void delete(String storageKey);
}

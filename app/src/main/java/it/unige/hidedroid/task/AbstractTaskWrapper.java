package it.unige.hidedroid.task;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import io.realm.RealmConfiguration;

public class AbstractTaskWrapper {
    public Map<String, AtomicInteger> selectedPrivacyLevels;
    public ReentrantLock selectedPrivacyLevelsLock;
    public File[] files = null;
    public AtomicBoolean isDebugEnabled;

    public AbstractTaskWrapper(Map<String, AtomicInteger> selectedPrivacyLevels, ReentrantLock selectedPrivacyLevelsLock, File[] files,
                               AtomicBoolean isDebugEnabled) {
        this.selectedPrivacyLevels = selectedPrivacyLevels;
        this.selectedPrivacyLevelsLock = selectedPrivacyLevelsLock;
        this.files = files;
        this.isDebugEnabled = isDebugEnabled;
    }
}

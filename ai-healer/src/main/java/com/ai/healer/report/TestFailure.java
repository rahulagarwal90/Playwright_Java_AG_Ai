package com.ai.healer.report;

import java.nio.file.Path;

/**
 * Plain data holder for one failed Surefire testcase — nothing else. No logic, no I/O.
 */
public class TestFailure {
    public String testName;
    public String className;
    public String failureType;
    public String failureMessage;
    public String stackTrace;
    public Path domSnapshotPath;
    public boolean domSnapshotFound;
}

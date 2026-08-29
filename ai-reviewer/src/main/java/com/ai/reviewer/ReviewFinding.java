package com.ai.reviewer;

/**
 * This class just holds the data for one review finding — nothing else. No logic, no I/O.
 * A "finding" is one category's result: PASSED or FAILED, and if it failed, which file and
 * line the problem is on, what the problem is, and how the model suggests fixing it.
 */
public class ReviewFinding {
    public String category;
    public String status;
    public String file;
    public int line;
    public String problem;
    public String suggestedFix;
}

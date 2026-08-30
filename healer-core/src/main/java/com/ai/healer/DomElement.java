package com.ai.healer;

/**
 * Plain data holder for one entry in a Hooks-captured DOM snapshot JSON array — nothing else. No
 * logic, no I/O. Field names mirror the JSON keys Hooks writes (see
 * captureDomSnapshot in playwright-tests' Hooks.java) so Gson can deserialize directly.
 */
public class DomElement {
    public String tag;
    public String id;
    public String testId;
    public String role;
    public String aria;
    public String text;
}

package com.novelforge.core.models;

/**
 * HookOp — hook operation from Reflector delta.
 * Types: UPSERT (create/update), MENTION (increment count), RESOLVE (mark done), DEFER (push later)
 */
public class HookOp {

    public enum Type { UPSERT, MENTION, RESOLVE, DEFER }

    private Type type;
    private String hookId;
    private String description;   // narrative promise description
    private int chapterOrigin;    // chapter where hook was created
    private int mentionCount;     // how many times hook has been mentioned
    private String priority;      // high / medium / low

    // --- Getters/Setters ---
    public Type getType() { return type; }
    public void setType(Type t) { this.type = t; }
    public String getHookId() { return hookId; }
    public void setHookId(String id) { this.hookId = id; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public int getChapterOrigin() { return chapterOrigin; }
    public void setChapterOrigin(int c) { this.chapterOrigin = c; }
    public int getMentionCount() { return mentionCount; }
    public void setMentionCount(int m) { this.mentionCount = m; }
    public String getPriority() { return priority; }
    public void setPriority(String p) { this.priority = p; }
}

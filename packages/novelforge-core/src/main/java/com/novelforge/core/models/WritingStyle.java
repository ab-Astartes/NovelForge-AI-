package com.novelforge.core.models;

/**
 * WritingStyle — captures vocabulary, sentence patterns, rhythm, and clichés.
 * Used for style cloning (analyze reference text → import style).
 */
public class WritingStyle {

    private String name;
    private String description;
    private String vocabularyPattern;   // preferred vocabulary and word frequency
    private String sentenceStructure;   // typical sentence length and structure patterns
    private String pacingPattern;       // rhythm of narrative flow
    private String dialogueStyle;       // dialogue patterns and tags
    private String descriptionStyle;    // description density and focus areas
    private String referenceSample;     // reference text for LLM to learn from

    // --- Getters/Setters ---
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getVocabularyPattern() { return vocabularyPattern; }
    public void setVocabularyPattern(String v) { this.vocabularyPattern = v; }
    public String getSentenceStructure() { return sentenceStructure; }
    public void setSentenceStructure(String s) { this.sentenceStructure = s; }
    public String getPacingPattern() { return pacingPattern; }
    public void setPacingPattern(String p) { this.pacingPattern = p; }
    public String getDialogueStyle() { return dialogueStyle; }
    public void setDialogueStyle(String d) { this.dialogueStyle = d; }
    public String getDescriptionStyle() { return descriptionStyle; }
    public void setDescriptionStyle(String d) { this.descriptionStyle = d; }
    public String getReferenceSample() { return referenceSample; }
    public void setReferenceSample(String r) { this.referenceSample = r; }
}

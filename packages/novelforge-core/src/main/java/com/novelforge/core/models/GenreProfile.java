package com.novelforge.core.models;

/**
 * GenreProfile — genre-specific rules, tropes, and templates.
 * Built-in profiles for Chinese web novel genres (xuanhuan, xianxia, urban, horror, romance)
 * and English genres (fantasy, thriller, romance, sci-fi, mystery).
 */
public class GenreProfile {

    private String key;        // e.g. "xuanhuan", "urban", "fantasy"
    private String label;      // display name
    private String language;   // "zh" or "en"
    private String outlineTemplate;  // genre-specific outline structure
    private String[] tropes;           // common genre tropes/clichés
    private String[] avoidanceList;    // patterns to avoid
    private String namingConvention;   // character/place naming style
    private String pacingRules;        // genre-specific pacing expectations
    private String upgradeSystem;      // power/level system template (if applicable)

    // --- Getters/Setters ---
    public String getKey() { return key; }
    public void setKey(String k) { this.key = k; }
    public String getLabel() { return label; }
    public void setLabel(String l) { this.label = l; }
    public String getLanguage() { return language; }
    public void setLanguage(String l) { this.language = l; }
    public String getOutlineTemplate() { return outlineTemplate; }
    public void setOutlineTemplate(String t) { this.outlineTemplate = t; }
    public String[] getTropes() { return tropes; }
    public void setTropes(String[] t) { this.tropes = t; }
    public String[] getAvoidanceList() { return avoidanceList; }
    public void setAvoidanceList(String[] a) { this.avoidanceList = a; }
    public String getNamingConvention() { return namingConvention; }
    public void setNamingConvention(String n) { this.namingConvention = n; }
    public String getPacingRules() { return pacingRules; }
    public void setPacingRules(String r) { this.pacingRules = r; }
    public String getUpgradeSystem() { return upgradeSystem; }
    public void setUpgradeSystem(String u) { this.upgradeSystem = u; }
}

package com.novelforge.core.models;

/**
 * 写作素材：参考文献或参照作品
 */
public class Reference {

    private String id;         // unique identifier
    private String title;      // 标题/书名
    private String author;     // 作者
    private String type;       // 类型: book/paper/web/article/film/game/other
    private String category;   // 素材类别: reference(参考文献) / inspiration(参照作品)
    private String summary;    // 简要说明/摘要
    private String notes;      // 作者笔记（如何参考/对标）
    private String url;        // 链接（如有）

    public Reference() { this.id = "ref-" + System.currentTimeMillis(); }

    // --- Getters/Setters ---
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    @Override
    public String toString() {
        return title + (author != null ? " · " + author : "") + " [" + type + "]";
    }
}

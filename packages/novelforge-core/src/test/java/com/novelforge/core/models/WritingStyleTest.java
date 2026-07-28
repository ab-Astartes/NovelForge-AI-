package com.novelforge.core.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WritingStyleTest {

    @Test
    void testDefaultCreation() {
        WritingStyle style = new WritingStyle();
        assertNull(style.getName());
        assertNull(style.getDescription());
        assertNull(style.getVocabularyPattern());
        assertNull(style.getSentenceStructure());
        assertNull(style.getPacingPattern());
        assertNull(style.getDialogueStyle());
        assertNull(style.getDescriptionStyle());
        assertNull(style.getReferenceSample());
    }

    @Test
    void testSetAndGetFields() {
        WritingStyle style = new WritingStyle();
        style.setName("古风雅韵");
        style.setDescription("仿古文风，含蓄隽永");
        style.setVocabularyPattern("文言词汇为主，偶尔使用口语");
        style.setSentenceStructure("短句为主，偶有长句铺陈");
        style.setPacingPattern("缓起急收，转折利落");
        style.setDialogueStyle("含蓄对白，少直接陈述");
        style.setDescriptionStyle("意象密集，注重环境渲染");
        style.setReferenceSample("月光如水，倾泻于院中。他默然伫立，似有所思。");

        assertEquals("古风雅韵", style.getName());
        assertEquals("仿古文风，含蓄隽永", style.getDescription());
        assertEquals("文言词汇为主，偶尔使用口语", style.getVocabularyPattern());
        assertEquals("短句为主，偶有长句铺陈", style.getSentenceStructure());
        assertEquals("缓起急收，转折利落", style.getPacingPattern());
        assertEquals("含蓄对白，少直接陈述", style.getDialogueStyle());
        assertEquals("意象密集，注重环境渲染", style.getDescriptionStyle());
        assertEquals("月光如水，倾泻于院中。他默然伫立，似有所思。", style.getReferenceSample());
    }

    @Test
    void testNullFieldsSafe() {
        WritingStyle style = new WritingStyle();
        assertNull(style.getName());
        assertNull(style.getDescription());
        // No crash on accessing null fields
        assertDoesNotThrow(() -> {
            String n = style.getName();
            if (n != null) n.length();
        });
    }

    @Test
    void testStyleNameEquality() {
        WritingStyle s1 = new WritingStyle();
        s1.setName("古风");

        WritingStyle s2 = new WritingStyle();
        s2.setName("古风");

        assertEquals(s1.getName(), s2.getName());
    }

    @Test
    void testModifyFields() {
        WritingStyle style = new WritingStyle();
        style.setName("武侠");
        style.setPacingPattern("快节奏");
        assertEquals("武侠", style.getName());

        style.setName("仙侠");
        assertEquals("仙侠", style.getName());
        assertEquals("快节奏", style.getPacingPattern());
    }
}

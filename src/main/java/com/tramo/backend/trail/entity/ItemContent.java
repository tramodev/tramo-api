package com.tramo.backend.trail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.Date;
import java.util.ArrayList;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Entity
@NoArgsConstructor
@Getter
@Setter
public class ItemContent {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    @Column(columnDefinition = "TEXT")
    private String content;
    private Date updatedDate;
    private Long wordCount = 0L;
    private Long characterCount = 0L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Zs}\\u2028\\u2029\\uFEFF]+");

    public void setContent(String content) {
        this.content = content;
        wordCount = 0L;
        characterCount = 0L;
        if (content == null || content.isBlank()) return;
        try {
            JsonNode root = MAPPER.readTree(content);
            var texts = new ArrayList<String>();
            collectText(root.path("root"), texts);
            characterCount = texts.stream().mapToLong(String::length).sum();
            wordCount = java.util.Arrays.stream(WHITESPACE.split(String.join(" ", texts)))
                    .filter(word -> !word.isEmpty()).count();
        } catch (Exception ignored) {
            wordCount = 0L;
            characterCount = 0L;
        }
    }

    private static void collectText(JsonNode node, ArrayList<String> texts) {
        JsonNode text = node.path("text");
        JsonNode equation = node.path("equation");
        if (text.isString()) texts.add(text.asString());
        else if (equation.isString()) texts.add(equation.asString());
        JsonNode children = node.path("children");
        if (children.isArray()) for (JsonNode child : children) collectText(child, texts);
    }
}

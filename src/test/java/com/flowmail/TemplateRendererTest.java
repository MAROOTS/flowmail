package com.flowmail;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowmail.campaigns.service.TemplateRenderer;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer();

    @Test
    void htmlRenderingEscapesValues() {
        String out = renderer.render("<p>Hi {{ firstName }}</p>", Map.of("firstName", "<b>Eve</b>"), true);
        assertThat(out).isEqualTo("<p>Hi &lt;b&gt;Eve&lt;/b&gt;</p>");
    }

    @Test
    void plainTextRenderingDoesNotEscape() {
        String out = renderer.render("Hi {{firstName}}", Map.of("firstName", "A&B"), false);
        assertThat(out).isEqualTo("Hi A&B");
    }

    @Test
    void missingValuesBecomeEmpty() {
        String out = renderer.render("Hi {{firstName}}!", Map.of(), true);
        assertThat(out).isEqualTo("Hi !");
    }

    @Test
    void subjectsCannotInjectHeaders() {
        String subject = renderer.renderSubject("Hello {{firstName}}",
                Map.of("firstName", "Eve\r\nBcc: victim@example.com"));
        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
    }

    @Test
    void findsDistinctVariables() {
        assertThat(renderer.variablesIn("{{a}} and {{ b }} and {{a}}")).containsExactly("a", "b");
    }
}
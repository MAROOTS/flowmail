package com.flowmail.campaigns.service;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class TemplateRenderer {

    public static final Set<String> ALLOWED_VARIABLES =
            Set.of("firstName", "lastName", "email", "unsubscribeUrl");

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*\\}\\}");

    public Set<String> variablesIn(String text) {
        Set<String> found = new TreeSet<>();
        if (text == null) {
            return found;
        }
        Matcher matcher = VARIABLE.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    public String render(String template, Map<String, String> values, boolean escapeHtml) {
        if (template == null) {
            return null;
        }
        return VARIABLE.matcher(template).replaceAll(match -> {
            String value = values.get(match.group(1));
            if (value == null) {
                value = "";
            }
            return Matcher.quoteReplacement(escapeHtml ? HtmlUtils.htmlEscape(value) : value);
        });
    }

    /** Subjects become email headers, so line breaks in values must never survive. */
    public String renderSubject(String template, Map<String, String> values) {
        String rendered = render(template, values, false);
        return rendered == null ? null : rendered.replaceAll("[\\r\\n]+", " ").trim();
    }
}
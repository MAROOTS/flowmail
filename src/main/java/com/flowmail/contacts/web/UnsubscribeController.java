package com.flowmail.contacts.web;

import com.flowmail.common.ApiException;
import com.flowmail.contacts.service.UnsubscribeService;
import com.flowmail.contacts.service.UnsubscribeTokenService;
import com.flowmail.contacts.service.UnsubscribeTokenService.TokenData;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/unsubscribe")
@RequiredArgsConstructor
public class UnsubscribeController {

    private static final String INVALID = "<p>This link is not valid.</p>";

    private final UnsubscribeTokenService tokens;
    private final UnsubscribeService unsubscribeService;

    /** Shows a confirmation page only. GET must not change state, because mail scanners prefetch links. */
    @GetMapping("/{token}")
    ResponseEntity<String> confirmPage(@PathVariable String token) {
        if (tokens.verify(token).isEmpty()) {
            return page(HttpStatus.NOT_FOUND, INVALID);
        }
        return page(HttpStatus.OK, """
                <p>Do you want to unsubscribe from these emails?</p>
                <form method="post" action=""><button type="submit">Unsubscribe</button></form>
                """);
    }

    /** Performs the unsubscribe. Also the target of one-click unsubscribe requests from mail clients. */
    @PostMapping("/{token}")
    ResponseEntity<String> confirm(@PathVariable String token) {
        Optional<TokenData> data = tokens.verify(token);
        if (data.isEmpty()) {
            return page(HttpStatus.NOT_FOUND, INVALID);
        }
        try {
            unsubscribeService.unsubscribe(data.get().tenantId(), data.get().contactId());
        } catch (ApiException e) {
            return page(HttpStatus.NOT_FOUND, INVALID);
        }
        return page(HttpStatus.OK, "<p>You have been unsubscribed.....</p>");
    }

    private static ResponseEntity<String> page(HttpStatus status, String body) {
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Unsubscribe</title></head><body>" + body + "</body></html>";
        return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(html);
    }
}
package org.example.karvachauth.serviceImple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.karvachauth.service.KarixService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class KarixServiceImple implements KarixService {

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${karix.api.url}")
    private String karixApiUrl;

    @Value("${karix.api.key}")
    private String karixApiKey;

    @Value("${karix.waba.number}")
    private String wabaNumber;

    /** "draft" while the Flow is not yet published (adds mode=draft); "published" after. */
    @Value("${karvachauth.store-flow.mode:published}")
    private String flowMode;

    // ==================================================================
    // PUBLIC API
    // ==================================================================

    @Override
    public String sendTextMessage(String toPhone, String text) {
        return postForMid(buildTextPayload(toPhone, text), toPhone, "TEXT");
    }

    @Override
    public String sendButtonMessage(String toPhone, String bodyText, List<String[]> buttons) {
        return postForMid(buildButtonPayload(toPhone, bodyText, buttons), toPhone, "BUTTON_MESSAGE");
    }

    @Override
    public String sendCarousel(String toPhone, String bodyText, List<CarouselCard> cards) {
        return postForMid(buildCarouselPayload(toPhone, bodyText, cards), toPhone, "CAROUSEL");
    }

    @Override
    public String sendListMessage(String toPhone, String bodyText, String buttonLabel, List<String[]> options) {
        return postForMid(buildListPayload(toPhone, bodyText, buttonLabel, options), toPhone, "LIST_MESSAGE");
    }

    @Override
    public String sendCtaUrlMessage(String toPhone, String bodyText, String displayText, String url) {
        return postForMid(buildCtaUrlPayload(toPhone, bodyText, displayText, url), toPhone, "CTA_URL");
    }

    @Override
    public String sendLocationRequest(String toPhone, String bodyText) {
        return postForMid(buildLocationRequestPayload(toPhone, bodyText), toPhone, "LOCATION_REQUEST");
    }

    @Override
    public String sendFlowMessage(String toPhone, String bodyText, String flowId, String flowToken,
                                  String ctaText, String screen, Map<String, Object> data) {
        return postForMid(buildFlowPayload(toPhone, bodyText, flowId, flowToken, ctaText, screen, data),
                toPhone, "FLOW");
    }

    // ==================================================================
    // PAYLOAD BUILDERS
    // ==================================================================

    private Map<String, Object> buildTextPayload(String toPhone, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("preview_url", false);
        content.put("text", text);
        content.put("type", "TEXT");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("content", content);
        message.put("recipient", buildRecipient(toPhone));
        message.put("sender", buildSender());

        return wrap(message);
    }

    private Map<String, Object> buildButtonPayload(String toPhone, String bodyText, List<String[]> buttons) {
        List<Map<String, Object>> buttonList = new ArrayList<>();

        // Karix/WhatsApp allows a max of 3 quick-reply buttons per message.
        for (String[] btn : buttons.stream().limit(3).toList()) {
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("id", btn[0]);
            reply.put("title", btn[1]);

            Map<String, Object> button = new LinkedHashMap<>();
            button.put("type", "reply");
            button.put("reply", reply);
            buttonList.add(button);
        }

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("buttons", buttonList);

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "button");
        interactive.put("body", Map.of("text", bodyText));
        interactive.put("action", action);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "INTERACTIVE");
        content.put("interactive", interactive);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("recipient", buildRecipient(toPhone));
        message.put("sender", buildSender());
        message.put("content", content);

        return wrap(message);
    }


    private Map<String, Object> buildListPayload(String toPhone, String bodyText, String buttonLabel, List<String[]> options) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] opt : options) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", opt[0]);
            row.put("title", opt[1]);                       // max 24 characters
            if (opt.length >= 3 && opt[2] != null && !opt[2].isBlank()) {
                row.put("description", opt[2]);             // max 72 characters
            }
            rows.add(row);
        }

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("title", "Options");
        section.put("rows", rows);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("button", buttonLabel);                  // max 20 characters
        action.put("sections", List.of(section));

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "list");
        interactive.put("body", Map.of("text", bodyText));
        interactive.put("action", action);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "INTERACTIVE");
        content.put("interactive", interactive);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("recipient", buildRecipient(toPhone));
        message.put("sender", buildSender());
        message.put("content", content);

        return wrap(message);
    }


    /**
     * WhatsApp Flow — same shape as Meta's interactive "flow" message, inside Karix's content.interactive
     * (like the list / CTA messages above). flow_action "navigate" opens `screen` with `data`.
     */
    private Map<String, Object> buildFlowPayload(String toPhone, String bodyText, String flowId, String flowToken,
                                                 String ctaText, String screen, Map<String, Object> data) {
        Map<String, Object> actionPayload = new LinkedHashMap<>();
        actionPayload.put("screen", screen);
        if (data != null && !data.isEmpty()) {
            actionPayload.put("data", data);
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("flow_message_version", "3");
        parameters.put("flow_token", flowToken);
        parameters.put("flow_id", flowId);
        parameters.put("flow_cta", ctaText);
        parameters.put("flow_action", "navigate");
        parameters.put("flow_action_payload", actionPayload);
        if ("draft".equalsIgnoreCase(flowMode)) {
            parameters.put("mode", "draft");
        }

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", "flow");
        action.put("parameters", parameters);

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "flow");
        interactive.put("body", Map.of("text", bodyText));
        interactive.put("action", action);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "INTERACTIVE");
        content.put("interactive", interactive);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("recipient", buildRecipient(toPhone));
        message.put("sender", buildSender());
        message.put("content", content);

        return wrap(message);
    }

    private Map<String, Object> buildCtaUrlPayload(String toPhone, String bodyText, String displayText, String url) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("display_text", displayText);
        parameters.put("url", url);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", "cta_url");
        action.put("parameters", parameters);

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "cta_url");
        interactive.put("body", Map.of("text", bodyText));
        interactive.put("action", action);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "INTERACTIVE");
        content.put("preview_url", false);
        content.put("shorten_url", false);
        content.put("interactive", interactive);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("webHookDNId", "1001");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("content", content);
        message.put("recipient", buildRecipient(toPhone));
        message.put("sender", buildSender());
        message.put("preferences", preferences);

        return wrap(message);
    }

    /** WhatsApp location request message — body text + a "Send location" button. */
    private Map<String, Object> buildLocationRequestPayload(String toPhone, String bodyText) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("name", "send_location");

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "location_request_message");
        interactive.put("body", Map.of("text", bodyText));
        interactive.put("action", action);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "INTERACTIVE");
        content.put("interactive", interactive);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("webHookDNId", "1001");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("content", content);
        message.put("recipient", buildRecipient(toPhone));
        message.put("sender", buildSender());
        message.put("preferences", preferences);

        return wrap(message);
    }

    private Map<String, Object> buildCarouselPayload(String toPhone, String bodyText, List<KarixService.CarouselCard> cards) {
        List<Map<String, Object>> cardMaps = new ArrayList<>();
        int index = 0;

        for (KarixService.CarouselCard card : cards) {
            if (card.imageUrl() == null || card.imageUrl().isBlank()) {
                log.warn("Skipping carousel card '{}' — imageUrl is blank", card.title());
                continue;
            }

            String cardText = card.subtitle() == null || card.subtitle().isBlank()
                    ? card.title()
                    : card.title() + "\n" + card.subtitle();
            if (cardText.length() > 120) cardText = cardText.substring(0, 117) + "...";

            Map<String, Object> image = new LinkedHashMap<>();
            image.put("link", card.imageUrl());
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("type", "image");
            header.put("image", image);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", cardText);

            // Button ids must be unique across the whole carousel → "_<cardIndex>" appended.
            // onButtonTap strips it again before routing.
            List<Map<String, Object>> buttonList = new ArrayList<>();
            for (String[] btn : card.buttons()) {
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("id", btn[0] + "_" + index);
                reply.put("title", btn[1]);

                Map<String, Object> quickReplyButton = new LinkedHashMap<>();
                quickReplyButton.put("type", "quick_reply");
                quickReplyButton.put("quick_reply", reply);
                buttonList.add(quickReplyButton);
            }

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("buttons", buttonList);

            Map<String, Object> cardMap = new LinkedHashMap<>();
            cardMap.put("card_index", index);
            cardMap.put("type", "cta_url");
            cardMap.put("header", header);
            cardMap.put("body", body);
            cardMap.put("action", action);
            cardMaps.add(cardMap);
            index++;
        }

        Map<String, Object> carouselBody = new LinkedHashMap<>();
        carouselBody.put("text", bodyText);

        Map<String, Object> carouselAction = new LinkedHashMap<>();
        carouselAction.put("cards", cardMaps);

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "carousel");
        interactive.put("body", carouselBody);
        interactive.put("action", carouselAction);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "INTERACTIVE");
        content.put("preview_url", false);
        content.put("shorten_url", false);
        content.put("interactive", interactive);

        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("cust_ref", "kc_carousel_" + System.currentTimeMillis());
        reference.put("messageTag1", "Karva Chauth Carousel");
        reference.put("conversationId", "kc_" + toPhone + "_" + System.currentTimeMillis());

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("to", toPhone);
        recipient.put("recipient_type", "individual");
        recipient.put("reference", reference);

        Map<String, Object> preferences = new LinkedHashMap<>();
        preferences.put("webHookDNId", "1001");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("channel", "WABA");
        message.put("content", content);
        message.put("recipient", recipient);
        message.put("sender", buildSender());
        message.put("preferences", preferences);

        return wrap(message);
    }

    // ==================================================================
    // SHARED PIECES
    // ==================================================================

    private Map<String, Object> wrap(Map<String, Object> message) {
        Map<String, Object> metaData = new LinkedHashMap<>();
        metaData.put("version", "v1.0.9");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("message", message);
        requestBody.put("metaData", metaData);
        return requestBody;
    }

    private Map<String, Object> buildSender() {
        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("from", wabaNumber);
        return sender;
    }

    private Map<String, Object> buildRecipient(String toPhone) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("cust_ref", "kc_" + System.currentTimeMillis());

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("to", toPhone);
        recipient.put("recipient_type", "individual");
        recipient.put("reference", reference);
        return recipient;
    }

    // ==================================================================
    // HTTP CALL
    // ==================================================================

    /** @return Karix mid on success, null on any failure (never throws). */
    private String postForMid(Map<String, Object> body, String toPhone, String stepName) {
        try {
            if (karixApiKey == null || karixApiKey.isBlank()) {
                log.error("Karix API key is blank. Check karix.api.key in properties.");
                return null;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authentication", "Bearer " + karixApiKey);

            String json = objectMapper.writeValueAsString(body);
            log.info("Karix -> sending to={} step={} body={}", toPhone, stepName, json);

            HttpEntity<String> request = new HttpEntity<>(json, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(karixApiUrl, request, String.class);
            String responseBody = response.getBody();

            log.info("Karix API response status={} body={}", response.getStatusCode(), responseBody);

            if (responseBody == null || responseBody.isBlank()) return null;

            // Karix reports errors INSIDE the body with HTTP 200 — always check statusCode
            JsonNode root = objectMapper.readTree(responseBody);
            String statusCode = root.path("statusCode").asText("");
            String mid = root.path("mid").asText("");

            return ("200".equals(statusCode) && !mid.isBlank()) ? mid : null;

        } catch (Exception e) {
            log.error("Karix API error while sending to={} step={}", toPhone, stepName, e);
            return null;
        }
    }
}
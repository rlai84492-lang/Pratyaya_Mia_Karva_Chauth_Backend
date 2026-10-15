package org.example.karvachauth.service;

import java.util.List;
import java.util.Map;

public interface KarixService {

    String sendTextMessage(String toPhone, String text);

    String sendButtonMessage(String toPhone, String bodyText, List<String[]> buttons);
//
//    void sendListMessage(String toPhone, String bodyText, String buttonLabel, List<String[]> options);
    /** List message — one tappable row per option. options = {id, title} or {id, title, description}. */
    String sendListMessage(String toPhone, String bodyText, String buttonLabel, List<String[]> options);

    //
    String sendCarousel(String toPhone, String bodyText, List<CarouselCard> cards);

    record CarouselCard(String imageUrl, String title, String subtitle, List<String[]> buttons) {}
    //
//    /**
//     * Single URL button that opens an external link when tapped (e.g. a
//     * wa.me forward link). Karix's cta_url type — unlike quick_reply
//     * buttons — actually opens the link in the browser/app; it does NOT
//     * send a button_reply webhook back, so the bot can't detect the tap.
//     */
    String sendCtaUrlMessage(String toPhone, String bodyText, String displayText, String url);

    /**
     * Location request — the message shows a "Send location" button. When she taps it and shares,
     * a "location" message (latitude / longitude) comes back on the webhook.
     */
    String sendLocationRequest(String toPhone, String bodyText);

    /**
     * WhatsApp Flow message (C2 "Book Appointment"). Shows a button (ctaText, max 20 characters) that opens
     * the Flow on `screen` with `data` (storeList + minDate). When she submits it, an interactive
     * "nfm_reply" comes back on the webhook with her answers in response_json.
     *
     * @return Karix mid, or null if the send failed
     */
    String sendFlowMessage(String toPhone, String bodyText, String flowId, String flowToken,
                           String ctaText, String screen, Map<String, Object> data);
}
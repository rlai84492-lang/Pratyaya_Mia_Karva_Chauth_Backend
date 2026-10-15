package org.example.karvachauth.service;

public interface BotEngineService {

    /**
     * Step 0 — the Karix template (sent from the Karix portal) reached her: called on its first
     * delivery receipt. Saves the customer, a session on STEP_OPENER and the template message.
     */
    void recordOpenerTemplate(String phone, String mid, String templateId,
                              String status, String errorCode, String errorReason);

    /**
     * Step 0 — she tapped a template button. Saves the path and her tap, and sends "Choose my gift" /
     * "Find her a gift" / "Show me". The session stays on STEP_OPENER until she taps that button.
     *
     * @param path    TEMPLATE_WIFE / TEMPLATE_HUSBAND / TEMPLATE_SPARKLE
     * @param payload the tapped button's payload (null when started from /test)
     * @param title   the tapped button's visible text (may be null)
     */
    void startFromOpener(String phone, String name, String path, String payload, String title);

    /** /test only — same as startFromOpener without a real tap. */
    void startWifeFlow(String phone, String name);

    void startHusbandFlow(String phone, String name);

    void startSparkleFlow(String phone, String name);

    /** /test (template=opener) — the Karva Chauth opener on the Wife path. */
    void sendOpener(String phone, String name);

    boolean onButtonTap(String phone, String path, String payload);

    boolean onTextMessage(String phone, String text);

    /** C2 — she shared her location after "Visit Store": sends the 3 nearest Mia stores. */
    boolean onLocationShared(String phone, String latitude, String longitude);

    /** C2 — she submitted the "Book Appointment" WhatsApp Flow (store, date, time slot). */
    boolean onFlowReply(String phone, String responseJson);

    /**
     * Saves / refreshes the customer's WhatsApp profile name (Karix sends it on every message).
     * Safe to call on every inbound — does nothing if the name is empty or unchanged.
     */
    void updateCustomerName(String phone, String name);
}
package org.example.karvachauth.service;

public interface BotEngineService {

    /**
     * Step 0 — the Karix template (sent from the Karix portal) reached her: called on its first
     * delivery receipt. Saves the customer, a session on STEP_OPENER and the template message.
     */
    void recordOpenerTemplate(String phone, String mid, String templateId,
                              String status, String errorCode, String errorReason);


    void startFromOpener(String phone, String name, String path, String payload, String title);

    /** /test only — same as startFromOpener without a real tap. */
    void startWifeFlow(String phone, String name);

    void startHusbandFlow(String phone, String name);

    void startSparkleFlow(String phone, String name);

    boolean onButtonTap(String phone, String path, String payload);
    boolean onTextMessage(String phone, String text);
    /**
     * Saves / refreshes the customer's WhatsApp profile name (Karix sends it on every message).
     * Safe to call on every inbound — does nothing if the name is empty or unchanged.
     */

    boolean onLocationShared(String phone, String latitude, String longitude);
    void updateCustomerName(String phone, String name);

    /** Karva Chauth opener: text + [Choose my gift] [Visit nearby store].
     *  /test aur keyword ("karva chauth") dono yahi call karte hain. */
    void sendOpener(String phone, String name);
}
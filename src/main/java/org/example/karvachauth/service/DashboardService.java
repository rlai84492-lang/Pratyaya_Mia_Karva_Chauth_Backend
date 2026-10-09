package org.example.karvachauth.service;

import java.util.Map;

public interface DashboardService {

    // ---------------- Overview page ----------------

    Map<String, Object> getMetricCards(String flow, String range, String startDate, String endDate);

    Map<String, Object> getOutcomes(String flow, String range, String startDate, String endDate);

    Map<String, Object> getFlowDropOff(String flow, String range, String startDate, String endDate);

    Map<String, Object> getActivity(String flow, String range, String startDate, String endDate, int limit);

    Map<String, Object> getPathsSummary(String range, String startDate, String endDate);

    /** "Categories Picked" + "Budget Chosen" panels. */
    Map<String, Object> getCategoryAndBudgetPicks(String flow, String range, String startDate, String endDate);

    // ---------------- Conversations page ----------------

    Map<String, Object> getConversationsSummary(String flow, String range, String startDate, String endDate);

    Map<String, Object> getConversations(ConversationFilters filters, int page);

    Map<String, Object> exportConversations(ConversationFilters filters, String scope, int page);

    // ---------------- Store Visits page ----------------

    Map<String, Object> getStoreVisitsSummary(String flow, String range, String startDate, String endDate);

    Map<String, Object> getStoreBookings(String flow, String range, String startDate, String endDate, int page);

    // ---------------- Referral Leads page ----------------

    Map<String, Object> getReferralLeadsSummary(String flow, String range, String startDate, String endDate);

    /** show : all / husbands / wives / consented ("Consent: yes") */
    Map<String, Object> getReferralLeads(String flow, String range, String startDate, String endDate,
                                         String show, int page);

    Map<String, Object> exportReferralLeads(String flow, String range, String startDate, String endDate,
                                            String show, String scope, int page);

    // ---------------- Audiences page (date range only, no path tabs) ----------------

    Map<String, Object> getAudiencesSummary(String range, String startDate, String endDate);

    // One per card: the card's full count + the rows for its download.
    // fullMobile = "Full mobile numbers in downloads" (true = full numbers, false = masked).

    Map<String, Object> getHintSentNoVisitAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getListStartedNoHintAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getBrowsingNoPurchaseAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getShortlistedNoVisitAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getStoreVisitBookedAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getClickedBuyOnlineAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getReferredConsentGivenAudience(String range, String startDate, String endDate, boolean fullMobile);

    Map<String, Object> getOptedInNewArrivalsAudience(String range, String startDate, String endDate, boolean fullMobile);

    /**
     * Every filter on the Conversations page — the controller builds it from the request params.
     *
     * flow      : all / celebrating / shopping / sparkle
     * range     : today / 7d / 30d / custom   (custom needs from + to, yyyy-MM-dd)
     * category  : a category value, e.g. RINGS (empty = all categories)
     * step      : a flow-script step code — "1B", "2A-Budget", "1I-nudge" or "1B · Carousel"; empty = all steps
     * search    : part of the customer's name or phone number
     * sort      : customer / phone / path / step / category / budget / lastActivity
     * direction : asc / desc
     */
    record ConversationFilters(String flow, String range, String from, String to,
                               String category, String step, String search,
                               String sort, String direction) {
    }
}
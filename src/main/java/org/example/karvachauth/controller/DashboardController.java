package org.example.karvachauth.controller;

import lombok.RequiredArgsConstructor;
import org.example.karvachauth.service.DashboardService;
import org.example.karvachauth.service.DashboardService.ConversationFilters;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dashboard APIs — Overview, Conversations, Store Visits, Referral Leads and Audiences pages.
 *
 * flow  : all / celebrating / shopping / sparkle
 * range : today / 7d / 30d / custom   (custom needs from + to as yyyy-MM-dd, both inclusive)
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/metric-cards")
    public ResponseEntity<Map<String, Object>> metricCards(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getMetricCards(flow, range, from, to));
    }

    @GetMapping("/outcomes")
    public ResponseEntity<Map<String, Object>> outcomes(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getOutcomes(flow, range, from, to));
    }

    @GetMapping("/flow-dropoff")
    public ResponseEntity<Map<String, Object>> flowDropOff(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getFlowDropOff(flow, range, from, to));
    }

    @GetMapping("/activity")
    public ResponseEntity<Map<String, Object>> activity(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(dashboardService.getActivity(flow, range, from, to, limit));
    }

    /** "The three paths" section — always compares all three, so it has no flow filter. */
    @GetMapping("/paths")
    public ResponseEntity<Map<String, Object>> paths(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getPathsSummary(range, from, to));
    }

    /**
     * "Categories Picked" + "Budget Chosen" — customers per category / budget row.
     * flow = celebrating → budgets.applicable = false and every budget row is 0 (no budget step).
     */
    @GetMapping("/category-budget-picks")
    public ResponseEntity<Map<String, Object>> categoryBudgetPicks(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getCategoryAndBudgetPicks(flow, range, from, to));
    }

    // ==================================================================
    // CONVERSATIONS PAGE
    // ==================================================================

    /** The 5 cards at the top — follow the path tabs and the date range only. */
    @GetMapping("/conversations/summary")
    public ResponseEntity<Map<String, Object>> conversationsSummary(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getConversationsSummary(flow, range, from, to));
    }

    /** The table — 50 rows per page, page starts at 0. Every filter is optional. */
    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> conversations(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String step,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "lastActivity") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "0") int page) {
        ConversationFilters filters = new ConversationFilters(
                flow, range, from, to, category, step, search, sort, direction);
        return ResponseEntity.ok(dashboardService.getConversations(filters, page));
    }

    /**
     * Rows to export, with the same filters as the table. The frontend builds the CSV / PDF.
     * scope : page (the 50 rows of ?page=) / all (every row for the filters)
     */
    @GetMapping("/conversations/export")
    public ResponseEntity<Map<String, Object>> conversationsExport(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String step,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "lastActivity") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "0") int page) {
        ConversationFilters filters = new ConversationFilters(
                flow, range, from, to, category, step, search, sort, direction);
        return ResponseEntity.ok(dashboardService.exportConversations(filters, scope, page));
    }

    // ==================================================================
    // STORE VISITS PAGE
    // ==================================================================

    /** The 3 cards — Visits Booked, Booking Form Opened, Most Picked Slot. */
    @GetMapping("/store-visits/summary")
    public ResponseEntity<Map<String, Object>> storeVisitsSummary(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getStoreVisitsSummary(flow, range, from, to));
    }

    /** Recent Bookings table — newest first, 50 rows per page, page starts at 0. */
    @GetMapping("/store-visits/bookings")
    public ResponseEntity<Map<String, Object>> storeBookings(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(dashboardService.getStoreBookings(flow, range, from, to, page));
    }

    // ==================================================================
    // REFERRAL LEADS PAGE
    // ==================================================================

    /** The 5 cards and the Opt-in Leads section. */
    @GetMapping("/referral-leads/summary")
    public ResponseEntity<Map<String, Object>> referralLeadsSummary(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getReferralLeadsSummary(flow, range, from, to));
    }

    /**
     * The table — newest first, 50 rows per page, page starts at 0.
     * show : all / husbands / wives / consented
     */
    @GetMapping("/referral-leads")
    public ResponseEntity<Map<String, Object>> referralLeads(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "all") String show,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(dashboardService.getReferralLeads(flow, range, from, to, show, page));
    }

    /**
     * Rows for "Copy as CSV" / export, with the same filters as the table. The frontend builds the file.
     * scope : page (the 50 rows of ?page=) / all (every row for the filters)
     */
    @GetMapping("/referral-leads/export")
    public ResponseEntity<Map<String, Object>> referralLeadsExport(
            @RequestParam(defaultValue = "all") String flow,
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "all") String show,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(dashboardService.exportReferralLeads(flow, range, from, to, show, scope, page));
    }

    // ==================================================================
    // AUDIENCES PAGE — date range only, no path tabs
    // ==================================================================

    /** The 2 cards + "Where they stopped" + "Completed or opted in" (counts per segment). */
    @GetMapping("/cohorts/summary")
    public ResponseEntity<Map<String, Object>> cohortsSummary(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return ResponseEntity.ok(dashboardService.getAudiencesSummary(range, from, to));
    }

    /*
     * One endpoint per card. Each returns the card's full count + the rows for its "Download CSV"
     * (columns + all rows — the frontend builds the file).
     * fullMobile : true = full numbers, false = masked (default) — the "Full mobile numbers in downloads" checkbox
     */

    /** Hint sent, no visit yet (I'm celebrating) */
    @GetMapping("/cohorts/hint-sent-no-visit")
    public ResponseEntity<Map<String, Object>> hintSentNoVisit(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getHintSentNoVisitAudience(range, from, to, fullMobile));
    }

    /** List started, hint not sent (I'm celebrating) */
    @GetMapping("/cohorts/list-started-no-hint")
    public ResponseEntity<Map<String, Object>> listStartedNoHint(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getListStartedNoHintAudience(range, from, to, fullMobile));
    }

    /** Browsing for her, no purchase (Shopping for her) */
    @GetMapping("/cohorts/browsing-no-purchase")
    public ResponseEntity<Map<String, Object>> browsingNoPurchase(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getBrowsingNoPurchaseAudience(range, from, to, fullMobile));
    }

    /** Shortlisted for herself, no visit (Here for the sparkle) */
    @GetMapping("/cohorts/shortlisted-no-visit")
    public ResponseEntity<Map<String, Object>> shortlistedNoVisit(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getShortlistedNoVisitAudience(range, from, to, fullMobile));
    }

    /** Store visit booked (all paths) */
    @GetMapping("/cohorts/store-visit-booked")
    public ResponseEntity<Map<String, Object>> storeVisitBooked(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getStoreVisitBookedAudience(range, from, to, fullMobile));
    }

    /** Clicked Buy Online (Shopping for her) */
    @GetMapping("/cohorts/clicked-buy-online")
    public ResponseEntity<Map<String, Object>> clickedBuyOnline(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getClickedBuyOnlineAudience(range, from, to, fullMobile));
    }

    /** Referred contacts, consent given (I'm celebrating + Shopping for her) */
    @GetMapping("/cohorts/referred-consent-given")
    public ResponseEntity<Map<String, Object>> referredConsentGiven(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getReferredConsentGivenAudience(range, from, to, fullMobile));
    }

    /** Opted in for new arrivals (Here for the sparkle) */
    @GetMapping("/cohorts/opted-in-new-arrivals")
    public ResponseEntity<Map<String, Object>> optedInNewArrivals(
            @RequestParam(defaultValue = "today") String range,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean fullMobile) {
        return ResponseEntity.ok(dashboardService.getOptedInNewArrivalsAudience(range, from, to, fullMobile));
    }
}
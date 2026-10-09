package org.example.karvachauth.repository;

import org.example.karvachauth.entity.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 *

 * Two kinds of window:
 *   - EVENT queries (metric cards, outcomes, activity) count things that HAPPENED in the window.
 *   - FUNNEL queries (bot flow drop-off) follow the sessions that STARTED in the window
 *     (sessions.started_at), so every stage is a subset of "Opener" and never goes above 100%.
 *
 * How the bot records data (BotEngineServiceImple):
 *   - The Step 0 templates first delivery receipt creates a session on OPENER with NO path.
 *     When she taps a Step 0 button the path is set — so "path IS NOT NULL" = she tapped Step 0.
 *     (If she was mid-journey, the tap closes that session and starts a new one with the path.)
 *   - Every message is saved on the step it puts her on: a tap that IS a step ("Choose my gift",
 *     a category) moves the session first, and moveTo() re-saves our reply on the new step. So
 *     "reached step X" = any message (in or out) saved with step X.
 *   - The Step 0 template message itself has no path, so it only counts under "all paths".
 *
 * The funnel follows sessions by started_at — for a session made by the template receipt, that is
 * when the template reached her (the tap can come later).
 */
public interface DashboardRepository extends JpaRepository<Lead, Long> {

    // ==================================================================
    // METRIC CARDS — outbound messages (event window)
    // Step 0 campaign templates have no path, so they appear under "all" only.
    // ==================================================================

    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) FROM messages m " +
                    "WHERE m.direction = 'OUTBOUND' " +
                    "  AND m.status IN (:statuses) " +
                    "  AND (:path IS NULL OR m.path = :path) " +
                    "  AND m.created_at >= :from AND m.created_at < :to")
    long countOutboundMessagesByStatus(@Param("statuses") List<String> statuses,
                                       @Param("path") String path,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    // ==================================================================
    // OPENER TAPS — sessions that got a path, i.e. she tapped a Step 0 button.
    // Sessions still on the template (path NULL) are left out.
    // ==================================================================

    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) FROM sessions s " +
                    "WHERE s.path IS NOT NULL " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countOpenerTaps(@Param("path") String path,
                         @Param("from") LocalDateTime from,
                         @Param("to") LocalDateTime to);

    interface PathCountProjection {
        String getPath();
        Long getTotal();
    }

    @Query(nativeQuery = true, value =
            "SELECT s.path AS path, COUNT(*) AS total FROM sessions s " +
                    "WHERE s.path IS NOT NULL " +
                    "  AND s.started_at >= :from AND s.started_at < :to " +
                    "GROUP BY s.path")
    List<PathCountProjection> countOpenerTapsByPath(@Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    // ==================================================================
    // BOT FLOW DROP-OFF — sessions started in the window
    // ==================================================================

    /** Sessions that tapped this exact button (e.g. 'W_CHOOSE_MY_GIFT', 'W_SEND_ISHARA'). */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT m.session_id) FROM messages m " +
                    "JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.direction = 'INBOUND' " +
                    "  AND m.button_payload = :payload " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsWithButtonTap(@Param("payload") String payload,
                                    @Param("path") String path,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /**
     * Sessions that tapped any button starting with this prefix (e.g. 'CAT_' for a category,
     * 'H_BUDGET_' for a budget row). LEFT(...) is used instead of LIKE because '_' is a
     * wildcard in LIKE.
     */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT m.session_id) FROM messages m " +
                    "JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.direction = 'INBOUND' " +
                    "  AND LEFT(m.button_payload, CHAR_LENGTH(:payloadPrefix)) = :payloadPrefix " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsWithButtonTapPrefix(@Param("payloadPrefix") String payloadPrefix,
                                          @Param("path") String path,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /**
     * Sessions that tapped a button starting with either prefix — e.g. 'BUY_' ("Buy this for her") or
     * 'VISIT_' ("Visit nearby store") on a product card.
     */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT m.session_id) FROM messages m " +
                    "JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.direction = 'INBOUND' " +
                    "  AND (LEFT(m.button_payload, CHAR_LENGTH(:firstPrefix)) = :firstPrefix " +
                    "       OR LEFT(m.button_payload, CHAR_LENGTH(:secondPrefix)) = :secondPrefix) " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsWithEitherButtonTapPrefix(@Param("firstPrefix") String firstPrefix,
                                                @Param("secondPrefix") String secondPrefix,
                                                @Param("path") String path,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to);

    /**
     * Sessions that reached one of these steps — any message (inbound or outbound) saved on it.
     * The bot saves each message on the step it puts her on, so a session counts as soon as the
     * message for that step (e.g. the product carousel on W_BROWSE_PRODUCTS) is sent.
     */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT m.session_id) FROM messages m " +
                    "JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.step IN (:steps) " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsReachedStep(@Param("steps") List<String> steps,
                                  @Param("path") String path,
                                  @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT pp.session_id) FROM product_picks pp " +
                    "JOIN sessions s ON s.id = pp.session_id " +
                    "WHERE pp.action_type IN (:actionTypes) " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsWithPickAction(@Param("actionTypes") List<String> actionTypes,
                                     @Param("path") String path,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT l.session_id) FROM leads l " +
                    "JOIN sessions s ON s.id = l.session_id " +
                    "WHERE l.lead_type IN (:leadTypes) " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsWithLead(@Param("leadTypes") List<String> leadTypes,
                               @Param("path") String path,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    /** Partner captured WITH a mobile — the bot only keeps the mobile when consent is YES. */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT l.session_id) FROM leads l " +
                    "JOIN sessions s ON s.id = l.session_id " +
                    "WHERE l.lead_type IN (:referralTypes) " +
                    "  AND l.partner_phone IS NOT NULL AND l.partner_phone <> '' " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to")
    long countSessionsWithReferralLead(@Param("referralTypes") List<String> referralTypes,
                                       @Param("path") String path,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to);

    // ==================================================================
    // OUTCOMES + PATH CARDS — leads and picks (event window)
    // ==================================================================

    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT l.session_id) FROM leads l " +
                    "WHERE l.lead_type = :leadType " +
                    "  AND (:path IS NULL OR l.path = :path) " +
                    "  AND l.created_at >= :from AND l.created_at < :to")
    long countLeadsByType(@Param("leadType") String leadType,
                          @Param("path") String path,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to);

    /** A referral counts only when the partner's mobile was captured (= consent YES). */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT l.session_id) FROM leads l " +
                    "WHERE l.lead_type IN (:referralTypes) " +
                    "  AND l.partner_phone IS NOT NULL AND l.partner_phone <> '' " +
                    "  AND (:path IS NULL OR l.path = :path) " +
                    "  AND l.created_at >= :from AND l.created_at < :to")
    long countReferralLeads(@Param("referralTypes") List<String> referralTypes,
                            @Param("path") String path,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to);

    /** Distinct pieces added to a list (the same piece in two sessions counts twice). */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT CONCAT(pp.session_id, '-', pp.sku)) FROM product_picks pp " +
                    "WHERE pp.action_type = :actionType " +
                    "  AND (:path IS NULL OR pp.path = :path) " +
                    "  AND pp.created_at >= :from AND pp.created_at < :to")
    long countPiecesByPickAction(@Param("actionType") String actionType,
                                 @Param("path") String path,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);

    // ==================================================================
    // OUTCOMES — daily counts for the sparklines (event window)
    // ==================================================================

    interface DailyCountProjection {
        String getDay();     // yyyy-MM-dd
        Long getTotal();
    }

    @Query(nativeQuery = true, value =
            "SELECT DATE_FORMAT(l.created_at, '%Y-%m-%d') AS day, COUNT(DISTINCT l.session_id) AS total " +
                    "FROM leads l " +
                    "WHERE l.lead_type = :leadType " +
                    "  AND (:path IS NULL OR l.path = :path) " +
                    "  AND l.created_at >= :from AND l.created_at < :to " +
                    "GROUP BY DATE_FORMAT(l.created_at, '%Y-%m-%d') " +
                    "ORDER BY day")
    List<DailyCountProjection> findDailyLeadCounts(@Param("leadType") String leadType,
                                                   @Param("path") String path,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);

    @Query(nativeQuery = true, value =
            "SELECT DATE_FORMAT(l.created_at, '%Y-%m-%d') AS day, COUNT(DISTINCT l.session_id) AS total " +
                    "FROM leads l " +
                    "WHERE l.lead_type IN (:referralTypes) " +
                    "  AND l.partner_phone IS NOT NULL AND l.partner_phone <> '' " +
                    "  AND (:path IS NULL OR l.path = :path) " +
                    "  AND l.created_at >= :from AND l.created_at < :to " +
                    "GROUP BY DATE_FORMAT(l.created_at, '%Y-%m-%d') " +
                    "ORDER BY day")
    List<DailyCountProjection> findDailyReferralCounts(@Param("referralTypes") List<String> referralTypes,
                                                       @Param("path") String path,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    // ==================================================================
    // LIVE ACTIVITY — Step 0 taps + lead events + product pick events, newest first (event window)
    // ==================================================================

    interface ActivityRowProjection {
        String getSource();          // LEAD / PICK / OPENER_TAP
        String getEventType();       // lead_type or action_type
        String getPath();
        String getCustomerName();
        String getPhone();
        String getPartnerPhone();
        String getConsent();
        String getStoreName();
        String getProductName();
        String getSku();             // product SKU(s) — comma-separated for an Ishara
        LocalDate getVisitDate();    // store visit only (from bookings)
        String getTimeSlot();        // store visit only (from bookings)
        LocalDateTime getEventTime();
    }

    @Query(nativeQuery = true, value =
            "(SELECT 'LEAD' AS source, l.lead_type AS eventType, l.path AS path, " +
                    "        COALESCE(l.customer_name, c.name) AS customerName, l.phone AS phone, " +
                    "        l.partner_phone AS partnerPhone, l.consent AS consent, l.store_name AS storeName, " +
                    "        p.name AS productName, l.product_skus AS sku, " +
                    "        b.visit_date AS visitDate, b.time_slot AS timeSlot, " +
                    "        l.created_at AS eventTime " +
                    " FROM leads l " +
                    " LEFT JOIN customers c ON c.phone = l.phone " +
                    " LEFT JOIN products p ON p.sku = l.product_skus " +        // single piece (Buy Online)
                    " LEFT JOIN bookings b ON b.booking_ref = l.booking_ref " +  // store visit
                    " WHERE (:path IS NULL OR l.path = :path) " +
                    "   AND l.created_at >= :from AND l.created_at < :to) " +
                    "UNION ALL " +
                    "(SELECT 'PICK' AS source, pp.action_type AS eventType, pp.path AS path, " +
                    "        c.name AS customerName, pp.phone AS phone, " +
                    "        CAST(NULL AS CHAR) AS partnerPhone, CAST(NULL AS CHAR) AS consent, CAST(NULL AS CHAR) AS storeName, " +
                    "        p.name AS productName, pp.sku AS sku, " +
                    "        CAST(NULL AS DATE) AS visitDate, CAST(NULL AS CHAR) AS timeSlot, " +
                    "        pp.created_at AS eventTime " +
                    " FROM product_picks pp " +
                    " LEFT JOIN customers c ON c.phone = pp.phone " +
                    " LEFT JOIN products p ON p.sku = pp.sku " +
                    " WHERE pp.action_type IN (:pickActions) " +
                    "   AND (:path IS NULL OR pp.path = :path) " +
                    "   AND pp.created_at >= :from AND pp.created_at < :to) " +
                    "UNION ALL " +
                    // Step 0 taps — her first tap in the session, saved on OPENER with the path she picked
                    "(SELECT 'OPENER_TAP' AS source, m.button_payload AS eventType, m.path AS path, " +
                    "        c.name AS customerName, m.phone AS phone, " +
                    "        CAST(NULL AS CHAR) AS partnerPhone, CAST(NULL AS CHAR) AS consent, CAST(NULL AS CHAR) AS storeName, " +
                    "        CAST(NULL AS CHAR) AS productName, CAST(NULL AS CHAR) AS sku, " +
                    "        CAST(NULL AS DATE) AS visitDate, CAST(NULL AS CHAR) AS timeSlot, " +
                    "        m.created_at AS eventTime " +
                    " FROM messages m " +
                    " LEFT JOIN customers c ON c.id = m.customer_id " +
                    " WHERE m.direction = 'INBOUND' AND m.step = 'OPENER' AND m.path IS NOT NULL " +
                    "   AND m.button_payload IS NOT NULL AND m.button_payload <> 'BTN_STORE_FINDER' " +
                    "   AND NOT EXISTS (SELECT 1 FROM messages earlier " +
                    "                   WHERE earlier.session_id = m.session_id AND earlier.direction = 'INBOUND' " +
                    "                     AND earlier.id < m.id) " +
                    "   AND (:path IS NULL OR m.path = :path) " +
                    "   AND m.created_at >= :from AND m.created_at < :to) " +
                    "ORDER BY eventTime DESC " +
                    "LIMIT :limit")
    List<ActivityRowProjection> findRecentActivity(@Param("pickActions") List<String> pickActions,
                                                   @Param("path") String path,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to,
                                                   @Param("limit") int limit);

    // ==================================================================
    // CATEGORIES PICKED / BUDGET CHOSEN — customers per category / budget row,
    // counted from her taps made in the window
    // ==================================================================

    interface ChoiceCountProjection {
        String getChoice();      // category code (PEND, MSUT, …) or budget band (UNDER_20K, …)
        Long getCustomers();     // distinct customers who tapped it
    }

    /**
     * Category taps: 'CAT_PEND_3' → 'PEND' (the carousel adds "_<cardIndex>", SUBSTRING_INDEX drops it).
     * A customer who picked two categories counts once in each.
     */
    @Query(nativeQuery = true, value =
            "SELECT SUBSTRING_INDEX(SUBSTRING(m.button_payload, 5), '_', 1) AS choice, " +
                    "       COUNT(DISTINCT m.phone) AS customers " +
                    "FROM messages m " +
                    "JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.direction = 'INBOUND' " +
                    "  AND LEFT(m.button_payload, 4) = 'CAT_' " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND m.created_at >= :from AND m.created_at < :to " +
                    "GROUP BY choice")
    List<ChoiceCountProjection> countCustomersByCategory(@Param("path") String path,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to);

    /**
     * Budget taps: 'H_BUDGET_UNDER_20K' / 'S_BUDGET_UNDER_20K' → 'UNDER_20K'.
     * Only the Husband and Sparkle paths have a budget step, so the Wife path always returns nothing.
     */
    @Query(nativeQuery = true, value =
            "SELECT SUBSTRING(m.button_payload, 10) AS choice, " +
                    "       COUNT(DISTINCT m.phone) AS customers " +
                    "FROM messages m " +
                    "JOIN sessions s ON s.id = m.session_id " +
                    "WHERE m.direction = 'INBOUND' " +
                    "  AND LEFT(m.button_payload, 9) IN ('H_BUDGET_', 'S_BUDGET_') " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND m.created_at >= :from AND m.created_at < :to " +
                    "GROUP BY choice")
    List<ChoiceCountProjection> countCustomersByBudget(@Param("path") String path,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    // ==================================================================
    // CONVERSATIONS PAGE — one row per session (sessions started in the window)
    //
    // The SQL is split into three pieces so the page query and the count query
    // always use exactly the same rows and filters.
    // ==================================================================

    /**
     * The table's rows — one per session she started by tapping a Step 0 button (path IS NOT NULL).
     * A template she never tapped (session on OPENER with no path) is left out.
     *
     *   step         : where she got to. For a finished journey (current_step = 'CLOSED') it is the step
     *                  she finished from — the step of her last message before the goodbye
     *                  (moveTo saves the goodbye itself on CLOSED).
     *   sessionState : COMPLETED   — the journey reached E1 (CLOSED)
     *                  OPEN        — still going
     *                  ENDED       — closed without finishing (a new Step 0 tap started a new session)
     *   lastActivity : her last reply (the Step 0 tap counts), or when the session started if none.
     */
    String CONVERSATION_ROWS =
            "SELECT s.id AS sessionId, c.name AS customerName, s.phone AS phone, s.path AS path, " +
                    "       CASE WHEN s.current_step = 'CLOSED' " +
                    "            THEN (SELECT m.step FROM messages m " +
                    "                  WHERE m.session_id = s.id AND m.step <> 'CLOSED' " +
                    "                  ORDER BY m.id DESC LIMIT 1) " +
                    "            ELSE s.current_step END AS step, " +
                    "       s.current_step AS currentStep, " +
                    "       CASE WHEN s.current_step = 'CLOSED' THEN 'COMPLETED' " +
                    "            WHEN s.is_active THEN 'OPEN' " +
                    "            ELSE 'ENDED' END AS sessionState, " +
                    "       s.selected_category AS category, s.selected_budget AS budget, " +
                    "       s.started_at AS startedAt, COALESCE(s.last_inbound_at, s.started_at) AS lastActivity " +
                    "FROM sessions s " +
                    "LEFT JOIN customers c ON c.id = s.customer_id " +
                    "WHERE s.path IS NOT NULL " +
                    "  AND (:path IS NULL OR s.path = :path) " +
                    "  AND s.started_at >= :from AND s.started_at < :to";

    /**
     * Table filters. Unused filters are passed as NULL / filterByStep = false.
     * The step filter matches the step she got to, or the current step — the latter only
     * matters for 'CLOSED' (E1), since a finished journey shows the step it ended on.
     * searchName is a LIKE pattern on the name; searchPhone is a LIKE pattern on the digits
     * of the search text ('' when the text has no digits, which matches no phone).
     */
    String CONVERSATION_FILTERS =
            " WHERE (:category IS NULL OR conversation.category = :category) " +
                    "   AND (:filterByStep = FALSE " +
                    "        OR conversation.step IN (:steps) " +
                    "        OR conversation.currentStep IN (:steps)) " +
                    "   AND (:searchName IS NULL " +
                    "        OR conversation.customerName LIKE :searchName " +
                    "        OR conversation.phone LIKE :searchPhone)";

    /**
     * The value a column header sorts on. Step and budget sort in journey / price order,
     * not alphabetically. Everything is turned into text so one CASE can hold every column.
     */
    String CONVERSATION_SORT_VALUE =
            "CASE :sortColumn " +
                    "  WHEN 'customer' THEN LOWER(conversation.customerName) " +
                    "  WHEN 'phone'    THEN conversation.phone " +
                    "  WHEN 'path'     THEN conversation.path " +
                    "  WHEN 'step'     THEN LPAD(FIELD(conversation.step, " +
                    "                      'OPENER', 'W_CHOOSE_MY_GIFT', 'H_FIND_HER_GIFT', 'S_SHOW_ME', " +
                    "                      'W_PICK_CATEGORY', 'H_PICK_CATEGORY', 'S_PICK_CATEGORY', " +
                    "                      'W_BUDGET', 'H_BUDGET', 'S_BUDGET', " +
                    "                      'W_BROWSE_PRODUCTS', 'W_ADD_MORE', 'H_BROWSE_PRODUCTS', 'S_BROWSE_PRODUCTS', 'S_ADD_MORE', " +
                    "                      'W_ADDED_TO_LIST', 'S_ADDED_TO_LIST', 'H_CONFIRM_CHOICE', " +
                    "                      'W_HINT_READY', 'W_STORE_INVITE', 'BOOK_STORE_VISIT', " +
                    "                      'W_REINFORCE', 'W_REINFORCE_NUDGE', 'S_REINFORCE', 'H_CAP_DETAILS', " +
                    "                      'W_CAPH_NAME', 'W_CAPH_ANNIVERSARY', 'W_CAPH_MOBILE', " +
                    "                      'H_CAPW_NAME', 'H_CAPW_BIRTHDAY', 'H_CAPW_MOBILE', " +
                    "                      'W_CONSENT', 'H_CONSENT', 'CLOSED'), 3, '0') " +
                    "  WHEN 'category' THEN conversation.category " +
                    "  WHEN 'budget'   THEN LPAD(FIELD(conversation.budget, 'UNDER_20K', '20_50K', 'ABOVE_50K'), 3, '0') " +
                    "  ELSE DATE_FORMAT(conversation.lastActivity, '%Y%m%d%H%i%s') " +
                    "END";

    interface ConversationRowProjection {
        Long getSessionId();
        String getCustomerName();
        String getPhone();
        String getPath();
        String getStep();            // where she got to (see CONVERSATION_ROWS)
        String getCurrentStep();     // raw current step — 'CLOSED' for a finished journey
        String getSessionState();    // OPEN / COMPLETED / ENDED
        String getCategory();
        String getBudget();
        LocalDateTime getStartedAt();
        LocalDateTime getLastActivity();
    }

    /** One page of the table. Export uses the same query (limit = page size, or the export cap). */
    @Query(nativeQuery = true, value =
            "SELECT * FROM ( " +
                    "  SELECT conversation.*, " + CONVERSATION_SORT_VALUE + " AS sortValue " +
                    "  FROM (" + CONVERSATION_ROWS + ") conversation " +
                    CONVERSATION_FILTERS +
                    ") filtered " +
                    "ORDER BY CASE WHEN :sortDirection = 'asc'  THEN filtered.sortValue END ASC, " +
                    "         CASE WHEN :sortDirection = 'desc' THEN filtered.sortValue END DESC, " +
                    "         filtered.lastActivity DESC, filtered.sessionId DESC " +
                    "LIMIT :limit OFFSET :offset")
    List<ConversationRowProjection> findConversationsPage(@Param("path") String path,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          @Param("category") String category,
                                                          @Param("filterByStep") boolean filterByStep,
                                                          @Param("steps") List<String> steps,
                                                          @Param("searchName") String searchName,
                                                          @Param("searchPhone") String searchPhone,
                                                          @Param("sortColumn") String sortColumn,
                                                          @Param("sortDirection") String sortDirection,
                                                          @Param("limit") int limit,
                                                          @Param("offset") int offset);

    /** Total rows for the same filters — drives "Page 1 of 3" and the export cap check. */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) FROM (" + CONVERSATION_ROWS + ") conversation " + CONVERSATION_FILTERS)
    long countConversations(@Param("path") String path,
                            @Param("from") LocalDateTime from,
                            @Param("to") LocalDateTime to,
                            @Param("category") String category,
                            @Param("filterByStep") boolean filterByStep,
                            @Param("steps") List<String> steps,
                            @Param("searchName") String searchName,
                            @Param("searchPhone") String searchPhone);

    // ==================================================================
    // STORE VISITS PAGE — bookings from the C2 WhatsApp Flow (event window on bookings.created_at)
    //
    // A booking row is written when she submits the booking form. Cancelled bookings are left out
    // of every number and of the table.
    // ==================================================================

    /** Bookings made in the window (not cancelled). */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) FROM bookings b " +
                    "WHERE b.status <> 'CANCELLED' " +
                    "  AND (:path IS NULL OR b.path = :path) " +
                    "  AND b.created_at >= :from AND b.created_at < :to")
    long countBookings(@Param("path") String path,
                       @Param("from") LocalDateTime from,
                       @Param("to") LocalDateTime to);

    /**
     * Sessions that got to C2 in the window — she tapped "Visit nearby store" / "Visit Store",
     * which puts the session on BOOK_STORE_VISIT, where the booking form is sent.
     */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT m.session_id) FROM messages m " +
                    "WHERE m.step = 'BOOK_STORE_VISIT' " +
                    "  AND (:path IS NULL OR m.path = :path) " +
                    "  AND m.created_at >= :from AND m.created_at < :to")
    long countSessionsOpenedBookingForm(@Param("path") String path,
                                        @Param("from") LocalDateTime from,
                                        @Param("to") LocalDateTime to);

    interface SlotCountProjection {
        String getTimeSlot();
        Long getTotal();
    }

    /** The time slot picked most often (ties → the earlier one alphabetically). */
    @Query(nativeQuery = true, value =
            "SELECT b.time_slot AS timeSlot, COUNT(*) AS total FROM bookings b " +
                    "WHERE b.status <> 'CANCELLED' AND b.time_slot IS NOT NULL " +
                    "  AND (:path IS NULL OR b.path = :path) " +
                    "  AND b.created_at >= :from AND b.created_at < :to " +
                    "GROUP BY b.time_slot " +
                    "ORDER BY total DESC, b.time_slot " +
                    "LIMIT 1")
    List<SlotCountProjection> findMostPickedSlot(@Param("path") String path,
                                                 @Param("from") LocalDateTime from,
                                                 @Param("to") LocalDateTime to);

    interface BookingRowProjection {
        Long getBookingId();
        String getBookingRef();
        String getCustomerName();
        String getPhone();
        String getPath();
        String getStoreName();
        LocalDate getVisitDate();
        String getTimeSlot();
        String getReservedSkus();        // comma-separated
        String getReservedProductName(); // name of the piece when exactly one was reserved
        String getStatus();
        LocalDateTime getBookedAt();
    }

    /** One page of bookings, newest first. */
    @Query(nativeQuery = true, value =
            "SELECT b.id AS bookingId, b.booking_ref AS bookingRef, c.name AS customerName, b.phone AS phone, " +
                    "       b.path AS path, b.store_name AS storeName, b.visit_date AS visitDate, " +
                    "       b.time_slot AS timeSlot, b.reserved_skus AS reservedSkus, " +
                    "       p.name AS reservedProductName, b.status AS status, b.created_at AS bookedAt " +
                    "FROM bookings b " +
                    "LEFT JOIN customers c ON c.id = b.customer_id " +
                    "LEFT JOIN products p ON p.sku = b.reserved_skus " +     // matches only a single SKU
                    "WHERE b.status <> 'CANCELLED' " +
                    "  AND (:path IS NULL OR b.path = :path) " +
                    "  AND b.created_at >= :from AND b.created_at < :to " +
                    "ORDER BY b.created_at DESC, b.id DESC " +
                    "LIMIT :limit OFFSET :offset")
    List<BookingRowProjection> findBookingsPage(@Param("path") String path,
                                                @Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    // ==================================================================
    // REFERRAL LEADS PAGE — partner details captured at 1J/1K (husband) and 2F/2G (wife)
    // Event window on leads.created_at (when she / he answered the consent question).
    // Every captured contact counts, consent YES or NO; the bot keeps the partner's mobile only on YES.
    // ==================================================================

    interface ReferralSummaryProjection {
        Long getTotal();
        Long getHusbands();
        Long getWives();
        Long getConsented();       // consent = YES
        Long getMessageable();     // consent = YES and a mobile was kept
    }

    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) AS total, " +
                    "       COALESCE(SUM(CASE WHEN l.lead_type = 'HUSBAND_CAPTURED' THEN 1 ELSE 0 END), 0) AS husbands, " +
                    "       COALESCE(SUM(CASE WHEN l.lead_type = 'WIFE_CAPTURED' THEN 1 ELSE 0 END), 0) AS wives, " +
                    "       COALESCE(SUM(CASE WHEN l.consent = 'YES' THEN 1 ELSE 0 END), 0) AS consented, " +
                    "       COALESCE(SUM(CASE WHEN l.consent = 'YES' AND l.partner_phone IS NOT NULL " +
                    "                          AND l.partner_phone <> '' THEN 1 ELSE 0 END), 0) AS messageable " +
                    "FROM leads l " +
                    "WHERE l.lead_type IN ('HUSBAND_CAPTURED', 'WIFE_CAPTURED') " +
                    "  AND (:path IS NULL OR l.path = :path) " +
                    "  AND l.created_at >= :from AND l.created_at < :to")
    ReferralSummaryProjection summarizeReferralLeads(@Param("path") String path,
                                                     @Param("from") LocalDateTime from,
                                                     @Param("to") LocalDateTime to);

    interface ReferralLeadRowProjection {
        Long getLeadId();
        String getCustomerName();
        String getPhone();               // the customer who shared the contact
        String getPath();
        String getLeadType();            // HUSBAND_CAPTURED / WIFE_CAPTURED
        String getPartnerName();
        String getPartnerPhone();        // null when consent is NO
        LocalDate getPartnerDate();
        String getPartnerDateType();     // ANNIVERSARY / BIRTHDAY
        String getConsent();             // YES / NO
        LocalDateTime getCapturedAt();
    }

    /**
     * Table filter: leadTypes = both types, or one ("Husbands" / "Wives");
     * consentedOnly = true for "Consent: yes".
     */
    String REFERRAL_LEAD_FILTERS =
            "WHERE l.lead_type IN (:leadTypes) " +
                    "  AND (:consentedOnly = FALSE OR l.consent = 'YES') " +
                    "  AND (:path IS NULL OR l.path = :path) " +
                    "  AND l.created_at >= :from AND l.created_at < :to ";

    /** One page of referral leads, newest first. Export uses the same query with a bigger limit. */
    @Query(nativeQuery = true, value =
            "SELECT l.id AS leadId, COALESCE(l.customer_name, c.name) AS customerName, l.phone AS phone, " +
                    "       l.path AS path, l.lead_type AS leadType, l.partner_name AS partnerName, " +
                    "       l.partner_phone AS partnerPhone, l.partner_date AS partnerDate, " +
                    "       l.partner_date_type AS partnerDateType, l.consent AS consent, l.created_at AS capturedAt " +
                    "FROM leads l " +
                    "LEFT JOIN customers c ON c.phone = l.phone " +
                    REFERRAL_LEAD_FILTERS +
                    "ORDER BY l.created_at DESC, l.id DESC " +
                    "LIMIT :limit OFFSET :offset")
    List<ReferralLeadRowProjection> findReferralLeadsPage(@Param("leadTypes") List<String> leadTypes,
                                                          @Param("consentedOnly") boolean consentedOnly,
                                                          @Param("path") String path,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          @Param("limit") int limit,
                                                          @Param("offset") int offset);

    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) FROM leads l " + REFERRAL_LEAD_FILTERS)
    long countReferralLeadsForTable(@Param("leadTypes") List<String> leadTypes,
                                    @Param("consentedOnly") boolean consentedOnly,
                                    @Param("path") String path,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    // ==================================================================
    // AUDIENCES PAGE — no path tabs, date range only
    //
    // "Where they stopped": one row per CUSTOMER — her latest journey (session with a path) started in the
    // window, if it is still open (not CLOSED). Each customer lands in at most one segment, by her path and
    // what she has / hasn't done:
    //   HINT_SENT_NO_VISIT     Wife    — Ishara sent (HINT_SENT), then no tap on "Visit Store" / "Maybe later",
    //                                    no store tap anywhere, no booking
    //   LIST_STARTED_NO_HINT   Wife    — 1+ pieces on her list, no Ishara sent
    //   BROWSING_NO_PURCHASE   Husband — reached the product carousel (2B), no "Buy Online" and no store tap
    //   SHORTLISTED_NO_VISIT   Sparkle — 1+ pieces on her list, no store tap, no booking
    //   OTHER                  — engaged, not finished, but not in one of the four (e.g. still choosing a category)
    //
    // "Completed or opted in": actions taken in the window (bookings / leads by created_at, closed journeys by
    // closed_at).
    // ==================================================================

    /** A tap that heads to a store: "Visit nearby store" / "📍 Visit Store" or "Visit Store" on a product card. */
    String STORE_TAP_ON_SESSION =
            "EXISTS (SELECT 1 FROM messages tap WHERE tap.session_id = s.id AND tap.direction = 'INBOUND' " +
                    "        AND (tap.button_payload = 'BTN_STORE_FINDER' OR LEFT(tap.button_payload, 6) = 'VISIT_'))";

    String AUDIENCE_JOURNEYS =
            "SELECT s.id AS sessionId, c.name AS customerName, s.phone AS phone, s.path AS path, " +
                    "       s.current_step AS currentStep, s.selected_category AS category, s.selected_budget AS budget, " +
                    "       (SELECT COUNT(*) FROM wishlist_items w WHERE w.session_id = s.id) AS piecesOnList, " +
                    "       s.started_at AS startedAt, COALESCE(s.last_inbound_at, s.started_at) AS lastActivity, " +
                    "       CASE " +
                    "         WHEN s.path = 'WIFE' " +
                    "              AND EXISTS (SELECT 1 FROM leads l WHERE l.session_id = s.id AND l.lead_type = 'HINT_SENT') " +
                    "              AND NOT EXISTS (SELECT 1 FROM messages tap WHERE tap.session_id = s.id AND tap.direction = 'INBOUND' " +
                    "                              AND tap.button_payload = 'W_MAYBE_LATER') " +
                    "              AND NOT " + STORE_TAP_ON_SESSION +
                    "              AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.session_id = s.id AND b.status <> 'CANCELLED') " +
                    "           THEN 'HINT_SENT_NO_VISIT' " +
                    "         WHEN s.path = 'WIFE' " +
                    "              AND EXISTS (SELECT 1 FROM wishlist_items w WHERE w.session_id = s.id) " +
                    "              AND NOT EXISTS (SELECT 1 FROM leads l WHERE l.session_id = s.id AND l.lead_type = 'HINT_SENT') " +
                    "           THEN 'LIST_STARTED_NO_HINT' " +
                    "         WHEN s.path = 'HUSBAND' " +
                    "              AND EXISTS (SELECT 1 FROM messages seen WHERE seen.session_id = s.id AND seen.step = 'H_BROWSE_PRODUCTS') " +
                    "              AND NOT EXISTS (SELECT 1 FROM messages tap WHERE tap.session_id = s.id AND tap.direction = 'INBOUND' " +
                    "                              AND tap.button_payload = 'H_BUY_ONLINE') " +
                    "              AND NOT " + STORE_TAP_ON_SESSION +
                    "           THEN 'BROWSING_NO_PURCHASE' " +
                    "         WHEN s.path = 'SPARKLE' " +
                    "              AND EXISTS (SELECT 1 FROM wishlist_items w WHERE w.session_id = s.id) " +
                    "              AND NOT " + STORE_TAP_ON_SESSION +
                    "              AND NOT EXISTS (SELECT 1 FROM bookings b WHERE b.session_id = s.id AND b.status <> 'CANCELLED') " +
                    "           THEN 'SHORTLISTED_NO_VISIT' " +
                    "         ELSE 'OTHER' " +
                    "       END AS segment " +
                    "FROM sessions s " +
                    "LEFT JOIN customers c ON c.id = s.customer_id " +
                    "WHERE s.path IS NOT NULL " +
                    "  AND s.is_active = TRUE AND s.current_step <> 'CLOSED' " +
                    "  AND s.started_at >= :from AND s.started_at < :to " +
                    "  AND s.id = (SELECT MAX(latest.id) FROM sessions latest " +
                    "              WHERE latest.phone = s.phone AND latest.path IS NOT NULL " +
                    "                AND latest.started_at >= :from AND latest.started_at < :to)";

    interface SegmentCountProjection {
        String getSegment();
        Long getTotal();
    }

    /** Engaged-not-finished customers per segment (OTHER included, so the sum = "Engaged, Not Finished"). */
    @Query(nativeQuery = true, value =
            "SELECT journey.segment AS segment, COUNT(*) AS total " +
                    "FROM (" + AUDIENCE_JOURNEYS + ") journey " +
                    "GROUP BY journey.segment")
    List<SegmentCountProjection> countAudienceSegments(@Param("from") LocalDateTime from,
                                                       @Param("to") LocalDateTime to);

    interface AudienceMemberProjection {
        Long getSessionId();
        String getCustomerName();
        String getPhone();
        String getPath();
        String getCurrentStep();
        String getCategory();
        String getBudget();
        Long getPiecesOnList();
        LocalDateTime getStartedAt();
        LocalDateTime getLastActivity();
        String getSegment();
    }

    /** The customers in one "Where they stopped" segment — for its download. */
    @Query(nativeQuery = true, value =
            "SELECT journey.* FROM (" + AUDIENCE_JOURNEYS + ") journey " +
                    "WHERE journey.segment = :segment " +
                    "ORDER BY journey.lastActivity DESC " +
                    "LIMIT :limit")
    List<AudienceMemberProjection> findAudienceSegmentMembers(@Param("segment") String segment,
                                                              @Param("from") LocalDateTime from,
                                                              @Param("to") LocalDateTime to,
                                                              @Param("limit") int limit);

    // ---------- Completed or opted in ----------

    /**
     * Customers who took at least one of these actions in the window: finished a journey (E1),
     * booked a store visit, tapped Buy Online, gave consent for a referred contact, or opted in (Sparkle).
     */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT action.phone) FROM ( " +
                    "  SELECT s.phone AS phone FROM sessions s " +
                    "  WHERE s.current_step = 'CLOSED' AND s.closed_at >= :from AND s.closed_at < :to " +
                    "  UNION ALL " +
                    "  SELECT b.phone FROM bookings b " +
                    "  WHERE b.status <> 'CANCELLED' AND b.created_at >= :from AND b.created_at < :to " +
                    "  UNION ALL " +
                    "  SELECT l.phone FROM leads l " +
                    "  WHERE ((l.lead_type IN ('HUSBAND_CAPTURED', 'WIFE_CAPTURED') AND l.consent = 'YES') " +
                    "         OR l.lead_type IN ('BUY_ONLINE_CLICKED', 'SPARKLE_OPT_IN')) " +
                    "    AND l.created_at >= :from AND l.created_at < :to " +
                    ") action")
    long countCompletedOrOptedInCustomers(@Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** Customers with a confirmed (not cancelled) store visit booked in the window — any path. */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT b.phone) FROM bookings b " +
                    "WHERE b.status <> 'CANCELLED' AND b.created_at >= :from AND b.created_at < :to")
    long countCustomersWithBooking(@Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /** Customers with a lead of one of these types in the window (consentYesOnly = consent 'YES' rows only). */
    @Query(nativeQuery = true, value =
            "SELECT COUNT(DISTINCT l.phone) FROM leads l " +
                    "WHERE l.lead_type IN (:leadTypes) " +
                    "  AND (:consentYesOnly = FALSE OR l.consent = 'YES') " +
                    "  AND l.created_at >= :from AND l.created_at < :to")
    long countCustomersWithLead(@Param("leadTypes") List<String> leadTypes,
                                @Param("consentYesOnly") boolean consentYesOnly,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    interface AudienceLeadRowProjection {
        String getCustomerName();
        String getPhone();
        String getPath();
        String getLeadType();
        String getProductSkus();
        String getProductName();         // when exactly one SKU
        String getPartnerName();
        String getPartnerPhone();
        LocalDate getPartnerDate();
        String getPartnerDateType();
        String getConsent();
        LocalDateTime getCreatedAt();
    }

    /** Lead rows for a "Completed or opted in" download (Buy Online / referred contacts / opt-ins). */
    @Query(nativeQuery = true, value =
            "SELECT COALESCE(l.customer_name, c.name) AS customerName, l.phone AS phone, l.path AS path, " +
                    "       l.lead_type AS leadType, l.product_skus AS productSkus, p.name AS productName, " +
                    "       l.partner_name AS partnerName, l.partner_phone AS partnerPhone, " +
                    "       l.partner_date AS partnerDate, l.partner_date_type AS partnerDateType, " +
                    "       l.consent AS consent, l.created_at AS createdAt " +
                    "FROM leads l " +
                    "LEFT JOIN customers c ON c.phone = l.phone " +
                    "LEFT JOIN products p ON p.sku = l.product_skus " +
                    "WHERE l.lead_type IN (:leadTypes) " +
                    "  AND (:consentYesOnly = FALSE OR l.consent = 'YES') " +
                    "  AND l.created_at >= :from AND l.created_at < :to " +
                    "ORDER BY l.created_at DESC " +
                    "LIMIT :limit")
    List<AudienceLeadRowProjection> findAudienceLeadRows(@Param("leadTypes") List<String> leadTypes,
                                                         @Param("consentYesOnly") boolean consentYesOnly,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("to") LocalDateTime to,
                                                         @Param("limit") int limit);
}
package org.example.karvachauth.serviceImple;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.karvachauth.repository.DashboardRepository;
import org.example.karvachauth.service.DashboardService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.example.karvachauth.constants.KarvaChauthConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImple implements DashboardService {

    private final DashboardRepository dashboardRepo;

    /** Same setting as the bot's idle nudge — a conversation quiet for longer than this shows as "Idle". */
    @Value("${karvachauth.idle.minutes:60}")
    private long idleMinutes;

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final int MAX_ACTIVITY_LIMIT = 50;

    // Message statuses
    private static final List<String> ALL_SENT_STATUSES =
            List.of(MSG_STATUS_SENT, MSG_STATUS_DELIVERED, MSG_STATUS_READ, MSG_STATUS_FAILED);
    private static final List<String> DELIVERED_STATUSES = List.of(MSG_STATUS_DELIVERED, MSG_STATUS_READ);
    private static final List<String> READ_STATUSES = List.of(MSG_STATUS_READ);
    private static final List<String> FAILED_STATUSES = List.of(MSG_STATUS_FAILED);

    // Button payloads the bot records on inbound taps (same strings as BotEngineServiceImple)
    private static final String TAP_WIFE_CHOOSE_MY_GIFT = "W_CHOOSE_MY_GIFT";
    private static final String TAP_WIFE_SEND_ISHARA = "W_SEND_ISHARA";
    private static final String TAP_HUSBAND_FIND_HER_GIFT = "H_FIND_HER_GIFT";
    private static final String TAP_SPARKLE_SHOW_ME = "S_SHOW_ME";
    private static final String TAP_CATEGORY_PREFIX = "CAT_";
    private static final String TAP_HUSBAND_BUDGET_PREFIX = "H_BUDGET_";
    private static final String TAP_SPARKLE_BUDGET_PREFIX = "S_BUDGET_";
    private static final String TAP_HUSBAND_BUY_PREFIX = "BUY_";
    private static final String TAP_PRODUCT_VISIT_PREFIX = "VISIT_";

    // Product carousel step per path
    private static final List<String> ALL_CAROUSEL_STEPS =
            List.of(STEP_W_BROWSE_PRODUCTS, STEP_H_BROWSE_PRODUCTS, STEP_S_BROWSE_PRODUCTS);

    // Product pick actions
    private static final List<String> ADDED_ACTION = List.of(PICK_ADDED);
    private static final List<String> CHOSE_PIECE_ACTIONS = List.of(PICK_ADDED, PICK_SELECTED);

    // Referral leads (partner details captured)
    private static final List<String> ALL_REFERRAL_TYPES = List.of(LEAD_HUSBAND_CAPTURED, LEAD_WIFE_CAPTURED);
    private static final List<String> HUSBAND_REFERRAL = List.of(LEAD_HUSBAND_CAPTURED);
    private static final List<String> WIFE_REFERRAL = List.of(LEAD_WIFE_CAPTURED);

    // ==================================================================
    // METRIC CARDS
    // ==================================================================

    @Override
    public Map<String, Object> getMetricCards(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);

        long sent = dashboardRepo.countOutboundMessagesByStatus(ALL_SENT_STATUSES, pathType, window.from(), window.to());
        long delivered = dashboardRepo.countOutboundMessagesByStatus(DELIVERED_STATUSES, pathType, window.from(), window.to());
        long read = dashboardRepo.countOutboundMessagesByStatus(READ_STATUSES, pathType, window.from(), window.to());
        long failed = dashboardRepo.countOutboundMessagesByStatus(FAILED_STATUSES, pathType, window.from(), window.to());
        long openerTaps = dashboardRepo.countOpenerTaps(pathType, window.from(), window.to());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messagesSent", sent);
        data.put("messagesPerDay", Math.round((double) sent / window.numberOfDays()));
        data.put("messagesDelivered", delivered);
        data.put("messagesFailed", failed);
        data.put("deliveryRate", pct(delivered, sent));
        data.put("readReceipts", read);
        data.put("openRate", pct(read, delivered));
        data.put("openerTaps", openerTaps);
        data.put("clickRate", pct(openerTaps, delivered));
        data.put("flow", normaliseFlow(flow));
        data.put("range", normaliseRange(range));
        return data;
    }

    // ==================================================================
    // OUTCOMES
    // An outcome that doesn't exist on the selected path comes back with total = null,
    // so the card can show "—" (e.g. Buy-online clicks on "I'm celebrating").
    // ==================================================================

    @Override
    public Map<String, Object> getOutcomes(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        boolean allPaths = pathType == null;

        boolean hasReferrals = !TEMPLATE_SPARKLE.equals(pathType);
        boolean hasIshara = allPaths || TEMPLATE_WIFE.equals(pathType);
        boolean hasBuyOnline = allPaths || TEMPLATE_HUSBAND.equals(pathType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("referralLeads", hasReferrals ? buildReferralOutcome(pathType, window) : notApplicable());
        result.put("isharaSent", hasIshara ? buildOutcome(LEAD_HINT_SENT, pathType, window) : notApplicable());
        result.put("storeVisitsBooked", buildOutcome(LEAD_STORE_VISIT_BOOKED, pathType, window));
        result.put("buyOnlineClicks", hasBuyOnline ? buildOutcome(LEAD_BUY_ONLINE_CLICKED, pathType, window) : notApplicable());
        result.put("flow", normaliseFlow(flow));
        result.put("range", normaliseRange(range));
        return result;
    }

    private Map<String, Object> buildReferralOutcome(String pathType, DateWindow window) {
        long husbandsReferred = dashboardRepo.countReferralLeads(HUSBAND_REFERRAL, pathType, window.from(), window.to());
        long wivesReferred = dashboardRepo.countReferralLeads(WIFE_REFERRAL, pathType, window.from(), window.to());

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("total", husbandsReferred + wivesReferred);
        outcome.put("husbandsReferred", husbandsReferred);
        outcome.put("wivesReferred", wivesReferred);
        outcome.put("trend", buildDailyTrend(
                dashboardRepo.findDailyReferralCounts(ALL_REFERRAL_TYPES, pathType, window.from(), window.to()), window));
        return outcome;
    }

    private Map<String, Object> buildOutcome(String leadType, String pathType, DateWindow window) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("total", dashboardRepo.countLeadsByType(leadType, pathType, window.from(), window.to()));
        outcome.put("trend", buildDailyTrend(
                dashboardRepo.findDailyLeadCounts(leadType, pathType, window.from(), window.to()), window));
        return outcome;
    }

    private Map<String, Object> notApplicable() {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("total", null);
        outcome.put("trend", List.of());
        return outcome;
    }

    /** One point per day in the window — days with no leads get 0 so the sparkline has no gaps. */
    private List<Map<String, Object>> buildDailyTrend(List<DashboardRepository.DailyCountProjection> dailyCounts,
                                                      DateWindow window) {
        Map<String, Long> countByDay = new HashMap<>();
        for (DashboardRepository.DailyCountProjection dailyCount : dailyCounts) {
            countByDay.put(dailyCount.getDay(), valueOrZero(dailyCount.getTotal()));
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (LocalDate day = window.from().toLocalDate(); day.isBefore(window.to().toLocalDate()); day = day.plusDays(1)) {
            String dayKey = day.format(ISO_DATE);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", dayKey);
            point.put("count", countByDay.getOrDefault(dayKey, 0L));
            trend.add(point);
        }
        return trend;
    }

    // ==================================================================
    // BOT FLOW DROP-OFF
    // Follows the sessions that STARTED in the window, so every stage is a share of "Opener".
    // Each path has its own stages, matching its journey in the flow script.
    // ==================================================================

    @Override
    public Map<String, Object> getFlowDropOff(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        long openerTaps = dashboardRepo.countOpenerTaps(pathType, window.from(), window.to());

        List<Map<String, Object>> stages;
        if (pathType == null) {
            stages = buildAllPathsStages(window, openerTaps);
        } else {
            stages = switch (pathType) {
                case TEMPLATE_WIFE -> buildWifeStages(window, openerTaps);
                case TEMPLATE_HUSBAND -> buildHusbandStages(window, openerTaps);
                default -> buildSparkleStages(window, openerTaps);
            };
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stages", stages);
        result.put("flow", normaliseFlow(flow));
        result.put("range", normaliseRange(range));
        return result;
    }

    /** "I'm celebrating" — no budget step on this path. */
    private List<Map<String, Object>> buildWifeStages(DateWindow window, long openerTaps) {
        String wife = TEMPLATE_WIFE;
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(buildStage("OPENER", "Opener", openerTaps, openerTaps));
        stages.add(buildStage("CHOOSE_MY_GIFT", "Choose My Gift",
                dashboardRepo.countSessionsWithButtonTap(TAP_WIFE_CHOOSE_MY_GIFT, wife, from, to), openerTaps));
        stages.add(buildStage("CATEGORY", "Category",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_CATEGORY_PREFIX, wife, from, to), openerTaps));
        stages.add(buildStage("CAROUSEL", "Carousel",
                dashboardRepo.countSessionsReachedStep(List.of(STEP_W_BROWSE_PRODUCTS), wife, from, to), openerTaps));
        stages.add(buildStage("ADDED_TO_LIST", "Added to List",
                dashboardRepo.countSessionsWithPickAction(ADDED_ACTION, wife, from, to), openerTaps));
        stages.add(buildStage("ISHARA_CREATED", "Ishara Created",
                dashboardRepo.countSessionsWithButtonTap(TAP_WIFE_SEND_ISHARA, wife, from, to), openerTaps));
        stages.add(buildStage("ISHARA_SENT", "Ishara Sent",
                dashboardRepo.countSessionsWithLead(List.of(LEAD_HINT_SENT), wife, from, to), openerTaps));
        stages.add(buildStage("HIS_DETAILS", "His Details",
                dashboardRepo.countSessionsReachedStep(List.of(STEP_W_CONSENT), wife, from, to), openerTaps));
        stages.add(buildStage("CONSENT", "Consent",
                dashboardRepo.countSessionsWithReferralLead(HUSBAND_REFERRAL, wife, from, to), openerTaps));
        return stages;
    }

    /**
     * "Shopping for her" — category → budget → products → buy or visit → her details.
     * Buy / Visit = "Buy this for her" or "Visit nearby store" on a product card.
     * Her Details Ask = 2E sent ("shall we remember her birthday?"); Her Details = name, birthday and
     * mobile given (consent asked); Consent = she agreed, mobile kept.
     */
    private List<Map<String, Object>> buildHusbandStages(DateWindow window, long openerTaps) {
        String husband = TEMPLATE_HUSBAND;
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(buildStage("OPENER", "Opener", openerTaps, openerTaps));
        stages.add(buildStage("FIND_HER_GIFT", "Find Her a Gift",
                dashboardRepo.countSessionsWithButtonTap(TAP_HUSBAND_FIND_HER_GIFT, husband, from, to), openerTaps));
        stages.add(buildStage("CATEGORY", "Category",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_CATEGORY_PREFIX, husband, from, to), openerTaps));
        stages.add(buildStage("BUDGET", "Budget",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_HUSBAND_BUDGET_PREFIX, husband, from, to), openerTaps));
        stages.add(buildStage("CAROUSEL", "Carousel",
                dashboardRepo.countSessionsReachedStep(List.of(STEP_H_BROWSE_PRODUCTS), husband, from, to), openerTaps));
        stages.add(buildStage("BUY_OR_VISIT", "Buy / Visit",
                dashboardRepo.countSessionsWithEitherButtonTapPrefix(TAP_HUSBAND_BUY_PREFIX, TAP_PRODUCT_VISIT_PREFIX,
                        husband, from, to), openerTaps));
        stages.add(buildStage("HER_DETAILS_ASK", "Her Details Ask",
                dashboardRepo.countSessionsReachedStep(List.of(STEP_H_CAP_DETAILS), husband, from, to), openerTaps));
        stages.add(buildStage("HER_DETAILS", "Her Details",
                dashboardRepo.countSessionsReachedStep(List.of(STEP_H_CONSENT), husband, from, to), openerTaps));
        stages.add(buildStage("CONSENT", "Consent",
                dashboardRepo.countSessionsWithReferralLead(WIFE_REFERRAL, husband, from, to), openerTaps));
        return stages;
    }

    /** "Here for the sparkle" — no Ishara and no partner details on this path. */
    private List<Map<String, Object>> buildSparkleStages(DateWindow window, long openerTaps) {
        String sparkle = TEMPLATE_SPARKLE;
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(buildStage("OPENER", "Opener", openerTaps, openerTaps));
        stages.add(buildStage("SHOW_ME", "Show Me",
                dashboardRepo.countSessionsWithButtonTap(TAP_SPARKLE_SHOW_ME, sparkle, from, to), openerTaps));
        stages.add(buildStage("CATEGORY", "Category",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_CATEGORY_PREFIX, sparkle, from, to), openerTaps));
        stages.add(buildStage("BUDGET", "Budget",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_SPARKLE_BUDGET_PREFIX, sparkle, from, to), openerTaps));
        stages.add(buildStage("CAROUSEL", "Carousel",
                dashboardRepo.countSessionsReachedStep(List.of(STEP_S_BROWSE_PRODUCTS), sparkle, from, to), openerTaps));
        stages.add(buildStage("ADDED_TO_LIST", "Added to List",
                dashboardRepo.countSessionsWithPickAction(ADDED_ACTION, sparkle, from, to), openerTaps));
        stages.add(buildStage("STORE_VISIT_BOOKED", "Store Visit Booked",
                dashboardRepo.countSessionsWithLead(List.of(LEAD_STORE_VISIT_BOOKED), sparkle, from, to), openerTaps));
        stages.add(buildStage("KEEP_ME_POSTED", "Keep Me Posted",
                dashboardRepo.countSessionsWithLead(List.of(LEAD_SPARKLE_OPT_IN), sparkle, from, to), openerTaps));
        return stages;
    }

    /**
     * "All paths" — the shared stages. Budget is asked only on the Husband and Sparkle paths,
     * and the Ishara only on the Wife path, so those two bars are a share of ALL opener taps.
     */
    private List<Map<String, Object>> buildAllPathsStages(DateWindow window, long openerTaps) {
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        // A session belongs to one path, so the two budget counts never overlap
        long budget = dashboardRepo.countSessionsWithButtonTapPrefix(TAP_HUSBAND_BUDGET_PREFIX, TEMPLATE_HUSBAND, from, to)
                + dashboardRepo.countSessionsWithButtonTapPrefix(TAP_SPARKLE_BUDGET_PREFIX, TEMPLATE_SPARKLE, from, to);

        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(buildStage("OPENER_TAPPED", "Opener Tapped", openerTaps, openerTaps));
        stages.add(buildStage("CATEGORY", "Category",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_CATEGORY_PREFIX, null, from, to), openerTaps));
        stages.add(buildStage("BUDGET", "Budget", budget, openerTaps));
        stages.add(buildStage("CAROUSEL", "Carousel",
                dashboardRepo.countSessionsReachedStep(ALL_CAROUSEL_STEPS, null, from, to), openerTaps));
        stages.add(buildStage("CHOSE_PIECE", "Added / Chose Piece",
                dashboardRepo.countSessionsWithPickAction(CHOSE_PIECE_ACTIONS, null, from, to), openerTaps));
        stages.add(buildStage("ISHARA_SENT", "Ishara Sent",
                dashboardRepo.countSessionsWithLead(List.of(LEAD_HINT_SENT), null, from, to), openerTaps));
        stages.add(buildStage("STORE_VISIT_BOOKED", "Store Visit Booked",
                dashboardRepo.countSessionsWithLead(List.of(LEAD_STORE_VISIT_BOOKED), null, from, to), openerTaps));
        stages.add(buildStage("REFERRAL_LEADS", "Referral Leads",
                dashboardRepo.countSessionsWithReferralLead(ALL_REFERRAL_TYPES, null, from, to), openerTaps));
        return stages;
    }

    private Map<String, Object> buildStage(String step, String label, long count, long openerTaps) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("step", step);
        stage.put("label", label);
        stage.put("count", count);
        stage.put("pct", pctTwoDecimals(count, openerTaps));
        return stage;
    }

    // ==================================================================
    // LIVE ACTIVITY
    // ==================================================================

    @Override
    public Map<String, Object> getActivity(String flow, String range, String startDate, String endDate, int limit) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        int safeLimit = Math.min(Math.max(limit, 1), MAX_ACTIVITY_LIMIT);

        List<DashboardRepository.ActivityRowProjection> rows =
                dashboardRepo.findRecentActivity(CHOSE_PIECE_ACTIONS, pathType, window.from(), window.to(), safeLimit);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> events = new ArrayList<>();
        for (DashboardRepository.ActivityRowProjection row : rows) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("badge", activityBadge(row));
            event.put("summary", summarizeActivityRow(row));
            event.put("path", row.getPath());
            event.put("timestamp", row.getEventTime());
            event.put("minutesAgo", row.getEventTime() == null ? null : ChronoUnit.MINUTES.between(row.getEventTime(), now));
            events.add(event);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", events);
        result.put("flow", normaliseFlow(flow));
        return result;
    }

    private String activityBadge(DashboardRepository.ActivityRowProjection row) {
        if ("PICK".equals(row.getSource()) || "OPENER_TAP".equals(row.getSource())) {
            return "MSG";
        }
        return switch (row.getEventType()) {
            case LEAD_HUSBAND_CAPTURED, LEAD_WIFE_CAPTURED -> "REFERRAL";
            case LEAD_HINT_SENT -> "ISHARA";
            case LEAD_STORE_VISIT_BOOKED -> "VISIT";
            case LEAD_BUY_ONLINE_CLICKED -> "BUY";
            case LEAD_SPARKLE_OPT_IN -> "OPT-IN";
            case LEAD_SPARKLE_OPT_OUT -> "OPT-OUT";
            default -> "LEAD";
        };
    }

    private String summarizeActivityRow(DashboardRepository.ActivityRowProjection row) {
        String customer = displayName(row.getCustomerName(), row.getPhone());

        if ("OPENER_TAP".equals(row.getSource())) {
            return customer + " tapped “" + openerButtonLabel(row.getPath()) + "”";
        }
        if ("PICK".equals(row.getSource())) {
            String piece = hasText(row.getProductName()) ? row.getProductName() : row.getSku();
            return PICK_ADDED.equals(row.getEventType())
                    ? customer + " added " + piece + " to her list"
                    : customer + " picked " + piece;
        }

        String mobileText = hasText(row.getPartnerPhone()) ? " + mobile" : "";
        String consentText = consentText(row.getConsent());
        String piece = hasText(row.getProductName()) ? row.getProductName() : row.getSku();

        return switch (row.getEventType()) {
            case LEAD_HUSBAND_CAPTURED -> customer + " referred her husband · anniversary" + mobileText + consentText;
            case LEAD_WIFE_CAPTURED -> customer + " referred his wife · birthday" + mobileText + consentText;
            case LEAD_HINT_SENT -> customer + " sent her Ishara to her husband" + pieceCountText(row.getSku());
            case LEAD_STORE_VISIT_BOOKED -> customer + " booked a visit" + visitText(row);
            case LEAD_BUY_ONLINE_CLICKED -> customer + " tapped Buy Online" + (hasText(piece) ? " · " + piece : "");
            case LEAD_SPARKLE_OPT_IN -> customer + " asked to be kept posted";
            case LEAD_SPARKLE_OPT_OUT -> customer + " said no to updates";
            default -> customer + " · " + row.getEventType();
        };
    }

    /** " (2 pieces)" from the Ishara's comma-separated SKUs. */
    private String pieceCountText(String skus) {
        if (!hasText(skus)) return "";
        long pieces = Arrays.stream(skus.split(",")).filter(this::hasText).count();
        return " (" + pieces + (pieces == 1 ? " piece)" : " pieces)");
    }

    /** " · Mia, Select Citywalk – Saket, Tomorrow 6:30 pm" — store, day and slot from the booking. */
    private String visitText(DashboardRepository.ActivityRowProjection row) {
        List<String> parts = new ArrayList<>();
        if (hasText(row.getStoreName())) parts.add(row.getStoreName());
        String when = (visitDayText(row.getVisitDate()) + " " + (row.getTimeSlot() == null ? "" : row.getTimeSlot())).trim();
        if (!when.isEmpty()) parts.add(when);
        return parts.isEmpty() ? "" : " · " + String.join(", ", parts);
    }

    /** Today / Tomorrow / "12 Oct". */
    private String visitDayText(LocalDate visitDate) {
        if (visitDate == null) return "";
        LocalDate today = LocalDate.now();
        if (visitDate.equals(today)) return "Today";
        if (visitDate.equals(today.plusDays(1))) return "Tomorrow";
        return visitDate.format(DAY_MONTH);
    }

    /** The Step 0 button she tapped, by the path it started. */
    private String openerButtonLabel(String path) {
        if (path == null) return "the opener";
        return switch (path) {
            case TEMPLATE_WIFE -> "I'm celebrating";
            case TEMPLATE_HUSBAND -> "Shopping for her";
            case TEMPLATE_SPARKLE -> "Here for the sparkle";
            default -> path;
        };
    }

    private String consentText(String consent) {
        if (CONSENT_YES.equalsIgnoreCase(consent)) return ", consent yes";
        if (CONSENT_NO.equalsIgnoreCase(consent)) return ", consent no";
        return "";
    }

    /** Customer's name if we have it, otherwise a masked phone number. */
    private String displayName(String customerName, String phone) {
        if (hasText(customerName)) return customerName;
        if (phone == null || phone.length() < 4) return "A customer";
        return "******" + phone.substring(phone.length() - 4);
    }

    // ==================================================================
    // THE THREE PATHS
    // ==================================================================

    @Override
    public Map<String, Object> getPathsSummary(String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        Map<String, Long> openerTapsByPath = new HashMap<>();
        for (DashboardRepository.PathCountProjection pathCount : dashboardRepo.countOpenerTapsByPath(from, to)) {
            openerTapsByPath.put(pathCount.getPath(), valueOrZero(pathCount.getTotal()));
        }
        long totalOpenerTaps = openerTapsByPath.values().stream().mapToLong(Long::longValue).sum();

        // I'm celebrating (Wife)
        Map<String, Object> celebrating = buildPathCard("celebrating",
                openerTapsByPath.getOrDefault(TEMPLATE_WIFE, 0L), totalOpenerTaps);
        celebrating.put("isharaSent", dashboardRepo.countLeadsByType(LEAD_HINT_SENT, TEMPLATE_WIFE, from, to));
        celebrating.put("visitsBooked", dashboardRepo.countLeadsByType(LEAD_STORE_VISIT_BOOKED, TEMPLATE_WIFE, from, to));
        celebrating.put("husbandsReferred", dashboardRepo.countReferralLeads(HUSBAND_REFERRAL, TEMPLATE_WIFE, from, to));

        // Shopping for her (Husband)
        Map<String, Object> shopping = buildPathCard("shopping",
                openerTapsByPath.getOrDefault(TEMPLATE_HUSBAND, 0L), totalOpenerTaps);
        shopping.put("buyOnlineClicks", dashboardRepo.countLeadsByType(LEAD_BUY_ONLINE_CLICKED, TEMPLATE_HUSBAND, from, to));
        shopping.put("visitsBooked", dashboardRepo.countLeadsByType(LEAD_STORE_VISIT_BOOKED, TEMPLATE_HUSBAND, from, to));
        shopping.put("wivesReferred", dashboardRepo.countReferralLeads(WIFE_REFERRAL, TEMPLATE_HUSBAND, from, to));

        // Here for the sparkle
        Map<String, Object> sparkle = buildPathCard("sparkle",
                openerTapsByPath.getOrDefault(TEMPLATE_SPARKLE, 0L), totalOpenerTaps);
        sparkle.put("piecesShortlisted", dashboardRepo.countPiecesByPickAction(PICK_ADDED, TEMPLATE_SPARKLE, from, to));
        sparkle.put("visitsBooked", dashboardRepo.countLeadsByType(LEAD_STORE_VISIT_BOOKED, TEMPLATE_SPARKLE, from, to));
        sparkle.put("keepMePosted", dashboardRepo.countLeadsByType(LEAD_SPARKLE_OPT_IN, TEMPLATE_SPARKLE, from, to));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalOpenerTaps", totalOpenerTaps);
        result.put("paths", List.of(celebrating, shopping, sparkle));
        result.put("range", normaliseRange(range));
        return result;
    }

    private Map<String, Object> buildPathCard(String flow, long openerTaps, long totalOpenerTaps) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("flow", flow);
        card.put("openerTaps", openerTaps);
        card.put("share", pct(openerTaps, totalOpenerTaps));
        return card;
    }

    // ==================================================================
    // CATEGORIES PICKED / BUDGET CHOSEN
    // Distinct customers per category / budget row, from taps made in the window.
    // pct = that row's share of all the picks in the panel, so the rows add up to 100.
    // ==================================================================

    /** Carousel code in the button payload (CAT_PEND) → category value — same codes as the bot. */
    private static final Map<String, String> CATEGORY_BY_CODE = orderedMap(
            "PEND", CAT_PENDANTS,
            "MSUT", CAT_MIA_SUTRA,
            "PNCH", CAT_PENDANT_CHAIN,
            "NECK", CAT_NECKLACES,
            "EARR", CAT_EARRINGS,
            "RING", CAT_RINGS,
            "BRBG", CAT_BRACELETS_BANGLES);

    @Override
    public Map<String, Object> getCategoryAndBudgetPicks(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);

        // ---- Categories Picked ----
        Map<String, Long> customersByCategory = new HashMap<>();
        for (DashboardRepository.ChoiceCountProjection row
                : dashboardRepo.countCustomersByCategory(pathType, window.from(), window.to())) {
            String category = CATEGORY_BY_CODE.get(row.getChoice());
            if (category != null) customersByCategory.put(category, valueOrZero(row.getCustomers()));
        }
        // Mia Sutra is never offered on the Sparkle path, so it isn't listed there
        List<String> categoriesShown = CATEGORY_LABELS.keySet().stream()
                .filter(category -> !(TEMPLATE_SPARKLE.equals(pathType) && CAT_MIA_SUTRA.equals(category)))
                .toList();

        // ---- Budget Chosen ---- (the Wife path has no budget step → every row is 0)
        boolean budgetApplicable = !TEMPLATE_WIFE.equals(pathType);
        Map<String, Long> customersByBudget = new HashMap<>();
        if (budgetApplicable) {
            for (DashboardRepository.ChoiceCountProjection row
                    : dashboardRepo.countCustomersByBudget(pathType, window.from(), window.to())) {
                if (BUDGET_LABELS.containsKey(row.getChoice())) {      // older bands are left out
                    customersByBudget.put(row.getChoice(), valueOrZero(row.getCustomers()));
                }
            }
        }

        Map<String, Object> budgets = buildChoicePanel(new ArrayList<>(BUDGET_LABELS.keySet()), BUDGET_LABELS, customersByBudget);
        budgets.put("applicable", budgetApplicable);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", buildChoicePanel(categoriesShown, CATEGORY_LABELS, customersByCategory));
        result.put("budgets", budgets);
        result.put("flow", normaliseFlow(flow));
        result.put("range", normaliseRange(range));
        return result;
    }

    /** One panel: a row per option (in display order, 0 when nobody picked it) + the panel total. */
    private Map<String, Object> buildChoicePanel(List<String> keys, Map<String, String> labels, Map<String, Long> customersByKey) {
        long total = keys.stream().mapToLong(key -> customersByKey.getOrDefault(key, 0L)).sum();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String key : keys) {
            long customers = customersByKey.getOrDefault(key, 0L);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", labels.get(key));
            row.put("customers", customers);
            row.put("pct", pct(customers, total));
            rows.add(row);
        }

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("total", total);
        panel.put("rows", rows);
        return panel;
    }

    // ==================================================================
    // CONVERSATIONS PAGE
    // Rows are the sessions STARTED in the window that she began by tapping a Step 0 button —
    // the same sessions as "Opener" on the Overview funnel. A template she never tapped isn't shown.
    // ==================================================================

    private static final int CONVERSATIONS_PAGE_SIZE = 50;
    /** "Export all" has no row limit — the queries share LIMIT with the paged tables, so this means every row. */
    private static final int ALL_ROWS = Integer.MAX_VALUE;
    /** IN (:steps) can't be an empty list; this placeholder is ignored when filterByStep = false. */
    private static final List<String> NO_STEP_FILTER = List.of("-");
    private static final int MAX_SEARCH_LENGTH = 50;
    private static final DateTimeFormatter EXPORT_DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    /** Export columns, in order. "Budget" is dropped on the "I'm celebrating" path (see budgetShownFor). */
    private static final List<String> EXPORT_COLUMNS = List.of(
            "Customer", "Phone", "Path", "Step", "Status", "Category", "Budget", "Started At", "Last Activity");

    /** Column header → value the repository sorts on (see CONVERSATION_SORT_VALUE). */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "customer", "customer",
            "phone", "phone",
            "path", "path",
            "step", "step",
            "category", "category",
            "budget", "budget",
            "lastactivity", "lastActivity");

    private static final Map<String, String> CATEGORY_LABELS = orderedMap(
            CAT_PENDANTS, "Pendants",
            CAT_MIA_SUTRA, "Mia Sutra",
            CAT_PENDANT_CHAIN, "Pendant & Chain",
            CAT_NECKLACES, "Necklaces",
            CAT_EARRINGS, "Earrings",
            CAT_RINGS, "Rings",
            CAT_BRACELETS_BANGLES, "Bracelets & Bangles");

    private static final Map<String, String> BUDGET_LABELS = orderedMap(
            BUDGET_UNDER_20K, "Less than ₹20,000",
            BUDGET_20_50K, "₹20,000 – ₹50,000",
            BUDGET_ABOVE_50K, "Above ₹50,000");

    /**
     * The flow script's step codes — the same codes as the frontend's Step filter and the
     * comments in KarvaChauthConstants (W_PICK_CATEGORY //1A, H_BUDGET //2A-BUDGET, …).
     * Nothing in the bot changes: this only maps the bot's step values to the script codes.
     *
     * The Wife and Sparkle journeys share codes (1A, 1B, 1C, 1D, 1I), so filtering by "1A"
     * matches both; the path filter narrows it down. Labels can differ per path
     * (1I is "Reminder Offer" for the Wife, "Keep Me Posted" for Sparkle).
     *
     *   0  · Opener — tapped a Step 0 button, but not yet "Choose my gift" / "Find her a gift" / "Show me".
     *   C2 · Store Visit — tapped "Visit nearby store" (the booking form is sent here once it's built).
     *   E1 · Closed — finished journeys (current step CLOSED). A finished row still shows the
     *                 step it ended on (e.g. 1K · Consent), with status "Completed".
     *
     * When the bot gets a new step, add it here (and to the sort order in CONVERSATION_SORT_VALUE).
     */
    private record ScriptStep(String code, String label, List<String> steps) {
    }

    private static final List<ScriptStep> SCRIPT_STEPS = List.of(
            new ScriptStep("0", "Opener", List.of(STEP_OPENER)),
            // I'm celebrating (Wife)
            new ScriptStep("1", "Choose My Gift", List.of(STEP_W_CHOOSE_MY_GIFT)),
            new ScriptStep("1A", "Category", List.of(STEP_W_PICK_CATEGORY)),
            new ScriptStep("1B", "Carousel", List.of(STEP_W_BROWSE_PRODUCTS)),
            new ScriptStep("1C", "Added to List", List.of(STEP_W_ADDED_TO_LIST)),
            new ScriptStep("1D", "Adding More", List.of(STEP_W_ADD_MORE)),
            new ScriptStep("1G", "Ishara Created", List.of(STEP_W_HINT_READY)),
            new ScriptStep("1H", "Ishara Sent", List.of(STEP_W_STORE_INVITE)),
            new ScriptStep("1I", "Reminder Offer", List.of(STEP_W_REINFORCE)),
            new ScriptStep("1I-nudge", "Reminder Nudge", List.of(STEP_W_REINFORCE_NUDGE)),
            new ScriptStep("1J", "His Details", List.of(STEP_W_CAPH_NAME, STEP_W_CAPH_ANNIVERSARY, STEP_W_CAPH_MOBILE)),
            new ScriptStep("1K", "Consent", List.of(STEP_W_CONSENT)),
            // Shopping for her (Husband)
            new ScriptStep("2", "Find Her a Gift", List.of(STEP_H_FIND_HER_GIFT)),
            new ScriptStep("2A", "Category", List.of(STEP_H_PICK_CATEGORY)),
            new ScriptStep("2A-Budget", "Budget", List.of(STEP_H_BUDGET)),
            new ScriptStep("2B", "Carousel", List.of(STEP_H_BROWSE_PRODUCTS)),
            new ScriptStep("2C", "Buy / Visit", List.of(STEP_H_CONFIRM_CHOICE)),
            new ScriptStep("2E", "Her Details Ask", List.of(STEP_H_CAP_DETAILS)),
            new ScriptStep("2F", "Her Details", List.of(STEP_H_CAPW_NAME, STEP_H_CAPW_BIRTHDAY, STEP_H_CAPW_MOBILE)),
            new ScriptStep("2G", "Consent", List.of(STEP_H_CONSENT)),
            // Here for the sparkle
            new ScriptStep("1-Sparkle", "Show Me", List.of(STEP_S_SHOW_ME)),
            new ScriptStep("1A", "Category", List.of(STEP_S_PICK_CATEGORY)),
            new ScriptStep("1A-Budget", "Budget", List.of(STEP_S_BUDGET)),
            new ScriptStep("1B", "Carousel", List.of(STEP_S_BROWSE_PRODUCTS)),
            new ScriptStep("1C", "Added to List", List.of(STEP_S_ADDED_TO_LIST)),
            new ScriptStep("1D", "Adding More", List.of(STEP_S_ADD_MORE)),
            new ScriptStep("1I", "Keep Me Posted", List.of(STEP_S_REINFORCE)),
            // Shared
            new ScriptStep("C2", "Store Visit", List.of(STEP_BOOK_STORE_VISIT)),
            new ScriptStep("E1", "Closed", List.of(STEP_CLOSED)));

    /** Bot step value → its script step, for the table's Step column. */
    private static final Map<String, ScriptStep> SCRIPT_STEP_BY_BOT_STEP = buildScriptStepByBotStep();

    private static Map<String, ScriptStep> buildScriptStepByBotStep() {
        Map<String, ScriptStep> scriptStepByBotStep = new HashMap<>();
        for (ScriptStep scriptStep : SCRIPT_STEPS) {
            for (String botStep : scriptStep.steps()) {
                scriptStepByBotStep.put(botStep, scriptStep);
            }
        }
        return scriptStepByBotStep;
    }

    /** All the table filters, checked and turned into query parameters. */
    private record ConversationQuery(String pathType, DateWindow window, String category,
                                     boolean filterByStep, List<String> steps,
                                     String searchName, String searchPhone,
                                     String sortColumn, String sortDirection) {
    }

    // ---------- summary cards ----------

    /**
     * Total Sessions = every row the table shows for this path and range (with no table filters),
     * so it always matches "Showing N sessions" — and equals "Opener" on the Overview.
     */
    @Override
    public Map<String, Object> getConversationsSummary(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSessions",
                dashboardRepo.countConversations(pathType, from, to, null, false, NO_STEP_FILTER, null, ""));
        data.put("enteredDiscovery",
                dashboardRepo.countSessionsWithButtonTapPrefix(TAP_CATEGORY_PREFIX, pathType, from, to));
        data.put("carouselReached",
                dashboardRepo.countSessionsReachedStep(carouselStepsFor(pathType), pathType, from, to));
        // The Ishara exists only on the Wife path, so this is 0 on "Shopping for her" / "Here for the sparkle"
        data.put("isharaSent", dashboardRepo.countSessionsWithLead(List.of(LEAD_HINT_SENT), pathType, from, to));
        data.put("visitsBooked",
                dashboardRepo.countSessionsWithLead(List.of(LEAD_STORE_VISIT_BOOKED), pathType, from, to));
        data.put("flow", normaliseFlow(flow));
        data.put("range", normaliseRange(range));
        return data;
    }

    private List<String> carouselStepsFor(String pathType) {
        if (pathType == null) return ALL_CAROUSEL_STEPS;
        return switch (pathType) {
            case TEMPLATE_WIFE -> List.of(STEP_W_BROWSE_PRODUCTS);
            case TEMPLATE_HUSBAND -> List.of(STEP_H_BROWSE_PRODUCTS);
            default -> List.of(STEP_S_BROWSE_PRODUCTS);
        };
    }

    // ---------- the table ----------

    @Override
    public Map<String, Object> getConversations(ConversationFilters filters, int page) {
        ConversationQuery query = buildConversationQuery(filters);
        int safePage = Math.max(page, 0);
        boolean showBudget = budgetShownFor(query.pathType());

        long totalElements = countConversations(query);
        List<DashboardRepository.ConversationRowProjection> rows =
                findConversations(query, CONVERSATIONS_PAGE_SIZE, safePage * CONVERSATIONS_PAGE_SIZE);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> tableRows = new ArrayList<>();
        for (DashboardRepository.ConversationRowProjection row : rows) {
            tableRows.add(toTableRow(row, now, showBudget));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", tableRows);
        result.put("showBudget", showBudget);
        result.put("page", safePage);
        result.put("size", CONVERSATIONS_PAGE_SIZE);
        result.put("totalElements", totalElements);
        result.put("totalPages", totalPages(totalElements));
        result.put("flow", normaliseFlow(filters.flow()));
        result.put("range", normaliseRange(filters.range()));
        result.put("sort", query.sortColumn());
        result.put("direction", query.sortDirection());
        return result;
    }

    /**
     * One table row. On "I'm celebrating" there is no budget at all, so the budget fields are left out;
     * on the other tabs they are present, and null for Wife rows (shown blank).
     */
    private Map<String, Object> toTableRow(DashboardRepository.ConversationRowProjection row,
                                           LocalDateTime now, boolean showBudget) {
        ScriptStep scriptStep = SCRIPT_STEP_BY_BOT_STEP.get(row.getStep());

        Map<String, Object> tableRow = new LinkedHashMap<>();
        tableRow.put("sessionId", row.getSessionId());
        tableRow.put("customerName", displayName(row.getCustomerName(), row.getPhone()));
        tableRow.put("phone", maskPhone(row.getPhone()));
        tableRow.put("flow", pathToFlow(row.getPath()));
        tableRow.put("pathLabel", pathLabel(row.getPath()));
        tableRow.put("stepCode", scriptStep == null ? null : scriptStep.code());
        tableRow.put("stepLabel", scriptStep == null ? row.getStep() : scriptStep.label());
        tableRow.put("status", conversationStatus(row, now));
        tableRow.put("category", row.getCategory());
        tableRow.put("categoryLabel", CATEGORY_LABELS.get(row.getCategory()));
        if (showBudget) {
            tableRow.put("budget", row.getBudget());
            tableRow.put("budgetLabel", BUDGET_LABELS.get(row.getBudget()));
        }
        tableRow.put("startedAt", row.getStartedAt());
        tableRow.put("lastActivity", row.getLastActivity());
        tableRow.put("minutesAgo", row.getLastActivity() == null ? null
                : ChronoUnit.MINUTES.between(row.getLastActivity(), now));
        return tableRow;
    }

    // ---------- export ----------

    /**
     * The rows to export — the frontend turns them into CSV / PDF.
     * scope = "page" → the 50 rows of that page; scope = "all" → every row for the filters,
     * no row limit.
     */
    @Override
    public Map<String, Object> exportConversations(ConversationFilters filters, String scope, int page) {
        ConversationQuery query = buildConversationQuery(filters);
        String exportScope = hasText(scope) ? scope.trim().toLowerCase() : "all";
        if (!exportScope.equals("page") && !exportScope.equals("all")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be page or all");
        }
        int safePage = Math.max(page, 0);
        boolean currentPageOnly = exportScope.equals("page");
        boolean showBudget = budgetShownFor(query.pathType());

        long totalElements = countConversations(query);
        List<DashboardRepository.ConversationRowProjection> rows = currentPageOnly
                ? findConversations(query, CONVERSATIONS_PAGE_SIZE, safePage * CONVERSATIONS_PAGE_SIZE)
                : findConversations(query, ALL_ROWS, 0);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, String>> exportRows = new ArrayList<>();
        for (DashboardRepository.ConversationRowProjection row : rows) {
            exportRows.add(toExportRow(row, now, showBudget));
        }


        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", showBudget
                ? EXPORT_COLUMNS
                : EXPORT_COLUMNS.stream().filter(column -> !column.equals("Budget")).toList());
        result.put("rows", exportRows);
        result.put("scope", exportScope);
        result.put("page", safePage);
        result.put("totalElements", totalElements);
        result.put("totalPages", totalPages(totalElements));
        result.put("exportedRows", exportRows.size());
        result.put("flow", normaliseFlow(filters.flow()));
        result.put("range", normaliseRange(filters.range()));
        return result;
    }

    /** Export rows carry the full phone number (the table masks it) and plain-text labels. */
    private Map<String, String> toExportRow(DashboardRepository.ConversationRowProjection row,
                                            LocalDateTime now, boolean showBudget) {
        ScriptStep scriptStep = SCRIPT_STEP_BY_BOT_STEP.get(row.getStep());

        Map<String, String> exportRow = new LinkedHashMap<>();
        exportRow.put("Customer", textOrEmpty(row.getCustomerName()));
        exportRow.put("Phone", formatPhone(row.getPhone()));
        exportRow.put("Path", pathLabel(row.getPath()));
        exportRow.put("Step", scriptStep == null ? textOrEmpty(row.getStep())
                : scriptStep.code() + " · " + scriptStep.label());
        exportRow.put("Status", conversationStatus(row, now));
        exportRow.put("Category", textOrEmpty(CATEGORY_LABELS.get(row.getCategory())));
        if (showBudget) {
            exportRow.put("Budget", textOrEmpty(BUDGET_LABELS.get(row.getBudget())));
        }
        exportRow.put("Started At", row.getStartedAt() == null ? "" : row.getStartedAt().format(EXPORT_DATE_TIME));
        exportRow.put("Last Activity", row.getLastActivity() == null ? "" : row.getLastActivity().format(EXPORT_DATE_TIME));
        return exportRow;
    }

    /** The Wife path never asks a budget, so "I'm celebrating" has no Budget column at all. */
    private boolean budgetShownFor(String pathType) {
        return !TEMPLATE_WIFE.equals(pathType);
    }

    // ---------- shared by the table and the export ----------

    private ConversationQuery buildConversationQuery(ConversationFilters filters) {
        DateWindow window = resolveDateTimeRange(filters.range(), filters.from(), filters.to());
        String pathType = flowToPathType(filters.flow());

        // Category — a CAT_* value, or empty for all categories
        String category = null;
        if (hasText(filters.category()) && !"all".equalsIgnoreCase(filters.category().trim())) {
            category = filters.category().trim().toUpperCase();
            if (!CATEGORY_LABELS.containsKey(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "category must be one of " + CATEGORY_LABELS.keySet());
            }
        }

        // Step — the script code from the Step filter ("1B", "2A-Budget", or "1B · Carousel"); empty = all steps
        boolean filterByStep = false;
        List<String> steps = NO_STEP_FILTER;
        if (hasText(filters.step()) && !isAllSteps(filters.step())) {
            filterByStep = true;
            steps = botStepsForFilter(filters.step());
        }

        // Search — name contains the text, or phone contains its digits
        String searchName = null;
        String searchPhone = "";
        if (hasText(filters.search())) {
            String searchText = filters.search().trim();
            if (searchText.length() > MAX_SEARCH_LENGTH) {
                searchText = searchText.substring(0, MAX_SEARCH_LENGTH);
            }
            searchName = "%" + escapeLikePattern(searchText) + "%";
            String digits = searchText.replaceAll("\\D", "");
            searchPhone = digits.isEmpty() ? "" : "%" + digits + "%";
        }

        // Sort — newest activity first unless a column header was clicked
        String sortColumn = "lastActivity";
        if (hasText(filters.sort())) {
            sortColumn = SORT_COLUMNS.get(filters.sort().trim().toLowerCase());
            if (sortColumn == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "sort must be customer, phone, path, step, category, budget or lastActivity");
            }
        }
        String sortDirection = hasText(filters.direction()) ? filters.direction().trim().toLowerCase() : "desc";
        if (!sortDirection.equals("asc") && !sortDirection.equals("desc")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be asc or desc");
        }

        return new ConversationQuery(pathType, window, category, filterByStep, steps,
                searchName, searchPhone, sortColumn, sortDirection);
    }

    private List<DashboardRepository.ConversationRowProjection> findConversations(ConversationQuery query,
                                                                                  int limit, int offset) {
        return dashboardRepo.findConversationsPage(query.pathType(), query.window().from(), query.window().to(),
                query.category(), query.filterByStep(), query.steps(),
                query.searchName(), query.searchPhone(),
                query.sortColumn(), query.sortDirection(), limit, offset);
    }

    private long countConversations(ConversationQuery query) {
        return dashboardRepo.countConversations(query.pathType(), query.window().from(), query.window().to(),
                query.category(), query.filterByStep(), query.steps(),
                query.searchName(), query.searchPhone());
    }

    /**
     * Completed = the journey reached E1.
     * Ended = closed without finishing (she tapped a Step 0 button again, which starts a new session).
     * Idle = no reply for longer than the idle nudge time. Active = everything else.
     */
    private String conversationStatus(DashboardRepository.ConversationRowProjection row, LocalDateTime now) {
        String sessionState = row.getSessionState() == null ? "" : row.getSessionState();
        switch (sessionState) {
            case "COMPLETED":
                return "Completed";
            case "ENDED":
                return "Ended";
            default:
                LocalDateTime idleCutoff = now.minusMinutes(idleMinutes);
                if (row.getLastActivity() != null && row.getLastActivity().isBefore(idleCutoff)) return "Idle";
                return "Active";
        }
    }

    private boolean isAllSteps(String step) {
        String value = step.trim().toLowerCase();
        return value.equals("all") || value.equals("all steps");
    }

    /**
     * The bot steps behind one Step-filter option. Accepts the code ("1B"), the dropdown text
     * ("1B · Carousel") or, if the frontend sends only a label, the label ("Carousel" → 1B and 2B).
     */
    private List<String> botStepsForFilter(String step) {
        String value = step.trim();
        String code = value.contains("·") ? value.substring(0, value.indexOf('·')).trim() : value;

        List<ScriptStep> matches = SCRIPT_STEPS.stream()
                .filter(scriptStep -> sameCode(scriptStep.code(), code))
                .toList();
        if (matches.isEmpty() && !value.contains("·")) {
            matches = SCRIPT_STEPS.stream()
                    .filter(scriptStep -> scriptStep.label().equalsIgnoreCase(value))
                    .toList();
        }
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "step must be one of "
                    + SCRIPT_STEPS.stream().map(ScriptStep::code).distinct().toList());
        }
        return matches.stream().flatMap(scriptStep -> scriptStep.steps().stream()).toList();
    }

    /** "2a-budget" = "2A-Budget", "1i nudge" = "1I-nudge" — case, spaces and dashes don't matter. */
    private boolean sameCode(String code, String requested) {
        return code.replaceAll("[^A-Za-z0-9]", "").equalsIgnoreCase(requested.replaceAll("[^A-Za-z0-9]", ""));
    }

    private long totalPages(long totalElements) {
        return (totalElements + CONVERSATIONS_PAGE_SIZE - 1) / CONVERSATIONS_PAGE_SIZE;
    }

    /** LIKE treats % and _ as wildcards — escape them so a search for "50_" means exactly that. */
    private String escapeLikePattern(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String pathToFlow(String path) {
        if (path == null) return null;
        return switch (path) {
            case TEMPLATE_WIFE -> "celebrating";
            case TEMPLATE_HUSBAND -> "shopping";
            case TEMPLATE_SPARKLE -> "sparkle";
            default -> path.toLowerCase();
        };
    }

    private String pathLabel(String path) {
        if (path == null) return "";
        return switch (path) {
            case TEMPLATE_WIFE -> "Celebrating";
            case TEMPLATE_HUSBAND -> "Shopping for her";
            case TEMPLATE_SPARKLE -> "Sparkle";
            default -> path;
        };
    }

    /** "919835912846" → "+91 98359 ••846" — the table never needs the full number. */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "";
        if (phone.length() == 12 && phone.startsWith("91")) {
            return "+91 " + phone.substring(2, 7) + " ••" + phone.substring(9);
        }
        return "••" + phone.substring(phone.length() - 4);
    }

    /** "919835912846" → "+91 98359 12846" — used in exports. */
    private String formatPhone(String phone) {
        if (phone == null) return "";
        if (phone.length() == 12 && phone.startsWith("91")) {
            return "+91 " + phone.substring(2, 7) + " " + phone.substring(7);
        }
        return "+" + phone;
    }

    private String textOrEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Map.of doesn't keep order — labels need to stay in this order. */
    private static Map<String, String> orderedMap(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    // ==================================================================
    // STORE VISITS PAGE
    // Bookings come from the C2 WhatsApp Flow (bookings table), counted by when they were made.
    // Cancelled bookings are left out everywhere.
    // ==================================================================

    private static final int BOOKINGS_PAGE_SIZE = 50;
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

    /**
     * Visits Booked, Booking Form Opened (with the share that went on to book) and Most Picked Slot.
     * Booking Form Opened = sessions that reached C2 (tapped "Visit nearby store") in the window.
     */
    @Override
    public Map<String, Object> getStoreVisitsSummary(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);

        long visitsBooked = dashboardRepo.countBookings(pathType, window.from(), window.to());
        long bookingFormOpened = dashboardRepo.countSessionsOpenedBookingForm(pathType, window.from(), window.to());
        List<DashboardRepository.SlotCountProjection> topSlot =
                dashboardRepo.findMostPickedSlot(pathType, window.from(), window.to());

        Map<String, Object> mostPickedSlot = new LinkedHashMap<>();
        if (topSlot.isEmpty()) {
            mostPickedSlot.put("timeSlot", null);
            mostPickedSlot.put("bookings", 0L);
            mostPickedSlot.put("pctOfBookings", 0.0);
        } else {
            long slotBookings = valueOrZero(topSlot.get(0).getTotal());
            mostPickedSlot.put("timeSlot", topSlot.get(0).getTimeSlot());
            mostPickedSlot.put("bookings", slotBookings);
            mostPickedSlot.put("pctOfBookings", pct(slotBookings, visitsBooked));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visitsBooked", visitsBooked);
        data.put("bookingFormOpened", bookingFormOpened);
        data.put("completedPct", pct(visitsBooked, bookingFormOpened));
        data.put("mostPickedSlot", mostPickedSlot);
        data.put("flow", normaliseFlow(flow));
        data.put("range", normaliseRange(range));
        return data;
    }

    /** Recent Bookings — newest first, 50 per page. */
    @Override
    public Map<String, Object> getStoreBookings(String flow, String range, String startDate, String endDate, int page) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        int safePage = Math.max(page, 0);

        long totalElements = dashboardRepo.countBookings(pathType, window.from(), window.to());
        List<DashboardRepository.BookingRowProjection> rows = dashboardRepo.findBookingsPage(
                pathType, window.from(), window.to(), BOOKINGS_PAGE_SIZE, safePage * BOOKINGS_PAGE_SIZE);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> bookings = new ArrayList<>();
        for (DashboardRepository.BookingRowProjection row : rows) {
            bookings.add(toBookingRow(row, now));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", bookings);
        result.put("page", safePage);
        result.put("size", BOOKINGS_PAGE_SIZE);
        result.put("totalElements", totalElements);
        result.put("totalPages", (totalElements + BOOKINGS_PAGE_SIZE - 1) / BOOKINGS_PAGE_SIZE);
        result.put("flow", normaliseFlow(flow));
        result.put("range", normaliseRange(range));
        return result;
    }

    private Map<String, Object> toBookingRow(DashboardRepository.BookingRowProjection row, LocalDateTime now) {
        List<String> reservedSkus = row.getReservedSkus() == null ? List.of()
                : Arrays.stream(row.getReservedSkus().split(",")).map(String::trim).filter(this::hasText).toList();

        Map<String, Object> booking = new LinkedHashMap<>();
        booking.put("bookingId", row.getBookingId());
        booking.put("bookingRef", row.getBookingRef());
        booking.put("customerName", displayName(row.getCustomerName(), row.getPhone()));
        booking.put("phone", maskPhone(row.getPhone()));
        booking.put("flow", pathToFlow(row.getPath()));
        booking.put("pathLabel", pathLabel(row.getPath()));
        booking.put("storeName", row.getStoreName());
        booking.put("visitDate", row.getVisitDate());
        booking.put("timeSlot", row.getTimeSlot());
        booking.put("slotLabel", slotLabel(row.getVisitDate(), row.getTimeSlot()));
        booking.put("reservedLabel", reservedLabel(reservedSkus, row.getReservedProductName()));
        booking.put("reservedSkus", reservedSkus);
        booking.put("status", row.getStatus());
        booking.put("bookedAt", row.getBookedAt());
        booking.put("minutesAgo", row.getBookedAt() == null ? null
                : ChronoUnit.MINUTES.between(row.getBookedAt(), now));
        return booking;
    }

    /** "Today, 4:00 pm" / "Tomorrow, 6:30 pm" / "Sat, 12:30 pm" (within a week) / "18 Oct, 11:00 am". */
    private String slotLabel(LocalDate visitDate, String timeSlot) {
        String day = "";
        if (visitDate != null) {
            LocalDate today = LocalDate.now();
            long daysAhead = ChronoUnit.DAYS.between(today, visitDate);
            if (daysAhead == 0) day = "Today";
            else if (daysAhead == 1) day = "Tomorrow";
            else if (daysAhead > 1 && daysAhead < 7) day = visitDate.format(WEEKDAY);
            else day = visitDate.format(DAY_MONTH);
        }
        String slot = timeSlot == null ? "" : timeSlot.trim();
        if (day.isEmpty()) return slot;
        return slot.isEmpty() ? day : day + ", " + slot;
    }

    /** One piece → its name (SKU if the catalogue doesn't have it); several → "Wishlist"; none → null. */
    private String reservedLabel(List<String> reservedSkus, String productName) {
        if (reservedSkus.isEmpty()) return null;
        if (reservedSkus.size() > 1) return "Wishlist";
        return hasText(productName) ? productName : reservedSkus.get(0);
    }

    // ==================================================================
    // REFERRAL LEADS PAGE
    // Contacts a customer shared for a future occasion: his details from the Wife path (1J → 1K)
    // and her details from the Husband path (2F → 2G). Counted by when the consent was answered.
    // ==================================================================

    private static final int REFERRAL_PAGE_SIZE = 50;
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("dd/MM");

    private static final List<String> REFERRAL_EXPORT_COLUMNS = List.of(
            "Referred By", "Path", "Referred Contact", "Relation", "Mobile", "Key Date", "Consent", "Status", "Captured At");

    /**
     * The 5 cards + the Opt-in Leads section.
     * A card that doesn't apply to the selected path is null (shown as "—"):
     * Husbands Referred and Ishara Forwards exist only on "I'm celebrating", Wives Referred only on "Shopping for her".
     */
    @Override
    public Map<String, Object> getReferralLeadsSummary(String flow, String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        boolean allPaths = pathType == null;
        boolean wifePath = allPaths || TEMPLATE_WIFE.equals(pathType);
        boolean husbandPath = allPaths || TEMPLATE_HUSBAND.equals(pathType);

        DashboardRepository.ReferralSummaryProjection referrals =
                dashboardRepo.summarizeReferralLeads(pathType, window.from(), window.to());
        long total = referrals == null ? 0 : valueOrZero(referrals.getTotal());
        long consented = referrals == null ? 0 : valueOrZero(referrals.getConsented());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("referralLeads", total);
        data.put("husbandsReferred", wifePath ? (referrals == null ? 0L : valueOrZero(referrals.getHusbands())) : null);
        data.put("wivesReferred", husbandPath ? (referrals == null ? 0L : valueOrZero(referrals.getWives())) : null);
        data.put("consentGivenPct", pctWhole(consented, total));
        data.put("canBeMessaged", referrals == null ? 0L : valueOrZero(referrals.getMessageable()));
        // Ishara sent — the husband hears about it, but his number isn't captured
        data.put("isharaForwards", wifePath
                ? dashboardRepo.countLeadsByType(LEAD_HINT_SENT, pathType, window.from(), window.to())
                : null);
        data.put("optInLeads", buildOptInSection(window));
        data.put("flow", normaliseFlow(flow));
        data.put("range", normaliseRange(range));
        return data;
    }

    /**
     * "Here for the sparkle" customers who said yes to new arrivals and offers. Only the Sparkle path asks,
     * so this is always the Sparkle numbers for the date range, whichever path tab is selected.
     */
    private Map<String, Object> buildOptInSection(DateWindow window) {
        long optedIn = dashboardRepo.countLeadsByType(LEAD_SPARKLE_OPT_IN, TEMPLATE_SPARKLE, window.from(), window.to());
        long optedOut = dashboardRepo.countLeadsByType(LEAD_SPARKLE_OPT_OUT, TEMPLATE_SPARKLE, window.from(), window.to());
        long sparkleOpenerTaps = dashboardRepo.countOpenerTaps(TEMPLATE_SPARKLE, window.from(), window.to());

        Map<String, Object> optIn = new LinkedHashMap<>();
        optIn.put("optedIn", optedIn);
        optIn.put("pctOfSparkleOpenerTaps", pct(optedIn, sparkleOpenerTaps));
        optIn.put("sparkleOpenerTaps", sparkleOpenerTaps);
        optIn.put("optedOut", optedOut);
        return optIn;
    }

    @Override
    public Map<String, Object> getReferralLeads(String flow, String range, String startDate, String endDate,
                                                String show, int page) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        String showFilter = normaliseReferralShow(show);
        int safePage = Math.max(page, 0);

        long totalElements = countReferralRows(showFilter, pathType, window);
        List<DashboardRepository.ReferralLeadRowProjection> rows =
                findReferralRows(showFilter, pathType, window, REFERRAL_PAGE_SIZE, safePage * REFERRAL_PAGE_SIZE);

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> tableRows = new ArrayList<>();
        for (DashboardRepository.ReferralLeadRowProjection row : rows) {
            tableRows.add(toReferralRow(row, now));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", tableRows);
        result.put("page", safePage);
        result.put("size", REFERRAL_PAGE_SIZE);
        result.put("totalElements", totalElements);
        result.put("totalPages", (totalElements + REFERRAL_PAGE_SIZE - 1) / REFERRAL_PAGE_SIZE);
        result.put("show", showFilter);
        result.put("flow", normaliseFlow(flow));
        result.put("range", normaliseRange(range));
        return result;
    }

    @Override
    public Map<String, Object> exportReferralLeads(String flow, String range, String startDate, String endDate,
                                                   String show, String scope, int page) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        String pathType = flowToPathType(flow);
        String showFilter = normaliseReferralShow(show);
        String exportScope = hasText(scope) ? scope.trim().toLowerCase() : "all";
        if (!exportScope.equals("page") && !exportScope.equals("all")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scope must be page or all");
        }
        int safePage = Math.max(page, 0);
        boolean currentPageOnly = exportScope.equals("page");

        long totalElements = countReferralRows(showFilter, pathType, window);
        List<DashboardRepository.ReferralLeadRowProjection> rows = currentPageOnly
                ? findReferralRows(showFilter, pathType, window, REFERRAL_PAGE_SIZE, safePage * REFERRAL_PAGE_SIZE)
                : findReferralRows(showFilter, pathType, window, ALL_ROWS, 0);

        List<Map<String, String>> exportRows = new ArrayList<>();
        for (DashboardRepository.ReferralLeadRowProjection row : rows) {
            Map<String, String> exportRow = new LinkedHashMap<>();
            exportRow.put("Referred By", textOrEmpty(row.getCustomerName()));
            exportRow.put("Path", pathLabel(row.getPath()));
            exportRow.put("Referred Contact", textOrEmpty(row.getPartnerName()));
            exportRow.put("Relation", relationLabel(row.getLeadType()));
            exportRow.put("Mobile", hasText(row.getPartnerPhone()) ? formatPhone(row.getPartnerPhone()) : "");
            exportRow.put("Key Date", keyDateLabel(row.getPartnerDateType(), row.getPartnerDate()));
            exportRow.put("Consent", consentLabel(row.getConsent()));
            exportRow.put("Status", referralStatusLabel(row.getConsent()));
            exportRow.put("Captured At", row.getCapturedAt() == null ? "" : row.getCapturedAt().format(EXPORT_DATE_TIME));
            exportRows.add(exportRow);
        }


        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", REFERRAL_EXPORT_COLUMNS);
        result.put("rows", exportRows);
        result.put("scope", exportScope);
        result.put("page", safePage);
        result.put("totalElements", totalElements);
        result.put("exportedRows", exportRows.size());
        result.put("show", showFilter);
        result.put("flow", normaliseFlow(flow));
        result.put("range", normaliseRange(range));
        return result;
    }

    private Map<String, Object> toReferralRow(DashboardRepository.ReferralLeadRowProjection row, LocalDateTime now) {
        boolean husband = LEAD_HUSBAND_CAPTURED.equals(row.getLeadType());

        Map<String, Object> tableRow = new LinkedHashMap<>();
        tableRow.put("leadId", row.getLeadId());
        // Referred by — the customer who shared the contact
        tableRow.put("referredBy", displayName(row.getCustomerName(), row.getPhone()));
        tableRow.put("flow", pathToFlow(row.getPath()));
        tableRow.put("pathLabel", pathLabel(row.getPath()));
        tableRow.put("capturedAt", row.getCapturedAt());
        tableRow.put("minutesAgo", row.getCapturedAt() == null ? null
                : ChronoUnit.MINUTES.between(row.getCapturedAt(), now));
        // Referred contact
        tableRow.put("contactName", row.getPartnerName());
        tableRow.put("contactStep", husband ? "1J" : "2F");
        tableRow.put("relation", relationLabel(row.getLeadType()));
        tableRow.put("mobile", hasText(row.getPartnerPhone()) ? maskPhone(row.getPartnerPhone()) : null);
        tableRow.put("keyDateType", row.getPartnerDateType());
        tableRow.put("keyDate", row.getPartnerDate());
        tableRow.put("keyDateLabel", keyDateLabel(row.getPartnerDateType(), row.getPartnerDate()));
        tableRow.put("consent", row.getConsent());
        tableRow.put("consentLabel", consentLabel(row.getConsent()));
        tableRow.put("status", CONSENT_YES.equalsIgnoreCase(row.getConsent()) ? "NEW" : "CONSENT_NOT_GIVEN");
        tableRow.put("statusLabel", referralStatusLabel(row.getConsent()));
        return tableRow;
    }

    /** all → both types; husbands / wives → one type; consented → both types, consent YES only. */
    private String normaliseReferralShow(String show) {
        String value = hasText(show) ? show.trim().toLowerCase() : "all";
        return switch (value) {
            case "all", "husbands", "wives", "consented" -> value;
            case "husband" -> "husbands";
            case "wife" -> "wives";
            case "consent", "yes" -> "consented";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "show must be all, husbands, wives or consented");
        };
    }

    private List<String> referralLeadTypesFor(String showFilter) {
        return switch (showFilter) {
            case "husbands" -> List.of(LEAD_HUSBAND_CAPTURED);
            case "wives" -> List.of(LEAD_WIFE_CAPTURED);
            default -> ALL_REFERRAL_TYPES;
        };
    }

    private long countReferralRows(String showFilter, String pathType, DateWindow window) {
        return dashboardRepo.countReferralLeadsForTable(referralLeadTypesFor(showFilter),
                showFilter.equals("consented"), pathType, window.from(), window.to());
    }

    private List<DashboardRepository.ReferralLeadRowProjection> findReferralRows(String showFilter, String pathType,
                                                                                 DateWindow window, int limit, int offset) {
        return dashboardRepo.findReferralLeadsPage(referralLeadTypesFor(showFilter),
                showFilter.equals("consented"), pathType, window.from(), window.to(), limit, offset);
    }

    private String relationLabel(String leadType) {
        if (LEAD_HUSBAND_CAPTURED.equals(leadType)) return "Husband";
        if (LEAD_WIFE_CAPTURED.equals(leadType)) return "Wife";
        return "";
    }

    /** "Anniversary 05/05" / "Birthday 06/01" — day/month only, the year doesn't matter for a reminder. */
    private String keyDateLabel(String dateType, LocalDate date) {
        if (date == null) return "";
        String type = "BIRTHDAY".equalsIgnoreCase(dateType) ? "Birthday"
                : "ANNIVERSARY".equalsIgnoreCase(dateType) ? "Anniversary" : "";
        return (type + " " + date.format(KEY_DATE)).trim();
    }

    private String consentLabel(String consent) {
        if (CONSENT_YES.equalsIgnoreCase(consent)) return "Yes";
        if (CONSENT_NO.equalsIgnoreCase(consent)) return "No";
        return "";
    }

    /**
     * "Consent not given" when she / he said no. A YES contact is "New" — nothing records follow-ups yet;
     * Messaged / Reminder scheduled / Visited store need a status saved when that happens.
     */
    private String referralStatusLabel(String consent) {
        return CONSENT_YES.equalsIgnoreCase(consent) ? "New" : "Consent not given";
    }

    /** Whole-number percentage, e.g. 73 — used by the Consent Given card. */
    private long pctWhole(long part, long total) {
        if (total <= 0) return 0L;
        return Math.round((part * 100.0) / total);
    }

    // ==================================================================
    // AUDIENCES PAGE — date range only (no path tabs)
    //
    //   Engaged, Not Finished : customers whose latest journey started in the range is still open
    //   Where they stopped    : 4 segments of those customers — each customer in one at most
    //   Completed or opted in : customers who took an action in the range (finished, booked, bought online,
    //                           gave consent, opted in) + one card per action
    // Every segment has a download (rows for CSV); fullMobile = the "Full mobile numbers in downloads" box.
    // ==================================================================

    /** A segment on the page: URL key → DB value / label / paths / priority. */
    private record AudienceSegment(String key, String dbValue, String label, List<String> flows, Integer priority) {
    }

    private static final List<AudienceSegment> STOPPED_SEGMENTS = List.of(
            new AudienceSegment("hint-sent-no-visit", "HINT_SENT_NO_VISIT", "Hint sent, no visit yet",
                    List.of("celebrating"), 1),
            new AudienceSegment("list-started-no-hint", "LIST_STARTED_NO_HINT", "List started, hint not sent",
                    List.of("celebrating"), 2),
            new AudienceSegment("browsing-no-purchase", "BROWSING_NO_PURCHASE", "Browsing for her, no purchase",
                    List.of("shopping"), 3),
            new AudienceSegment("shortlisted-no-visit", "SHORTLISTED_NO_VISIT", "Shortlisted for herself, no visit",
                    List.of("sparkle"), 3));

    private static final List<AudienceSegment> COMPLETED_SEGMENTS = List.of(
            new AudienceSegment("store-visit-booked", null, "Store visit booked",
                    List.of("celebrating", "shopping", "sparkle"), null),
            new AudienceSegment("clicked-buy-online", null, "Clicked Buy Online", List.of("shopping"), null),
            new AudienceSegment("referred-consent-given", null, "Referred contacts, consent given",
                    List.of("celebrating", "shopping"), null),
            new AudienceSegment("opted-in-new-arrivals", null, "Opted in for new arrivals", List.of("sparkle"), null));

    @Override
    public Map<String, Object> getAudiencesSummary(String range, String startDate, String endDate) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();

        // Where they stopped (+ OTHER, so the total = Engaged, Not Finished)
        Map<String, Long> countBySegment = new HashMap<>();
        for (DashboardRepository.SegmentCountProjection row : dashboardRepo.countAudienceSegments(from, to)) {
            countBySegment.put(row.getSegment(), valueOrZero(row.getTotal()));
        }
        long engagedNotFinished = countBySegment.values().stream().mapToLong(Long::longValue).sum();

        List<Map<String, Object>> stopped = new ArrayList<>();
        long stoppedTotal = 0;
        for (AudienceSegment segment : STOPPED_SEGMENTS) {
            long count = countBySegment.getOrDefault(segment.dbValue(), 0L);
            stoppedTotal += count;
            stopped.add(audienceCard(segment, count));
        }

        // Completed or opted in
        List<Map<String, Object>> completed = new ArrayList<>();
        completed.add(audienceCard(COMPLETED_SEGMENTS.get(0), dashboardRepo.countCustomersWithBooking(from, to)));
        completed.add(audienceCard(COMPLETED_SEGMENTS.get(1),
                dashboardRepo.countCustomersWithLead(List.of(LEAD_BUY_ONLINE_CLICKED), false, from, to)));
        completed.add(audienceCard(COMPLETED_SEGMENTS.get(2),
                dashboardRepo.countCustomersWithLead(ALL_REFERRAL_TYPES, true, from, to)));
        completed.add(audienceCard(COMPLETED_SEGMENTS.get(3),
                dashboardRepo.countCustomersWithLead(List.of(LEAD_SPARKLE_OPT_IN), false, from, to)));
        long completedOrOptedIn = dashboardRepo.countCompletedOrOptedInCustomers(from, to);

        Map<String, Object> whereTheyStopped = new LinkedHashMap<>();
        whereTheyStopped.put("total", stoppedTotal);
        whereTheyStopped.put("segments", stopped);

        Map<String, Object> completedOrOptedInSection = new LinkedHashMap<>();
        completedOrOptedInSection.put("total", completedOrOptedIn);
        completedOrOptedInSection.put("segments", completed);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("engagedNotFinished", engagedNotFinished);
        data.put("completedOrOptedIn", completedOrOptedIn);
        data.put("whereTheyStopped", whereTheyStopped);
        data.put("completedOrOptedInSegments", completedOrOptedInSection);
        data.put("range", normaliseRange(range));
        return data;
    }

    private Map<String, Object> audienceCard(AudienceSegment segment, long count) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("key", segment.key());
        card.put("label", segment.label());
        card.put("flows", segment.flows());
        card.put("priority", segment.priority());
        card.put("count", count);
        return card;
    }

    // ---------- one endpoint per card: its full count + the rows for its "Download CSV" ----------

    @Override
    public Map<String, Object> getHintSentNoVisitAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("hint-sent-no-visit", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getListStartedNoHintAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("list-started-no-hint", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getBrowsingNoPurchaseAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("browsing-no-purchase", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getShortlistedNoVisitAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("shortlisted-no-visit", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getStoreVisitBookedAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("store-visit-booked", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getClickedBuyOnlineAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("clicked-buy-online", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getReferredConsentGivenAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("referred-consent-given", range, startDate, endDate, fullMobile);
    }

    @Override
    public Map<String, Object> getOptedInNewArrivalsAudience(String range, String startDate, String endDate, boolean fullMobile) {
        return buildAudienceCardData("opted-in-new-arrivals", range, startDate, endDate, fullMobile);
    }

    /** The full (uncapped) count of one card — the same number the summary shows. */
    private long audienceCardCount(AudienceSegment segment, LocalDateTime from, LocalDateTime to) {
        if (segment.dbValue() != null) {
            return dashboardRepo.countAudienceSegments(from, to).stream()
                    .filter(row -> segment.dbValue().equals(row.getSegment()))
                    .mapToLong(row -> valueOrZero(row.getTotal()))
                    .sum();
        }
        return switch (segment.key()) {
            case "store-visit-booked" -> dashboardRepo.countCustomersWithBooking(from, to);
            case "clicked-buy-online" -> dashboardRepo.countCustomersWithLead(List.of(LEAD_BUY_ONLINE_CLICKED), false, from, to);
            case "referred-consent-given" -> dashboardRepo.countCustomersWithLead(ALL_REFERRAL_TYPES, true, from, to);
            default -> dashboardRepo.countCustomersWithLead(List.of(LEAD_SPARKLE_OPT_IN), false, from, to);
        };
    }

    /**
     * One card: its full count + all the rows for its "Download CSV" — the frontend builds the file.
     * fullMobile = true → "+91 98765 43210"; false → "+91 98765 ••210" (the checkbox on the page).
     */
    private Map<String, Object> buildAudienceCardData(String segmentKey, String range, String startDate, String endDate,
                                                      boolean fullMobile) {
        DateWindow window = resolveDateTimeRange(range, startDate, endDate);
        LocalDateTime from = window.from();
        LocalDateTime to = window.to();
        String key = segmentKey == null ? "" : segmentKey.trim().toLowerCase();

        List<String> columns;
        List<Map<String, String>> rows = new ArrayList<>();

        AudienceSegment stoppedSegment = STOPPED_SEGMENTS.stream()
                .filter(segment -> segment.key().equals(key)).findFirst().orElse(null);
        AudienceSegment completedSegment = COMPLETED_SEGMENTS.stream()
                .filter(segment -> segment.key().equals(key)).findFirst().orElse(null);

        if (stoppedSegment != null) {
            columns = List.of("Customer", "Mobile", "Path", "Step", "Category", "Budget", "Pieces on List",
                    "Started At", "Last Activity");
            for (DashboardRepository.AudienceMemberProjection member : dashboardRepo.findAudienceSegmentMembers(
                    stoppedSegment.dbValue(), from, to, ALL_ROWS)) {
                ScriptStep step = SCRIPT_STEP_BY_BOT_STEP.get(member.getCurrentStep());
                Map<String, String> row = new LinkedHashMap<>();
                row.put("Customer", textOrEmpty(member.getCustomerName()));
                row.put("Mobile", exportPhone(member.getPhone(), fullMobile));
                row.put("Path", pathLabel(member.getPath()));
                row.put("Step", step == null ? textOrEmpty(member.getCurrentStep()) : step.code() + " · " + step.label());
                row.put("Category", textOrEmpty(CATEGORY_LABELS.get(member.getCategory())));
                row.put("Budget", textOrEmpty(BUDGET_LABELS.get(member.getBudget())));
                row.put("Pieces on List", String.valueOf(valueOrZero(member.getPiecesOnList())));
                row.put("Started At", formatExportTime(member.getStartedAt()));
                row.put("Last Activity", formatExportTime(member.getLastActivity()));
                rows.add(row);
            }
        } else if (completedSegment != null && key.equals("store-visit-booked")) {
            columns = List.of("Customer", "Mobile", "Path", "Boutique", "Slot", "Reserved", "Booking Ref", "Booked At");
            for (DashboardRepository.BookingRowProjection booking : dashboardRepo.findBookingsPage(
                    null, from, to, ALL_ROWS, 0)) {
                List<String> reservedSkus = booking.getReservedSkus() == null ? List.of()
                        : Arrays.stream(booking.getReservedSkus().split(",")).map(String::trim).filter(this::hasText).toList();
                Map<String, String> row = new LinkedHashMap<>();
                row.put("Customer", textOrEmpty(booking.getCustomerName()));
                row.put("Mobile", exportPhone(booking.getPhone(), fullMobile));
                row.put("Path", pathLabel(booking.getPath()));
                row.put("Boutique", textOrEmpty(booking.getStoreName()));
                row.put("Slot", textOrEmpty(slotLabel(booking.getVisitDate(), booking.getTimeSlot())));
                row.put("Reserved", textOrEmpty(reservedLabel(reservedSkus, booking.getReservedProductName())));
                row.put("Booking Ref", textOrEmpty(booking.getBookingRef()));
                row.put("Booked At", formatExportTime(booking.getBookedAt()));
                rows.add(row);
            }
        } else if (completedSegment != null && key.equals("clicked-buy-online")) {
            columns = List.of("Customer", "Mobile", "Path", "Product", "SKU", "Clicked At");
            for (DashboardRepository.AudienceLeadRowProjection lead : dashboardRepo.findAudienceLeadRows(
                    List.of(LEAD_BUY_ONLINE_CLICKED), false, from, to, ALL_ROWS)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("Customer", textOrEmpty(lead.getCustomerName()));
                row.put("Mobile", exportPhone(lead.getPhone(), fullMobile));
                row.put("Path", pathLabel(lead.getPath()));
                row.put("Product", textOrEmpty(lead.getProductName()));
                row.put("SKU", textOrEmpty(lead.getProductSkus()));
                row.put("Clicked At", formatExportTime(lead.getCreatedAt()));
                rows.add(row);
            }
        } else if (completedSegment != null && key.equals("referred-consent-given")) {
            columns = List.of("Referred By", "Mobile", "Path", "Referred Contact", "Relation", "Contact Mobile",
                    "Key Date", "Consent", "Captured At");
            for (DashboardRepository.AudienceLeadRowProjection lead : dashboardRepo.findAudienceLeadRows(
                    ALL_REFERRAL_TYPES, true, from, to, ALL_ROWS)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("Referred By", textOrEmpty(lead.getCustomerName()));
                row.put("Mobile", exportPhone(lead.getPhone(), fullMobile));
                row.put("Path", pathLabel(lead.getPath()));
                row.put("Referred Contact", textOrEmpty(lead.getPartnerName()));
                row.put("Relation", relationLabel(lead.getLeadType()));
                row.put("Contact Mobile", exportPhone(lead.getPartnerPhone(), fullMobile));
                row.put("Key Date", keyDateLabel(lead.getPartnerDateType(), lead.getPartnerDate()));
                row.put("Consent", consentLabel(lead.getConsent()));
                row.put("Captured At", formatExportTime(lead.getCreatedAt()));
                rows.add(row);
            }
        } else if (completedSegment != null && key.equals("opted-in-new-arrivals")) {
            columns = List.of("Customer", "Mobile", "Path", "Opted In At");
            for (DashboardRepository.AudienceLeadRowProjection lead : dashboardRepo.findAudienceLeadRows(
                    List.of(LEAD_SPARKLE_OPT_IN), false, from, to, ALL_ROWS)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("Customer", textOrEmpty(lead.getCustomerName()));
                row.put("Mobile", exportPhone(lead.getPhone(), fullMobile));
                row.put("Path", pathLabel(lead.getPath()));
                row.put("Opted In At", formatExportTime(lead.getCreatedAt()));
                rows.add(row);
            }
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "segment must be one of "
                    + STOPPED_SEGMENTS.stream().map(AudienceSegment::key).toList() + " or "
                    + COMPLETED_SEGMENTS.stream().map(AudienceSegment::key).toList());
        }

        AudienceSegment segment = stoppedSegment != null ? stoppedSegment : completedSegment;
        long totalCount = audienceCardCount(segment, from, to);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segment", segment.key());
        result.put("label", segment.label());
        result.put("flows", segment.flows());
        result.put("priority", segment.priority());
        result.put("count", totalCount);                       // full count of the card, never capped
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("exportedRows", rows.size());
        result.put("fullMobile", fullMobile);
        result.put("range", normaliseRange(range));
        return result;
    }

    /** Full number, or masked like the tables — the "Full mobile numbers in downloads" checkbox. */
    private String exportPhone(String phone, boolean fullMobile) {
        if (!hasText(phone)) return "";
        return fullMobile ? formatPhone(phone) : maskPhone(phone);
    }

    private String formatExportTime(LocalDateTime time) {
        return time == null ? "" : time.format(EXPORT_DATE_TIME);
    }

    // ==================================================================
    // HELPERS
    // ==================================================================

    /** Frontend tab → value stored in the path column. "all" means no filter. */
    private String flowToPathType(String flow) {
        if (flow == null || flow.isBlank()) return null;
        return switch (flow.trim().toLowerCase()) {
            case "all" -> null;
            case "celebrating", "wife" -> TEMPLATE_WIFE;
            case "shopping", "husband" -> TEMPLATE_HUSBAND;
            case "sparkle" -> TEMPLATE_SPARKLE;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "flow must be all, celebrating, shopping or sparkle");
        };
    }

    private record DateWindow(LocalDateTime from, LocalDateTime to) {
        long numberOfDays() {
            return Math.max(ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()), 1);
        }
    }

    /**
     * Uses the server's clock, the same clock the entities use for createdAt / startedAt —
     * so "today" lines up with the stored timestamps. Run the app in IST (see notes).
     */
    private DateWindow resolveDateTimeRange(String range, String startDate, String endDate) {
        LocalDate today = LocalDate.now();
        LocalDateTime endOfToday = today.plusDays(1).atStartOfDay();

        return switch (normaliseRange(range)) {
            case "today" -> new DateWindow(today.atStartOfDay(), endOfToday);
            case "7d" -> new DateWindow(today.minusDays(6).atStartOfDay(), endOfToday);
            case "30d" -> new DateWindow(today.minusDays(29).atStartOfDay(), endOfToday);
            case "custom" -> resolveCustomRange(startDate, endDate);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "range must be today, 7d, 30d or custom");
        };
    }

    private DateWindow resolveCustomRange(String startDate, String endDate) {
        if (!hasText(startDate) || !hasText(endDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "custom range needs both from and to");
        }
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate.trim(), ISO_DATE);
            end = LocalDate.parse(endDate.trim(), ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to must be in yyyy-MM-dd format");
        }
        if (end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must be on or after from");
        }
        return new DateWindow(start.atStartOfDay(), end.plusDays(1).atStartOfDay());
    }

    private String normaliseRange(String range) {
        return (range == null || range.isBlank()) ? "today" : range.trim().toLowerCase();
    }

    private String normaliseFlow(String flow) {
        return (flow == null || flow.isBlank()) ? "all" : flow.trim().toLowerCase();
    }

    /** Percentage with one decimal, e.g. 94.3 */
    private double pct(long part, long total) {
        if (total <= 0) return 0.0;
        return Math.round((part * 1000.0) / total) / 10.0;
    }

    /** Percentage with two decimals, e.g. 66.16 — used by the drop-off bars. */
    private double pctTwoDecimals(long part, long total) {
        if (total <= 0) return 0.0;
        return Math.round((part * 10000.0) / total) / 100.0;
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
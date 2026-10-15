package org.example.karvachauth.serviceImple;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.karvachauth.entity.Booking;
import org.example.karvachauth.entity.Customer;
import org.example.karvachauth.entity.Lead;
import org.example.karvachauth.entity.Message;
import org.example.karvachauth.entity.Product;
import org.example.karvachauth.entity.ProductPick;
import org.example.karvachauth.entity.Session;
import org.example.karvachauth.entity.WishlistItem;
import org.example.karvachauth.repository.BookingRepository;
import org.example.karvachauth.repository.CustomerRepository;
import org.example.karvachauth.repository.LeadRepository;
import org.example.karvachauth.repository.MessageRepository;
import org.example.karvachauth.repository.ProductPickRepository;
import org.example.karvachauth.repository.ProductRepository;
import org.example.karvachauth.repository.SessionRepository;
import org.example.karvachauth.repository.WishlistItemRepository;
import org.example.karvachauth.service.BotEngineService;
import org.example.karvachauth.service.KarixService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.example.karvachauth.constants.KarvaChauthConstants.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotEngineServiceImple implements BotEngineService {

    private final KarixService karixService;
    private final CustomerRepository customerRepository;
    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;
    private final WishlistItemRepository wishlistItemRepository;
    private final LeadRepository leadRepository;                 // conversions → dashboard funnel
    private final ProductPickRepository productPickRepository;   // product interactions → "top products"
    private final ProductRepository productRepository;           // the catalogue — carousels, wishlist, Ishara, Buy Online
    private final BookingRepository bookingRepository;           // C2 store visits — PENDING (store picked) → CONFIRMED
    private final TaskScheduler taskScheduler;   // needs @EnableScheduling on the application class

    // ---------- C2 — nearby store API ----------
    @Value("${karvachauth.store-api.url:https://tw-dashboard.centralindia.cloudapp.azure.com/api/dhanteras/store-names/nearby}")
    private String storeApiUrl;

    @Value("${karvachauth.store-api.key:}")
    private String storeApiKey;

    private static final int NEARBY_STORE_LIMIT = 3;

    /** WhatsApp Flow "Book Appointment" (Karix) — store dropdown + date + time slot. */
    @Value("${karvachauth.store-flow.id:979994928482969}")
    private String storeFlowId;

    /** The Flow's time-slot ids → {label shown / saved, start hour (24h)}. Same ids as the Flow JSON. */
    private static final Map<String, String[]> FLOW_TIME_SLOTS = Map.of(
            "11-12", new String[]{"11 am – 12 pm", "11"},
            "12-1",  new String[]{"12 pm – 1 pm", "12"},
            "1-2",   new String[]{"1 pm – 2 pm", "13"},
            "2-3",   new String[]{"2 pm – 3 pm", "14"},
            "3-4",   new String[]{"3 pm – 4 pm", "15"},
            "4-5",   new String[]{"4 pm – 5 pm", "16"},
            "5-6",   new String[]{"5 pm – 6 pm", "17"},
            "6-7",   new String[]{"6 pm – 7 pm", "18"});
    private static final HttpClient STORE_HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ObjectMapper STORE_JSON = new ObjectMapper();

    // ---------- C2 — visit date / time rules (confirm store hours with the Mia team) ----------
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime STORE_OPENS = LocalTime.of(11, 0);
    private static final LocalTime LAST_VISIT = LocalTime.of(20, 30);
    private static final int VISIT_MAX_DAYS_AHEAD = 30;
    private static final long AFTER_BOOKING_DELAY_SECONDS = 5;
    private static final SecureRandom BOOKING_RANDOM = new SecureRandom();
    private static final DateTimeFormatter VISIT_DATE_TEXT = DateTimeFormatter.ofPattern("EEE, d MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter VISIT_TIME_TEXT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
//    private static final DateTimeFormatter VISIT_EXAMPLE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

//    /**
//     * Visit date & time — no slash needed, am / pm in any case:
//     * "16 10 5 pm", "16 oct 5:30pm", "16 October 2026 5:30 PM", "Oct 16 6.30 pm", "16/10/2026 17:30", "tomorrow 5pm".
//     * VISIT_TIME takes the time off the end; the rest must match one of the date shapes below.
//     */
//    private static final java.util.regex.Pattern VISIT_TIME = java.util.regex.Pattern.compile(
//            "^(.*?)[\\s,]*(?:\\bat\\s*)?(?<!\\d)(\\d{1,2})(?:\\s*[:.]\\s*(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?$");
//    private static final java.util.regex.Pattern VISIT_DATE_NUMBERS = java.util.regex.Pattern.compile(
//            "^(\\d{1,2})(?:st|nd|rd|th)?[\\s/.-]+(\\d{1,2})(?:[\\s/.-]+(\\d{4}|(?<=[/.-])\\d{2}))?$");
//    private static final java.util.regex.Pattern VISIT_DATE_DAY_MONTH = java.util.regex.Pattern.compile(
//            "^(\\d{1,2})(?:st|nd|rd|th)?[\\s/.-]*(?:of\\s+)?([a-z]{3,9})\\.?(?:[\\s/.-]+(\\d{4}))?$");
//    private static final java.util.regex.Pattern VISIT_DATE_MONTH_DAY = java.util.regex.Pattern.compile(
//            "^([a-z]{3,9})\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?(?:\\s+(\\d{4}))?$");

    /**
     * The last message recordOutbound() saved on this thread, for moveTo() to put on the new step.
     * Cleared at the start of every tap / text / opener / follow-up, so it never leaks into the next one.
     */
    private final ThreadLocal<Message> lastOutbound = new ThreadLocal<>();

    /** Minutes of silence before the IDLE nudge. 60 in production — set it to 1-2 in dev to test quickly. */
    @Value("${karvachauth.idle.minutes:60}")
    private long idleMinutes;

    /** Max sessions nudged per run — keeps one run short if many go quiet at once. */
    private static final int IDLE_BATCH_SIZE = 200;

    /** Max product cards per carousel page (1B / 1D / "See more"). */
    private static final int PRODUCT_PAGE_SIZE = 9;

    /**
     * Seconds between the product carousel and the browse buttons (See more / Another category / Visit store).
     * The pause lets the carousel (with its images) land first, so the buttons appear BELOW it.
     */
    private static final long BROWSE_CONTROLS_DELAY_SECONDS = 20;

    /** Max pieces listed in the Ishara (1G) — keeps the message under WhatsApp's 1024-char body limit. */
    private static final int MAX_HINT_ITEMS = 5;

    /**
     * Seconds between the Ishara (1G) and "Want to see them in person?" (1H).
     * WhatsApp doesn't tell us when she taps a URL button, so 1H is sent after a pause instead.
     **/
    private static final long HINT_TO_STORE_INVITE_DELAY_SECONDS = 10;

    /** Seconds between the "Buy Online" link (2C) and "shall we remember her birthday?" (2E). Same reason as above. */
    private static final long BUY_ONLINE_TO_DETAILS_DELAY_SECONDS = 20;

    // TODO: confirm the "Browse online" link for the hint with the Mia team
    private static final String HINT_BROWSE_LINK = "https://www.miabytanishq.com/";

    // TODO: replace with Karva Chauth images from the Mia team. These are the Rakhi images — fine for testing.
    private static final String IMG = "https://titanwatchimages123.blob.core.windows.net/miarakhi/";


    private static final String[][] CATEGORIES = {
            {"PEND", "Pendants",            "Dainty to dazzling – a piece for everyday wear",  IMG + "Pendants.png"},
            {"MSUT", "Mia Sutra",           "✨ The new collection – the piece for today",      "https://titanwatchimages123.blob.core.windows.net/watch-images/mia-sutra-kc.png"},
            {"PNCH", "Pendant & Chain",     "Ready-to-wear sets, chain included",              IMG + "Pendant%20&%20Chain.png"},
            {"NECK", "Necklaces",           "For the days that call for a little extra shine", IMG + "Necklaces.png"},
            {"EARR", "Earrings",            "Studs & drops that do all the talking",           IMG + "Earrings.png"},
            {"RING", "Rings",               "A little gold for every gesture",                 IMG + "Rings.png"},
            {"BRBG", "Bracelets & Bangles", "Wrist candy, the grown-up way",                   IMG + "Bracelets%20&%20Bangles.png"}
    };

    // ==================================================================
    // STEP 0 — OPENER
    //
    //   Karix template reaches her            → session on OPENER (no path yet)
    //   she taps "I'm celebrating" / …        → path saved, "Choose my gift" / … sent — still OPENER
    //   she taps "Choose my gift" / "Find her a gift" / "Show me"
    //                                         → W_CHOOSE_MY_GIFT / H_FIND_HER_GIFT / S_SHOW_ME
    //   or she taps "Visit nearby store"      → BOOK_STORE_VISIT (C2)
    //
    // So until she taps one of those two buttons she shows as OPENER everywhere.
    // ==================================================================
    /** "karva chauth" / "karwa chauth" / "karvachauth" / "Karwa Chouth"… type kare toh opener. */
    /** Pura message sirf ye shabd ho toh journey shuru: wife / husband / sparkle. */
    private static final java.util.regex.Pattern KARVA_KEYWORD =
            java.util.regex.Pattern.compile("kar[vw]a+ch[ao]u?th");
    private static final String KEYWORD_PAYLOAD_PREFIX = "KW_";
    private static final int KEYWORD_MAX_LENGTH = 40;

    /** Typed text -> journey path, ya null. Exact word match, taaki naam/date wale jawab journey na todein. */
    private static String keywordPath(String text) {
        if (text == null || text.length() > KEYWORD_MAX_LENGTH) return null;
        String w = text.toLowerCase().replaceAll("[^a-z]", "");
        return switch (w) {
            case "wife"    -> TEMPLATE_WIFE;
            case "husband" -> TEMPLATE_HUSBAND;
            case "sparkle" -> TEMPLATE_SPARKLE;
            // "karva chauth" -> Wife. Nahi chahiye toh ye line hata do:
            default        -> KARVA_KEYWORD.matcher(w).matches() ? TEMPLATE_WIFE : null;
        };
    }

//    private static boolean isOpenerKeyword(String text) {
//        if (text == null || text.length() > KEYWORD_MAX_LENGTH) return false;   // lambe jawab journey nahi todenge
//        String letters = text.toLowerCase().replaceAll("[^a-z]", "");
//        return OPENER_KEYWORD.matcher(letters).find();
//    }
    /**
     * The Step 0 template — sent from the Karix portal, not by us, so we first see it when its first
     * delivery receipt reaches the webhook. Saves the customer, a session on OPENER and the template
     * message (on OPENER, linked to that session).
     *
     * No new session if the template FAILED (she never got it), or if she's in the middle of a journey —
     * her current buttons must keep working; tapping the new template starts a fresh journey anyway.
     */
    @Override
    public void recordOpenerTemplate(String phone, String mid, String templateId,
                                     String status, String errorCode, String errorReason) {
        String to = normalizePhone(phone);
        if (to == null || mid == null) {
            log.warn("recordOpenerTemplate: missing phone/mid");
            return;
        }

//        // Re-engagement template opener nahi hai — naya OPENER session mat banao
//        Reminder reminder = resolveReminder(templateId);
//        if (reminder != null) {
//            try {
//                recordReminderTemplate(to, reminder, mid, templateId, status, errorCode, errorReason);
//            } catch (Exception e) {
//                log.error("Failed to record reminder template mid={}", mid, e);
//            }
//            return;
//        }

        // Re-engagement / occasion template: apne session par save, naya OPENER session mat banao.
        // OPENER_NO_TAP (path null) opener jaisa hi hai -> neeche wala normal opener logic chalne do.
        Reminder reengagement = resolveReminder(templateId);
        if (reengagement != null && reengagement.path() != PATH_OPENER) {
            try {
                recordReminderTemplate(to, reengagement, mid, templateId, status, errorCode, errorReason);
            } catch (Exception e) {
                log.error("Failed to record reminder template mid={}", mid, e);
            }
            return;
        }



        try {
            Customer customer = findOrCreateCustomer(to);
            boolean failed = MSG_STATUS_FAILED.equals(status);

            Session session = null;
            if (!failed) {
                Optional<Session> active = sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
                if (active.isEmpty()) {
                    session = sessionRepository.save(Session.builder()
                            .customerId(customer.getId())
                            .phone(to)
                            .currentStep(STEP_OPENER)            // path stays null until she taps
                            .build());
                    log.info("stage=OPENER_SESSION_CREATED sessionId={}", session.getId());
                } else if (STEP_OPENER.equals(active.get().getCurrentStep()) && active.get().getPath() == null) {
                    session = active.get();                      // an earlier template she never tapped
                } else {
                    log.info("stage=OPENER_TEMPLATE_MID_JOURNEY sessionId={} step={} — current journey kept",
                            active.get().getId(), active.get().getCurrentStep());
                }
            }

            messageRepository.save(Message.builder()
                    .customerId(customer.getId())
                    .sessionId(session == null ? null : session.getId())
                    .phone(to)
                    .direction(DIR_OUTBOUND)
                    .karixMessageId(mid)
                    .status(status)
                    .step(STEP_OPENER)
                    .messageType("template")
                    .templateName(templateId)
                    .errorCode(failed ? errorCode : null)
                    .errorReason(failed && errorReason != null
                            ? errorReason.substring(0, Math.min(errorReason.length(), 500)) : null)
                    .statusUpdatedAt(LocalDateTime.now())
                    .build());
            log.info("stage=OPENER_TEMPLATE_RECORDED customerId={} sessionId={} template={} status={}",
                    customer.getId(), session == null ? null : session.getId(), templateId, status);
        } catch (Exception e) {
            log.error("Failed to record the Step 0 template mid={}", mid, e);
        }
    }


    // ==================================================================
    // RE-ENGAGEMENT / OCCASION TEMPLATES (Karix campaign)
    //
    // Karix template bhejta hai -> user button dabata hai -> PAYLOAD hamare server par aata hai
    // -> us journey ka session wapas active -> wahi PURANA function aage chalta hai.
    //
    // Naya template: (1) Reminder banao, (2) REMINDERS mein daalo, (3) resolveReminder() mein case.
    // ==================================================================

    /** Reminder.path ke special values */
    private static final String PATH_OPENER = null;   // opener jaisa: buttons = opener payloads (step0Path handle karta hai)
    private static final String PATH_ANY    = "*";    // koi bhi journey: aakhri session; naya user -> Sparkle

    /**
     * @param path    TEMPLATE_WIFE / TEMPLATE_HUSBAND / TEMPLATE_SPARKLE, ya PATH_OPENER / PATH_ANY
     * @param step    DB / dashboard mein is template ka step naam
     * @param body    test message: {name} = ", Priya" (ya khaali), {days} = Karwa Chauth tak din
     * @param buttons {payload, buttonTitle, realPayload} — tap par realPayload ka flow chalta hai
     */
    private record Reminder(String path, String step, String body, String[][] buttons) { }

    // ---------- 1. Wife: list bana, Ishara nahi bheja ----------
    private static final Reminder W_LIST_STARTED = new Reminder(
            TEMPLATE_WIFE, "W_REMINDER_LIST_STARTED",
            "Your Ishara's almost ready{name} ✨\n"
                    + "You've already picked out pieces you love - all that's left is turning them into a hint he can't miss. "
                    + "Only {days} days to Karwa Chauth.\n"
                    + "Finish sharing your Ishara now, so he knows exactly what to get you this year.",
            new String[][]{
                    {"W_REMINDER_SHARE_ISHARA", "💌 Share my Ishara", "W_SEND_ISHARA"},
                    {"W_REMINDER_VISIT_STORE",  "📍 Visit store",        "BTN_STORE_FINDER"}});

    // ---------- 2. Wife: Ishara bheja, phir chali gayi ----------
    private static final Reminder W_HINT_SENT = new Reminder(
            TEMPLATE_WIFE, "W_REMINDER_HINT_SENT",
            "You've done your part{name} 🌙\n"
                    + "Your Ishara's with him now - he knows exactly what to get you. "
                    + "If he hasn't picked up on it yet, a quick resend keeps it top of mind.\n"
                    + "Want to see it up close yourself? You're always welcome to swing by a Mia store - no rush, no reason needed.",
            new String[][]{
                    {"W_REMINDER_RESEND_ISHARA", "\uD83D\uDC8C Resend my Ishara", "W_SEND_ISHARA"},
                    {"W_REMINDER_SEE_IN_PERSON", "\uD83D\uDC40 See it in person", "BTN_STORE_FINDER"}});

    // ---------- 3. Husband: budget chuna, Buy Online / Visit Store nahi ----------
    private static final Reminder H_BUDGET_PICKED = new Reminder(
            TEMPLATE_HUSBAND, "H_REMINDER_BUDGET_PICKED",
            "Still deciding{name}? 💛\n"
                    + "You were this close to picking something she'd love - don't let it slip past Karwa Chauth. "
                    + "Complete it online, or walk into a Mia store and see it in person.\n"
                    + "Only {days} days left to make her day special.",
            new String[][]{
                    {"H_REMINDER_BUY_ONLINE",  "\uD83D\uDECD Buy Online",  "IDLE_BROWSE_AGAIN"},
                    {"H_REMINDER_VISIT_STORE", "📍 Visit store", "BTN_STORE_FINDER"}});

    // ---------- 4. Sparkle: Add to my list kiya, phir chali gayi ----------
    private static final Reminder S_LIST_ADDED = new Reminder(
            TEMPLATE_SPARKLE, "S_REMINDER_LIST_ADDED",
            "That piece is still waiting for you{name} ✨\n"
                    + "You added it to your list, and it's every bit as beautiful as when you found it. "
                    + "Come see it in person - book a quick visit and it'll be set aside, just for you.\n"
                    + "Only a few days left for this festive season.",
            new String[][]{
                    {"S_REMINDER_VISIT_STORE",   "📍 Visit store",   "BTN_STORE_FINDER"},
                    {"S_REMINDER_KEEP_BROWSING", "Keep browsing", "IDLE_BROWSE_AGAIN"}});

    // ---------- 5. Opener mila, koi button nahi dabaya (buttons = opener ke hi payloads) ----------
    private static final Reminder OPENER_NO_TAP = new Reminder(
            PATH_OPENER, "OPENER_REMINDER",
            "Karwa Chauth is only {days} days away{name} ✨\n"
                    + "Still haven't found the piece that says it all? Your Ishara's ready whenever you are.\n"
                    + "Pick a path, and let's find something beautiful - for her, or for you.",
            new String[][]{
                    {"BTN_CELEBRATING",      "💛 I'm celebrating",  "BTN_CELEBRATING"},
                    {"BTN_SHOPPING_FOR_HER", "🎁 Shopping for her", "BTN_SHOPPING_FOR_HER"},
                    {"BTN_SPARKLE",          "✨ Here for sparkle",  "BTN_SPARKLE"}});

    // ---------- 6. Occasion: Happy Karwa Chauth ----------
    private static final Reminder OCCASION_KARWA_CHAUTH = new Reminder(
            PATH_ANY, "OCCASION_KARWA_CHAUTH",
            "Happy Karwa Chauth{name}! 🌙\n"
                    + "Wishing you and your partner a day filled with warmth and grace, from all of us at Mia.",
            new String[][]{
                    {"OCCASION_VISIT_STORE", "📍 Visit store", "BTN_STORE_FINDER"}});

    // ---------- 7. Occasion: Festive season ----------
    private static final Reminder OCCASION_FESTIVE = new Reminder(
            PATH_ANY, "OCCASION_FESTIVE",
            "Wishing you a sparkling festive season{name}! ✨ "
                    + "Here's to treating yourself to something beautiful this season, from all of us at Mia.",
            new String[][]{
                    {"OCCASION_VISIT_STORE", "📍 Visit store", "BTN_STORE_FINDER"}});

    private static final List<Reminder> REMINDERS = List.of(
            W_LIST_STARTED, W_HINT_SENT, H_BUDGET_PICKED, S_LIST_ADDED,
            OPENER_NO_TAP, OCCASION_KARWA_CHAUTH, OCCASION_FESTIVE);

    /** reminder button payload -> Reminder / asli payload. Opener-type yahan nahi (step0Path handle karta hai). */
    private static final Map<String, Reminder> REMINDER_BY_PAYLOAD = new HashMap<>();
    private static final Map<String, String> REMINDER_REAL_PAYLOAD = new HashMap<>();

    static {
        for (Reminder r : REMINDERS) {
            if (r.path() == PATH_OPENER) continue;
            for (String[] b : r.buttons()) {
                REMINDER_BY_PAYLOAD.put(b[0], r);
                REMINDER_REAL_PAYLOAD.put(b[0], b[2]);
            }
        }
    }

    @Value("${karvachauth.karwa-chauth-date:2026-10-29}")
    private String karwaChauthDate;



    private Reminder resolveReminder(String templateId) {
        if (templateId == null || templateId.isBlank()) return null;
        return switch (templateId.trim().toLowerCase(Locale.ROOT)) {
            // ----- Karix production template IDs -----
            case "mia_karwa_chauth_c1", "mia_karwa_chauth_nudge_c1_oct"                           -> W_LIST_STARTED;     // C1 wife: list bana, Ishara nahi bheja
            case "mia_karwa_chauth_c2", "mia_karwa_chauth_nudge_c2_oct"                           -> W_HINT_SENT;        // C2 wife: Ishara bheja
            case "mia_karwa_chauth_c3h", "mia_karwa_chauth_nudge_c3h_oct"                         -> H_BUDGET_PICKED;    // C3-H husband
            case "mia_karwa_chauth_c3sp", "mia_karwa_chauth_nudge_c3sp",
                 "mia_karwa_chauth_nudge_c3sp_oct"                                                -> S_LIST_ADDED;       // C3-Sp sparkle
            case "mia_karwa_chauth_c4_oct"                                                        -> OPENER_NO_TAP;      // C4: opener mila, tap nahi kiya
            case "mia_karwa_chauth_final"                                                         -> OCCASION_KARWA_CHAUTH; // TP4: Karwa Chauth day

            // ----- testing names (WhatsApp par type karke test) -----
            case "list started, hint not sent", "list_started_hint_not_sent"             -> W_LIST_STARTED;
            case "hint sent", "hint_sent"                                                 -> W_HINT_SENT;
            case "budget picked", "budget_picked"                                         -> H_BUDGET_PICKED;
            case "sparkle list added", "sparkle_list_added"                               -> S_LIST_ADDED;
            case "opener delivered, no interaction", "opener_delivered_no_interaction"   -> OPENER_NO_TAP;
            case "happy karwa chauth wishes", "happy_karwa_chauth_wishes"                 -> OCCASION_KARWA_CHAUTH;
            case "festive season wishes", "festive_season_wishes"                         -> OCCASION_FESTIVE;
            default -> null;
        };
    }

    /** {name} -> ", Priya" (naam nahi toh khaali), {days} -> Karwa Chauth tak bache din. */
    private String fillReminderText(String body, String customerName) {
        long days;
        try {
            days = Math.max(0, ChronoUnit.DAYS.between(
                    LocalDate.now(ZoneId.of("Asia/Kolkata")), LocalDate.parse(karwaChauthDate.trim())));
        } catch (Exception e) {
            log.warn("karvachauth.karwa-chauth-date invalid: {}", karwaChauthDate);
            days = 0;
        }
        String name = (customerName == null || customerName.isBlank()) ? "" : ", " + customerName.trim();
        return body.replace("{name}", name).replace("{days}", String.valueOf(days));
    }

    /** Aakhri session: path diya hai toh usi path ka, warna (opener / any) koi bhi. */
    private Optional<Session> lastSession(String to, String path) {
        return (path == null || PATH_ANY.equals(path))
                ? sessionRepository.findFirstByPhoneOrderByIdDesc(to)
                : sessionRepository.findFirstByPhoneAndPathOrderByIdDesc(to, path);
    }

    /**
     * Journey dobara chalu.
     *  - PATH_ANY: active session hai toh wahi, warna aakhri session wapas active.
     *  - path diya: active usi path ka toh wahi, warna us path ka aakhri session wapas + doosri journey band.
     */
    private Optional<Session> resumeSession(String to, String path) {
        Optional<Session> active = sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
        if (active.isPresent() && active.get().getPath() != null
                && (PATH_ANY.equals(path) || path.equals(active.get().getPath()))) {
            return active;
        }
        Optional<Session> last = PATH_ANY.equals(path)
                ? sessionRepository.findFirstByPhoneOrderByIdDesc(to).filter(s -> s.getPath() != null)
                : sessionRepository.findFirstByPhoneAndPathOrderByIdDesc(to, path);
        if (last.isEmpty()) {
            return Optional.empty();
        }
        Session s = last.get();
        active.filter(other -> !other.getId().equals(s.getId())).ifPresent(other -> {
            other.setIsActive(false);
            sessionRepository.save(other);
            log.info("stage=SESSION_CLOSED sessionId={} reason=REMINDER oldPath={}", other.getId(), other.getPath());
        });
        s.setIsActive(true);
        s.setLastInboundAt(LocalDateTime.now());
        s.setIdleNudgeSent(false);
        sessionRepository.save(s);
        log.info("stage=SESSION_RESUMED sessionId={} path={} step={}", s.getId(), s.getPath(), s.getCurrentStep());
        return Optional.of(s);
    }

    /** Naya user occasion template ka button dabaye -> Sparkle path ka naya session (store visit ke liye). */
    private Session newSparkleSession(String to) {
        Customer customer = findOrCreateCustomer(to);
        sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to).ifPresent(other -> {
            other.setIsActive(false);
            sessionRepository.save(other);
        });
        Session s = sessionRepository.save(Session.builder()
                .customerId(customer.getId())
                .phone(to)
                .path(TEMPLATE_SPARKLE)
                .currentStep(STEP_OPENER)
                .build());
        log.info("stage=SESSION_CREATED sessionId={} path=SPARKLE reason=OCCASION_TEMPLATE", s.getId());
        return s;
    }

    /** Karix ne template bheja (pehli delivery receipt) -> aakhri session par save (session active nahi karte). */
    private void recordReminderTemplate(String to, Reminder r, String mid, String templateId,
                                        String status, String errorCode, String errorReason) {
        Customer customer = findOrCreateCustomer(to);
        Long sessionId = lastSession(to, r.path()).map(Session::getId).orElse(null);
        boolean failed = MSG_STATUS_FAILED.equals(status);
        messageRepository.save(Message.builder()
                .customerId(customer.getId())
                .sessionId(sessionId)
                .phone(to)
                .direction(DIR_OUTBOUND)
                .karixMessageId(mid)
                .status(status)
                .step(r.step())
                .messageType("template")
                .templateName(templateId)
                .errorCode(failed ? errorCode : null)
                .errorReason(failed && errorReason != null
                        ? errorReason.substring(0, Math.min(errorReason.length(), 500)) : null)
                .statusUpdatedAt(LocalDateTime.now())
                .build());
        log.info("stage=REMINDER_TEMPLATE_RECORDED step={} customerId={} sessionId={} status={}",
                r.step(), customer.getId(), sessionId, status);
    }

    /** TEST: template ka naam type karo -> wahi message free-form (24h window ke andar) aata hai. */
    private boolean sendReminderTest(String to, Reminder r) {
        Customer customer = findOrCreateCustomer(to);
        Long sessionId = lastSession(to, r.path()).map(Session::getId).orElse(null);

        String body = fillReminderText(r.body(), customer.getName());
        List<String[]> buttons = Arrays.stream(r.buttons())
                .map(b -> new String[]{b[0], b[1]})
                .toList();
        String mid = karixService.sendButtonMessage(to, body, buttons);

        messageRepository.save(Message.builder()
                .customerId(customer.getId())
                .sessionId(sessionId)
                .phone(to)
                .direction(DIR_OUTBOUND)
                .karixMessageId(mid)
                .status(mid != null ? MSG_STATUS_SENT : MSG_STATUS_FAILED)
                .step(r.step())
                .messageType("button")
                .statusUpdatedAt(LocalDateTime.now())
                .build());
        log.info("stage=REMINDER_TEST_SENT step={} sessionId={} sent={}", r.step(), sessionId, mid != null);
        return mid != null;
    }


    /**
     * She tapped a Step 0 button ("I'm celebrating" / "Shopping for her" / "Here for the sparkle").
     *
     *   1. Her OPENER session (made when the template arrived) gets the path. If there's none — or she's
     *      already on a journey — the old one is closed and a new OPENER session is started.
     *   2. Her tap is saved on OPENER, with the button's payload and text.
     *   3. "Choose my gift" / "Find her a gift" / "Show me" is sent, also saved on OPENER.
     *
     * The session STAYS on OPENER — it moves to W_CHOOSE_MY_GIFT / H_FIND_HER_GIFT / S_SHOW_ME when she
     * taps that button, or to BOOK_STORE_VISIT when she taps "Visit nearby store" (onButtonTap).
     *
     * payload = null when started from /test (no real tap) — then step 2 is skipped.
     */
    @Override
    public void startFromOpener(String phone, String name, String path, String payload, String title) {
        lastOutbound.remove();
        String to = normalizePhone(phone);
        if (to == null) {
            log.warn("startFromOpener: invalid phone");
            return;
        }



        String text;
        List<String[]> buttons;
        switch (path == null ? "" : path) {
            case TEMPLATE_WIFE -> {
                text = "How lovely! Choose a piece that feels like you - "
                        + "we'll make sure he knows exactly what to gift you this Karwa Chauth.";
                buttons = List.of(
                        new String[]{"W_CHOOSE_MY_GIFT", "💛 Choose my gift"},
                        new String[]{"BTN_STORE_FINDER", "📍 Visit Store"});
            }
            case TEMPLATE_HUSBAND -> {
                text = "Let's find her something she'll cherish. Shall we take a look together?";
                buttons = List.of(
                        new String[]{"H_FIND_HER_GIFT", "💛 Find her a gift"},
                        new String[]{"BTN_STORE_FINDER", "📍 Visit Store"});
            }
            case TEMPLATE_SPARKLE -> {
                text = "Who needs an occasion anyway? Let's find something beautiful for you. ✨";
                buttons = List.of(
                        new String[]{"S_SHOW_ME", "✨ Show me"},
                        new String[]{"BTN_STORE_FINDER", "📍 Visit Store"});
            }
            default -> {
                log.warn("startFromOpener: unknown path={}", path);
                return;
            }
        }

        Customer customer = getOrCreateCustomer(to, name, path);

        // 1. Her OPENER session from the template receipt, or a fresh one
        Optional<Session> active = sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
        Session session;
        if (active.isPresent() && STEP_OPENER.equals(active.get().getCurrentStep()) && active.get().getPath() == null) {
            session = active.get();
            session.setPath(path);
            session.setLastInboundAt(LocalDateTime.now());
            session.setIdleNudgeSent(false);
            session = sessionRepository.save(session);
            log.info("stage=OPENER_PATH_CHOSEN sessionId={} path={}", session.getId(), path);
        } else {
            session = startNewSession(customer, path, STEP_OPENER);   // closes any older session
            linkOpenerTemplate(session);
        }

        // 2. Her Step 0 tap, on OPENER
        if (payload != null && !payload.isBlank()) {
            recordInbound(session, payload.trim(), title);
        }

        // 3. The path's first message, on OPENER — the session stays on OPENER until she taps it
        String mid = karixService.sendButtonMessage(to, text, buttons);
        recordOutbound(session, "interactive", text, mid);

        log.info("stage=OPENER_ANSWERED sessionId={} path={} payload={} step={} sent={}",
                session.getId(), path, payload, session.getCurrentStep(), mid != null);
    }

    /** /test only — starts the Wife journey as if Step 0 was tapped (there's no real tap to save). */
    @Override
    public void startWifeFlow(String phone, String name) {
        startFromOpener(phone, name, TEMPLATE_WIFE, null, null);
    }

    /** /test only — starts the Husband journey as if Step 0 was tapped. */
    @Override
    public void startHusbandFlow(String phone, String name) {
        startFromOpener(phone, name, TEMPLATE_HUSBAND, null, null);
    }

    /** /test only — starts the Sparkle journey as if Step 0 was tapped. */
    @Override
    public void startSparkleFlow(String phone, String name) {
        startFromOpener(phone, name, TEMPLATE_SPARKLE, null, null);
    }



    @Override
    public boolean onButtonTap(String phone, String path, String payload) {
        lastOutbound.remove();
        String to = normalizePhone(phone);
        if (to == null || payload == null || payload.isBlank()) {
            log.warn("onButtonTap: missing phone/payload");
            return false;
        }
        // Carousel buttons come back with "_<cardIndex>" appended (CAT_PEND_0) — strip it before routing
        String p = payload.trim().replaceAll("_\\d+$", "");

//        // Re-engagement template ka button -> us path ka aakhri session wapas, phir asli payload ka flow
//        Reminder reminder = REMINDER_BY_PAYLOAD.get(p);
//        Optional<Session> found = reminder != null
//                ? resumeSession(to, reminder.path())
//                : sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
//
//        if (found.isEmpty()) {
//            if (reminder != null) {
//                // Is path par kabhi journey hi nahi ki -> shuru se
//                log.info("stage=REMINDER_NO_SESSION path={} — starting journey", reminder.path());
//                startFromOpener(to, null, reminder.path(), payload.trim(), null);
//                return true;
//            }
//            log.warn("stage=NO_ACTIVE_SESSION payload={} — send the opener first", p);
//            return false;
//        }
//
//        Session session = found.get();
//        if (reminder != null) {
//            String real = REMINDER_REAL_PAYLOAD.get(p);
//            log.info("stage=REMINDER_TAP sessionId={} step={} payload={} -> {}", session.getId(), reminder.step(), p, real);
//            p = real;   // W_SEND_ISHARA / BTN_STORE_FINDER / IDLE_BROWSE_AGAIN -> purana flow aage chalega
//        }


        // Re-engagement / occasion template ka button -> journey wapas, phir asli payload ka flow
        Reminder reminder = REMINDER_BY_PAYLOAD.get(p);
        Optional<Session> found = reminder != null
                ? resumeSession(to, reminder.path())
                : sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);

        if (found.isEmpty()) {
            if (reminder != null && PATH_ANY.equals(reminder.path())) {
                // Occasion template, naya user -> Sparkle session, phir neeche store visit chalega
                found = Optional.of(newSparkleSession(to));
            } else if (reminder != null) {
                // Is journey par kabhi aayi hi nahi -> shuru se
                log.info("stage=REMINDER_NO_SESSION path={} — starting journey", reminder.path());
                startFromOpener(to, null, reminder.path(), payload.trim(), null);
                return true;
            } else {
                log.warn("stage=NO_ACTIVE_SESSION payload={} — send the opener first", p);
                return false;
            }
        }
        Session session = found.get();
        if (reminder != null) {
            String real = REMINDER_REAL_PAYLOAD.get(p);
            log.info("stage=REMINDER_TAP sessionId={} step={} payload={} -> {}", session.getId(), reminder.step(), p, real);
            p = real;   // W_SEND_ISHARA / BTN_STORE_FINDER / IDLE_BROWSE_AGAIN -> purana flow aage chalega
        }


        if (path != null && !path.equals(session.getPath())) {
            log.warn("stage=PATH_MISMATCH requested={} session={} — using the session's path", path, session.getPath());
        }

        // Taps that ARE a step ("Choose my gift", a category …) move the session to that step first,
        // so the tap and our reply to it are both saved on it. Every other tap is saved on the step she was on.
        String tapStep = stepForTap(session.getPath(), p);
        if (tapStep != null) {
            session.setCurrentStep(tapStep);
        }

        // Record the tap (original payload, with suffix) and reset the idle timer
        recordInbound(session, payload.trim());
        session.setLastInboundAt(LocalDateTime.now());
        session.setIdleNudgeSent(false);
        log.info("stage=BUTTON_TAP sessionId={} path={} step={} payload={}",
                session.getId(), session.getPath(), session.getCurrentStep(), p);

        // Still on the Step 0 template with no path picked (e.g. she tapped a button from an old,
        // finished journey) — nothing to route to until she taps one of the template's buttons
        if (session.getPath() == null) {
            log.warn("stage=NO_PATH_YET sessionId={} payload={} — waiting for a Step 0 tap", session.getId(), p);
            sessionRepository.save(session);
            return false;
        }

        boolean handled = false;

        // Shared buttons — same behaviour on every path
        if (isStoreVisitTap(p)) {
            // C2 — session is now on BOOK_STORE_VISIT (stepForTap). Ask her to share her location;
            // for a product card, p.substring(6) is the SKU she tapped — reserved with the booking.
            if (p.startsWith("VISIT_")) session.setSelectedProductSku(p.substring(6));
            handled = askForLocation(session);
//        } else if (p.startsWith("STORE_")) {
//            // C2 — she picked one of the nearby stores from the list → ask for date & time
//            handled = onStorePicked(session, p.substring(6));
        } else if ("TEST_C2_BOOKED".equals(p)) {
            // TEST ONLY — pretends the C2 booking just completed, so 1I can be tested before C2 exists.
            // No real button ever sends this. Delete this branch once C2 is built.
            log.info("stage=TEST_C2_BOOKED sessionId={} — simulating a completed store booking", session.getId());
            handled = afterStoreVisitBooked(session);
        } else if ("ANOTHER_CATEGORY".equals(p)) {
            // Back to the category carousel for this path (1A / 2A / 1A-Sparkle)
            handled = switch (session.getPath()) {
                case TEMPLATE_WIFE    -> wifePickCategory(session);
                case TEMPLATE_HUSBAND -> husbandPickCategory(session);
                case TEMPLATE_SPARKLE -> sparklePickCategory(session);
                default -> false;
            };
        } else if ("IDLE_BROWSE_AGAIN".equals(p)) {
            // IDLE nudge "Browse again" — pick up where she left off
            handled = browseAgain(session);
        } else if ("SEE_MORE".equals(p)) {
            // Next 9 products from the category being browsed
            handled = seeMore(session);
        } else if ("ADD_MORE".equals(p)) {
            // 1D — re-open the same category. Checked here, BEFORE the "ADD_" prefix below,
            // otherwise "ADD_MORE" would be read as "add product with SKU MORE".
            handled = addMore(session);
        } else {
            switch (session.getPath()) {
                case TEMPLATE_WIFE -> {
                    // 1 — "Choose my gift": OPENER → W_CHOOSE_MY_GIFT (categories shown)
                    if ("W_CHOOSE_MY_GIFT".equals(p))  handled = wifePickCategory(session, STEP_W_CHOOSE_MY_GIFT);
                    else if (p.startsWith("CAT_"))     handled = wifeBrowseProducts(session, p.substring(4));
                    else if (p.startsWith("ADD_"))     handled = addToList(session, p.substring(4));
                    else if ("W_SEND_ISHARA".equals(p)) handled = sendIshara(session);
                        // 1H → 1I
                    else if ("W_MAYBE_LATER".equals(p)) handled = wifeReinforce(session);
                        // 1I / 1I-nudge → 1J (both buttons start capturing his details)
                    else if ("W_ADD_HIS_DETAILS".equals(p) || "W_YES_GO_AHEAD".equals(p)) handled = askHusbandName(session);
                        // 1I "No thanks" → 1I-nudge
                    else if ("W_NO_THANKS".equals(p))   handled = wifeReinforceNudge(session);
                        // 1I-nudge "I'll remind him" → E1
                    else if ("W_ILL_REMIND_HIM".equals(p)) handled = closeJourney(session);
                        // 1K consent → E1
                    else if ("W_CONSENT_YES".equals(p)) handled = onHusbandConsent(session, true);
                    else if ("W_CONSENT_NO".equals(p))  handled = onHusbandConsent(session, false);
                }
                case TEMPLATE_HUSBAND -> {
                    // 2 — "Find her a gift": OPENER → H_FIND_HER_GIFT (categories shown)
                    if ("H_FIND_HER_GIFT".equals(p))   handled = husbandPickCategory(session, STEP_H_FIND_HER_GIFT);
                    else if (p.startsWith("CAT_"))     handled = husbandChooseBudget(session, p.substring(4));    // 2A → 2A-Budget
                    else if (p.startsWith("H_BUDGET_")) handled = husbandBrowseProducts(session, p.substring(9)); // 2A-Budget → 2B
                    else if (p.startsWith("BUY_"))      handled = husbandConfirmChoice(session, p.substring(4));  // 2B → 2C
                    else if ("H_BUY_ONLINE".equals(p))  handled = husbandBuyOnline(session);                     // 2C → link, then 2E
                    else if ("H_NO_THANKS".equals(p))   handled = closeJourney(session);                         // 2E → E1
                    else if ("H_ADD_HER_DETAILS".equals(p)) handled = askWifeName(session);                      // 2E → 2F Q1
                    else if ("H_CONSENT_YES".equals(p)) handled = onWifeConsent(session, true);                  // 2G → E1
                    else if ("H_CONSENT_NO".equals(p))  handled = onWifeConsent(session, false);                 // 2G → E1
                }
                case TEMPLATE_SPARKLE -> {
                    // 1-Sparkle — "Show me": OPENER → S_SHOW_ME (categories shown)
                    if ("S_SHOW_ME".equals(p))         handled = sparklePickCategory(session, STEP_S_SHOW_ME);
                    else if (p.startsWith("CAT_"))      handled = sparkleChooseBudget(session, p.substring(4));    // 1A → 1A-Budget
                    else if (p.startsWith("S_BUDGET_")) handled = sparkleBrowseProducts(session, p.substring(9));  // 1A-Budget → 1B
                    else if (p.startsWith("ADD_"))     handled = addToList(session, p.substring(4));   // ready for when Sparkle 1B is built
                        // 1I (Sparkle) — reached after C2, which is built later
                    else if ("S_KEEP_POSTED".equals(p)) handled = sparkleKeepPosted(session);
                    else if ("S_NO_THANKS".equals(p))   handled = sparkleNoThanks(session);
                }
                default -> log.warn("onButtonTap: unknown path={}", session.getPath());
            }
            if (!handled) {
                log.warn("stage=UNHANDLED_PAYLOAD sessionId={} path={} payload={}", session.getId(), session.getPath(), p);
            }
        }

        // Save step (if it moved), category, lastInboundAt, idleNudgeSent
        sessionRepository.save(session);
        return handled;
    }

    /**
     * The step a tap itself stands for, or null if the tap is just an answer on the current step.
     *
     *   "Choose my gift" / "Find her a gift" / "Show me"  → W_CHOOSE_MY_GIFT / H_FIND_HER_GIFT / S_SHOW_ME
     *   a category ("Browse these", CAT_*)                → W_PICK_CATEGORY / H_PICK_CATEGORY / S_PICK_CATEGORY
     *
     *   "Visit nearby store" / "Visit Store" (BTN_STORE_FINDER, VISIT_<sku>) → BOOK_STORE_VISIT, on every path
     *
     * The reply then moves the session on (products → W_BROWSE_PRODUCTS, budget buttons → H_BUDGET / S_BUDGET),
     * but the tap and the reply stay saved on the step she chose.
     *
     * C2 (Book a store visit): the store tap and the booking form sent after it are both saved on
     * BOOK_STORE_VISIT — that step means "started booking". The booking is complete only when she submits
     * the form: that writes a Booking row and a STORE_VISIT_BOOKED lead, sends the confirmed card, and then
     * afterStoreVisitBooked() moves her on (1I / 2E).
     */
    private static String stepForTap(String path, String p) {
        if (path == null) return null;
        if (isStoreVisitTap(p)) return STEP_BOOK_STORE_VISIT;
        boolean category = p.startsWith("CAT_");
        return switch (path) {
            case TEMPLATE_WIFE    -> "W_CHOOSE_MY_GIFT".equals(p) ? STEP_W_CHOOSE_MY_GIFT
                    : category ? STEP_W_PICK_CATEGORY : null;
            case TEMPLATE_HUSBAND -> "H_FIND_HER_GIFT".equals(p) ? STEP_H_FIND_HER_GIFT
                    : category ? STEP_H_PICK_CATEGORY : null;
            case TEMPLATE_SPARKLE -> "S_SHOW_ME".equals(p) ? STEP_S_SHOW_ME
                    : category ? STEP_S_PICK_CATEGORY : null;
            default -> null;
        };
    }

    /**
     * C2 — "Visit nearby store" / "Visit Store" tapped: asks her to share her current location.
     * The message is saved on BOOK_STORE_VISIT. Her location comes back as a "location" message
     * → onLocationShared() → the 3 nearest Mia stores.
     */
    private boolean askForLocation(Session session) {
        String text = "Let's find a Mia store near you 📍 "
                + "Tap *Send location* below to share your current location.";
        String mid = karixService.sendLocationRequest(session.getPhone(), text);
        recordOutbound(session, "interactive", text, mid);
        log.info("stage=LOCATION_REQUEST_SENT sessionId={} path={} step={} sent={}",
                session.getId(), session.getPath(), session.getCurrentStep(), mid != null);
        return mid != null;
    }

    /** "Visit nearby store" / "📍 Visit Store" (BTN_STORE_FINDER) or "Visit Store" on a product card (VISIT_<sku>). */
    private static boolean isStoreVisitTap(String p) {
        return "BTN_STORE_FINDER".equals(p) || p.startsWith("VISIT_");
    }

    // ==================================================================
    // C2 — BOOK A STORE VISIT
    //
    //   "Visit Store" tap        → location request                     BOOK_STORE_VISIT
    //   she shares her location  → 3 nearest stores (list message)      STORE_PICK
    //   she picks a store        → bookings row PENDING, ask date/time  STORE_DATETIME
    //   valid date & time        → bookings row CONFIRMED, lead,
    //                              "Your visit is booked"               STORE_BOOKED
    //   5 seconds later          → 1I (Wife / Sparkle) or 2E (Husband)  (afterStoreVisitBooked)
    // ==================================================================

    /** C2 — she shared her location (webhook "location" message). Saves it and sends the 3 nearest stores. */
    @Override
    public boolean onLocationShared(String phone, String latitude, String longitude) {
        lastOutbound.remove();
        String to = normalizePhone(phone);
        Double lat = parseCoordinate(latitude, 90);
        Double lng = parseCoordinate(longitude, 180);
        if (to == null || lat == null || lng == null) {
            log.warn("stage=LOCATION_INVALID — missing phone or bad latitude/longitude");
            return false;
        }
        Optional<Session> found = sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
        if (found.isEmpty() || found.get().getPath() == null) {
            log.warn("stage=LOCATION_NO_SESSION — no active journey for this location");
            return false;
        }
        Session session = found.get();

        try {
            messageRepository.save(Message.builder()
                    .customerId(session.getCustomerId())
                    .sessionId(session.getId())
                    .phone(session.getPhone())
                    .direction(DIR_INBOUND)
                    .status(MSG_STATUS_READ)
                    .step(session.getCurrentStep())
                    .path(session.getPath())
                    .messageType("location")
                    .contentText(lat + "," + lng)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record location sessionId={}", session.getId(), e);
        }
        session.setLastInboundAt(LocalDateTime.now());
        session.setIdleNudgeSent(false);
        log.info("stage=LOCATION_IN sessionId={} path={} step={}", session.getId(), session.getPath(), session.getCurrentStep());

        boolean handled = sendNearbyStores(session, lat, lng);
        sessionRepository.save(session);
        return handled;
    }

    /** The 3 nearest stores as a list message (one row per store). No store / API down → "Try again" / "Keep browsing". */
    private boolean sendNearbyStores(Session session, double lat, double lng) {
        List<String[]> stores = findNearbyStores(lat, lng);

        if (stores.isEmpty()) {
            String text = "Sorry, I couldn't find a Mia store near that location right now 😔\n"
                    + "You can share another location, or keep browsing.";
            String mid = karixService.sendButtonMessage(session.getPhone(), text,
                    List.of(
                            new String[]{"BTN_STORE_FINDER", "📍 Try again"},
                            new String[]{"ANOTHER_CATEGORY", "🛍 Keep browsing"}));
            recordOutbound(session, "interactive", text, mid);
            moveTo(session, STEP_BOOK_STORE_VISIT);
            log.warn("stage=NO_NEARBY_STORES sessionId={}", session.getId());
            return mid != null;
        }

//        List<String[]> rows = new ArrayList<>();
//        for (String[] store : stores) {
//            String name = store[0];
//            String city = store[1];
//            String place = name.replaceFirst("(?i)^mia\\s+by\\s+tanishq\\s*,\\s*", "");
//            int comma = place.indexOf(',');
//            String rowTitle = comma > 0 ? place.substring(0, comma).trim() : place;
//            String rowDescription = comma > 0 ? place.substring(comma + 1).trim() : city;
//            String rowId = "STORE_" + city + "|" + name;
//            rows.add(new String[]{
//                    rowId.length() <= 200 ? rowId : rowId.substring(0, 200),
//                    fit(rowTitle, 24),
//                    fit(rowDescription, 72)});
//        }

//        String text = "Here are the Mia stores closest to you 📍\n"
//                + "Pick one, and I'll book your visit.";
//        String mid = karixService.sendListMessage(session.getPhone(), text, "Choose a store", rows);
//        recordOutbound(session, "interactive", text + "\n\n"
//                + stores.stream().map(s -> "• " + s[0]).collect(Collectors.joining("\n")), mid);
//        moveTo(session, STEP_STORE_PICK);
//        log.info("stage=NEARBY_STORES_SENT sessionId={} stores={} sent={}", session.getId(), stores.size(), mid != null);
//        return mid != null;
        // C2 (Flow) — the 3 stores go into the "Book Appointment" WhatsApp Flow: store + date + time in one form
        List<Map<String, Object>> storeList = new ArrayList<>();
        for (String[] store : stores) {
            Map<String, Object> option = new java.util.LinkedHashMap<>();
            option.put("id", fit(store[1] + "|" + store[0], 150));
            option.put("title", flowStoreTitle(store[0]));
            storeList.add(option);
        }
        return sendStoreBookingFlow(session, storeList,
                "Here are the Mia stores closest to you 📍\nTap *Book Store Visit* to pick a store, date and time.");
    }

    /**
     * Calls the nearby-store API: GET {url}?lat=..&lng=..&limit=3 with header X-Api-Key.
     * Returns {shortName, city} per store, nearest first. Empty list if the API fails — never throws.
     */
    private List<String[]> findNearbyStores(double lat, double lng) {
        if (storeApiKey == null || storeApiKey.isBlank()) {
            log.error("stage=STORE_API_NOT_CONFIGURED — set karvachauth.store-api.key");
            return List.of();
        }
        try {
            String url = storeApiUrl + "?lat=" + lat + "&lng=" + lng + "&limit=" + NEARBY_STORE_LIMIT;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-Api-Key", storeApiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = STORE_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("stage=STORE_API_FAILED httpStatus={}", response.statusCode());
                return List.of();
            }
            JsonNode root = STORE_JSON.readTree(response.body());
            if (!root.path("success").asBoolean(false)) {
                log.warn("stage=STORE_API_FAILED success=false");
                return List.of();
            }
            List<String[]> stores = new ArrayList<>();
            for (JsonNode store : root.path("stores")) {
                String name = store.path("shortName").asText("").trim();
                String city = store.path("city").asText("").trim();
                if (!name.isEmpty() && stores.size() < NEARBY_STORE_LIMIT) stores.add(new String[]{name, city});
            }
            log.info("stage=STORE_API_OK count={}", stores.size());
            return stores;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("stage=STORE_API_FAILED — interrupted");
            return List.of();
        } catch (Exception e) {
            log.error("stage=STORE_API_FAILED", e);
            return List.of();
        }
    }

    /**
     * C2 (Flow) — sends the "Book Appointment" WhatsApp Flow with the stores to choose from.
     * minDate = today, so past dates can't be picked. flow_token = "BOOK_<sessionId>".
     */
    private boolean sendStoreBookingFlow(Session session, List<Map<String, Object>> storeList, String text) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("storeList", storeList);
        data.put("minDate", LocalDate.now(IST).toString());
        String mid = karixService.sendFlowMessage(session.getPhone(), text, storeFlowId,
                "BOOK_" + session.getId(), "Book Store Visit", "SCREEN_ONE", data);
        recordOutbound(session, "flow", text + "\n\n" + storeList.stream()
                .map(option -> "• " + option.get("title")).collect(Collectors.joining("\n")), mid);
        moveTo(session, STEP_STORE_PICK);
        log.info("stage=STORE_FLOW_SENT sessionId={} stores={} sent={}", session.getId(), storeList.size(), mid != null);
        return mid != null;
    }

    /** "Mia by Tanishq, Park Street, Kolkata" → "Park Street, Kolkata" (WhatsApp dropdown title: max 30 characters). */
    private static String flowStoreTitle(String storeName) {
        return fit(storeName.replaceFirst("(?i)^mia\\s+by\\s+tanishq\\s*,\\s*", ""), 30);
    }

    /**
     * C2 (Flow) — she submitted the booking form. response_json = {"store": "<city>|<shortName>",
     * "visitDate": "2026-10-20", "timeSlot": "6-7", "flow_token": "BOOK_<sessionId>"}.
     * Valid → bookings row CONFIRMED + STORE_VISIT_BOOKED lead + "Your visit is booked", then 1I / 2E.
     * Past slot / missing value → explains and sends the form again with the store she picked.
     */
    @Override
    public boolean onFlowReply(String phone, String responseJson) {
        lastOutbound.remove();
        String to = normalizePhone(phone);
        if (to == null || responseJson == null || responseJson.isBlank()) {
            log.warn("stage=FLOW_REPLY_INVALID — missing phone or response_json");
            return false;
        }
        Optional<Session> found = sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
        if (found.isEmpty() || found.get().getPath() == null) {
            log.warn("stage=FLOW_REPLY_NO_SESSION — no active journey for this booking form");
            return false;
        }
        Session session = found.get();

        JsonNode answer;
        try {
            answer = STORE_JSON.readTree(responseJson);
        } catch (Exception e) {
            log.warn("stage=FLOW_REPLY_UNREADABLE sessionId={}", session.getId());
            return false;
        }
        String storeId = answer.path("store").asText("").trim();
        String dateText = answer.path("visitDate").asText("").trim();
        String slotId = answer.path("timeSlot").asText("").trim();
        String token = answer.path("flow_token").asText("");

        try {
            messageRepository.save(Message.builder()
                    .customerId(session.getCustomerId())
                    .sessionId(session.getId())
                    .phone(session.getPhone())
                    .direction(DIR_INBOUND)
                    .status(MSG_STATUS_READ)
                    .step(session.getCurrentStep())
                    .path(session.getPath())
                    .messageType("flow")
                    .buttonPayload("FLOW_REPLY")
                    .contentText(storeId + " | " + dateText + " | " + slotId)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record flow reply sessionId={}", session.getId(), e);
        }
        session.setLastInboundAt(LocalDateTime.now());
        session.setIdleNudgeSent(false);
        log.info("stage=FLOW_REPLY_IN sessionId={} path={} step={} tokenMatches={}", session.getId(),
                session.getPath(), session.getCurrentStep(), ("BOOK_" + session.getId()).equals(token));

        int bar = storeId.indexOf('|');
        String city = bar > 0 ? storeId.substring(0, bar).trim() : null;
        String storeName = (bar >= 0 ? storeId.substring(bar + 1) : storeId).trim();
        if (storeName.isEmpty()) {
            boolean sent = askForLocation(session);
            sessionRepository.save(session);
            return sent;
        }
        String[] slot = FLOW_TIME_SLOTS.get(slotId);
        LocalDate visitDate = null;
        try {
            visitDate = LocalDate.parse(dateText.length() > 10 ? dateText.substring(0, 10) : dateText);
        } catch (DateTimeException e) {
            // handled below
        }

        LocalDateTime now = LocalDateTime.now(IST);
        String problem = null;
        if (slot == null || visitDate == null) {
            problem = "I couldn't read the date or time";
        } else if (visitDate.isBefore(now.toLocalDate())) {
            problem = "That date has already passed";
        } else if (visitDate.isAfter(now.toLocalDate().plusDays(VISIT_MAX_DAYS_AHEAD))) {
            problem = "Please pick a date within the next " + VISIT_MAX_DAYS_AHEAD + " days";
        } else if (visitDate.equals(now.toLocalDate()) && Integer.parseInt(slot[1]) <= now.getHour()) {
            problem = "That time slot has already passed today";
        }
        if (problem != null) {
            log.info("stage=FLOW_REPLY_REJECTED sessionId={} reason={}", session.getId(), problem);
            Map<String, Object> option = new java.util.LinkedHashMap<>();
            option.put("id", storeId);
            option.put("title", flowStoreTitle(storeName));
            boolean sent = sendStoreBookingFlow(session, List.of(option),
                    problem + " ⏰ Please pick another date and time for *" + storeName + "*.");
            sessionRepository.save(session);
            return sent;
        }

        Booking booking = bookingRepository.save(Booking.builder()
                .bookingRef(newBookingRef())
                .customerId(session.getCustomerId())
                .sessionId(session.getId())
                .phone(session.getPhone())
                .path(session.getPath())
                .storeName(fit(storeName, 200))
                .city(city == null || city.isEmpty() ? null : fit(city, 50))
                .visitDate(visitDate)
                .timeSlot(slot[0])
                .reservedSkus(reservedSkus(session))
                .status(BOOKING_CONFIRMED)
                .confirmedAt(LocalDateTime.now())
                .build());
        session.setStoreName(fit(storeName, 200));

        recordLead(session, LEAD_STORE_VISIT_BOOKED, Lead.builder()
                .bookingRef(booking.getBookingRef())
                .storeName(booking.getStoreName())
                .productSkus(booking.getReservedSkus()));
        if (booking.getReservedSkus() != null) {
            List<String> skus = Arrays.asList(booking.getReservedSkus().split(","));
            for (Product prod : productRepository.findBySkuIn(skus)) {
                recordPick(session, prod.getSku(), prod.getCategory(), PICK_RESERVED);
            }
        }

        String text = "Done. Your visit is booked. 🎉\n\n"
                + "📍 *" + booking.getStoreName() + "*\n"
                + "📅 " + visitDate.format(VISIT_DATE_TEXT) + "\n"
                + "⏰ " + slot[0] + "\n"
                + "🔖 Booking ID: *" + booking.getBookingRef() + "*\n\n"
                + (booking.getReservedSkus() != null
                ? "Just show this message at the store - your picks will be ready for you 💛"
                : "Just show this message at the store - we can't wait to see you 💛");
        String mid = karixService.sendTextMessage(session.getPhone(), text);
        recordOutbound(session, "text", text, mid);
        moveTo(session, STEP_STORE_BOOKED);
        sessionRepository.save(session);
        log.info("stage=STORE_VISIT_BOOKED sessionId={} bookingRef={} visitDate={} slot={} sent={}",
                session.getId(), booking.getBookingRef(), visitDate, slotId, mid != null);

        scheduleFollowUp(session.getId(), STEP_STORE_BOOKED, AFTER_BOOKING_DELAY_SECONDS,
                "AFTER_BOOKING", this::afterStoreVisitBooked);
        return mid != null;
    }

//    /**
//     * C2 — she tapped a store in the list (row id "STORE_<city>|<shortName>").
//     * Saves / updates this session's PENDING booking with the store and asks for the date & time.
//     */
//    private boolean onStorePicked(Session session, String value) {
//        int bar = value.indexOf('|');
//        String city = bar > 0 ? value.substring(0, bar).trim() : null;
//        String storeName = (bar >= 0 ? value.substring(bar + 1) : value).trim();
//        if (storeName.isEmpty()) {
//            log.warn("stage=STORE_PICK_INVALID sessionId={}", session.getId());
//            return askForLocation(session);
//        }

//        Booking booking = bookingRepository
//                .findFirstBySessionIdAndStatusOrderByIdDesc(session.getId(), BOOKING_PENDING)
//                .orElseGet(() -> Booking.builder()
//                        .bookingRef(newBookingRef())
//                        .customerId(session.getCustomerId())
//                        .sessionId(session.getId())
//                        .phone(session.getPhone())
//                        .path(session.getPath())
//                        .status(BOOKING_PENDING)
//                        .build());
//        booking.setStoreName(fit(storeName, 200));
//        booking.setCity(city == null || city.isEmpty() ? null : fit(city, 50));
//        booking.setReservedSkus(reservedSkus(session));
//        bookingRepository.save(booking);
//        session.setStoreName(fit(storeName, 200));
//        log.info("stage=STORE_PICKED sessionId={} bookingRef={} city={}", session.getId(), booking.getBookingRef(), city);

//        String example = LocalDate.now(IST).plusDays(1).format(VISIT_EXAMPLE);
//        String question = "Lovely choice! 💛 When would you like to visit *" + storeName + "*?\n\n"
//                + "Please send the date and time, like this: *" + example + " 5:30 pm*\n"
//                + "(Stores are open " + STORE_OPENS.format(VISIT_TIME_TEXT) + " – " + LAST_VISIT.format(VISIT_TIME_TEXT) + ")";
//        boolean sent = askQuestion(session, question);
//        moveTo(session, STEP_STORE_DATETIME);
//        return sent;
//    }

//    /** C2 — her typed date & time. Invalid → explain and ask again. Valid → booking CONFIRMED + confirmation. */
//    private boolean onVisitDateTime(Session session, String answer) {
//        Optional<Booking> pending = bookingRepository
//                .findFirstBySessionIdAndStatusOrderByIdDesc(session.getId(), BOOKING_PENDING);
//        if (pending.isEmpty()) {
//            log.warn("stage=VISIT_NO_PENDING_BOOKING sessionId={} — asking for location again", session.getId());
//            session.setCurrentStep(STEP_BOOK_STORE_VISIT);
//            return askForLocation(session);
//        }

//        String example = LocalDate.now(IST).plusDays(1).format(VISIT_EXAMPLE) + " 5:30 pm";
//        LocalDateTime visit = parseVisitDateTime(answer);
//        if (visit == null) {
//            return reask(session, "Hmm, I couldn't read that. Please send the date and time like this: *" + example + "*");
//        }
//        LocalDateTime now = LocalDateTime.now(IST);
//        if (!visit.isAfter(now.plusMinutes(30))) {
//            return reask(session, "That time has already passed or is too soon ⏰ Please pick a later date and time, e.g. *" + example + "*");
//        }
//        if (visit.toLocalDate().isAfter(now.toLocalDate().plusDays(VISIT_MAX_DAYS_AHEAD))) {
//            return reask(session, "Please pick a date within the next " + VISIT_MAX_DAYS_AHEAD + " days, e.g. *" + example + "*");
//        }
//        LocalTime time = visit.toLocalTime();
//        if (time.isBefore(STORE_OPENS) || time.isAfter(LAST_VISIT)) {
//            return reask(session, "Stores are open " + STORE_OPENS.format(VISIT_TIME_TEXT) + " – "
//                    + LAST_VISIT.format(VISIT_TIME_TEXT) + ". Please pick a time in between, e.g. *" + example + "*");
//        }

//        Booking booking = pending.get();
//        booking.setVisitDate(visit.toLocalDate());
//        booking.setTimeSlot(time.format(DateTimeFormatter.ofPattern("HH:mm")));
//        booking.setStatus(BOOKING_CONFIRMED);
//        booking.setConfirmedAt(LocalDateTime.now());
//        bookingRepository.save(booking);

//        recordLead(session, LEAD_STORE_VISIT_BOOKED, Lead.builder()
//                .bookingRef(booking.getBookingRef())
//                .storeName(booking.getStoreName())
//                .productSkus(booking.getReservedSkus()));
//        if (booking.getReservedSkus() != null) {
//            List<String> skus = Arrays.asList(booking.getReservedSkus().split(","));
//            for (Product prod : productRepository.findBySkuIn(skus)) {
//                recordPick(session, prod.getSku(), prod.getCategory(), PICK_RESERVED);
//            }
//        }

//        String text = "Done. Your visit is booked. 🎉\n\n"
//                + "📍 *" + booking.getStoreName() + "*\n"
//                + "📅 " + visit.toLocalDate().format(VISIT_DATE_TEXT) + "\n"
//                + "⏰ " + time.format(VISIT_TIME_TEXT) + "\n"
//                + "🔖 Booking ID: *" + booking.getBookingRef() + "*\n\n"
//                + (booking.getReservedSkus() != null
//                        ? "Just show this message at the store - your picks will be ready for you 💛"
//                        : "Just show this message at the store - we can't wait to see you 💛");
//        String mid = karixService.sendTextMessage(session.getPhone(), text);
//        recordOutbound(session, "text", text, mid);
//        moveTo(session, STEP_STORE_BOOKED);
//        log.info("stage=STORE_VISIT_BOOKED sessionId={} bookingRef={} visitDate={} time={} sent={}",
//                session.getId(), booking.getBookingRef(), booking.getVisitDate(), booking.getTimeSlot(), mid != null);

//        scheduleFollowUp(session.getId(), STEP_STORE_BOOKED, AFTER_BOOKING_DELAY_SECONDS,
//                "AFTER_BOOKING", this::afterStoreVisitBooked);
//        return mid != null;
//    }

//    /** "16 oct 5:30 pm" / "16 10 5pm" / "16/10/2026 17:30" / "tomorrow 5 pm" → 2026-10-16T17:30. Year optional. Null if unreadable. */
//    private static LocalDateTime parseVisitDateTime(String raw) {
//        String text = raw.toLowerCase(Locale.ROOT).replace(',', ' ').replaceAll("\\s+", " ").trim();
//        java.util.regex.Matcher t = VISIT_TIME.matcher(text);
//        if (!t.matches()) return null;
//        try {
//            int hour = Integer.parseInt(t.group(2));
//            int minute = t.group(3) == null ? 0 : Integer.parseInt(t.group(3));
//            if (t.group(4) != null) {
//                if (hour < 1 || hour > 12) return null;
//                hour = (hour % 12) + (t.group(4).startsWith("p") ? 12 : 0);
//            }

//            String datePart = t.group(1).trim();
//            LocalDate today = LocalDate.now(IST);
//            if (datePart.matches("today|aaj")) return today.atTime(hour, minute);
//            if (datePart.matches("tomorrow|tomorow|tommorow|tmrw|kal")) return today.plusDays(1).atTime(hour, minute);

//            java.util.regex.Matcher d = VISIT_DATE_NUMBERS.matcher(datePart);
//            if (d.matches()) {
//                int year = d.group(3) == null ? today.getYear()
//                        : d.group(3).length() == 2 ? 2000 + Integer.parseInt(d.group(3)) : Integer.parseInt(d.group(3));
//                return LocalDate.of(year, Integer.parseInt(d.group(2)), Integer.parseInt(d.group(1))).atTime(hour, minute);
//            }

//            String day;
//            String monthName;
//            String year;
//            if ((d = VISIT_DATE_DAY_MONTH.matcher(datePart)).matches()) {
//                day = d.group(1); monthName = d.group(2); year = d.group(3);
//            } else if ((d = VISIT_DATE_MONTH_DAY.matcher(datePart)).matches()) {
//                monthName = d.group(1); day = d.group(2); year = d.group(3);
//            } else {
//                return null;
//            }
//            int index = "janfebmaraprmayjunjulaugsepoctnovdec".indexOf(monthName.substring(0, 3));
//            if (index < 0 || index % 3 != 0) return null;
//            return LocalDate.of(year == null ? today.getYear() : Integer.parseInt(year), index / 3 + 1, Integer.parseInt(day))
//                    .atTime(hour, minute);
//        } catch (DateTimeException | NumberFormatException e) {
//            return null;
//        }
//    }

    /** Pieces to keep ready at the store: her list (Wife / Sparkle), else the one piece she tapped / picked. */
    private String reservedSkus(Session session) {
        List<String> skus = wishlistItemRepository.findBySessionIdOrderByAddedAtAsc(session.getId())
                .stream().map(WishlistItem::getSku).distinct().toList();
        if (skus.isEmpty() && session.getSelectedProductSku() != null) skus = List.of(session.getSelectedProductSku());
        return skus.isEmpty() ? null : fit(String.join(",", skus), 1000);
    }

    /** "MIA-7K2QXP" — unique, no look-alike characters (0/O, 1/I). */
    private String newBookingRef() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        String ref;
        do {
            StringBuilder sb = new StringBuilder("MIA-");
            for (int i = 0; i < 6; i++) sb.append(chars.charAt(BOOKING_RANDOM.nextInt(chars.length())));
            ref = sb.toString();
        } while (bookingRepository.existsByBookingRef(ref));
        return ref;
    }

    /** "12.97" → 12.97 if it's a real coordinate (|value| <= limit), else null. */
    private static Double parseCoordinate(String raw, double limit) {
        try {
            double v = Double.parseDouble(raw == null ? "" : raw.trim());
            return Double.isFinite(v) && Math.abs(v) <= limit ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Cuts text to max characters (WhatsApp list limits: id 200, title 24, description 72). */
    private static String fit(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ==================================================================
    // CUSTOMER NAME (WhatsApp profile name from Karix)
    // ==================================================================

    @Override
    public void updateCustomerName(String phone, String name) {
        String to = normalizePhone(phone);
        if (to == null || name == null || name.isBlank()) return;
        try {
            customerRepository.findByPhone(to).ifPresent(c -> {
                if (!name.trim().equals(c.getName())) {
                    c.setName(name.trim());
                    customerRepository.save(c);
                    log.info("stage=CUSTOMER_NAME_UPDATED customerId={}", c.getId());   // name itself not logged
                }
            });
        } catch (Exception e) {
            log.error("Failed to update customer name", e);   // never break the flow
        }
    }

    /**
     * Karva Chauth opener: "How lovely! …" + [Choose my gift] [Visit nearby store].
     * /test (template=opener) yahi call karta hai. Real keyword wala raasta onTextMessage() mein hai.
     */
    @Override
    public void sendOpener(String phone, String name) {
        startFromOpener(phone, name, TEMPLATE_WIFE, null, null);
    }

    // ==================================================================
    // TYPED REPLIES — answers to questions (1J on the Wife path, 2F on the Husband path)
    // ==================================================================

    /**
     * A typed message (not a button). Only steps that ask a question expect one;
     * anywhere else it's logged and ignored.
     */
    @Transactional
    @Override
    public boolean onTextMessage(String phone, String text) {
        lastOutbound.remove();
        String to = normalizePhone(phone);
        if (to == null || text == null || text.isBlank()) {
            log.warn("onTextMessage: missing phone/text");
            return false;
        }
        String answer = text.trim();

        // TEST: template ka naam type karo ("List started, hint not sent") -> wahi reminder
        Reminder testReminder = resolveReminder(answer);
        if (testReminder != null) {
            log.info("stage=REMINDER_TEST_TRIGGER step={}", testReminder.step());
            return sendReminderTest(to, testReminder);
        }

        // ---------- Keyword -> us journey ka opener ----------
        // "wife" -> Wife, "husband" -> Husband, "sparkle" -> Sparkle, "karva chauth" -> Wife.
        // Session check se PEHLE: naye user ka koi session nahi hota.
        // startFromOpener() purani journey band karke nayi OPENER session banata hai,
        // ye text tap ki tarah save karta hai (payload KW_<PATH>), aur us journey ka pehla message bhejta hai.
        String keywordPath = keywordPath(answer);
        if (keywordPath != null) {
            log.info("stage=KEYWORD_OPENER path={}", keywordPath);
            startFromOpener(to, null, keywordPath, KEYWORD_PAYLOAD_PREFIX + keywordPath, answer);
            return true;
        }

        // ---------- Pending question ka jawab ----------
        Optional<Session> found = sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(to);
        if (found.isEmpty()) {
            log.warn("stage=NO_ACTIVE_SESSION type=text — send the opener first");
            return false;
        }
        Session session = found.get();

        recordInboundText(session, answer);
        session.setLastInboundAt(LocalDateTime.now());
        session.setIdleNudgeSent(false);
        // The text itself is NOT logged — it's personal data (names, dates, numbers)
        log.info("stage=TEXT_IN sessionId={} step={} length={}", session.getId(), session.getCurrentStep(), answer.length());

        String step = session.getCurrentStep() == null ? "" : session.getCurrentStep();
        boolean handled = switch (step) {
            case STEP_W_CAPH_NAME        -> onHusbandName(session, answer);
            case STEP_W_CAPH_ANNIVERSARY -> onAnniversary(session, answer);
            case STEP_W_CAPH_MOBILE      -> onHusbandMobile(session, answer);
            case STEP_H_CAPW_NAME        -> onWifeName(session, answer);
            case STEP_H_CAPW_BIRTHDAY    -> onWifeBirthday(session, answer);
            case STEP_H_CAPW_MOBILE      -> onWifeMobile(session, answer);
//            case STEP_STORE_DATETIME     -> onVisitDateTime(session, answer);
            default -> {
                log.info("stage=TEXT_NOT_EXPECTED sessionId={} step={} — no question pending", session.getId(), step);
                yield false;
            }
        };

        sessionRepository.save(session);
        return handled;
    }




    // ==================================================================
    // STEP 1A / 2A — category carousel
    // ==================================================================

    /** Step 1A (Wife) — 7 categories incl. Mia Sutra. */
    private boolean wifePickCategory(Session session) {
        return wifePickCategory(session, STEP_W_PICK_CATEGORY);
    }

    /**
     * step = W_CHOOSE_MY_GIFT when she's just tapped "Choose my gift" (first time she sees the categories),
     * W_PICK_CATEGORY when she comes back to them later ("Another category", idle nudge).
     */
    private boolean wifePickCategory(Session session, String step) {
        String text = "Beautiful. Pick a category you’re drawn to – "
                + "I’ll help you put together something to share with him.";
        return sendCategoryCarousel(session, text, true, step);
    }

    /** Step 2A (Husband) — same 7 categories, he browses on her behalf. */
    private boolean husbandPickCategory(Session session) {
        return husbandPickCategory(session, STEP_H_PICK_CATEGORY);
    }

    /** step = H_FIND_HER_GIFT right after "Find her a gift", H_PICK_CATEGORY when she comes back later. */
    private boolean husbandPickCategory(Session session, String step) {
        String text = "Love that. Let's find her something she'll adore. ✨";
        return sendCategoryCarousel(session, text, true, step);
    }

    /** Step 1A (Sparkle) — 6 categories, Mia Sutra excluded (not necessarily married). */
    private boolean sparklePickCategory(Session session) {
        return sparklePickCategory(session, STEP_S_PICK_CATEGORY);
    }

    /** step = S_SHOW_ME right after "Show me", S_PICK_CATEGORY when she comes back later. */
    private boolean sparklePickCategory(Session session, String step) {
        String text = "Wonderful! Pick a category you’re drawn to – "
                + "let’s put together something that’s entirely yours.";
        return sendCategoryCarousel(session, text, false, step);
    }

    // ==================================================================
    // STEP 1A-Budget (Sparkle) / 2A-Budget (Husband) — "Choose your budget" list
    // Same list on both paths; only the row payloads and the next step differ.
    // ==================================================================

    /**
     * Sparkle path: category tap → budget buttons (the Wife path skips this and goes straight to 1B).
     * Budget taps come back as S_BUDGET_<band> → 1B, filtered.
     */
    private boolean sparkleChooseBudget(Session session, String code) {
        if (CAT_MIA_SUTRA.equals(categoryFromCode(code))) {   // Mia Sutra isn't offered on Sparkle
            log.warn("stage=UNKNOWN_CATEGORY sessionId={} code={} — Mia Sutra not on Sparkle", session.getId(), code);
            return false;
        }
        return sendBudgetButtons(session, code, "S_BUDGET_", STEP_S_BUDGET);
    }

    /**
     * Husband path (2A-Budget): category tap → the same budget buttons.
     * Budget taps come back as H_BUDGET_<band> → 2B, filtered.
     */
    private boolean husbandChooseBudget(Session session, String code) {
        return sendBudgetButtons(session, code, "H_BUDGET_", STEP_H_BUDGET);
    }

    /**
     * Saves the chosen category and sends the 3 budget bands as 3 tappable buttons (one message).
     * Same payloads as before (S_BUDGET_<band> / H_BUDGET_<band>), so routing and the dashboard don't change.
     * Button titles are max 20 characters (WhatsApp limit).
     */
    private boolean sendBudgetButtons(Session session, String code, String payloadPrefix, String nextStep) {
        String category = categoryFromCode(code);
        if (category == null) {
            log.warn("stage=UNKNOWN_CATEGORY sessionId={} code={}", session.getId(), code);
            return false;
        }
        session.setSelectedCategory(category);
        session.setSelectedBudget(null);
        session.setProductPage(0);

        String text = "Choose your budget for *" + categoryTitle(code) + "* 💫";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{payloadPrefix + BUDGET_UNDER_20K, "💫 Less than 20,000"},
                        new String[]{payloadPrefix + BUDGET_20_50K,    "💎 20,000 - 50,000"},
                        new String[]{payloadPrefix + BUDGET_ABOVE_50K, "👑 Above 50,000"}));
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=BUDGET_BUTTONS_FAILED sessionId={} path={} — step not changed", session.getId(), session.getPath());
            return false;
        }
        moveTo(session, nextStep);
        log.info("stage=BUDGET_BUTTONS_SENT sessionId={} path={} category={}", session.getId(), session.getPath(), category);
        return true;
    }

    /** 1B (Sparkle) — budget button tapped → her category, filtered to that band. */
    private boolean sparkleBrowseProducts(Session session, String budget) {
        return browseWithBudget(session, budget, STEP_S_BROWSE_PRODUCTS);
    }

    /** 2B (Husband) — budget button tapped → the category he picked, filtered to that band. */
    private boolean husbandBrowseProducts(Session session, String budget) {
        return browseWithBudget(session, budget, STEP_H_BROWSE_PRODUCTS);
    }

    /**
     * Shared by Sparkle 1B and Husband 2B: saves the budget and sends the products of the
     * category picked in 1A / 2A, filtered to that band (full category if nothing matches).
     * The product buttons differ by path — see sendProductPage().
     */
    private boolean browseWithBudget(Session session, String budget, String nextStep) {
        if (priceRange(budget) == null) {
            log.warn("stage=UNKNOWN_BUDGET sessionId={} budget={}", session.getId(), budget);
            return false;
        }
        String category = session.getSelectedCategory();
        String code = category == null ? null : codeFromCategory(category);
        if (code == null) {
            // Budget tapped before any category (e.g. old list in a new session) — show categories first
            log.warn("stage=BUDGET_WITHOUT_CATEGORY sessionId={} — sending categories", session.getId());
            return TEMPLATE_HUSBAND.equals(session.getPath())
                    ? husbandPickCategory(session)
                    : sparklePickCategory(session);
        }
        session.setSelectedBudget(budget);
        session.setProductPage(0);
        log.info("stage=BUDGET_CHOSEN sessionId={} path={} category={} budget={}",
                session.getId(), session.getPath(), category, budget);
        return sendProductPage(session, code, nextStep, false);
    }

    /**
     * Sends the category carousel. The step only moves forward if Karix accepted it,
     * so a failed send leaves the user where they were and the tap can be retried.
     */
    private boolean sendCategoryCarousel(Session session, String bodyText, boolean includeMiaSutra, String nextStep) {
        List<KarixService.CarouselCard> cards = new ArrayList<>();
        for (String[] c : CATEGORIES) {
            if (!includeMiaSutra && "MSUT".equals(c[0])) continue;

            List<String[]> buttons = new ArrayList<>();
            buttons.add(new String[]{"CAT_" + c[0], "Browse these"});

            cards.add(new KarixService.CarouselCard(c[3], "*" + c[1] + "*", c[2], buttons));
        }

        String mid = karixService.sendCarousel(session.getPhone(), bodyText, cards);
        recordOutbound(session, "carousel", bodyText, mid);

        if (mid == null) {
            log.warn("stage=CATEGORY_CAROUSEL_FAILED sessionId={} path={} cards={} — step not changed",
                    session.getId(), session.getPath(), cards.size());
            return false;
        }
        moveTo(session, nextStep);
        log.info("stage=CATEGORY_CAROUSEL_SENT sessionId={} path={} step={} cards={} mid={}",
                session.getId(), session.getPath(), nextStep, cards.size(), mid);
        return true;
    }

    // ==================================================================
    // STEP 1B (Wife) — browse products in the chosen category
    // ==================================================================

    /** Wife path: category tap → straight to products, full category, no budget (script v7.1). */
    private boolean wifeBrowseProducts(Session session, String code) {
        String category = categoryFromCode(code);
        if (category == null) {
            log.warn("stage=UNKNOWN_CATEGORY sessionId={} code={}", session.getId(), code);
            return false;
        }
        session.setSelectedCategory(category);
        session.setSelectedBudget(null);
        session.setProductPage(0);
        return sendProductPage(session, code, STEP_W_BROWSE_PRODUCTS, false);
    }

    /**
     * Sends one page (max 9 products) as a carousel; for Wife/Sparkle the browse buttons follow shortly after.
     * Shared by 1B, 1D and "See more" — and later by Sparkle/Husband with a budget filter.
     *
     * @param again true for 1D ("Here's {category} again"), false for 1B / See more
     */
    private boolean sendProductPage(Session session, String code, String nextStep, boolean again) {
        String category = session.getSelectedCategory();
        String title = categoryTitle(code);
        int page = session.getProductPage() == null ? 0 : session.getProductPage();

        // Budget band — set on Sparkle / Husband after the budget buttons; null on the Wife path
        int[] range = priceRange(session.getSelectedBudget());

        // The whole category from the products table — active pieces only, in the Mia team's display order.
        // A piece with no price ("Enquire at store") is shown on the Wife path only.
        List<Product> allInCategory = productRepository.findByCategoryAndActiveTrueOrderByDisplayOrderAscPriceAsc(
                        category, Pageable.unpaged()).stream()
                .filter(prod -> TEMPLATE_WIFE.equals(session.getPath()) || prod.getPrice() != null)
                .toList();

        // Budget (Sparkle / Husband):
        //   - pieces in her budget → ONLY those pieces (4 in budget → 4 cards; 12 → 9 + "See more" for 3)
        //   - none in her budget   → the whole category
        //   - exactly 1 in budget  → that piece + the closest-priced other piece, because a WhatsApp
        //                            carousel needs at least 2 cards
        // Wife: no budget, the whole category.
        List<Product> pool;
        int inBudgetCount;
        if (range != null) {
            List<Product> inBand = allInCategory.stream()
                    .filter(prod -> prod.getPrice() != null && prod.getPrice() >= range[0] && prod.getPrice() <= range[1])
                    .toList();
            inBudgetCount = inBand.size();
            if (inBand.isEmpty()) {
                pool = allInCategory;
            } else if (inBand.size() == 1 && allInCategory.size() > 1) {
                pool = withClosestPricedPiece(inBand.get(0), allInCategory);
            } else {
                pool = inBand;
            }
        } else {
            pool = allInCategory;
            inBudgetCount = allInCategory.size();
        }
        // true when the carousel shows pieces outside her budget (none in budget, or the 1-piece top-up)
        boolean budgetFallback = range != null && pool.size() > inBudgetCount;

        long total = pool.size();
        int fromIdx = Math.min(page * PRODUCT_PAGE_SIZE, pool.size());
        int toIdx = Math.min(fromIdx + PRODUCT_PAGE_SIZE, pool.size());
        // A last page with a single piece (e.g. 10 products → page 2 has 1) would be a 1-card carousel,
        // which WhatsApp rejects — start that page one piece earlier so it shows the last 2.
        if (toIdx - fromIdx == 1 && fromIdx > 0) {
            fromIdx--;
        }
        List<Product> products = pool.subList(fromIdx, toIdx);

        boolean hasMore = (long) (page + 1) * PRODUCT_PAGE_SIZE < total;

        // Nothing in this category yet — don't send an empty carousel
        if (products.isEmpty()) {
            String text = "We're adding fresh designs to *" + title + "* – take a look at another category meanwhile 💛";
            String mid = karixService.sendButtonMessage(session.getPhone(), text,
                    List.of(
                            new String[]{"ANOTHER_CATEGORY", "Another category"},
                            new String[]{"BTN_STORE_FINDER", "📍 Visit Store"}));
            recordOutbound(session, "interactive", text, mid);
            log.warn("stage=NO_PRODUCTS sessionId={} category={}", session.getId(), category);
            return mid != null;
        }

        // Carousel: one card per product, two buttons each.
        // Husband (2B) is the direct buyer, so his buttons differ from Wife/Sparkle (1B), who build a list.
        boolean husband = TEMPLATE_HUSBAND.equals(session.getPath());

        List<KarixService.CarouselCard> cards = new ArrayList<>();
        for (Product prod : products) {
            List<String[]> buttons = new ArrayList<>();
            if (husband) {
                buttons.add(new String[]{"BUY_" + prod.getSku(), "Buy this for her"});      // → 2C
                buttons.add(new String[]{"VISIT_" + prod.getSku(), "Visit nearby store"});  // → C2
            } else {
                buttons.add(new String[]{"ADD_" + prod.getSku(), "Add to my list"});        // → 1C
                buttons.add(new String[]{"VISIT_" + prod.getSku(), "Visit Store"});         // → C2
            }

            cards.add(new KarixService.CarouselCard(
                    prod.getImageUrl(),
                    "*" + prod.getName() + "*",
                    prod.getPrice() == null ? "Price: Enquire at store"
                            : "≈ ₹" + formatInr(prod.getPrice()) + "* (tentative)\n\n*Tentative; varies by city & gold rate",
                    buttons));
        }

        // Same text whether or not the budget fallback kicked in — she just sees the products.
        // (budgetFallback = pieces outside her budget are shown — logged below.)
        String bodyText = again
                ? "Here's *" + title + "* again 💛 Take your time."                 // 1D
                : "Here's what's beautiful in *" + title + "* 💛 Take your time.";  // 1B / 2B / See more
        String mid = karixService.sendCarousel(session.getPhone(), bodyText, cards);
        recordOutbound(session, "carousel", bodyText, mid);

        if (mid == null) {
            log.warn("stage=PRODUCT_CAROUSEL_FAILED sessionId={} category={} page={} — step not changed",
                    session.getId(), category, page);
            return false;
        }
        moveTo(session, nextStep);
        log.info("stage=PRODUCT_CAROUSEL_SENT sessionId={} category={} budget={} inBudget={} toppedUp={} page={} cards={} hasMore={}",
                session.getId(), category, session.getSelectedBudget(), inBudgetCount, budgetFallback, page, cards.size(), hasMore);

        // Browse buttons (See more / Another category / Visit nearby store) — Wife & Sparkle only.
        // The Husband is a direct buyer: he just gets the products, nothing after the carousel.
        if (!husband) {
            scheduleBrowseControls(session.getId(), nextStep, category, page, title, hasMore);
        }
        return true;
    }

    /**
     * The one in-budget piece followed by the other piece of the category whose price is closest to it —
     * so the carousel still has the 2 cards WhatsApp needs.
     */
    private static List<Product> withClosestPricedPiece(Product inBudget, List<Product> allInCategory) {
        int price = inBudget.getPrice() == null ? 0 : inBudget.getPrice();
        Product closest = allInCategory.stream()
                .filter(prod -> prod != inBudget)
                .min(Comparator.comparingInt(prod -> Math.abs((prod.getPrice() == null ? 0 : prod.getPrice()) - price)))
                .orElse(null);
        return closest == null ? List.of(inBudget) : List.of(inBudget, closest);
    }

    /**
     * Sends the browse buttons a couple of seconds after the product carousel (BROWSE_CONTROLS_DELAY_SECONDS).
     * Skipped if she has moved on in the meantime — tapped a product, another category,
     * or "See more" (a newer page schedules its own buttons, so the old ones would be stale).
     */
    private void scheduleBrowseControls(Long sessionId, String expectedStep, String category,
                                        int page, String title, boolean hasMore) {
        LocalDateTime carouselSentAt = LocalDateTime.now();
        scheduleFollowUp(sessionId, expectedStep, BROWSE_CONTROLS_DELAY_SECONDS, "BROWSE_CONTROLS", latest -> {
            if (messageRepository.existsBySessionIdAndDirectionAndCreatedAtAfter(sessionId, DIR_INBOUND, carouselSentAt)) {
                log.info("stage=BROWSE_CONTROLS_SKIPPED sessionId={} — she already tapped after the carousel", sessionId);
                return false;
            }
            boolean samePage = category.equals(latest.getSelectedCategory())
                    && Integer.valueOf(page).equals(latest.getProductPage());
            if (samePage) {
                sendBrowseControls(latest, title, hasMore);
            } else {
                log.info("stage=BROWSE_CONTROLS_SKIPPED sessionId={} — a newer page/category is showing", sessionId);
            }
            return false;   // nothing on the session changes, so no save (avoids clashing with her next tap)
        });
    }

    /** The buttons under the carousel. "See more" only when there's another page (max 3 buttons). */
    private void sendBrowseControls(Session session, String title, boolean hasMore) {
        String text;
        List<String[]> buttons = new ArrayList<>();
        if (hasMore) {
            text = "Not the one? There's more to explore.";
            buttons.add(new String[]{"SEE_MORE", "See more"});
        } else {
            text = "That's everything in *" + title + "* for now. Explore another?";
        }
        buttons.add(new String[]{"ANOTHER_CATEGORY", "Another category"});
        buttons.add(new String[]{"BTN_STORE_FINDER", "📍 Visit Store"});   // → C2

        String mid = karixService.sendButtonMessage(session.getPhone(), text, buttons);
        recordOutbound(session, "interactive", text, mid);
        log.info("stage=BROWSE_CONTROLS_SENT sessionId={} hasMore={} sent={}", session.getId(), hasMore, mid != null);
    }

    /** "See more" — next page of products from the category she's browsing. */
    private boolean seeMore(Session session) {
        String category = session.getSelectedCategory();
        String code = category == null ? null : codeFromCategory(category);
        if (code == null) {
            log.warn("stage=SEE_MORE_NO_CATEGORY sessionId={} — nothing being browsed", session.getId());
            return false;
        }

        int current = session.getProductPage() == null ? 0 : session.getProductPage();
        session.setProductPage(current + 1);

        String step = switch (session.getPath()) {
            case TEMPLATE_SPARKLE -> STEP_S_BROWSE_PRODUCTS;
            case TEMPLATE_HUSBAND -> STEP_H_BROWSE_PRODUCTS;
            default               -> STEP_W_BROWSE_PRODUCTS;
        };

        boolean sent = sendProductPage(session, code, step, false);
        if (!sent) {
            session.setProductPage(current);   // roll back so the tap can be retried
        }
        log.info("stage=SEE_MORE sessionId={} category={} page={} sent={}",
                session.getId(), category, session.getProductPage(), sent);
        return sent;
    }

    // ==================================================================
    // STEP 1C — "Add to my list" → save to wishlist, show the count
    // STEP 1D — "➕ Add more" → re-open the same category
    // ==================================================================

    /**
     * Saves the tapped product to this session's wishlist and sends 1C.
     * A second tap on the same product doesn't add it twice — the count just stays the same.
     */
    private boolean addToList(Session session, String sku) {
        Product product = findProduct(sku);
        if (product == null) {
            log.warn("stage=ADD_UNKNOWN_SKU sessionId={} sku={}", session.getId(), sku);
            return false;
        }

        // 1. Save — skip if it's already on the list (double tap / tapped again from an old carousel)
        boolean alreadyOnList = wishlistItemRepository.existsBySessionIdAndSku(session.getId(), sku);
        if (!alreadyOnList) {
            try {
                wishlistItemRepository.save(WishlistItem.builder()
                        .sessionId(session.getId())
                        .phone(session.getPhone())
                        .sku(sku)
                        .category(product.getCategory())
                        .build());
            } catch (DataIntegrityViolationException e) {
                alreadyOnList = true;   // unique key (session, sku) — another tap got there first
            }
            if (!alreadyOnList) {
                recordPick(session, sku, product.getCategory(), PICK_ADDED);
            }
        }
        long count = wishlistItemRepository.countBySessionId(session.getId());

        // "Add more" must re-open the category this product is from — she may have tapped it
        // on an older carousel from a different category.
        session.setSelectedCategory(product.getCategory());

        log.info("stage=WISHLIST_ADD sessionId={} sku={} category={} alreadyOnList={} count={}",
                session.getId(), sku, product.getCategory(), alreadyOnList, count);

        // 2. Send 1C — copy and third button differ by path
        String opening = alreadyOnList
                ? "That one's already on your list 💛"
                : "A lovely choice.";
        String itemsWord = count == 1 ? " piece" : " pieces";

        String text;
        List<String[]> buttons;
        String nextStep;

        if (TEMPLATE_SPARKLE.equals(session.getPath())) {
            text = opening + " That's " + count + itemsWord + " on your list.";
            buttons = List.of(
                    new String[]{"ADD_MORE", "➕ Add more"},
                    new String[]{"ANOTHER_CATEGORY", "≡ Another category"},
                    new String[]{"BTN_STORE_FINDER", "📍 Visit Store"});          // → C2
            nextStep = STEP_S_ADDED_TO_LIST;
        } else {
            text = opening + " That's " + count + itemsWord + " on your list. Add another, or let's turn it "
                    + "into your Ishara – a ready-to-send list showing him exactly what to get you.";
            buttons = List.of(
                    new String[]{"ADD_MORE", "➕ Add more"},
                    new String[]{"ANOTHER_CATEGORY", "≡ Another category"},
                    new String[]{"W_SEND_ISHARA", "💌 Send my Ishara"});         // → 1G
            nextStep = STEP_W_ADDED_TO_LIST;
        }

        String mid = karixService.sendButtonMessage(session.getPhone(), text, buttons);
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            // The item IS saved; only the message failed. A retry won't duplicate it.
            log.warn("stage=ADDED_TO_LIST_MSG_FAILED sessionId={} — step not changed", session.getId());
            return false;
        }
        moveTo(session, nextStep);
        return true;
    }

    /** 1D — "➕ Add more": the same category's products again, from the first page. */
    private boolean addMore(Session session) {
        String category = session.getSelectedCategory();
        String code = category == null ? null : codeFromCategory(category);
        if (code == null) {
            log.warn("stage=ADD_MORE_NO_CATEGORY sessionId={}", session.getId());
            return false;
        }
        session.setProductPage(0);

        String step = TEMPLATE_SPARKLE.equals(session.getPath()) ? STEP_S_ADD_MORE : STEP_W_ADD_MORE;
        return sendProductPage(session, code, step, true);
    }

    /**
     * An active product by SKU, or null. Used when she taps a product (Add to my list / Buy this for her /
     * Buy Online): a piece the Mia team has switched off (active = false) can't be picked from an old carousel.
     */
    private Product findProduct(String sku) {
        if (sku == null || sku.isBlank()) return null;
        return productRepository.findBySku(sku)
                .filter(product -> Boolean.TRUE.equals(product.getActive()))
                .orElse(null);
    }

    // ==================================================================
    // STEP 1G — the Ishara (hint), ready to forward  ← PRIMARY OBJECTIVE
    // STEP 1H — "Want to see them in person?" (sent automatically after 1G)
    // ==================================================================

    /**
     * Builds the hint from her wishlist and sends it with a "📤 Send to Husband" button.
     * The button opens wa.me/?text=..., which shows WhatsApp's own contact picker with the
     * hint pre-filled — she picks her husband and taps send. (Same approach as the Rakhi bot.)
     */
    // Send Ishara Now give product link changed ========================= Pratyaya Date-10/1========================================
    private boolean sendIshara(Session session) {
        List<WishlistItem> items = wishlistItemRepository.findBySessionIdOrderByAddedAtAsc(session.getId());

        // Stale tap with an empty list (e.g. "Send my Ishara" from an old message in a new session)
        if (items.isEmpty()) {
            String text = "Let's pick a piece or two first 💛";
            String mid = karixService.sendButtonMessage(session.getPhone(), text,
                    List.<String[]>of(new String[]{"ANOTHER_CATEGORY", "Browse categories"}));
            recordOutbound(session, "interactive", text, mid);
            log.warn("stage=ISHARA_EMPTY_LIST sessionId={}", session.getId());
            return mid != null;
        }

        // 1. The list of pieces — "• *Name* - ≈₹price". All of her pieces in one query; a piece already on
        //    her list is still shown even if it has since been switched off.
        Map<String, Product> productsBySku = productRepository
                .findBySkuIn(items.stream().map(WishlistItem::getSku).toList())
                .stream()
                .collect(Collectors.toMap(Product::getSku, product -> product, (first, second) -> first));
        String shareHeader = "Karwa Chauth is here, and I'm keeping my fast for you, as always 🌙\n\n"
                + "Here's my Ishara – just in case you're wondering what would make me smile, "
                + "here are a few ideas:\n\n";
        String previewStart = "Here's your Ishara, ready to share with him 👇\n\n";
        String previewEnd = "\n\nWhen you're ready - one tap sends it across on WhatsApp.";
        int fixedLength = previewStart.length() + shareHeader.length() + previewEnd.length();

        StringBuilder lines = new StringBuilder();
        int shown = 0;
        for (WishlistItem item : items) {
            if (shown >= MAX_HINT_ITEMS) break;
            Product prod = productsBySku.get(item.getSku());
            if (prod == null) continue;

            String buyLink = (prod.getBuyLink() == null || prod.getBuyLink().isBlank())
                    ? HINT_BROWSE_LINK
                    : prod.getBuyLink().trim();
            StringBuilder piece = new StringBuilder();
            piece.append("• *").append(prod.getName()).append("*");
            if (prod.getPrice() != null) piece.append(" - ≈₹").append(formatInr(prod.getPrice()));
            piece.append("\n");
            piece.append("Browse online: ").append(buyLink).append("\n\n");

            int remainingAfterThisPiece = items.size() - (shown + 1);
            String moreLine = remainingAfterThisPiece > 0
                    ? "+" + remainingAfterThisPiece
                    : "";
            if (fixedLength + lines.length() + piece.length() + moreLine.length() > 1024) break;

            lines.append(piece);
            shown++;
        }
        int remaining = items.size() - shown;
        if (remaining > 0) {
            lines.append("+").append(remaining).append("\n");
        }

        // 2. The text HE will receive
        String shareText = shareHeader + lines.toString().stripTrailing();

        // 3. What SHE sees: intro + preview of the hint + follow-up line
        String preview = previewStart + shareText + previewEnd;
        if (preview.length() > 1024) {
            log.warn("stage=ISHARA_TOO_LONG sessionId={} length={} — WhatsApp may reject it", session.getId(), preview.length());
        }

        // URLEncoder turns spaces into "+", which some WhatsApp clients show literally — use %20
        String waLink = "https://wa.me/?text="
                + URLEncoder.encode(shareText, StandardCharsets.UTF_8).replace("+", "%20");

        String mid = karixService.sendCtaUrlMessage(session.getPhone(), preview, "📤 Send to Husband", waLink);
        recordOutbound(session, "cta_url", preview, mid);

        if (mid == null) {
            log.warn("stage=ISHARA_FAILED sessionId={} — step not changed", session.getId());
            return false;
        }
        moveTo(session, STEP_W_HINT_READY);
        log.info("stage=ISHARA_SENT sessionId={} items={} shown={}", session.getId(), items.size(), shown);

        // Primary objective reached — record the conversion and which pieces were in the hint
        List<String> skus = items.stream().map(WishlistItem::getSku).toList();
        recordLead(session, LEAD_HINT_SENT, Lead.builder().productSkus(String.join(",", skus)));
        for (WishlistItem item : items) {
            recordPick(session, item.getSku(), item.getCategory(), PICK_HINT_SENT);
        }

        scheduleStoreInvite(session.getId());
        return true;
    }
    //--=======================================Changes Ended=======================================================

    /** Sends 1H a few seconds after 1G — but only if she's still on 1G. */
    private void scheduleStoreInvite(Long sessionId) {
        scheduleFollowUp(sessionId, STEP_W_HINT_READY, HINT_TO_STORE_INVITE_DELAY_SECONDS,
                "STORE_INVITE", this::sendStoreInvite);
    }

    /**
     * Sends a follow-up message after a pause — used after link buttons, because WhatsApp
     * doesn't tell us when a link is tapped. It only fires if the customer is still on
     * {@code expectedStep}; if they've tapped something else in the meantime, it does nothing.
     *
     * Note: this timer lives in memory — a restart in those few seconds loses it.
     * Fine for now; in production it moves to a Service Bus scheduled message.
     */
    private void scheduleFollowUp(Long sessionId, String expectedStep, long delaySeconds,
                                  String label, Predicate<Session> sender) {
        taskScheduler.schedule(() -> {
            lastOutbound.remove();
            try {
                Session latest = sessionRepository.findById(sessionId).orElse(null);
                if (latest == null
                        || !Boolean.TRUE.equals(latest.getIsActive())
                        || !expectedStep.equals(latest.getCurrentStep())) {
                    log.info("stage={}_SKIPPED sessionId={} — already moved on", label, sessionId);
                    return;
                }
                if (sender.test(latest)) {
                    sessionRepository.save(latest);
                }
            } catch (Exception e) {
                log.error("stage={}_FAILED sessionId={}", label, sessionId, e);
            }
        }, Instant.now().plusSeconds(delaySeconds));

        log.info("stage={}_SCHEDULED sessionId={} inSeconds={}", label, sessionId, delaySeconds);
    }

    /** 1H — "Beautiful picks ✨ Want to see them in person at a Mia store near you?" */
    private boolean sendStoreInvite(Session session) {
        String text = "Beautiful picks ✨ Want to see them in person at a Mia store near you?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"BTN_STORE_FINDER", "📍 Visit Store"},    // → C2, then 1I
                        new String[]{"W_MAYBE_LATER", "Maybe later"}));        // → 1I
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=STORE_INVITE_MSG_FAILED sessionId={}", session.getId());
            return false;
        }
        moveTo(session, STEP_W_STORE_INVITE);
        log.info("stage=STORE_INVITE_SENT sessionId={}", session.getId());
        return true;
    }

    // ==================================================================
    // STEP 1I       — "Shall I remind you closer to your anniversary?"
    // STEP 1I-nudge — one gentle persuasion turn if she says no
    // STEP 1J (Q1)  — starts capturing his details (answers handled in the next step)
    // STEP E1       — close
    // ==================================================================

    /**
     * Where every journey goes once a C2 store booking is confirmed (script: "→ Step 1I (Wife/Sparkle)
     * or Step 2E (Husband)"). C2 will call this after it sends "Done. Your visit is booked. 🎉".
     */
    private boolean afterStoreVisitBooked(Session session) {
        return switch (session.getPath()) {
            case TEMPLATE_WIFE    -> wifeReinforce(session);      // "Shall I remind you closer to your anniversary…"
            case TEMPLATE_SPARKLE -> sparkleReinforce(session);   // "Shall we keep you posted…"
            case TEMPLATE_HUSBAND -> husbandCaptureDetails(session);   // 2E "shall we remember her birthday too?"
            default -> false;
        };
    }

    /** 1I (Wife) — after "Maybe later" on 1H, or after a C2 booking. */
    private boolean wifeReinforce(Session session) {
        String text = "Shall I remind him closer to your anniversary, with something special?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"W_ADD_HIS_DETAILS", "💍 Add his details"},   // → 1J
                        new String[]{"W_NO_THANKS", "✕ No thanks"}));            // → 1I-nudge
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=REINFORCE_FAILED sessionId={}", session.getId());
            return false;
        }
        moveTo(session, STEP_W_REINFORCE);
        log.info("stage=REINFORCE_SENT sessionId={} path={}", session.getId(), session.getPath());
        return true;
    }

    /** 1I-nudge (Wife) — she said "No thanks" once; ask one more time, gently. */
    private boolean wifeReinforceNudge(Session session) {
        String text = "No pressure at all. Though a gentle reminder never hurts, na – "
                + "shall we make sure the day doesn't slip by unnoticed?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"W_YES_GO_AHEAD", "📲 Yes, go ahead"},       // → 1J
                        new String[]{"W_ILL_REMIND_HIM", "💪 I'll remind him"}));  // → E1
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=REINFORCE_NUDGE_FAILED sessionId={}", session.getId());
            return false;
        }
        moveTo(session, STEP_W_REINFORCE_NUDGE);
        log.info("stage=REINFORCE_NUDGE_SENT sessionId={}", session.getId());
        return true;
    }

    /**
     * 1J — Question 1 of 3. Her typed reply comes back as a TEXT message, not a button,
     * so the answer is handled by the text-message handler (next step to build).
     */
    private boolean askHusbandName(Session session) {
        String text = "What's your husband's name?";

        String mid = karixService.sendTextMessage(session.getPhone(), text);
        recordOutbound(session, "text", text, mid);

        if (mid == null) {
            log.warn("stage=ASK_HUSBAND_NAME_FAILED sessionId={}", session.getId());
            return false;
        }
        moveTo(session, STEP_W_CAPH_NAME);
        log.info("stage=ASK_HUSBAND_NAME sessionId={}", session.getId());
        return true;
    }

    /** 1I (Sparkle) — the only way in is after a C2 booking (via afterStoreVisitBooked). */
    private boolean sparkleReinforce(Session session) {
        String text = "Shall we keep you posted on new arrivals and offers?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"S_KEEP_POSTED", "✅ Keep me posted"},   // → ack, then E1
                        new String[]{"S_NO_THANKS", "✕ No thanks"}));            // → E1
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=SPARKLE_REINFORCE_FAILED sessionId={}", session.getId());
            return false;
        }
        moveTo(session, STEP_S_REINFORCE);
        return true;
    }

    /** Sparkle "✅ Yes, keep me posted" — her own opt-in: acknowledge it, then close. */
    private boolean sparkleKeepPosted(Session session) {
        session.setConsent(CONSENT_YES);
        recordLead(session, LEAD_SPARKLE_OPT_IN, Lead.builder().consent(CONSENT_YES));
        saveMarketingConsent(session, CONSENT_YES);

        String text = "Wonderful – you're on the list 🎉 "
                + "We'll drop you a note whenever something new and beautiful arrives.";
        String mid = karixService.sendTextMessage(session.getPhone(), text);
        recordOutbound(session, "text", text, mid);
        log.info("stage=SPARKLE_OPT_IN sessionId={} sent={}", session.getId(), mid != null);

        return closeJourney(session);
    }

    /** Sparkle "✕ No thanks" — record the no, then close. */
    private boolean sparkleNoThanks(Session session) {
        session.setConsent(CONSENT_NO);
        recordLead(session, LEAD_SPARKLE_OPT_OUT, Lead.builder().consent(CONSENT_NO));   // a "no" is recorded too
        saveMarketingConsent(session, CONSENT_NO);
        log.info("stage=SPARKLE_OPT_OUT sessionId={}", session.getId());
        return closeJourney(session);
    }

    /**
     * E1 — closing line (depends on the path), then the session is closed.
     * After this, taps on old buttons find no active session; a new Step 0 tap starts fresh.
     * If the closing message fails, the session stays open so the tap can be retried.
     */
    private boolean closeJourney(Session session) {
        String text = TEMPLATE_SPARKLE.equals(session.getPath())
                ? "Wishing you a wonderful festive season ahead, from all of us at Mia ✨"
                : "Happy Karwa Chauth! Wishing you both a day filled with warmth and grace, from all of us at Mia 🌙";

        String mid = karixService.sendTextMessage(session.getPhone(), text);
        recordOutbound(session, "text", text, mid);

        if (mid == null) {
            log.warn("stage=CLOSE_FAILED sessionId={} — session left open", session.getId());
            return false;
        }
        moveTo(session, STEP_CLOSED);
        session.setIsActive(false);
        session.setClosedAt(LocalDateTime.now());
        log.info("stage=JOURNEY_CLOSED sessionId={} path={} consent={}",
                session.getId(), session.getPath(), session.getConsent());
        return true;
    }

    // ==================================================================
    // STEP 1J — capture his details: name → anniversary → mobile (typed replies, validated)
    // STEP 1K — consent → E1
    // ==================================================================

    /** 1J Q1 answer → save name, ask Q2. */
    private boolean onHusbandName(Session session, String answer) {
        String name = cleanName(answer);
        if (name == null) {
            return reask(session, "Could you share just his name? Letters only, like *Rahul* 😊");
        }
        session.setPartnerName(name);

        if (!askQuestion(session, "And your wedding anniversary? (DD/MM/YYYY)")) return false;
        moveTo(session, STEP_W_CAPH_ANNIVERSARY);
        log.info("stage=CAPTURED_HUSBAND_NAME sessionId={}", session.getId());
        return true;
    }

    /** 1J Q2 answer → save anniversary, ask Q3. */
    private boolean onAnniversary(Session session, String answer) {
        LocalDate date = parsePastDate(answer);
        if (date == null) {
            return reask(session, "Hmm, I couldn't read that date. Could you send day, month and year? e.g. (DD/MM/YYYY)");
        }
        session.setPartnerDate(date);

        if (!askQuestion(session, "Lastly, his mobile number 📱")) return false;
        moveTo(session, STEP_W_CAPH_MOBILE);
        log.info("stage=CAPTURED_ANNIVERSARY sessionId={}", session.getId());
        return true;
    }

    /** 1J Q3 answer → save mobile, send 1K consent. */
    private boolean onHusbandMobile(Session session, String answer) {
        String mobile = normalizeIndianMobile(answer);
        if (mobile == null) {
            return reask(session, "That doesn't look like a valid mobile number. Could you share his 10-digit number? 📱");
        }
        if (mobile.equals(session.getPhone())) {
            return reask(session, "That looks like your own number 😊 Could you share his?");
        }
        session.setPartnerPhone(mobile);
        log.info("stage=CAPTURED_HUSBAND_MOBILE sessionId={}", session.getId());

        return sendHusbandConsent(session);
    }

    /** 1K — "May we occasionally share a thoughtful gift idea with {husband_name}…?" */
    private boolean sendHusbandConsent(Session session) {
        String text = "One tiny thing - may we occasionally share a thoughtful gift idea with "
                + session.getPartnerName() + ", closer to an important date?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"W_CONSENT_YES", "✅ Yes, that's fine"},
                        new String[]{"W_CONSENT_NO", "✖ No, don't message"}));
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=CONSENT_ASK_FAILED sessionId={}", session.getId());
            return false;
        }
        session.setConsent(CONSENT_PENDING);
        moveTo(session, STEP_W_CONSENT);
        return true;
    }

    /**
     * 1K answer → E1.
     * On "No", his mobile is removed — we don't keep a number we're not allowed to use.
     * His name and the anniversary stay: they're about HER reminder (1I "Shall I remind you…").
     */
    private boolean onHusbandConsent(Session session, boolean yes) {
        session.setConsent(yes ? CONSENT_YES : CONSENT_NO);
        if (!yes) {
            session.setPartnerPhone(null);
        }
        log.info("stage=HUSBAND_CONSENT sessionId={} consent={}", session.getId(), session.getConsent());

        recordLead(session, LEAD_HUSBAND_CAPTURED, Lead.builder()
                .partnerName(session.getPartnerName())
                .partnerDate(session.getPartnerDate())
                .partnerDateType("ANNIVERSARY")
                .partnerPhone(session.getPartnerPhone())     // null when she said No
                .consent(session.getConsent()));
        return closeJourney(session);
    }

    /** Sends the next question as plain text. */
    private boolean askQuestion(Session session, String question) {
        String mid = karixService.sendTextMessage(session.getPhone(), question);
        recordOutbound(session, "text", question, mid);
        if (mid == null) {
            log.warn("stage=QUESTION_FAILED sessionId={} step={}", session.getId(), session.getCurrentStep());
            return false;
        }
        return true;
    }

    /** Invalid answer — explain and wait for another try. The step does not change. */
    private boolean reask(Session session, String message) {
        log.info("stage=INVALID_ANSWER sessionId={} step={}", session.getId(), session.getCurrentStep());
        return askQuestion(session, message);
    }

    // ---------- validation ----------

    /** "  rahul   kumar " → "Rahul Kumar". Null if it isn't a plausible name. */
    private static String cleanName(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        if (s.length() < 2 || s.length() > 50) return null;
        if (!s.matches("[\\p{L} .'-]+")) return null;          // letters, spaces, . ' - only
        if (!s.matches(".*\\p{L}.*")) return null;              // at least one letter

        StringBuilder out = new StringBuilder();
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static final DateTimeFormatter DMY =
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Day-month-year in any of these forms — no "/" needed:
     *   14 02 2015   14022015   14-02-2015   14.02.2015   14/02/2015   4 2 2015
     * Whatever she types, it's stored the same way: a real date (partner_date = 2015-02-14).
     * Rejects impossible dates (31 02), future dates and years before 1920.
     */
    private static LocalDate parsePastDate(String raw) {
        String s = raw.trim();
        if (s.matches("\\d{8}")) {
            // 14022015 → 14/02/2015  (8 digits, no separators: DDMMYYYY)
            s = s.substring(0, 2) + "/" + s.substring(2, 4) + "/" + s.substring(4);
        } else {
            // any non-digits (space, -, ., /, comma…) between the numbers → "/"
            s = s.replaceAll("[^0-9]+", "/").replaceAll("^/|/$", "");
        }
        try {
            LocalDate d = LocalDate.parse(s, DMY);
            if (d.isAfter(LocalDate.now()) || d.getYear() < 1920) return null;
            return d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * "98765 43210" / "+91 98765-43210" / "098765 43210" → "919876543210" (same format as session.phone).
     * Must be a 10-digit Indian mobile starting 6-9. Null otherwise.
     */
    private static String normalizeIndianMobile(String raw) {
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("91")) d = d.substring(2);
        else if (d.length() == 11 && d.startsWith("0")) d = d.substring(1);
        return d.matches("[6-9]\\d{9}") ? "91" + d : null;
    }

    // ==================================================================
    // STEP 2C — "Buy this for her" → confirm his choice
    // STEP 2E — "Shall we remember her birthday too?"
    // ==================================================================

    /** 2C — he tapped "Buy this for her" on a product card. */
    private boolean husbandConfirmChoice(Session session, String sku) {
        Product product = findProduct(sku);
        if (product == null) {
            log.warn("stage=BUY_UNKNOWN_SKU sessionId={} sku={}", session.getId(), sku);
            return false;
        }
        session.setSelectedProductSku(sku);   // the single piece — used by Buy Online and later by C2 ("Reserved")
        recordPick(session, sku, product.getCategory(), PICK_SELECTED);

        String text = "A beautiful choice. ✨ She's going to love it. "
                + "Here's to making her Karwa Chauth extra special. ✨\n\n"
                + "You can complete the purchase online, or visit a store near you.";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"H_BUY_ONLINE", "🛍 Buy Online"},          // → link, then 2E
                        new String[]{"BTN_STORE_FINDER", "📍 Visit Store"}));   // → C2, then 2E
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=CONFIRM_CHOICE_FAILED sessionId={} — step not changed", session.getId());
            return false;
        }
        moveTo(session, STEP_H_CONFIRM_CHOICE);
        log.info("stage=CONFIRM_CHOICE_SENT sessionId={} sku={}", session.getId(), sku);
        return true;
    }

    /**
     * 2C "🛍 Buy Online" tapped → a link button to the product page, then 2E after a pause.
     * (Two taps: a message can't have a link button AND a "Visit Store" button together.)
     */
    private boolean husbandBuyOnline(Session session) {
        String sku = session.getSelectedProductSku();
        Product product = sku == null ? null : findProduct(sku);
        if (product == null) {
            log.warn("stage=BUY_ONLINE_NO_PRODUCT sessionId={}", session.getId());
            return false;
        }
        String link = (product.getBuyLink() == null || product.getBuyLink().isBlank())
                ? HINT_BROWSE_LINK
                : product.getBuyLink();

        String text = "Here's *" + product.getName() + "* – tap below to complete the purchase 🛍";
        String mid = karixService.sendCtaUrlMessage(session.getPhone(), text, "🛍 Buy Online", link);
        recordOutbound(session, "cta_url", text, mid);

        if (mid == null) {
            log.warn("stage=BUY_ONLINE_LINK_FAILED sessionId={}", session.getId());
            return false;
        }
        log.info("stage=BUY_ONLINE_LINK_SENT sessionId={} sku={}", session.getId(), sku);

        // = "asked for the link" — WhatsApp doesn't report link taps, so the actual sale comes from the website
        recordLead(session, LEAD_BUY_ONLINE_CLICKED, Lead.builder()
                .productSkus(sku)
                .selectedCategory(product.getCategory()));

        // We can't see him tap the link — send 2E after a pause (step stays H_CONFIRM_CHOICE until then)
        scheduleFollowUp(session.getId(), STEP_H_CONFIRM_CHOICE, BUY_ONLINE_TO_DETAILS_DELAY_SECONDS,
                "HER_DETAILS_ASK", this::husbandCaptureDetails);
        return true;
    }

    /** 2E — after Buy Online (or a C2 booking): ask to remember her birthday. */
    private boolean husbandCaptureDetails(Session session) {
        String text = "One last thing – shall we remember her birthday too, so she's celebrated then as well?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"H_ADD_HER_DETAILS", "💍 Add her details"},   // → 2F (next step)
                        new String[]{"H_NO_THANKS", "✕ No thanks"}));            // → E1
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=HER_DETAILS_ASK_FAILED sessionId={}", session.getId());
            return false;
        }
        moveTo(session, STEP_H_CAP_DETAILS);
        log.info("stage=HER_DETAILS_ASK_SENT sessionId={}", session.getId());
        return true;
    }

    // ==================================================================
    // STEP 2F — capture her details: name → birthday → mobile (typed replies, validated)
    // STEP 2G — consent → E1
    // Same pattern and validation as 1J / 1K on the Wife path.
    // ==================================================================

    /** 2F Q1 — "💍 Add her details" tapped on 2E. */
    private boolean askWifeName(Session session) {
        if (!askQuestion(session, "What's her name?")) return false;
        moveTo(session, STEP_H_CAPW_NAME);
        log.info("stage=ASK_WIFE_NAME sessionId={}", session.getId());
        return true;
    }

    /** 2F Q1 answer → save name, ask Q2. */
    private boolean onWifeName(Session session, String answer) {
        String name = cleanName(answer);
        if (name == null) {
            return reask(session, "Could you share just her name? Letters only, like *Priya* 😊");
        }
        session.setPartnerName(name);

        if (!askQuestion(session, "And her birthday? (DD/MM/YYYY)")) return false;
        moveTo(session, STEP_H_CAPW_BIRTHDAY);
        log.info("stage=CAPTURED_WIFE_NAME sessionId={}", session.getId());
        return true;
    }

    /** 2F Q2 answer → save birthday, ask Q3. */
    private boolean onWifeBirthday(Session session, String answer) {
        LocalDate date = parsePastDate(answer);
        if (date == null) {
            return reask(session, "Hmm, I couldn't read that date. Could you send day, month and year?(DD/MM/YYYY)");
        }
        session.setPartnerDate(date);

        if (!askQuestion(session, "Lastly, her mobile number 📱")) return false;
        moveTo(session, STEP_H_CAPW_MOBILE);
        log.info("stage=CAPTURED_WIFE_BIRTHDAY sessionId={}", session.getId());
        return true;
    }

    /** 2F Q3 answer → save mobile, send 2G consent. */
    private boolean onWifeMobile(Session session, String answer) {
        String mobile = normalizeIndianMobile(answer);
        if (mobile == null) {
            return reask(session, "That doesn't look like a valid mobile number. Could you share her 10-digit number? 📱");
        }
        if (mobile.equals(session.getPhone())) {
            return reask(session, "That looks like your own number 😊 Could you share hers?");
        }
        session.setPartnerPhone(mobile);
        log.info("stage=CAPTURED_WIFE_MOBILE sessionId={}", session.getId());

        return sendWifeConsent(session);
    }

    /** 2G — "May we occasionally share a thoughtful gift idea for {her_name}, closer to her birthday?" */
    private boolean sendWifeConsent(Session session) {
        String text = "One tiny thing - may we occasionally share a thoughtful gift idea for "
                + session.getPartnerName() + ", closer to her birthday?";

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"H_CONSENT_YES", "✅ Yes, that's fine"},
                        new String[]{"H_CONSENT_NO", "✖ No, don't message"}));
        recordOutbound(session, "interactive", text, mid);

        if (mid == null) {
            log.warn("stage=WIFE_CONSENT_ASK_FAILED sessionId={}", session.getId());
            return false;
        }
        session.setConsent(CONSENT_PENDING);
        moveTo(session, STEP_H_CONSENT);
        return true;
    }

    /**
     * 2G answer → E1.
     * On "No", her mobile is removed — we don't keep a number we're not allowed to use.
     * Her name and birthday stay with HIS record, for reminding him.
     */
    private boolean onWifeConsent(Session session, boolean yes) {
        session.setConsent(yes ? CONSENT_YES : CONSENT_NO);
        if (!yes) {
            session.setPartnerPhone(null);
        }
        log.info("stage=WIFE_CONSENT sessionId={} consent={}", session.getId(), session.getConsent());

        recordLead(session, LEAD_WIFE_CAPTURED, Lead.builder()
                .partnerName(session.getPartnerName())
                .partnerDate(session.getPartnerDate())
                .partnerDateType("BIRTHDAY")
                .partnerPhone(session.getPartnerPhone())     // null when he said No
                .consent(session.getConsent())
                .productSkus(session.getSelectedProductSku()));
        return closeJourney(session);
    }

    // ==================================================================
    // IDLE — re-engagement nudge after 1 hour of no reply (all paths)
    // ==================================================================

    /**
     * Runs every minute. Finds sessions that went quiet more than {@code idleMinutes} ago,
     * haven't been nudged yet, aren't closed, and are still inside WhatsApp's 24-hour window
     * (we use 23h as a safety margin — after 24h only templates can be sent).
     *
     * One nudge per quiet spell: idleNudgeSent goes back to false the moment she replies
     * (onButtonTap / onTextMessage), so a later quiet spell can be nudged again.
     *
     * Note: with more than one app instance, two could nudge the same session — add ShedLock
     * or SELECT ... FOR UPDATE SKIP LOCKED before scaling out.
     */
    @Scheduled(fixedDelayString = "${karvachauth.idle.check-interval-ms:60000}")
    public void sendIdleNudges() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime idleCutoff = now.minusMinutes(idleMinutes);
        LocalDateTime windowCutoff = now.minusHours(23);

        List<Session> idle;
        try {
            idle = sessionRepository.findIdleSessions(idleCutoff, windowCutoff, PageRequest.of(0, IDLE_BATCH_SIZE));
        } catch (Exception e) {
            log.error("stage=IDLE_QUERY_FAILED", e);
            return;
        }
        if (idle.isEmpty()) return;

        log.info("stage=IDLE_RUN found={} idleMinutes={}", idle.size(), idleMinutes);
        for (Session session : idle) {
            lastOutbound.remove();
            try {
                if (sendIdleNudge(session)) {
                    session.setIdleNudgeSent(true);   // lastInboundAt is NOT touched — only her replies move it
                    sessionRepository.save(session);
                }
            } catch (Exception e) {
                // e.g. she tapped something at the same moment (optimistic lock) — skip, next run decides again
                log.warn("stage=IDLE_NUDGE_SKIPPED sessionId={} reason={}", session.getId(), e.getMessage());
            }
        }
    }

    /** The nudge itself — copy depends on the path; buttons are the same everywhere. */
    private boolean sendIdleNudge(Session session) {
        String text = switch (session.getPath() == null ? "" : session.getPath()) {
            case TEMPLATE_WIFE    -> "Take your time - though a little help never hurts 😊 "
                    + "Shall we pick up where we left off, before Karwa Chauth is here?";
            case TEMPLATE_SPARKLE -> "Take your time - though a little help never hurts 😊 "
                    + "Shall we pick up where we left off?";
            case TEMPLATE_HUSBAND -> "Still deciding? A thoughtful gift is worth the extra minute 😊 Shall we continue?";
            default -> null;
        };
        if (text == null) {
            log.warn("stage=IDLE_UNKNOWN_PATH sessionId={} path={}", session.getId(), session.getPath());
            return false;
        }

        String mid = karixService.sendButtonMessage(session.getPhone(), text,
                List.of(
                        new String[]{"IDLE_BROWSE_AGAIN", "Browse again"},    // → where she left off
                        new String[]{"ANOTHER_CATEGORY", "New category"}));   // → that path's category carousel
        recordOutbound(session, "interactive", text, mid);

        log.info("stage=IDLE_NUDGE_SENT sessionId={} path={} step={} sent={}",
                session.getId(), session.getPath(), session.getCurrentStep(), mid != null);
        return mid != null;
    }

    /**
     * IDLE "Browse again" — back to the products she was looking at (same category, budget and page).
     * If she hadn't picked a category yet, she gets that path's category carousel instead.
     */
    private boolean browseAgain(Session session) {
        String category = session.getSelectedCategory();
        String code = category == null ? null : codeFromCategory(category);
        if (code == null) {
            return switch (session.getPath()) {
                case TEMPLATE_HUSBAND -> husbandPickCategory(session);
                case TEMPLATE_SPARKLE -> sparklePickCategory(session);
                default               -> wifePickCategory(session);
            };
        }
        String step = switch (session.getPath()) {
            case TEMPLATE_SPARKLE -> STEP_S_BROWSE_PRODUCTS;
            case TEMPLATE_HUSBAND -> STEP_H_BROWSE_PRODUCTS;
            default               -> STEP_W_BROWSE_PRODUCTS;
        };
        log.info("stage=BROWSE_AGAIN sessionId={} category={} budget={} page={}",
                session.getId(), category, session.getSelectedBudget(), session.getProductPage());
        return sendProductPage(session, code, step, false);
    }

    // ==================================================================
    // CATEGORY + PRICE HELPERS
    // ==================================================================

    /** Carousel code → category value stored in products.category. */
    private static String categoryFromCode(String code) {
        return switch (code) {
            case "PEND" -> CAT_PENDANTS;
            case "MSUT" -> CAT_MIA_SUTRA;
            case "PNCH" -> CAT_PENDANT_CHAIN;
            case "NECK" -> CAT_NECKLACES;
            case "EARR" -> CAT_EARRINGS;
            case "RING" -> CAT_RINGS;
            case "BRBG" -> CAT_BRACELETS_BANGLES;
            default -> null;
        };
    }

    /** Category value → carousel code (reverse of categoryFromCode). */
    private static String codeFromCategory(String category) {
        return switch (category) {
            case CAT_PENDANTS          -> "PEND";
            case CAT_MIA_SUTRA         -> "MSUT";
            case CAT_PENDANT_CHAIN     -> "PNCH";
            case CAT_NECKLACES         -> "NECK";
            case CAT_EARRINGS          -> "EARR";
            case CAT_RINGS             -> "RING";
            case CAT_BRACELETS_BANGLES -> "BRBG";
            default -> null;
        };
    }

    /** Carousel code → display name ("Pendants", "Mia Sutra", …). */
    private static String categoryTitle(String code) {
        for (String[] c : CATEGORIES) {
            if (c[0].equals(code)) return c[1];
        }
        return code;
    }

    /**
     * Budget band → {min, max} price in rupees. Null for no budget (Wife path) or an unknown band.
     * Script v7.2: "Less than 20,000" excludes ₹20,000; ₹50,000 itself sits in "20,000 - 50,000" — confirm with the Mia team.
     */
    private static int[] priceRange(String budget) {
        if (budget == null) return null;
        return switch (budget) {
            case BUDGET_UNDER_20K -> new int[]{0, 19_999};
            case BUDGET_20_50K    -> new int[]{20_000, 50_000};
            case BUDGET_ABOVE_50K -> new int[]{50_001, Integer.MAX_VALUE};
            default -> null;
        };
    }

    /** 125000 → "1,25,000" (Indian grouping — Java's NumberFormat doesn't do this). */
    private static String formatInr(Integer price) {
        String s = String.valueOf(price);
        if (s.length() <= 3) return s;
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3).replaceAll("\\B(?=(\\d{2})+(?!\\d))", ",");
        return rest + "," + last3;
    }

    // ==================================================================
    // DB HELPERS
    // ==================================================================

    /** Finds the customer by phone or creates one. Fills in name / path only if not already set. */
    private Customer getOrCreateCustomer(String phone, String name, String path) {
        Customer customer = customerRepository.findByPhone(phone)
                .orElseGet(() -> Customer.builder().phone(phone).build());

        // Latest WhatsApp profile name wins (people change it); an empty name never wipes a saved one
        if (name != null && !name.isBlank()) {
            customer.setName(name.trim());
        }
        if (customer.getPath() == null) {
            customer.setPath(path);
        }
        return customerRepository.save(customer);
    }

    /**
     * Step 0 template receipt: finds the customer by phone or creates a bare one (no name / path yet —
     * both are filled in when she taps). An existing customer is left untouched.
     */
    private Customer findOrCreateCustomer(String phone) {
        Optional<Customer> existing = customerRepository.findByPhone(phone);
        if (existing.isPresent()) return existing.get();
        try {
            return customerRepository.save(Customer.builder().phone(phone).build());
        } catch (DataIntegrityViolationException e) {
            // Created a moment ago by another thread (phone is unique) — use that one
            return customerRepository.findByPhone(phone).orElseThrow(() -> e);
        }
    }

    /**
     * Links her latest Step 0 template message (saved when its receipt came in, before any session
     * existed) to the session her tap just started — so the session's messages begin at Step 0.
     * Every status row of that template (sent / delivered / read) is linked, not just one.
     */
    private void linkOpenerTemplate(Session session) {
        try {
            messageRepository.findFirstByPhoneAndMessageTypeAndSessionIdIsNullOrderByIdDesc(session.getPhone(), "template")
                    .ifPresent(template -> {
                        List<Message> rows = template.getKarixMessageId() == null
                                ? List.of(template)
                                : messageRepository.findByKarixMessageId(template.getKarixMessageId());
                        for (Message row : rows) {
                            row.setSessionId(session.getId());
                            if (row.getCustomerId() == null) row.setCustomerId(session.getCustomerId());
                            if (row.getStep() == null) row.setStep(STEP_OPENER);
                        }
                        messageRepository.saveAll(rows);
                    });
        } catch (Exception e) {
            log.error("Failed to link the Step 0 template sessionId={}", session.getId(), e);   // never break the flow
        }
    }

    /** Tapping a Step 0 button always starts a fresh journey: close the old session, open a new one. */
    private Session startNewSession(Customer customer, String path, String firstStep) {
        sessionRepository.findFirstByPhoneAndIsActiveTrueOrderByIdDesc(customer.getPhone()).ifPresent(old -> {
            old.setIsActive(false);
            old.setClosedAt(LocalDateTime.now());
            sessionRepository.save(old);
            log.info("stage=SESSION_CLOSED sessionId={} reason=RESTARTED oldStep={}", old.getId(), old.getCurrentStep());
        });

        Session session = Session.builder()
                .customerId(customer.getId())
                .phone(customer.getPhone())
                .path(path)
                .currentStep(firstStep)
                .lastInboundAt(LocalDateTime.now())   // the Step 0 tap counts as inbound
                .build();
        session = sessionRepository.save(session);
        log.info("stage=SESSION_CREATED sessionId={} path={}", session.getId(), path);
        return session;
    }

    /**
     * The customer's OWN answer to "keep me posted on new arrivals and offers?" (Sparkle 1I),
     * kept on the customer row — it outlives the session and is what to check before any
     * future marketing message. Latest answer wins, with the time it was given.
     */
    private void saveMarketingConsent(Session session, String consent) {
        try {
            customerRepository.findById(session.getCustomerId()).ifPresent(c -> {
                c.setMarketingConsent(consent);
                c.setMarketingConsentAt(LocalDateTime.now());
                customerRepository.save(c);
            });
        } catch (Exception e) {
            log.error("Failed to save marketing consent sessionId={}", session.getId(), e);
        }
    }

    /** One row per product interaction — for "top products" reports. Never breaks the flow. */
    private void recordPick(Session session, String sku, String category, String actionType) {
        try {
            productPickRepository.save(ProductPick.builder()
                    .phone(session.getPhone())
                    .sessionId(session.getId())
                    .sku(sku)
                    .category(category)
                    .actionType(actionType)
                    .path(session.getPath())
                    .sourceStep(session.getCurrentStep())
                    .build());
        } catch (Exception e) {
            log.error("Failed to record product pick sessionId={} sku={}", session.getId(), sku, e);
        }
    }

    /**
     * One row per conversion — for the dashboard funnel. Skipped if this session already
     * has this lead type (double tap, retried step), so counts stay honest. Never breaks the flow.
     */
    private void recordLead(Session session, String leadType, Lead.LeadBuilder details) {
        try {
            if (leadRepository.existsBySessionIdAndLeadType(session.getId(), leadType)) return;
            String customerName = session.getCustomerId() == null ? null
                    : customerRepository.findById(session.getCustomerId()).map(Customer::getName).orElse(null);
            leadRepository.save(details
                    .phone(session.getPhone())
                    .customerName(customerName)
                    .sessionId(session.getId())
                    .path(session.getPath())
                    .leadType(leadType)
                    .sourceStep(session.getCurrentStep())
                    .build());
        } catch (Exception e) {
            log.error("Failed to record lead sessionId={} type={}", session.getId(), leadType, e);
        }
    }

    private void recordInbound(Session session, String payload) {
        recordInbound(session, payload, null);
    }

    /** A button tap, saved on the step she was on. title = the button's visible text, if we have it. */
    private void recordInbound(Session session, String payload, String title) {
        try {
            messageRepository.save(Message.builder()
                    .customerId(session.getCustomerId())
                    .sessionId(session.getId())
                    .phone(session.getPhone())
                    .direction(DIR_INBOUND)
                    .status(MSG_STATUS_READ)               // her message — saved as READ, same as the Rakhi project
                    .step(session.getCurrentStep())
                    .path(session.getPath())
                    .messageType("button")
                    .buttonPayload(payload == null ? null : payload.substring(0, Math.min(payload.length(), 100)))
                    .buttonTitle(title == null ? null : title.substring(0, Math.min(title.length(), 100)))
                    .build());
        } catch (Exception e) {
            log.error("Failed to record inbound message sessionId={}", session.getId(), e);
        }
    }

    private void recordInboundText(Session session, String text) {
        try {
            messageRepository.save(Message.builder()
                    .customerId(session.getCustomerId())
                    .sessionId(session.getId())
                    .phone(session.getPhone())
                    .direction(DIR_INBOUND)
                    .status(MSG_STATUS_READ)               // her message — saved as READ, same as the Rakhi project
                    .step(session.getCurrentStep())
                    .path(session.getPath())
                    .messageType("text")
                    .contentText(text)
                    .build());
        } catch (Exception e) {
            log.error("Failed to record inbound text sessionId={}", session.getId(), e);
        }
    }

    /**
     * Our message, saved on the step the session is on now. If the reply then moves the session on,
     * moveTo() re-saves it on the new step.
     *
     * Status is NOT set here — it comes only from Karix receipts (the webhook): Karix's first receipt
     * (sent / delivered / read) fills this row, every later receipt adds its own row.
     * Only if Karix rejected the send (no mid — no receipt will ever come) it is saved as FAILED.
     */
    private void recordOutbound(Session session, String messageType, String text, String mid) {
        try {
            Message saved = messageRepository.save(Message.builder()
                    .customerId(session.getCustomerId())
                    .sessionId(session.getId())
                    .phone(session.getPhone())
                    .direction(DIR_OUTBOUND)
                    .karixMessageId(mid)
                    .status(mid != null ? null : MSG_STATUS_FAILED)   // null until Karix's first receipt
                    .step(session.getCurrentStep())
                    .path(session.getPath())
                    .messageType(messageType)
                    .contentText(text)
                    .errorReason(mid == null ? "Karix send failed — see 'Karix API response' log" : null)
                    .build());
            lastOutbound.set(saved);
        } catch (Exception e) {
            log.error("Failed to record outbound message sessionId={}", session.getId(), e);
        }
    }

    /**
     * Moves the session to its next step AND saves the message we just sent on that step — so a message
     * is stored on the step it puts her on: the Pendants carousel on W_BROWSE_PRODUCTS, the
     * "Happy Karwa Chauth" goodbye on CLOSED, the "What's your husband's name?" on W_CAPH_NAME, …
     */
    private void moveTo(Session session, String step) {
        session.setCurrentStep(step);
        Message last = lastOutbound.get();
        lastOutbound.remove();
        if (last == null || !session.getId().equals(last.getSessionId())) return;
        try {
            last.setStep(step);
            messageRepository.save(last);
        } catch (Exception e) {
            log.error("Failed to re-save message step sessionId={} step={}", session.getId(), step, e);
        }
    }

    /** "9876543210" → "919876543210". Null if invalid. */
    private static String normalizePhone(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("\\D", "");
        if (d.length() == 10) d = "91" + d;
        return (d.length() >= 11 && d.length() <= 15) ? d : null;
    }
}
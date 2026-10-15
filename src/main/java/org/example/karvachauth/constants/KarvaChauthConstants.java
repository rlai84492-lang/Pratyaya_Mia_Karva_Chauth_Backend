package org.example.karvachauth.constants;

public final class KarvaChauthConstants {
    private KarvaChauthConstants() {}

    public static final String TEMPLATE_WIFE  = "WIFE";
    public static final String TEMPLATE_HUSBAND = "HUSBAND";
    public static final String TEMPLATE_SPARKLE = "SPARKLE";


    public static final String CONSENT_PENDING = "PENDING";
    public static final String CONSENT_YES     = "YES";
    public static final String CONSENT_NO      = "NO";

    public static final String DIR_INBOUND  = "INBOUND";
    public static final String DIR_OUTBOUND = "OUTBOUND";

    //    =========================STEP 0 — OPENER (shared)=================================================
    // Session starts here when the Karix template reaches her (webhook receipt), and STAYS here after she
    // picks her path and we send "Choose my gift" / "Find her a gift" / "Show me" — until she taps that button.
    public static final String STEP_OPENER                = "OPENER";           //0

//    =========================WIFE_JOURNEY=======================================================

    public static final String STEP_W_CHOOSE_MY_GIFT      = "W_CHOOSE_MY_GIFT"; //1   tapped "💛 Choose my gift" → categories shown
    public static final String STEP_W_PICK_CATEGORY       = "W_PICK_CATEGORY";  //1A  categories shown again ("Another category" / idle nudge)
    public static final String STEP_W_BUDGET              = "W_BUDGET";          //1A
    public static final String STEP_W_BROWSE_PRODUCTS     = "W_BROWSE_PRODUCTS";         //1B
    public static final String STEP_W_ADDED_TO_LIST       = "W_ADDED_TO_LIST";   //1C
    public static final String STEP_W_ADD_MORE            = "W_ADD_MORE";       //1D
    public static final String STEP_W_HINT_READY          = "W_HINT_READY";       // 1G
    public static final String STEP_W_STORE_INVITE        = "W_STORE_INVITE";     // 1H
    public static final String STEP_W_REINFORCE           = "W_REINFORCE";       // 1I
    public static final String STEP_W_REINFORCE_NUDGE     = "W_REINFORCE_NUDGE";   //1I-NUDGE
    public static final String STEP_W_CAPH_NAME           = "W_CAPH_NAME";        //1J-Q1
    public static final String STEP_W_CAPH_ANNIVERSARY    = "W_CAPH_ANNIVERSARY";  //1J-Q2
    public static final String STEP_W_CAPH_MOBILE         = "W_CAPH_MOBILE";       //1J-Q3
    public static final String STEP_W_CONSENT             = "W_CONSENT";          // 1K




    //===============================HUSBAND JOURNEY======================================================
    public static final String STEP_H_FIND_HER_GIFT       = "H_FIND_HER_GIFT";  //2   tapped "💛 Find her a gift" → categories shown
    public static final String STEP_H_PICK_CATEGORY       = "H_PICK_CATEGORY";  //2A
    public static final String STEP_H_BUDGET              = "H_BUDGET";          //2A-BUDGET
    public static final String STEP_H_BROWSE_PRODUCTS     = "H_BROWSE_PRODUCTS";   //2B
    public static final String STEP_H_CONFIRM_CHOICE      = "H_CONFIRM_CHOICE";     //2C
    public static final String STEP_H_CAP_DETAILS         = "H_CAP_DETAILS";       //2E
    public static final String STEP_H_CAPW_NAME           = "H_CAPW_NAME";         //2F-Q1
    public static final String STEP_H_CAPW_BIRTHDAY       = "H_CAPW_BIRTHDAY";     //2F-Q2
    public static final String STEP_H_CAPW_MOBILE         = "H_CAPW_MOBILE";       //2F-Q3
    public static final String STEP_H_CONSENT             = "H_CONSENT";           //2G


    //    =========================SPARKLE_JOURNEY=======================================================

    public static final String STEP_S_SHOW_ME             = "S_SHOW_ME";         //1-Sparkle tapped "✨ Show me" → categories shown
    public static final String STEP_S_PICK_CATEGORY       = "S_PICK_CATEGORY";   //1A (6 categories, no Mia Sutra)
    public static final String STEP_S_BUDGET              = "S_BUDGET";          //1A-BUDGET
    public static final String STEP_S_BROWSE_PRODUCTS     = "S_BROWSE_PRODUCTS"; //1B
    public static final String STEP_S_ADDED_TO_LIST       = "S_ADDED_TO_LIST";   //1C (→ Add more / Another category / Visit Store → C2)
    public static final String STEP_S_ADD_MORE            = "S_ADD_MORE";        //1D
    public static final String STEP_S_REINFORCE           = "S_REINFORCE";       //1I (Sparkle: "Yes keep me posted" / "No thanks")


    //=====================SHARED COMPONENTS==============================
    public static final String STEP_BOOK_STORE_VISIT    = "BOOK_STORE_VISIT"; // C2 — Book a store visit (WhatsApp Flow). The only store step in v7.1 — there is no C1.
    public static final String STEP_CLOSED              = "CLOSED";             // E1

    // BUDGET BANDS (script v7.2)
    public static final String BUDGET_UNDER_20K  = "UNDER_20K";   // 💫 Less than 20,000
    public static final String BUDGET_20_50K     = "20_50K";      // 💎 20,000 - 50,000
    public static final String BUDGET_ABOVE_50K  = "ABOVE_50K";   // 👑 Above 50,000

    // ===================== CATEGORIES =====================
    public static final String CAT_PENDANTS         = "PENDANTS";
    public static final String CAT_MIA_SUTRA        = "MIA_SUTRA";        // excluded on Sparkle path
    public static final String CAT_PENDANT_CHAIN    = "PENDANT_CHAIN";
    public static final String CAT_NECKLACES        = "NECKLACES";
    public static final String CAT_EARRINGS         = "EARRINGS";
    public static final String CAT_RINGS            = "RINGS";
    public static final String CAT_BRACELETS_BANGLES = "BRACELETS_BANGLES";

    public static final String MSG_STATUS_RECEIVED  = "RECEIVED";
    public static final String MSG_STATUS_SENT      = "SENT";
    public static final String MSG_STATUS_DELIVERED = "DELIVERED";   // set by StatusConsumer
    public static final String MSG_STATUS_READ      = "READ";        // set by StatusConsumer
    public static final String MSG_STATUS_FAILED    = "FAILED";

    // ===================== LEAD TYPES (leads.lead_type) =====================
    public static final String LEAD_HINT_SENT          = "HINT_SENT";           // 1G Ishara sent
    public static final String LEAD_HUSBAND_CAPTURED   = "HUSBAND_CAPTURED";    // 1K answered
    public static final String LEAD_WIFE_CAPTURED      = "WIFE_CAPTURED";       // 2G answered (later)
    public static final String LEAD_SPARKLE_OPT_IN     = "SPARKLE_OPT_IN";      // Sparkle "Keep me posted"
    public static final String LEAD_SPARKLE_OPT_OUT    = "SPARKLE_OPT_OUT";     // Sparkle "No thanks" (consent = NO)
    public static final String LEAD_BUY_ONLINE_CLICKED = "BUY_ONLINE_CLICKED";  // 2C Buy Online link sent
    public static final String LEAD_STORE_VISIT_BOOKED = "STORE_VISIT_BOOKED";  // C2 booking (later)

    // ===================== PRODUCT PICK ACTIONS (product_picks.action_type) =====================
    public static final String PICK_ADDED     = "ADDED";       // 1C Add to my list
    public static final String PICK_HINT_SENT = "HINT_SENT";   // piece included in an Ishara
    public static final String PICK_SELECTED  = "SELECTED";    // 2B Buy this for her
    public static final String PICK_RESERVED  = "RESERVED";    // C2 booking (later)

    public static final String STEP_STORE_PICK     = "STORE_PICK";
    public static final String STEP_STORE_DATETIME = "STORE_DATETIME";
    public static final String STEP_STORE_BOOKED   = "STORE_BOOKED";
    public static final String BOOKING_PENDING     = "PENDING";
    public static final String BOOKING_CONFIRMED   = "CONFIRMED";
}
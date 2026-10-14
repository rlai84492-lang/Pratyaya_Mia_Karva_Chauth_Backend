//package org.example.karvachauth.serviceImple;
//
//
//import ch.qos.logback.classic.spi.ILoggingEvent;
//import ch.qos.logback.classic.spi.IThrowableProxy;
//import ch.qos.logback.core.AppenderBase;
//import org.example.karvachauth.entity.AppErrorLog;
////import org.example.karvachauth.repository.AppErrorLogRepository;
//import org.example.karvachauth.repository.AppErrorLogRepository;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Component;
//
//@Component
//public class DbLogAppender extends AppenderBase<ILoggingEvent> {
//
//    @Autowired
//    private AppErrorLogRepository errorLogRepository;
//
//    @Override
//    protected void append(ILoggingEvent event) {
//        try {
//            if (!event.getLevel().toString().equals("ERROR") && !event.getLevel().toString().equals("WARN")) {
//                return;
//            }
//
//            String stackTrace = "";
//            IThrowableProxy throwableProxy = event.getThrowableProxy();
//            if (throwableProxy != null) {
//                stackTrace = throwableProxy.getClassName() + ": " + throwableProxy.getMessage();
//            }
//
//            String message = event.getFormattedMessage();
//            String phone = extractPhone(message);
//
//            AppErrorLog log = AppErrorLog.builder()
//                    .level(event.getLevel().toString())
//                    .loggerName(event.getLoggerName())
//                    .message(message.length() > 500 ? message.substring(0, 500) : message)
//                    .stackTrace(stackTrace.length() > 2000 ? stackTrace.substring(0, 2000) : stackTrace)
//                    .phone(phone)
//                    .build();
//
//            errorLogRepository.save(log);
//        } catch (Exception e) {
//            // Silent-fail — logging-khud-crash-nahi-honi-chahiye
//        }
//    }
//
//    private String extractPhone(String message) {
//        if (message == null) return null;
//        java.util.regex.Matcher m = java.util.regex.Pattern.compile("phone=(\\d{10,15})").matcher(message);
//        return m.find() ? m.group(1) : null;
//    }
//}
//



package org.example.karvachauth.serviceImple;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.example.karvachauth.entity.AppErrorLog;
import org.example.karvachauth.repository.AppErrorLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Error logs ko DB (app_error_logs) mein likhta hai — bina app ko roke.
 *
 * Pehle kya galat tha:
 *  - AppenderBase "synchronized" hai → ek waqt par sirf ek thread log likh sakta tha,
 *    baaki sab workers line mein khade rehte the.
 *  - Har log ke liye usi worker thread mein MySQL insert hota tha.
 *  - Azure SDK / reactor ke hazaaron ERROR/WARN bhi DB mein jaate the.
 *  → Load par workers log likhne mein atak gaye aur processing ruk gayi.
 *
 * Ab:
 *  - UnsynchronizedAppenderBase → koi lock nahi.
 *  - Worker sirf ek chhoti in-memory queue mein daalta hai (microseconds); DB insert
 *    ek alag background thread batch mein karta hai.
 *  - Queue full ho (DB slow/down) toh naya log DROP hota hai — worker kabhi nahi rukta.
 *  - Sirf hamare code (org.example.karvachauth) ke ERROR/WARN DB mein jaate hain.
 *    Azure/reactor/Hibernate/MySQL ke logs console/file mein rehte hain.
 */
@Component
public class DbLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final String APP_PACKAGE = "org.example.karvachauth";
    private static final String WRITER_THREAD = "db-log-writer";
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 200;
    private static final Pattern PHONE = Pattern.compile("phone=(\\d{10,15})");

    @Autowired
    private AppErrorLogRepository errorLogRepository;

    private final BlockingQueue<AppErrorLog> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile Thread writer;

    @Override
    public void start() {
        super.start();
        synchronized (this) {
            if (writer == null) {
                writer = new Thread(this::writeLoop, WRITER_THREAD);
                writer.setDaemon(true);
                writer.start();
            }
        }
    }

    @Override
    public void stop() {
        super.stop();
        Thread w = writer;
        if (w != null) w.interrupt();
    }

    // ------------------------------------------------------------------
    // Worker thread yahan aata hai — sirf filter + queue.offer (kabhi block nahi)
    // ------------------------------------------------------------------
    @Override
    protected void append(ILoggingEvent event) {
        try {
            if (!event.getLevel().isGreaterOrEqual(Level.WARN)) return;

            String loggerName = event.getLoggerName();
            if (loggerName == null || !loggerName.startsWith(APP_PACKAGE)) return;

            // writer thread ke apne errors wapas DB mein na jaayein (infinite loop se bachao)
            if (WRITER_THREAD.equals(Thread.currentThread().getName())) return;

            String message = event.getFormattedMessage();
            if (message == null) message = "";

            String stackTrace = "";
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) stackTrace = tp.getClassName() + ": " + tp.getMessage();

            AppErrorLog row = AppErrorLog.builder()
                    .level(event.getLevel().toString())
                    .loggerName(loggerName)
                    .message(message.length() > 500 ? message.substring(0, 500) : message)
                    .stackTrace(stackTrace.length() > 2000 ? stackTrace.substring(0, 2000) : stackTrace)
                    .phone(extractPhone(message))
                    .build();

            if (!queue.offer(row)) {
                dropped.incrementAndGet();   // queue full → drop, worker ko mat roko
            }
        } catch (Exception ignored) {
            // logging kabhi app ko crash/slow na kare
        }
    }

    // ------------------------------------------------------------------
    // Background thread — batch mein DB insert
    // ------------------------------------------------------------------
    private void writeLoop() {
        List<AppErrorLog> batch = new ArrayList<>(BATCH_SIZE);
        while (!Thread.currentThread().isInterrupted()) {
            try {
                AppErrorLog first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);

                long d = dropped.getAndSet(0);
                if (d > 0) {
                    batch.add(AppErrorLog.builder()
                            .level("WARN")
                            .loggerName(DbLogAppender.class.getName())
                            .message("DB log queue full - dropped " + d + " log rows")
                            .stackTrace("")
                            .build());
                }

                if (errorLogRepository != null) {
                    errorLogRepository.saveAll(batch);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                // DB down/slow → yeh batch chhod do, app chalta rahe
            } finally {
                batch.clear();
            }
        }
    }

    private String extractPhone(String message) {
        Matcher m = PHONE.matcher(message);
        return m.find() ? m.group(1) : null;
    }
}
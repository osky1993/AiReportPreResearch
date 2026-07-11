package com.treasury.nl2sql.report.asset;

import com.treasury.nl2sql.report.domain.EventRecord;
import com.treasury.nl2sql.report.store.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 事件知识库管理（Phase05 契约2）。**录入白名单是注入防御第一道闸**（纪律 12）：
 * 事件文本视为数据不视为指令——含花括号/反引号/尖括号等模板与围栏字符一律拒绝录入
 * （第二道闸在 EventMatcher：进 prompt 前转义 + 截断，防御纵深不互相依赖）。
 * 服务端唯一执行点：长度/字符/结构校验全在此，前端仅展示。
 */
@Service
public class EventAdminService {

    private static final Logger log = LoggerFactory.getLogger(EventAdminService.class);

    /** 禁入字符（模板占位符/代码围栏/标签注入面）：{ } ` < > \ | 与控制字符。 */
    private static final Pattern FORBIDDEN = Pattern.compile("[{}`<>\\\\|\\p{Cntrl}]");
    private static final Pattern METRIC_ID = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final Pattern DIM_KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    private final EventRepository repo;

    public EventAdminService(EventRepository repo) {
        this.repo = repo;
    }

    public List<EventRecord> list() {
        return repo.findAll();
    }

    public long create(EventRecord e, String createdBy) {
        validateOrThrow(e);
        long id = repo.insert(withCreator(e, createdBy));
        log.info("[EVENT-ADMIN] 录入事件 #{} 「{}」 by {}", id, e.title(), createdBy);
        return id;
    }

    public void update(long eventId, EventRecord e, String updatedBy) {
        repo.findById(eventId).orElseThrow(() -> new IllegalArgumentException("事件不存在: " + eventId));
        validateOrThrow(e);
        repo.update(eventId, e, blankTo(updatedBy));
        log.info("[EVENT-ADMIN] 修改事件 #{} by {}", eventId, updatedBy);
    }

    public void deprecate(long eventId, String updatedBy) {
        repo.findById(eventId).orElseThrow(() -> new IllegalArgumentException("事件不存在: " + eventId));
        repo.updateStatus(eventId, EventRecord.STATUS_DEPRECATED, blankTo(updatedBy));
        log.info("[EVENT-ADMIN] 下架事件 #{} by {}", eventId, updatedBy);
    }

    /** 校验规则（正反例固化在 EventAdminServiceTest）：逐条收集不首错即停。 */
    public List<String> validate(EventRecord e) {
        List<String> errors = new ArrayList<>();
        if (e == null) {
            errors.add("事件体为空");
            return errors;
        }
        checkText(errors, "title", e.title(), 64, true);
        if (e.eventDate() == null) {
            errors.add("event_date 必填");
        }
        checkText(errors, "description", e.description(), 500, false);
        checkText(errors, "source", e.source(), 128, false);
        if (e.relatedMetrics() != null) {
            if (e.relatedMetrics().size() > 10) {
                errors.add("related_metrics 至多 10 个");
            }
            for (String m : e.relatedMetrics()) {
                if (m == null || !METRIC_ID.matcher(m.trim()).matches()) {
                    errors.add("related_metrics 含非法指标 id: " + m);
                }
            }
        }
        if (e.dimensions() != null) {
            if (e.dimensions().size() > 3) {
                errors.add("dimensions 至多 3 个键");
            }
            for (Map.Entry<String, String> d : e.dimensions().entrySet()) {
                if (d.getKey() == null || !DIM_KEY.matcher(d.getKey()).matches()) {
                    errors.add("dimensions 键非法: " + d.getKey());
                }
                checkText(errors, "dimensions." + d.getKey(), d.getValue(), 32, true);
            }
        }
        return errors;
    }

    private static void checkText(List<String> errors, String field, String value, int maxLen, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) errors.add(field + " 必填");
            return;
        }
        if (value.length() > maxLen) {
            errors.add(field + " 超长（≤" + maxLen + " 字）");
        }
        if (FORBIDDEN.matcher(value).find()) {
            errors.add(field + " 含禁入字符（{ } ` < > \\ | 或控制字符）——事件文本视为数据不视为指令");
        }
    }

    private void validateOrThrow(EventRecord e) {
        List<String> errors = validate(e);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("事件校验失败: " + String.join("；", errors));
        }
    }

    private static EventRecord withCreator(EventRecord e, String createdBy) {
        return new EventRecord(0, e.title().trim(), e.eventDate(), e.dimensions(), e.relatedMetrics(),
                e.description(), e.source(), EventRecord.STATUS_ACTIVE, blankTo(createdBy),
                null, null, null);
    }

    private static String blankTo(String s) {
        return s == null || s.isBlank() ? "demo" : s.trim();
    }
}

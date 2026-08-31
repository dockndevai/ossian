package io.github.dockndevai.ossian.audit;

import java.time.Instant;
import java.util.List;

import io.github.dockndevai.ossian.caller.CallerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records what was done, by whom.
 *
 * <p>Writes are {@code REQUIRES_NEW}, so an audit row survives the rollback of the thing it
 * describes. That is the case worth getting right: an attempt that failed halfway is more
 * interesting than one that succeeded, and joining the audit to the caller's transaction would
 * discard exactly those.
 *
 * <p>By the same argument a failure to write the audit row must not fail the request. It is
 * logged loudly instead — silently losing the record is how an audit trail comes to have gaps
 * nobody knows about, and a gap you know about is worth far more than one you do not.
 */
@Service
public class AuditService {

	/** The verbs. A closed set, because a log you cannot filter is a log nobody reads. */
	public static final String DOCUMENT_UPLOADED = "document.uploaded";

	public static final String DOCUMENT_DELETED = "document.deleted";

	public static final String DOCUMENT_REINDEXED = "document.reindexed";

	public static final String QUESTION_ASKED = "question.asked";

	public static final String KEY_ISSUED = "apikey.issued";

	public static final String KEY_REVOKED = "apikey.revoked";

	public static final String SETTING_CHANGED = "setting.changed";

	public static final String TRANSFORMATION_CHANGED = "transformation.changed";

	public static final String MEMORY_WRITTEN = "memory.written";

	public static final String MEMORY_FORGOTTEN = "memory.forgotten";

	private static final Logger log = LoggerFactory.getLogger(AuditService.class);

	public record Entry(long id, Instant at, String actor, String subject, boolean machine, String action,
			String targetType, String targetId, String namespace, String detail, String outcome, String ip) {
	}

	private final JdbcTemplate jdbc;

	private final CallerContext caller;

	public AuditService(JdbcTemplate jdbc, CallerContext caller) {
		this.jdbc = jdbc;
		this.caller = caller;
	}

	public void record(String action, String targetType, String targetId, String namespace, String detail) {
		write(action, targetType, targetId, namespace, detail, "success");
	}

	public void recordFailure(String action, String targetType, String targetId, String detail) {
		write(action, targetType, targetId, null, detail, "failure");
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void write(String action, String targetType, String targetId, String namespace, String detail,
			String outcome) {
		try {
			this.jdbc.update("""
					insert into audit_log (actor, subject, machine, action, target_type, target_id,
					                       namespace, detail, outcome, ip)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					""", this.caller.username(), this.caller.subject(), this.caller.isMachine(), action,
					targetType, truncate(targetId, 256), namespace, truncate(detail, 1000), outcome,
					clientIp());
		}
		catch (RuntimeException ex) {
			// Never fail the request for this, and never let it pass unnoticed either.
			log.error("AUDIT WRITE FAILED action={} target={}/{} — the trail now has a gap", action, targetType,
					targetId, ex);
		}
	}

	/** Recent entries, newest first, optionally narrowed. */
	public List<Entry> recent(String action, String actor, int limit) {
		return this.jdbc.query("""
				select id, at, actor, subject, machine, action, target_type, target_id, namespace,
				       detail, outcome, ip
				from audit_log
				where (?::text is null or action = ?)
				  and (?::text is null or actor = ?)
				order by at desc, id desc
				limit ?
				""",
				(rs, i) -> new Entry(rs.getLong("id"), rs.getTimestamp("at").toInstant(), rs.getString("actor"),
						rs.getString("subject"), rs.getBoolean("machine"), rs.getString("action"),
						rs.getString("target_type"), rs.getString("target_id"), rs.getString("namespace"),
						rs.getString("detail"), rs.getString("outcome"), rs.getString("ip")),
				action, action, actor, actor, Math.min(Math.max(limit, 1), 500));
	}

	/** What kinds of thing have happened, for the filter and for a sense of the shape. */
	public List<ActionCount> actions(int days) {
		return this.jdbc.query("""
				select action, count(*) as occurrences, max(at) as most_recent
				from audit_log
				where at > now() - make_interval(days => ?)
				group by action
				order by count(*) desc
				""", (rs, i) -> new ActionCount(rs.getString("action"), rs.getLong("occurrences"),
				rs.getTimestamp("most_recent").toInstant()), Math.min(Math.max(days, 1), 365));
	}

	public record ActionCount(String action, long occurrences, Instant mostRecent) {
	}

	/**
	 * The client address, honouring X-Forwarded-For only for its first entry.
	 *
	 * <p>The header is client-supplied and trivially forged, so this is a hint rather than
	 * evidence — recorded because it is often the only lead, and not to be relied on alone.
	 */
	private static String clientIp() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			var request = attributes.getRequest();
			String forwarded = request.getHeader("X-Forwarded-For");
			if (forwarded != null && !forwarded.isBlank()) {
				return truncate(forwarded.split(",")[0].trim(), 64);
			}
			return truncate(request.getRemoteAddr(), 64);
		}
		return null;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

}

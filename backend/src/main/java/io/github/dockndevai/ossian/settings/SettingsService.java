package io.github.dockndevai.ossian.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective configuration: what application.yml says, with a tenant's overrides applied.
 *
 * <p>The point is that changing a retrieval threshold or a chat model should not need a
 * redeploy, and should not change it for everyone. Overrides live in the database keyed by
 * tenant, so one tenant tuning its own corpus cannot affect another's.
 *
 * <p>Every setting is declared once, in {@link #DEFINITIONS}, with its type and bounds. That
 * list is what the UI renders and what validation is applied from — a setting cannot exist in
 * one and not the other, which is how the form and the server stay in agreement.
 */
@Service
public class SettingsService {

	/** What kind of value a setting holds, and therefore how it is parsed and rendered. */
	public enum Type {

		INT, DOUBLE, STRING, TEXT

	}

	/**
	 * A single tunable.
	 *
	 * @param key stable identifier, also the database key
	 * @param group which panel it belongs to in the UI
	 * @param label human name
	 * @param help why you would change it, and what breaks if you get it wrong
	 * @param type how to parse it
	 * @param min lower bound for numbers, null otherwise
	 * @param max upper bound for numbers, null otherwise
	 * @param requiresReindex whether existing chunks are stale once this changes
	 */
	public record Definition(String key, String group, String label, String help, Type type, Double min, Double max,
			boolean requiresReindex) {
	}

	public static final String CHAT_MODEL = "chat.model";

	public static final String CHAT_TEMPERATURE = "chat.temperature";

	public static final String CHAT_MAX_TOKENS = "chat.maxTokens";

	public static final String CHAT_SYSTEM_PROMPT = "chat.systemPrompt";

	public static final String RETRIEVAL_TOP_K = "retrieval.topK";

	public static final String RETRIEVAL_THRESHOLD = "retrieval.similarityThreshold";

	public static final String INGEST_CHUNK_SIZE = "ingest.chunkSize";

	public static final String INGEST_CHUNK_OVERLAP = "ingest.chunkOverlap";

	/**
	 * The catalogue. Adding a tunable means adding a line here and reading it where it applies —
	 * no migration, because settings are stored as key/value rows.
	 */
	public static final List<Definition> DEFINITIONS = List.of(
			new Definition(CHAT_MODEL, "model", "Chat model",
					"Model name as the gateway knows it. It must match a route the gateway has, or requests 404 before reaching a backend.",
					Type.STRING, null, null, false),
			new Definition(CHAT_TEMPERATURE, "model", "Temperature",
					"Higher is more varied. For answering from documents, low is right — creativity here shows up as invention.",
					Type.DOUBLE, 0.0, 2.0, false),
			new Definition(CHAT_MAX_TOKENS, "model", "Max tokens",
					"Ceiling on answer length. Too low truncates mid-sentence; the model is not told to be brief, it is simply cut off.",
					Type.INT, 1.0, 8192.0, false),
			new Definition(CHAT_SYSTEM_PROMPT, "model", "System prompt",
					"The instruction that makes the model answer from context and refuse otherwise. Weakening it is the usual cause of confident invention.",
					Type.TEXT, null, null, false),
			new Definition(RETRIEVAL_TOP_K, "retrieval", "Chunks retrieved",
					"How many chunks are handed to the model. More context is not always better: irrelevant chunks dilute the relevant one.",
					Type.INT, 1.0, 50.0, false),
			new Definition(RETRIEVAL_THRESHOLD, "retrieval", "Similarity threshold",
					"Minimum similarity for a chunk to count. Raise it and the system refuses more often; lower it and it answers from weak matches.",
					Type.DOUBLE, 0.0, 1.0, false),
			new Definition(INGEST_CHUNK_SIZE, "ingestion", "Chunk size (characters)",
					"How much text goes in one chunk. Large chunks retrieve imprecisely; small ones lose the context that makes a passage meaningful.",
					Type.INT, 200.0, 8000.0, true),
			new Definition(INGEST_CHUNK_OVERLAP, "ingestion", "Chunk overlap (characters)",
					"How much neighbouring chunks share. Overlap is what stops a fact that straddles a boundary from being lost by both chunks.",
					Type.INT, 0.0, 2000.0, true));

	private static final Map<String, Definition> BY_KEY = new LinkedHashMap<>();

	static {
		for (Definition d : DEFINITIONS) {
			BY_KEY.put(d.key(), d);
		}
	}

	private final TenantSettingRepository repository;

	private final OssianProperties properties;

	private final TenantContext tenant;

	public SettingsService(TenantSettingRepository repository, OssianProperties properties, TenantContext tenant) {
		this.repository = repository;
		this.properties = properties;
		this.tenant = tenant;
	}

	/** The value from application.yml, before any override. */
	public String fileDefault(String key) {
		OssianProperties p = this.properties;
		return switch (key) {
			case CHAT_MODEL -> p.getChat().getModel();
			case CHAT_TEMPERATURE -> String.valueOf(p.getChat().getTemperature());
			case CHAT_MAX_TOKENS -> String.valueOf(p.getChat().getMaxTokens());
			case CHAT_SYSTEM_PROMPT -> p.getChat().getSystemPrompt();
			case RETRIEVAL_TOP_K -> String.valueOf(p.getRetrieval().getTopK());
			case RETRIEVAL_THRESHOLD -> String.valueOf(p.getRetrieval().getSimilarityThreshold());
			case INGEST_CHUNK_SIZE -> String.valueOf(p.getIngest().getChunkSize());
			case INGEST_CHUNK_OVERLAP -> String.valueOf(p.getIngest().getChunkOverlap());
			default -> throw new IllegalArgumentException("Unknown setting: " + key);
		};
	}

	/**
	 * The value in force for an explicitly named tenant.
	 *
	 * <p>Ingestion runs on an {@code @Async} thread, where the security context — and therefore
	 * {@link TenantContext} — is not propagated. Reading settings there through the request-scoped
	 * lookup would silently resolve the fallback tenant and apply another tenant's configuration.
	 * Any caller that is not on a request thread must pass the tenant it already knows.
	 */
	public String effectiveFor(String tenantId, String key) {
		return this.repository.findByTenantIdAndKey(tenantId, key)
			.map(TenantSetting::getValue)
			.orElseGet(() -> fileDefault(key));
	}

	public int effectiveIntFor(String tenantId, String key) {
		return parse(tenantId, key, Integer::parseInt, () -> Integer.parseInt(fileDefault(key)));
	}

	public double effectiveDoubleFor(String tenantId, String key) {
		return parse(tenantId, key, Double::parseDouble, () -> Double.parseDouble(fileDefault(key)));
	}

	/** The value in force for the tenant on the current request. */
	public String effective(String key) {
		return effectiveFor(this.tenant.tenantId(), key);
	}

	public int effectiveInt(String key) {
		return effectiveIntFor(this.tenant.tenantId(), key);
	}

	public double effectiveDouble(String key) {
		return effectiveDoubleFor(this.tenant.tenantId(), key);
	}

	/**
	 * Reads a stored value, falling back to the file default when it cannot be parsed.
	 *
	 * <p>A malformed row must not take the service down. It got there through validation, so it
	 * should not happen — but a setting read on every request is the wrong place to discover
	 * that assumption was wrong.
	 */
	private <T> T parse(String tenantId, String key, Function<String, T> parser,
			java.util.function.Supplier<T> fallback) {
		String raw = effectiveFor(tenantId, key);
		try {
			return parser.apply(raw);
		}
		catch (RuntimeException ex) {
			return fallback.get();
		}
	}

	/** Every setting with its default, its override if any, and the metadata the UI renders. */
	public List<SettingView> all() {
		Map<String, TenantSetting> overrides = new LinkedHashMap<>();
		for (TenantSetting s : this.repository.findByTenantId(this.tenant.tenantId())) {
			overrides.put(s.getKey(), s);
		}
		List<SettingView> out = new ArrayList<>(DEFINITIONS.size());
		for (Definition d : DEFINITIONS) {
			TenantSetting override = overrides.get(d.key());
			out.add(new SettingView(d.key(), d.group(), d.label(), d.help(), d.type().name(), d.min(), d.max(),
					d.requiresReindex(), fileDefault(d.key()),
					(override == null) ? null : override.getValue(),
					(override == null) ? fileDefault(d.key()) : override.getValue(),
					(override == null) ? null : override.getUpdatedAt(),
					(override == null) ? null : override.getUpdatedBy()));
		}
		return out;
	}

	public record SettingView(String key, String group, String label, String help, String type, Double min, Double max,
			boolean requiresReindex, String defaultValue, String override, String effective, Instant updatedAt,
			String updatedBy) {
	}

	/** Applies an override after validating it against the setting's declared type and bounds. */
	@Transactional
	public void set(String key, String value, String actor) {
		Definition d = BY_KEY.get(key);
		if (d == null) {
			throw new IllegalArgumentException("Unknown setting: " + key);
		}
		String trimmed = (value == null) ? "" : value.trim();
		if (trimmed.isEmpty()) {
			// Clearing a field means "go back to the file default" rather than "set it to empty",
			// which is the only reading that lets someone undo a change they regret.
			this.repository.deleteByTenantIdAndKey(this.tenant.tenantId(), key);
			return;
		}
		validate(d, trimmed);

		TenantSetting entity = this.repository.findByTenantIdAndKey(this.tenant.tenantId(), key)
			.orElseGet(() -> {
				TenantSetting created = new TenantSetting();
				created.setTenantId(this.tenant.tenantId());
				created.setKey(key);
				return created;
			});
		entity.setValue(trimmed);
		entity.setUpdatedAt(Instant.now());
		entity.setUpdatedBy(actor);
		this.repository.save(entity);
	}

	@Transactional
	public void reset(String key) {
		if (!BY_KEY.containsKey(key)) {
			throw new IllegalArgumentException("Unknown setting: " + key);
		}
		this.repository.deleteByTenantIdAndKey(this.tenant.tenantId(), key);
	}

	private static void validate(Definition d, String value) {
		switch (d.type()) {
			case INT -> {
				int parsed;
				try {
					parsed = Integer.parseInt(value);
				}
				catch (NumberFormatException ex) {
					throw new IllegalArgumentException(d.label() + " must be a whole number");
				}
				bounds(d, parsed);
			}
			case DOUBLE -> {
				double parsed;
				try {
					parsed = Double.parseDouble(value);
				}
				catch (NumberFormatException ex) {
					throw new IllegalArgumentException(d.label() + " must be a number");
				}
				bounds(d, parsed);
			}
			case STRING, TEXT -> {
				if (value.length() > 8000) {
					throw new IllegalArgumentException(d.label() + " is too long");
				}
			}
		}
	}

	private static void bounds(Definition d, double parsed) {
		if (d.min() != null && parsed < d.min()) {
			throw new IllegalArgumentException(d.label() + " must be at least " + trim(d.min()));
		}
		if (d.max() != null && parsed > d.max()) {
			throw new IllegalArgumentException(d.label() + " must be at most " + trim(d.max()));
		}
	}

	private static String trim(double v) {
		return (v == Math.rint(v)) ? String.valueOf((long) v) : String.valueOf(v);
	}

}

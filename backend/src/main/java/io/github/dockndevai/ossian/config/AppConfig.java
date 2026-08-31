package io.github.dockndevai.ossian.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableConfigurationProperties(OssianProperties.class)
public class AppConfig {

	/**
	 * Three caches with deliberately different lifetimes, because what makes a hit stale differs.
	 * <p>
	 * {@code embeddings} is keyed on the text and the model. Embedding is deterministic, so a hit
	 * can never be stale and the entry lives for a month; re-ingesting an unchanged document
	 * costs nothing.
	 * <p>
	 * {@code insights} holds transformation output keyed by source text, prompt and model. Also
	 * deterministic in everything that matters, and by far the most expensive thing to recompute,
	 * so it is held for a day. Editing a prompt changes the key rather than needing an eviction.
	 * <p>
	 * {@code retrieval} is the short one, because it is the only one the corpus can invalidate
	 * underneath: a hit held too long answers from documents that have since been deleted.
	 */
	@Bean
	RedisCacheManager cacheManager(RedisConnectionFactory factory, OssianProperties properties) {
		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
			.disableCachingNullValues()
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
			.serializeValuesWith(RedisSerializationContext.SerializationPair
				.fromSerializer(new GenericJackson2JsonRedisSerializer()));

		return RedisCacheManager.builder(factory)
			.cacheDefaults(base.entryTtl(Duration.ofMinutes(10)))
			.withCacheConfiguration("embeddings", base.entryTtl(Duration.ofDays(30)))
			.withCacheConfiguration("insights", base.entryTtl(Duration.ofDays(1)))
			.withCacheConfiguration("retrieval",
					base.entryTtl(Duration.ofSeconds(properties.getRetrieval().getCacheSeconds())))
			.build();
	}

}

package io.github.dockndevai.openbook.config;

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
@EnableConfigurationProperties(OpenbookProperties.class)
public class AppConfig {

	/**
	 * Two caches with very different lifetimes.
	 * <p>
	 * {@code embeddings} is keyed on the text itself, so an identical string always embeds to
	 * the same vector — re-ingesting an unchanged document then costs nothing. {@code retrieval}
	 * is short-lived because the corpus changes underneath it; a stale hit would answer from
	 * documents that have since been deleted.
	 */
	@Bean
	RedisCacheManager cacheManager(RedisConnectionFactory factory, OpenbookProperties properties) {
		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
			.disableCachingNullValues()
			.serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
			.serializeValuesWith(RedisSerializationContext.SerializationPair
				.fromSerializer(new GenericJackson2JsonRedisSerializer()));

		return RedisCacheManager.builder(factory)
			.cacheDefaults(base.entryTtl(Duration.ofMinutes(10)))
			.withCacheConfiguration("embeddings", base.entryTtl(Duration.ofDays(30)))
			.withCacheConfiguration("retrieval",
					base.entryTtl(Duration.ofSeconds(properties.getRetrieval().getCacheSeconds())))
			.build();
	}

}

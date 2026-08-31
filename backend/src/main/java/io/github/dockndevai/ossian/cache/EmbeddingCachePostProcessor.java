package io.github.dockndevai.ossian.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Wraps whatever embedding model the context ends up with in {@link CachingEmbeddingModel}.
 *
 * <p>A post-processor rather than a {@code @Bean} with {@code @ConditionalOnBean}. That condition
 * is evaluated while user configuration is parsed, which happens before the AI auto-configuration
 * has registered the model it asks about — so it never matches and the cache is silently absent.
 * Nothing fails; embeddings are simply recomputed forever, which is exactly the kind of bug that
 * survives review because the system still works.
 *
 * <p>Post-processing has no such ordering problem: it sees every bean as it is created, whenever
 * that happens, including one supplied by a test.
 */
@Component
public class EmbeddingCachePostProcessor implements BeanPostProcessor {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingCachePostProcessor.class);

	private final ObjectProvider<CacheManager> caches;

	private final Environment environment;

	public EmbeddingCachePostProcessor(ObjectProvider<CacheManager> caches, Environment environment) {
		this.caches = caches;
		this.environment = environment;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if (bean instanceof EmbeddingModel model && !(bean instanceof CachingEmbeddingModel)) {
			String configured = this.environment.getProperty("spring.ai.openai.embedding.options.model", "unknown");
			log.info("caching embeddings for model '{}'", configured);
			return new CachingEmbeddingModel(model, this.caches, configured);
		}
		return bean;
	}

}

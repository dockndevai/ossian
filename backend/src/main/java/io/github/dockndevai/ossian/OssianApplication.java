package io.github.dockndevai.ossian;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Open-book RAG over your own documents.
 * <p>
 * Two surfaces on one service: a chat API that answers from retrieved chunks with citations,
 * and an admin API for the retrieval layer itself — ingestion jobs, re-indexing and retrieval
 * quality. Both are behind Keycloak; tenancy comes from the token, never from a header.
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class OssianApplication {

	public static void main(String[] args) {
		SpringApplication.run(OssianApplication.class, args);
	}

}

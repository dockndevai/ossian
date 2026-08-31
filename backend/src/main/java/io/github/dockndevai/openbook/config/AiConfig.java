package io.github.dockndevai.openbook.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

	/**
	 * Chat client aimed at whatever {@code spring.ai.openai.base-url} points to — by default the
	 * spring-llm-gateway, which is OpenAI-compatible. Routing to vLLM or Ollama, virtual keys,
	 * token quotas and usage metering all happen there rather than here, so this service holds
	 * no upstream credentials of its own.
	 */
	@Bean
	ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
		return ChatClient.builder(chatModel);
	}

}

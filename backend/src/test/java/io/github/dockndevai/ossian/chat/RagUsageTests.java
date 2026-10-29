package io.github.dockndevai.ossian.chat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import io.github.dockndevai.ossian.caller.CallerContext;
import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.namespace.NamespaceService;
import io.github.dockndevai.ossian.observability.PipelineMetrics;
import io.github.dockndevai.ossian.settings.SettingsService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagUsageTests {
	private final VectorStore store = mock(VectorStore.class);
	private final ChatModel model = mock(ChatModel.class);
	private final CallerContext caller = mock(CallerContext.class);
	private final QueryLogRepository logs = mock(QueryLogRepository.class);
	private final SettingsService settings = mock(SettingsService.class);
	private final NamespaceService namespaces = mock(NamespaceService.class);
	private RagService service;
	private final Dtos.AskRequest request = new Dtos.AskRequest("question", null, null);

	@BeforeEach
	void setUp() {
		when(caller.subject()).thenReturn("alice");
		when(namespaces.effectiveFilter(any())).thenReturn(Optional.empty());
		when(settings.effectiveInt(SettingsService.RETRIEVAL_TOP_K)).thenReturn(3);
		when(store.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(Document.builder().text("source").build()));
		when(settings.effective(SettingsService.CHAT_MODEL)).thenReturn("test");
		when(settings.effective(SettingsService.CHAT_SYSTEM_PROMPT)).thenReturn("system");
		service = new RagService(store, ChatClient.builder(model), mock(OssianProperties.class), caller,
				logs, settings, namespaces, mock(PipelineMetrics.class));
	}

	private static ChatResponse response(String text, int prompt, int completion) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(prompt, completion)).build());
	}

	private QueryLog saved() {
		ArgumentCaptor<QueryLog> captor = ArgumentCaptor.forClass(QueryLog.class);
		verify(logs, timeout(2000).times(1)).save(captor.capture());
		return captor.getValue();
	}

	@Test
	void synchronousUsageReachesResponseAndLog() {
		when(model.call(any(Prompt.class))).thenReturn(response("answer", 12, 4));
		Dtos.AskResponse result = service.ask(request);
		assertThat(result.answer()).isEqualTo("answer");
		assertThat(result.promptTokens()).isEqualTo(12);
		assertThat(result.completionTokens()).isEqualTo(4);
		assertThat(saved().getPromptTokens()).isEqualTo(12);
	}

	@Test
	void streamRecordsOnlyAfterCompletionWithUsageAndOriginalSubject() {
		when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("hello", 0, 0), response("", 12, 4))
				.publishOn(Schedulers.boundedElastic()));
		Flux<String> stream = service.askStreaming(request);
		verifyNoInteractions(logs);
		assertThat(stream.collectList().block()).containsExactly("hello");
		QueryLog entry = saved();
		assertThat(entry.isAnswered()).isTrue();
		assertThat(entry.getSubject()).isEqualTo("alice");
		assertThat(entry.getPromptTokens()).isEqualTo(12);
		assertThat(entry.getCompletionTokens()).isEqualTo(4);
		assertThat(entry.getLatencyMs()).isPositive();
		assertThat(entry.getFirstTokenMs()).isNotNull();
		assertThat(entry.getFailure()).isNull();
	}

	@Test
	void streamErrorIsRecordedAsFailure() {
		when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("failed")));
		assertThatThrownBy(() -> service.askStreaming(request).collectList().block()).hasMessageContaining("failed");
		QueryLog entry = saved();
		assertThat(entry.isAnswered()).isFalse();
		assertThat(entry.getFailure()).isEqualTo("ON_ERROR");
		assertThat(entry.getPromptTokens()).isNull();
	}

	@Test
	void streamSetupErrorIsRecordedAsFailure() {
		when(model.stream(any(Prompt.class))).thenThrow(new IllegalStateException("setup failed"));
		assertThatThrownBy(() -> service.askStreaming(request).collectList().block()).hasMessageContaining("setup failed");
		assertThat(saved().getFailure()).isEqualTo("ON_ERROR");
	}

	@Test
	void missingUsageRemainsUnknown() {
		when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));
		assertThat(service.ask(request).promptTokens()).isNull();
		assertThat(saved().getCompletionTokens()).isNull();
	}

	@Test
	void cancelledStreamIsNotRecordedAsAnswered() {
		when(model.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("partial", 0, 0)), Flux.never()));
		assertThat(service.askStreaming(request).take(1).collectList().block()).containsExactly("partial");
		QueryLog entry = saved();
		assertThat(entry.isAnswered()).isFalse();
		assertThat(entry.getFailure()).isEqualTo("CANCEL");
	}
}

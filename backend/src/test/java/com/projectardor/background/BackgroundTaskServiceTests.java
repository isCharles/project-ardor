package com.projectardor.background;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackgroundTaskServiceTests {
    private final BackgroundTaskStore store=mock(BackgroundTaskStore.class);
    private final UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
    private final BackgroundRetryHandler handler=mock(BackgroundRetryHandler.class);

    private BackgroundTaskService service() {
        when(handler.kinds()).thenReturn(Set.of("DOMAIN_FIXTURE"));
        return new BackgroundTaskService(store,List.of(handler));
    }
    private BackgroundTaskStore.Task task(String kind,String status) {
        var task=mock(BackgroundTaskStore.Task.class);
        when(task.kind()).thenReturn(kind);when(task.status()).thenReturn(status);
        when(store.get(owner,kind,id)).thenReturn(task);
        return task;
    }
    @Test void dispatchesAnOwnedFailedTaskOnlyAfterCheckingItsCompletionToken() {
        var service=service();var task=task("DOMAIN_FIXTURE","FAILED");
        service.retry(owner,"DOMAIN_FIXTURE",id,"completion");
        var order=inOrder(store,handler);
        order.verify(store).get(owner,"DOMAIN_FIXTURE",id);
        order.verify(store).read(owner,"DOMAIN_FIXTURE",id,"completion");
        order.verify(handler).retry(owner,task);
    }
    @Test void changedCompletionTokenCannotDispatchARetry() {
        var service=service();task("DOMAIN_FIXTURE","FAILED");
        doThrow(new IllegalStateException("changed")).when(store).read(owner,"DOMAIN_FIXTURE",id,"stale");
        assertThatThrownBy(() -> service.retry(owner,"DOMAIN_FIXTURE",id,"stale")).hasMessage("changed");
        verify(handler,never()).retry(any(),any());
    }
    @Test void activeTasksAndUnregisteredKindsNeverFallThroughToGenericAiJobs() {
        var service=service();task("DOMAIN_FIXTURE","RUNNING");
        assertThatThrownBy(() -> service.retry(owner,"DOMAIN_FIXTURE",id,"token")).isInstanceOf(IllegalStateException.class);
        task("UNKNOWN_KIND","FAILED");
        assertThatThrownBy(() -> service.retry(owner,"UNKNOWN_KIND",id,"token")).isInstanceOf(IllegalArgumentException.class);
        verify(store,never()).read(any(),any(),any(),any());
        verify(handler,never()).retry(any(),any());
    }
    @Test void missingOwnedTaskCannotDispatchAnything() {
        var service=service();
        when(store.get(owner,"DOMAIN_FIXTURE",id)).thenThrow(new com.projectardor.common.web.ResourceNotFoundException("missing"));
        assertThatThrownBy(() -> service.retry(owner,"DOMAIN_FIXTURE",id,"token")).isInstanceOf(com.projectardor.common.web.ResourceNotFoundException.class);
        verify(handler,never()).retry(any(),any());
    }
    @Test void overlappingOrEmptyRegistrationsAreRejected() {
        when(handler.kinds()).thenReturn(Set.of("DOMAIN_FIXTURE"));
        assertThatThrownBy(() -> new BackgroundTaskService(store,List.of(handler,handler))).isInstanceOf(IllegalStateException.class);
        when(handler.kinds()).thenReturn(Set.of());
        assertThatThrownBy(() -> new BackgroundTaskService(store,List.of(handler))).isInstanceOf(IllegalStateException.class);
    }

    @Test void domainRetriesPreserveTheirExistingOwnerBoundBusinessEntrypoints() {
        var resumes=mock(com.projectardor.materials.resume.service.ResumeAnalysisQueueService.class);
        var drafts=mock(com.projectardor.recap.service.InterviewRecapQueueService.class);
        var minutes=mock(com.projectardor.recap.service.InterviewMinutesService.class);
        var index=mock(com.projectardor.materials.knowledge.service.KnowledgeIndexTaskStore.class);
        var dispatcher=mock(com.projectardor.materials.knowledge.service.KnowledgeIndexDispatcher.class);
        var ai=mock(com.projectardor.aijobs.AiJobStore.class);
        var task=mock(BackgroundTaskStore.Task.class);
        UUID resource=UUID.randomUUID();when(task.id()).thenReturn(id);when(task.resourceId()).thenReturn(resource);
        new com.projectardor.materials.resume.service.ResumeAnalysisRetryHandler(resumes).retry(owner,task);
        new com.projectardor.recap.service.InterviewDraftRetryHandler(drafts).retry(owner,task);
        new com.projectardor.recap.service.InterviewReviewRetryHandler(minutes).retry(owner,task);
        new com.projectardor.materials.knowledge.service.KnowledgeIndexRetryHandler(index,dispatcher).retry(owner,task);
        var aiHandler=new com.projectardor.aijobs.AiJobRetryHandler(ai);
        aiHandler.retry(owner,task);
        verify(resumes).request(owner,resource);
        verify(drafts).retryFailed(owner,id);
        verify(minutes).retryReview(owner,id);
        var order=inOrder(index,dispatcher);order.verify(index).retry(owner,id);order.verify(dispatcher).dispatchPending(owner);
        verify(ai).retry(owner,id);
        assertThat(aiHandler.kinds()).containsExactlyInAnyOrder("LEARNING_CREATE","LEARNING_EVALUATE","REPLAY_EVALUATE","INTERVIEW_EVALUATE");
    }
}

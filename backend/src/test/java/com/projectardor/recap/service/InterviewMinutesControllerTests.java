package com.projectardor.recap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.projectardor.platform.identity.auth.domain.UserRole;
import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.recap.web.InterviewMinutesController;
import com.projectardor.platform.usage.QuotaService;

class InterviewMinutesControllerTests {
    @Test void openingExistingSourceIsReadOnlyAndDoesNotConsumeQuota() {
        var minutes=mock(InterviewMinutesService.class);
        var queue=mock(InterviewRecapQueueService.class);
        var quota=mock(QuotaService.class);
        var user=new ArdorPrincipal(UUID.randomUUID(),"fixture@ardor.invalid","unused",true,UserRole.USER);
        UUID report=UUID.randomUUID(),draft=UUID.randomUUID();
        when(minutes.sourceDraft(user.userId(),report)).thenReturn(draft);
        var controller=new InterviewMinutesController(minutes,queue,quota);
        assertThat(controller.fromReport(user,report,null)).containsEntry("draftId",draft);
        verifyNoInteractions(quota,queue);
    }

    @Test void confirmBindsTheAuthenticatedUserNotARequestSuppliedOwner() {
        var minutes=mock(InterviewMinutesService.class);
        var controller=new InterviewMinutesController(minutes,mock(InterviewRecapQueueService.class),mock(QuotaService.class));
        var user=new ArdorPrincipal(UUID.randomUUID(),"fixture@ardor.invalid","unused",true,UserRole.USER);
        UUID draft=UUID.randomUUID();
        controller.confirm(user,draft,new InterviewMinutesController.Confirm(2,UUID.randomUUID()));
        verify(minutes).confirm(user.userId(),draft,2);
    }
}

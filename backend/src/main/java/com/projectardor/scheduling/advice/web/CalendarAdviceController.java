package com.projectardor.scheduling.advice.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.projectardor.platform.identity.auth.security.ArdorPrincipal;
import com.projectardor.scheduling.advice.service.CalendarAdviceService;

@RestController
@RequestMapping("/api/calendar/advice")
public class CalendarAdviceController {
    private final CalendarAdviceService service;

    public CalendarAdviceController(CalendarAdviceService service) {
        this.service = service;
    }

    @GetMapping
    public CalendarAdviceResponse get(@AuthenticationPrincipal ArdorPrincipal principal) {
        return CalendarAdviceResponse.from(service.get(principal.userId()));
    }

    @PutMapping
    public CalendarAdviceResponse save(@AuthenticationPrincipal ArdorPrincipal principal,
            @RequestBody CalendarAdviceResponse.Update request) {
        return CalendarAdviceResponse.from(service.save(principal.userId(), request.headline(), request.days(), request.weeks(), request.months()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal ArdorPrincipal principal) {
        service.delete(principal.userId());
    }
}

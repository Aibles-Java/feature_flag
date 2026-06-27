package org.aibles.feature_flag.controller.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;
import org.aibles.feature_flag.service.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}

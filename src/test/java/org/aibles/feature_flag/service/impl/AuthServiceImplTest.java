package org.aibles.feature_flag.service.impl;

import org.aibles.feature_flag.domain.entity.User;
import org.aibles.feature_flag.dto.request.LoginRequest;
import org.aibles.feature_flag.dto.request.RegisterRequest;
import org.aibles.feature_flag.dto.response.AuthResponse;
import org.aibles.feature_flag.exception.DuplicateResourceException;
import org.aibles.feature_flag.repository.UserRepository;
import org.aibles.feature_flag.security.JwtTokenProvider;
import org.aibles.feature_flag.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userRepository, passwordEncoder, authenticationManager, jwtTokenProvider);
    }

    private RegisterRequest registerRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setPassword("plaintext-password");
        request.setFirstName("Ada");
        request.setLastName("Lovelace");
        return request;
    }

    @Test
    void registerHashesPasswordAndPersistsUser() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("plaintext-password")).thenReturn("hashed-password");

        authService.register(request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User user = saved.getValue();
        assertThat(user.getEmail()).isEqualTo("new@example.com");

        assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(user.getFirstName()).isEqualTo("Ada");
        assertThat(user.getLastName()).isEqualTo("Lovelace");
    }

    @Test
    void registerRejectsDuplicateEmailWithoutSaving() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByEmail("new@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("new@example.com");

        verify(userRepository, never()).save(any());
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void loginReturnsTokenAndPrincipalDetails() {
        UUID userId = UUID.randomUUID();
        LoginRequest request = new LoginRequest();
        request.setEmail("member@example.com");
        request.setPassword("secret");

        UserPrincipal principal = UserPrincipal.from(User.builder()
                .id(userId)
                .email("member@example.com")
                .passwordHash("hashed")
                .build());
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(jwtTokenProvider.generateToken(principal)).thenReturn("jwt-token");

        AuthResponse response = authService.login(request);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUserId()).isEqualTo(userId);
        assertThat(response.getEmail()).isEqualTo("member@example.com");
    }

    @Test
    void loginPropagatesAuthenticationFailure() {
        LoginRequest request = new LoginRequest();
        request.setEmail("member@example.com");
        request.setPassword("wrong");
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(jwtTokenProvider);
    }
}
